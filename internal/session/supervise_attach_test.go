package session

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net"
	"strings"
	"testing"
	"time"
)

// echoingSessionCommand 는 받은 줄을 그대로 되돌려주는 세션이다.
//
// 화면을 그리는 프로그램 대신 이것을 쓰는 이유는 바이트를 정확히 셀 수 있어서다 — 무엇을 보내면
// 무엇이 나오는지가 한 줄로 정해지므로 재생과 실시간의 경계에서 유실·중복을 눈으로 셀 수 있다.
//
// 첫 줄에 stty -echo 를 두어 터미널 자체의 메아리를 끈다. 그러지 않으면 우리가 보낸 키가 세션의
// 출력에 섞여 들어와 "세션이 뱉은 것" 과 "터미널이 되비춘 것" 이 구별되지 않는다. 준비 표시를
// 한 줄 찍는 것은 그 stty 가 실제로 적용된 뒤부터 키를 보내기 위해서다.
const echoingSessionCommand = `stty -echo; printf "준비\n"; while read line; do printf "%s\n" "$line"; done`

// readyLine 은 echoingSessionCommand 가 준비를 마치고 찍는 줄이다. PTY 가 개행을 CRLF 로 바꾼다.
const readyLine = "준비\r\n"

// attachedTestClient 는 감독의 attach 스트림에 붙은 시험용 클라이언트다.
//
// 진짜 소켓에 진짜 프레임을 흘린다. raw 모드 클라이언트를 거치지 않는 이유는 이 시험이 확인하려는
// 것이 프로토콜이지 터미널 다루기가 아니어서다 — 터미널 쪽은 PTY 안에서 따로 확인한다.
type attachedTestClient struct {
	t          *testing.T
	connection net.Conn
	collected  bytes.Buffer
}

// dialSessionForAttach 는 세션 소켓에 붙기만 하고 아무 프레임도 보내지 않는다.
func dialSessionForAttach(t *testing.T, sessionName string) net.Conn {
	t.Helper()

	connection, err := net.DialTimeout("unix", socketPathFor(t, sessionName), 5*time.Second)
	if err != nil {
		t.Fatalf("세션 소켓 연결 실패 (%s): %v", sessionName, err)
	}
	t.Cleanup(func() { connection.Close() })
	return connection
}

// attachToSession 은 세션에 붙어 준비 표시가 나올 때까지 읽는다.
func attachToSession(t *testing.T, sessionName string, columns, rows int) *attachedTestClient {
	t.Helper()

	connection := dialSessionForAttach(t, sessionName)
	request, err := json.Marshal(attachRequest{Version: attachProtocolVersion, Columns: columns, Rows: rows})
	if err != nil {
		t.Fatalf("붙기 요청 인코딩 실패: %v", err)
	}
	if err := writeAttachFrame(connection, attachFrameType, request); err != nil {
		t.Fatalf("ATTACH 프레임 전송 실패: %v", err)
	}
	return &attachedTestClient{t: t, connection: connection}
}

func (c *attachedTestClient) sendInput(text string) {
	c.t.Helper()

	if err := writeAttachFrame(c.connection, inputFrameType, []byte(text)); err != nil {
		c.t.Fatalf("INPUT 프레임 전송 실패: %v", err)
	}
}

func (c *attachedTestClient) sendResize(columns, rows int) {
	c.t.Helper()

	if err := writeAttachFrame(c.connection, resizeFrameType, encodeWindowSize(columns, rows)); err != nil {
		c.t.Fatalf("RESIZE 프레임 전송 실패: %v", err)
	}
}

// readFrame 은 프레임 하나를 받는다. 붙어 있는 동안은 감독이 마감 시각을 두지 않으므로 여기서 둔다.
func (c *attachedTestClient) readFrame() (byte, []byte) {
	c.t.Helper()

	if err := c.connection.SetReadDeadline(time.Now().Add(10 * time.Second)); err != nil {
		c.t.Fatalf("읽기 마감 시각 설정 실패: %v", err)
	}
	frameType, payload, err := readAttachFrame(c.connection)
	if err != nil {
		c.t.Fatalf("프레임 읽기 실패 (지금까지 받은 것 %q): %v", c.collected.String(), err)
	}
	return frameType, payload
}

// readOutputUntil 은 세션 출력이 want 를 담을 때까지 OUTPUT 프레임을 모은다.
//
// 모은 것을 통째로 돌려주므로 부르는 쪽이 "받은 전부" 를 그대로 견줄 수 있다 — 유실·중복은
// 부분 일치로는 드러나지 않는다.
func (c *attachedTestClient) readOutputUntil(want string) string {
	c.t.Helper()

	for !strings.Contains(c.collected.String(), want) {
		frameType, payload := c.readFrame()
		if frameType != outputFrameType {
			c.t.Fatalf("프레임 종류 = %#x, 세션 출력(%#x)을 기다리고 있었다 (페이로드 %q, 지금까지 %q)",
				frameType, outputFrameType, payload, c.collected.String())
		}
		c.collected.Write(payload)
	}
	return c.collected.String()
}

func (c *attachedTestClient) close() {
	c.t.Helper()

	if err := c.connection.Close(); err != nil {
		c.t.Fatalf("연결 닫기 실패: %v", err)
	}
}

// startEchoingSession 은 되돌려주는 세션 하나를 띄운다.
func startEchoingSession(t *testing.T, backend *SupervisorBackend, sessionName string) {
	t.Helper()

	if err := backend.Create(sessionName, t.TempDir(), []string{"sh", "-c", echoingSessionCommand}); err != nil {
		t.Fatalf("Create(%q) 실패: %v", sessionName, err)
	}
}

func TestAttachedClientExchangesBytesWithTheSession(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	const sessionName = "kyu-test-sv-attach-io"

	startEchoingSession(t, backend, sessionName)
	client := attachToSession(t, sessionName, 120, 40)

	client.readOutputUntil(readyLine)
	client.sendInput("첫 줄\n")

	if got := client.readOutputUntil("첫 줄\r\n"); got != readyLine+"첫 줄\r\n" {
		t.Errorf("받은 전부 = %q, want %q", got, readyLine+"첫 줄\r\n")
	}
}

func TestReattachReplaysWhatTheSessionPrintedWhileNobodyWasWatching(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	const sessionName = "kyu-test-sv-attach-replay"

	startEchoingSession(t, backend, sessionName)

	first := attachToSession(t, sessionName, 120, 40)
	first.readOutputUntil(readyLine)
	first.sendInput("떨어지기 전 줄\n")
	first.readOutputUntil("떨어지기 전 줄\r\n")
	first.close()

	// 연결을 닫는 것이 곧 detach 다. 다시 붙으면 하던 자리가 나와야 한다 — 그것이 tmux 가
	// 대신 해주던 일이고(설계 문서 1.3), 여기서는 링이 그 자리를 쥐고 있다(5.7).
	second := attachToSession(t, sessionName, 120, 40)
	second.sendInput("다시 붙은 뒤 줄\n")
	got := second.readOutputUntil("다시 붙은 뒤 줄\r\n")

	// 재생과 실시간이 같은 링에서 나오므로 그 경계에 잃거나 겹치는 바이트가 없다.
	want := readyLine + "떨어지기 전 줄\r\n" + "다시 붙은 뒤 줄\r\n"
	if got != want {
		t.Errorf("다시 붙어 받은 전부 = %q, want %q", got, want)
	}
}

func TestDetachLeavesTheSessionRunning(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	const sessionName = "kyu-test-sv-attach-detach"

	startEchoingSession(t, backend, sessionName)

	client := attachToSession(t, sessionName, 120, 40)
	client.readOutputUntil(readyLine)
	client.close()

	// 클라이언트가 죽어도 세션은 산다 — 그 성질을 지키려고 감독이 있다(설계 문서 2절).
	alive, err := backend.IsAlive(sessionName)
	if err != nil {
		t.Fatalf("IsAlive() 실패: %v", err)
	}
	if !alive {
		t.Errorf("detach 후 IsAlive() = false, 세션은 살아 있어야 한다")
	}
}

func TestAttachAppliesTheClientWindowSizeToTheSession(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	const sessionName = "kyu-test-sv-attach-size"

	// 세션은 받은 줄마다 자기 화면 크기를 답한다. stty size 는 "행 열" 순으로 찍는다.
	if err := backend.Create(sessionName, t.TempDir(), []string{
		"sh", "-c", `stty -echo; printf "준비\n"; while read line; do stty size; done`,
	}); err != nil {
		t.Fatalf("Create() 실패: %v", err)
	}

	client := attachToSession(t, sessionName, 120, 40)
	client.readOutputUntil(readyLine)

	client.sendInput("크기\n")
	if got := client.readOutputUntil("40 120\r\n"); !strings.Contains(got, "40 120\r\n") {
		t.Errorf("붙은 직후 세션 화면 = %q, 40 행 120 열이어야 한다", got)
	}

	// 창 크기를 바꾸면 세션 화면이 따라 바뀐다 — tmux 클라이언트가 대신 해주던 일이다(설계 문서 7절).
	client.sendResize(60, 20)
	client.sendInput("크기\n")
	if got := client.readOutputUntil("20 60\r\n"); !strings.Contains(got, "20 60\r\n") {
		t.Errorf("크기 변경 뒤 세션 화면 = %q, 20 행 60 열이어야 한다", got)
	}
}

func TestANewClientTakesTheSessionFromTheOneAlreadyAttached(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	const sessionName = "kyu-test-sv-attach-takeover"

	startEchoingSession(t, backend, sessionName)

	first := attachToSession(t, sessionName, 120, 40)
	first.readOutputUntil(readyLine)

	// 이미 붙은 클라이언트가 있는 세션에 새 클라이언트가 붙으면 앞의 것을 끊고 새 것이 받는다.
	// tmux 와 다른 선택이고, 그 근거는 앱이 "한 번에 하나만 연다" 로 이미 정해두었다는 것이다
	// (설계 문서 5.6).
	second := attachToSession(t, sessionName, 80, 24)

	if err := first.connection.SetReadDeadline(time.Now().Add(10 * time.Second)); err != nil {
		t.Fatalf("읽기 마감 시각 설정 실패: %v", err)
	}
	if _, _, err := readAttachFrame(first.connection); err == nil {
		t.Errorf("앞 클라이언트의 읽기 = nil, 끊겼어야 한다")
	}

	second.sendInput("새 주인\n")
	if got := second.readOutputUntil("새 주인\r\n"); !strings.Contains(got, "새 주인\r\n") {
		t.Errorf("새 클라이언트가 받은 것 = %q, 세션이 이어져야 한다", got)
	}
}

func TestAttachedClientIsToldTheSessionEndedAndWithWhatCode(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	const sessionName = "kyu-test-sv-attach-exit"

	if err := backend.Create(sessionName, t.TempDir(), []string{
		"sh", "-c", `stty -echo; printf "준비\n"; read line; exit 7`,
	}); err != nil {
		t.Fatalf("Create() 실패: %v", err)
	}

	client := attachToSession(t, sessionName, 120, 40)
	client.readOutputUntil(readyLine)
	client.sendInput("끝내자\n")

	// 세션 안 프로그램이 정상 종료한 것과 연결이 깨진 것은 사용자에게 다른 말을 해야 하는
	// 사건이다. 연결을 그냥 닫으면 그 둘이 뭉개진다(설계 문서 5.6).
	for {
		frameType, payload := client.readFrame()
		if frameType == outputFrameType {
			client.collected.Write(payload)
			continue
		}
		if frameType != exitFrameType {
			t.Fatalf("프레임 종류 = %#x, EXIT(%#x)여야 한다", frameType, exitFrameType)
		}

		var exited sessionExitPayload
		if err := json.Unmarshal(payload, &exited); err != nil {
			t.Fatalf("EXIT 페이로드 해석 실패 (%q): %v", payload, err)
		}
		if exited.Code != 7 {
			t.Errorf("세션 종료 코드 = %d, want 7", exited.Code)
		}
		return
	}
}

func TestAttachIsRefusedWhenItSpeaksAProtocolVersionTheSupervisorDoesNotKnow(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	const sessionName = "kyu-test-sv-attach-version"

	startEchoingSession(t, backend, sessionName)

	// 새 kyu 가 옛 감독을 만나는 일이 세션마다 따로 벌어진다(설계 문서 5.5·10절 4번).
	// 감독은 모르는 판의 요청을 안정된 코드로 거절해야 한다 — 문장으로 거절하면 클라이언트가
	// 문구 비교로 분기하게 되고, 그것은 문구를 다듬는 순간 조용히 깨진다.
	connection := dialSessionForAttach(t, sessionName)
	request, err := json.Marshal(attachRequest{Version: attachProtocolVersion + 98, Columns: 80, Rows: 24})
	if err != nil {
		t.Fatalf("붙기 요청 인코딩 실패: %v", err)
	}
	if err := writeAttachFrame(connection, attachFrameType, request); err != nil {
		t.Fatalf("ATTACH 프레임 전송 실패: %v", err)
	}

	if err := connection.SetReadDeadline(time.Now().Add(10 * time.Second)); err != nil {
		t.Fatalf("읽기 마감 시각 설정 실패: %v", err)
	}
	frameType, payload, err := readAttachFrame(connection)
	if err != nil {
		t.Fatalf("프레임 읽기 실패: %v", err)
	}
	if frameType != errorFrameType {
		t.Fatalf("프레임 종류 = %#x, ERROR(%#x)여야 한다", frameType, errorFrameType)
	}

	var refusal attachErrorPayload
	if err := json.Unmarshal(payload, &refusal); err != nil {
		t.Fatalf("ERROR 페이로드 해석 실패 (%q): %v", payload, err)
	}
	if refusal.Code != badProtocolVersionCode {
		t.Errorf("거절 코드 = %q, want %q", refusal.Code, badProtocolVersionCode)
	}
}

func TestControlRequestsStillAnswerWhileAClientIsAttached(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	const sessionName = "kyu-test-sv-attach-backpressure"

	startEchoingSession(t, backend, sessionName)

	// attach 를 전용 연결로 둔 이유가 이것이다. 한 연결에 제어와 attach 를 섞었다면 붙어 있는
	// 터미널의 역압이 alive 조회까지 멈춰 세우고, 3 초마다 도는 kyu list 가 그 세션에서
	// 걸린다(설계 문서 5.6). 여기서는 붙은 채로 조회가 그대로 답해야 한다.
	client := attachToSession(t, sessionName, 120, 40)
	client.readOutputUntil(readyLine)

	alive, err := backend.IsAlive(sessionName)
	if err != nil {
		t.Fatalf("붙어 있는 동안 IsAlive() 실패: %v", err)
	}
	if !alive {
		t.Errorf("붙어 있는 동안 IsAlive() = false, true 여야 한다")
	}

	names, err := backend.List()
	if err != nil {
		t.Fatalf("붙어 있는 동안 List() 실패: %v", err)
	}
	if len(names) != 1 || names[0] != sessionName {
		t.Errorf("붙어 있는 동안 List() = %v, want [%s]", names, sessionName)
	}
}

func TestKillEndsTheSessionEvenWithAClientAttached(t *testing.T) {
	backend := newIsolatedSupervisorBackend(t)
	const sessionName = "kyu-test-sv-attach-kill"

	startEchoingSession(t, backend, sessionName)

	client := attachToSession(t, sessionName, 120, 40)
	client.readOutputUntil(readyLine)

	if err := backend.Kill(sessionName); err != nil {
		t.Fatalf("붙어 있는 동안 Kill() 실패: %v", err)
	}

	// 붙어 있던 연결도 끝나야 한다. 끝나지 않으면 감독이 클라이언트를 기다리며 남는다.
	if err := client.connection.SetReadDeadline(time.Now().Add(10 * time.Second)); err != nil {
		t.Fatalf("읽기 마감 시각 설정 실패: %v", err)
	}
	for {
		frameType, _, err := readAttachFrame(client.connection)
		if err != nil {
			if errors.Is(err, io.EOF) {
				return
			}
			t.Fatalf("kill 뒤 프레임 읽기 실패: %v", err)
		}
		if frameType == exitFrameType {
			return
		}
	}
}
