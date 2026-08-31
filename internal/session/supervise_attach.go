package session

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"time"

	"github.com/creack/pty"
)

// attachedClient 는 지금 이 세션에 붙어 있는 클라이언트 하나다.
//
// 상태를 따로 관리하지 않는다 — 연결 자체가 곧 붙어 있음이고, 연결이 닫히면 그것이 detach 다.
// 클라이언트가 죽어도(터미널 창을 강제 종료해도) 커널이 연결을 닫아주므로 같은 경로로
// 처리된다(설계 문서 5.6).
type attachedClient struct {
	connection net.Conn

	// writeMutex 는 이 연결에 쓰는 자리를 하나로 만든다. 출력 프레임은 링을 읽는 고루틴이,
	// EXIT·ERROR 는 그 뒤가 보내므로 두 손이 같은 소켓에 닿는다.
	writeMutex sync.Mutex

	// gone 은 이 클라이언트가 끊겼을 때 닫힌다. 소켓을 닫는 것만으로는 링에서 기다리는 쪽을
	// 깨우지 못하므로 신호가 따로 있어야 한다.
	gone     chan struct{}
	goneOnce sync.Once
}

func newAttachedClient(connection net.Conn) *attachedClient {
	return &attachedClient{connection: connection, gone: make(chan struct{})}
}

// disconnect 는 이 클라이언트를 끊는다. 여러 번 불러도 한 번만 닫힌다.
func (c *attachedClient) disconnect() {
	c.goneOnce.Do(func() {
		close(c.gone)
		c.connection.Close()
	})
}

func (c *attachedClient) isGone() bool {
	select {
	case <-c.gone:
		return true
	default:
		return false
	}
}

func (c *attachedClient) send(frameType byte, payload []byte) error {
	c.writeMutex.Lock()
	defer c.writeMutex.Unlock()
	return writeAttachFrame(c.connection, frameType, payload)
}

// serveAttachConnection 은 attach 전용 연결 하나를 세션이 끝나거나 클라이언트가 떨어질 때까지 맡는다.
func (s *supervisorProcess) serveAttachConnection(connection net.Conn, reader io.Reader) {
	defer connection.Close()

	request, err := readAttachRequest(reader)
	if err != nil {
		s.refuseAttach(connection, supervisorInternalCode, err.Error())
		s.events.Printf("붙기 요청 해석 실패: %v", err)
		return
	}
	if request.Version != attachProtocolVersion {
		// 새 kyu 가 옛 감독을 만나는 일이 세션마다 따로 벌어진다(설계 문서 10절 4번).
		// 코드로 거절해야 클라이언트가 문구 비교 없이 분기한다.
		s.refuseAttach(connection, badProtocolVersionCode, fmt.Sprintf(
			"이 세션은 attach 프로토콜 %d 판으로 떴습니다 (요청은 %d 판)",
			attachProtocolVersion, request.Version))
		return
	}

	// 붙고 나면 마감 시각을 푼다. 사람이 다음 키를 누를 때까지의 사이는 얼마든 길 수 있고,
	// 출력이 없는 세션에 붙어 있는 것도 정상이다.
	if err := connection.SetDeadline(time.Time{}); err != nil {
		s.events.Printf("attach 연결 마감 시각 해제 실패: %v", err)
		return
	}

	client := s.takeOverAttachment(connection)
	defer s.releaseAttachment(client)

	// 크기를 먼저 적용한다. 그 결과로 세션이 다시 그린 바이트는 링에 재생 뒤로 들어가므로,
	// 클라이언트는 하던 자리를 본 다음 새 크기의 화면을 본다(설계 문서 5.7 대응 2).
	s.applySessionWindowSize(request.Columns, request.Rows)

	go s.pumpClientInputIntoSession(reader, client)
	s.pumpScrollbackToClient(client)
}

// readAttachRequest 는 첫 프레임을 읽어 붙기 요청으로 해석한다.
func readAttachRequest(reader io.Reader) (attachRequest, error) {
	frameType, payload, err := readAttachFrame(reader)
	if err != nil {
		return attachRequest{}, err
	}
	if frameType != attachFrameType {
		return attachRequest{}, fmt.Errorf("첫 프레임은 ATTACH(%#x)여야 합니다 (받은 것 %#x)", attachFrameType, frameType)
	}

	var request attachRequest
	if err := json.Unmarshal(payload, &request); err != nil {
		return attachRequest{}, fmt.Errorf("붙기 요청 해석 실패 (%q): %w", payload, err)
	}
	return request, nil
}

// refuseAttach 는 붙기를 거절하고 그 이유를 프레임으로 전한다.
func (s *supervisorProcess) refuseAttach(connection net.Conn, code, message string) {
	payload, err := encodeAttachErrorPayload(code, message)
	if err != nil {
		s.events.Printf("붙기 거절 전달 실패: %v", err)
		return
	}
	if err := writeAttachFrame(connection, errorFrameType, payload); err != nil {
		s.events.Printf("붙기 거절 전달 실패: %v", err)
	}
}

// takeOverAttachment 는 이 연결을 붙어 있는 클라이언트로 세우고 앞의 것을 끊는다.
//
// 앞의 것을 끊고 새 것이 받는다. tmux 와 다른 선택이고, 그 근거는 앱이 "한 번에 하나만 연다" 를
// 이미 정해두었다는 것과 여럿이 볼 때 화면 크기를 어느 쪽에 맞출지가 곧바로 문제가 된다는
// 것이다(설계 문서 5.6).
func (s *supervisorProcess) takeOverAttachment(connection net.Conn) *attachedClient {
	client := newAttachedClient(connection)

	s.attachMutex.Lock()
	previous := s.attachedClient
	s.attachedClient = client
	s.attachMutex.Unlock()

	if previous != nil {
		s.events.Printf("새 클라이언트가 붙어 앞서 붙어 있던 것을 끊습니다")
		previous.disconnect()
	}
	s.events.Printf("클라이언트가 붙었습니다")
	return client
}

func (s *supervisorProcess) releaseAttachment(client *attachedClient) {
	s.attachMutex.Lock()
	if s.attachedClient == client {
		s.attachedClient = nil
	}
	s.attachMutex.Unlock()

	client.disconnect()
	s.events.Printf("클라이언트가 떨어졌습니다")
}

// pumpScrollbackToClient 는 링을 읽어 클라이언트에게 흘려보낸다.
//
// 재생과 실시간을 가르지 않는다. 커서를 링의 재생 시작점에 두고 앞으로 읽어갈 뿐이고, 따라잡은
// 뒤로는 같은 읽기가 실시간이 된다 — 그래서 그 경계에서 잃거나 겹치는 바이트가 없다(설계 문서 5.7).
func (s *supervisorProcess) pumpScrollbackToClient(client *attachedClient) {
	stop := s.stopWhenClientLeavesOrSessionEnds(client)

	cursor := s.scrollback.replayStartOffset()
	buffer := make([]byte, sessionOutputChunkSize)
	for {
		read, next, hasMore := s.scrollback.read(cursor, buffer, stop)
		if !hasMore {
			break
		}
		if err := client.send(outputFrameType, buffer[:read]); err != nil {
			// 클라이언트가 받지 못하는 것은 감독의 실패가 아니다 — 창이 닫힌 것이 흔한 이유다.
			client.disconnect()
			return
		}
		cursor = next
	}

	if client.isGone() {
		return
	}
	s.tellClientTheSessionEnded(client)
}

// stopWhenClientLeavesOrSessionEnds 는 링에서 기다리는 것을 그만둘 신호를 만든다.
//
// 자식이 끝난 뒤에도 링이 닫히지 않는 경우가 있다 — 세션 안 프로그램이 남긴 손자가 PTY 슬레이브를
// 쥐고 있으면 마스터가 EOF 를 주지 않는다. 그때도 감독은 자기 세션이 끝났으므로 물러나야 하고,
// 붙어 있는 클라이언트를 영영 기다리게 두면 감독 프로세스가 남는다.
func (s *supervisorProcess) stopWhenClientLeavesOrSessionEnds(client *attachedClient) chan struct{} {
	stop := make(chan struct{})
	go func() {
		select {
		case <-client.gone:
		case <-s.childExited:
		}
		close(stop)
	}()
	return stop
}

// tellClientTheSessionEnded 는 EXIT 프레임을 보낸다.
func (s *supervisorProcess) tellClientTheSessionEnded(client *attachedClient) {
	// 종료 코드는 자식을 거둔 뒤에야 정해진다.
	select {
	case <-s.childExited:
	case <-client.gone:
		return
	}

	payload, err := json.Marshal(sessionExitPayload{Code: s.childExitCode})
	if err != nil {
		s.events.Printf("세션 종료 알림 인코딩 실패: %v", err)
		return
	}

	// 이 한 프레임에만 마감 시각을 둔다. 붙어 있는 동안의 역압은 그 연결에 머물러야 하지만,
	// 세션이 이미 끝난 뒤라면 받지 않는 클라이언트 하나가 감독을 붙잡아 둘 이유가 없다.
	if err := client.connection.SetWriteDeadline(time.Now().Add(controlConnectionDeadline)); err != nil {
		s.events.Printf("세션 종료 알림 마감 시각 설정 실패: %v", err)
		return
	}
	if err := client.send(exitFrameType, payload); err != nil {
		s.events.Printf("세션 종료 알림 전달 실패: %v", err)
	}
}

// pumpClientInputIntoSession 은 클라이언트가 보내는 프레임을 세션에 옮긴다.
//
// 입력과 크기 변경이 같은 스트림에 실려 오므로 순서가 그대로 지켜진다. 둘을 다른 연결로 나눴다면
// "화면이 커진 뒤에 도착해야 할 키" 와 "키 뒤에 도착해야 할 크기" 를 구별할 방법이 없다(설계 문서 5.6).
func (s *supervisorProcess) pumpClientInputIntoSession(reader io.Reader, client *attachedClient) {
	defer client.disconnect()

	for {
		frameType, payload, err := readAttachFrame(reader)
		if err != nil {
			// 연결이 끝난 것은 detach 다. 끊긴 클라이언트의 읽기 실패도 우리가 끊은 결과라 적지 않는다.
			if !errors.Is(err, io.EOF) && !client.isGone() {
				s.events.Printf("붙어 있는 클라이언트의 프레임 읽기 실패: %v", err)
			}
			return
		}

		switch frameType {
		case inputFrameType:
			if _, err := s.ptyMaster.Write(payload); err != nil {
				s.events.Printf("세션에 키 입력 전달 실패: %v", err)
				return
			}

		case resizeFrameType:
			columns, rows, err := decodeWindowSize(payload)
			if err != nil {
				s.events.Printf("크기 변경 해석 실패: %v", err)
				continue
			}
			s.applySessionWindowSize(columns, rows)

		default:
			// 모르는 프레임에 연결을 끊지 않는다. 판이 다른 클라이언트가 우리가 아직 모르는
			// 프레임을 보내는 것이 프로토콜이 늘어날 때의 정상적인 모습이다.
			s.events.Printf("모르는 attach 프레임입니다 (종류 %#x)", frameType)
		}
	}
}

// applySessionWindowSize 는 세션 PTY 의 화면 크기를 바꾼다.
//
// 커널은 크기가 실제로 바뀔 때만 포그라운드 프로세스 그룹에 SIGWINCH 를 보낸다. 그래서 떼었다가
// 같은 크기의 터미널로 다시 붙으면 세션 안 프로그램은 다시 그리지 않고, 화면은 재생된 바이트가
// 만든 모습에 머문다 — 설계 문서 5.7 이 지목한 한계다.
func (s *supervisorProcess) applySessionWindowSize(columns, rows int) {
	if columns <= 0 || rows <= 0 {
		// 크기를 모르는 클라이언트가 세션 화면을 0 으로 만들면 세션 안 프로그램이 그리기를
		// 멈춘다. 모른다는 것은 바꾸지 말라는 뜻으로 읽는다.
		return
	}

	if err := pty.Setsize(s.ptyMaster, &pty.Winsize{
		Cols: uint16(columns),
		Rows: uint16(rows),
	}); err != nil {
		s.events.Printf("세션 화면 크기 적용 실패 (%d 열 %d 행): %v", columns, rows, err)
	}
}
