package session

import (
	"fmt"
	"strings"
	"testing"
	"time"
)

// 이 파일은 설계 문서 9 절 4 단계의 **관문 실측**이다.
//
// 2 단계 PR 이 잰 재접속은 링이 아직 한 바퀴도 감기지 않은 세션이었다 — 세션이 뱉은 전부가
// 링에 통째로 들어 있어서 재생이 곧 처음부터 다시 보내기였고, 그래서 화면이 같은 것이
// 당연했다. 5.7 이 지목한 위험은 **링이 감긴 뒤에야** 나타난다. 여기서 그것을 잰다.
//
// 이 시험들은 진짜 감독 프로세스·진짜 PTY 위에서 링 용량을 실제로 넘겨 돌린다. 링 용량을
// 시험용으로 낮추지 않는 이유는, 낮추면 재는 대상이 "이 잠정값에서 무슨 일이 벌어지는가"
// 가 아니라 "임의의 작은 숫자에서 무슨 일이 벌어지는가" 가 되어서다.

// linesBeyondRingCapacity 는 4 MiB 링을 한 바퀴 넘게 감기는 줄 수다.
//
// 한 줄이 색 지정까지 붙어 160 바이트 남짓이라 6 MB 를 넘긴다. 넉넉히 잡는 이유는 이 시험의
// 전제가 "링이 실제로 감겼다" 여서다 — 전제가 아슬아슬하면 시험이 재는 것이 달라진다.
// 줄을 길게 만들어 반복을 줄인다. awk 의 반복 횟수가 이 시험이 쓰는 시간을 정한다.
const linesBeyondRingCapacity = 40000

// bulkOutputAwkProgram 은 색이 섞인 긴 줄을 링 용량 너머까지 뱉는다.
var bulkOutputAwkProgram = fmt.Sprintf(
	`awk 'BEGIN{pad=""; for(j=0;j<3;j++) pad = pad "색과 커서 이동이 섞인 출력물 "; `+
		`for(i=0;i<%d;i++) printf "\033[38;5;%%dm[%%06d] %%s\033[0m\n", (i%%200)+16, i, pad}'`,
	linesBeyondRingCapacity)

// gateDoneMarker 는 세션이 낼 것을 다 냈다는 표시다.
const gateDoneMarker = "측정끝"

// redrawMarker 는 세션이 SIGWINCH 를 받아 다시 그렸다는 표시다.
//
// 다시 그리기가 실제로 일어났는지를 먼저 확인해야 "다시 그렸는데도 모드는 돌아오지 않았다" 가
// 성립한다. 그것을 확인하지 않으면 아무 일도 일어나지 않은 것과 구별되지 않는다.
const redrawMarker = "다시 그림"

// sessionThatSetsModesOnceThenFloodsTheRing 은 기동 때 한 번만 세우는 터미널 모드를 찍고,
// 링 용량을 넘는 출력을 흘린 뒤, 크기 변경에는 다시 그려 답하는 세션이다.
//
// 실제 claude 가 하는 것과 같은 모양이다 — 실측(2026-08-31, claude 2.1.248): 기동 때
// `?2004h`(괄호 붙여넣기) · `?1004h`(포커스 보고) · `?2031h` · `[>4;2m`(확장 키 보고) ·
// `[>5u`(kitty 키보드) 를 **각각 한 번씩** 내고, SIGWINCH 로 다시 그릴 때는 `?2026h/l`
// (동기 출력 묶음) 말고는 아무 모드도 다시 세우지 않는다.
//
// WINCH 덫을 흘려보낸 뒤에 놓는다. bash 는 앞의 명령이 끝나야 덫을 실행하므로, 덫을 먼저
// 놓으면 첫 접속(80x24 → 120x40)이 부른 다시 그리기가 흘려보내기가 끝난 **뒤에** 찍혀
// 링의 꼬리에 남는다. 그러면 "같은 크기 재접속에는 다시 그리기가 없다" 를 잴 수 없다.
var sessionThatSetsModesOnceThenFloodsTheRing = `
printf '\033[?1049h\033[?2004h'
` + bulkOutputAwkProgram + `
printf '측정끝\n'
trap 'printf "다시 그림\n"' WINCH
while :; do read -t 5 -r _ || true; done`

// sessionThatFloodsTheRingWithPlainLines 는 줄 단위로 흐르기만 하는 세션이다 — 셸·빌드 로그 쪽.
var sessionThatFloodsTheRingWithPlainLines = bulkOutputAwkProgram + `; printf '측정끝\n'; sleep 600`

// altScreenEnter 와 bracketedPasteOn 은 화면을 다 쓰는 프로그램이 기동 때 한 번 세우는 모드다.
const (
	altScreenEnter   = "\x1b[?1049h"
	bracketedPasteOn = "\x1b[?2004h"
)

// drainUntilQuiet 은 더 오지 않을 때까지 세션 출력을 모아 돌려준다.
//
// 재생은 프레임 수천 개로 나뉘어 오고 그 끝을 알리는 표식이 따로 없다. "얼마 동안 아무것도
// 오지 않으면 끝" 이 이 자리에서 쓸 수 있는 유일한 판정이다.
func (c *attachedTestClient) drainUntilQuiet(idle time.Duration) string {
	c.t.Helper()

	for {
		if err := c.connection.SetReadDeadline(time.Now().Add(idle)); err != nil {
			c.t.Fatalf("읽기 마감 시각 설정 실패: %v", err)
		}
		frameType, payload, err := readAttachFrame(c.connection)
		if err != nil {
			return c.collected.String()
		}
		if frameType == outputFrameType {
			c.collected.Write(payload)
		}
	}
}

// watchASessionUntilItHasFloodedTheRing 은 세션을 띄우고 계속 붙어 있던 터미널이 받았을 전부를 돌려준다.
//
// 그 전부가 기준이다 — 재접속한 터미널이 무엇을 못 받았는지는 이것과 견줘야만 나온다.
func watchASessionUntilItHasFloodedTheRing(t *testing.T, sessionName, command string) (*SupervisorBackend, string) {
	t.Helper()

	backend := newIsolatedSupervisorBackend(t)
	if err := backend.Create(sessionName, t.TempDir(), []string{"bash", "-c", command}); err != nil {
		t.Fatalf("Create(%q) 실패: %v", sessionName, err)
	}

	watching := attachToSession(t, sessionName, 120, 40)
	watching.readOutputUntil(gateDoneMarker)
	whole := watching.drainUntilQuiet(time.Second)
	watching.close()

	return backend, whole
}

// TestReattachAfterTheRingWrapsNoLongerCarriesTheModesTheSessionSetOnce 는 4 단계를 막는 관문이다.
//
// 세션이 기동 때 한 번만 세운 터미널 모드는 링이 한 바퀴 감기면 재생 창 밖으로 밀려난다.
// 다시 붙은 터미널은 그 모드가 꺼진 채로 세션을 받고, 세션 안 프로그램은 켜져 있다고 믿는다.
// 화면에 보이는 격자가 우연히 같더라도 이것은 어긋난 상태다.
//
// **떨어져 있는 동안 셸이 되세워 주는 모드도 있다** — readline 은 자기 줄 편집을 위해
// `?2004h` 를 스스로 켠다. 그러나 셸이 쓰지 않는 모드(`?1049h` 대체 화면, `?1004h` 포커스
// 보고, `[>4;2m`·`[>5u` 확장 키 보고)는 아무도 되세우지 않는다. 확장 키 보고가 꺼진 터미널은
// 프로그램이 기다리는 키 조합을 다른 바이트로 보낸다 — 화면이 아니라 입력이 어긋난다.
//
// **크기를 바꿔 붙어도 낫지 않는다.** SIGWINCH 는 다시 그리기를 부르지만 다시 그리기는
// 화면에 찍는 일이지 모드를 세우는 일이 아니다. 설계 5.7 의 대응 (2) 는 그리기 쪽만 구제한다.
//
// 이 시험이 뒤집히는 날 — 재생 앞에 감독이 세션 상태를 다시 세워 보내게 되는 날 — 이
// 기본값 전환의 선행 조건이 채워지는 날이다(설계 5.7 의 화면 모델).
func TestReattachAfterTheRingWrapsNoLongerCarriesTheModesTheSessionSetOnce(t *testing.T) {
	const sessionName = "kyu-test-sv-wrap-modes"

	backend, whole := watchASessionUntilItHasFloodedTheRing(t, sessionName, sessionThatSetsModesOnceThenFloodsTheRing)
	defer backend.Kill(sessionName)

	for _, mode := range []string{altScreenEnter, bracketedPasteOn} {
		if !strings.Contains(whole, mode) {
			t.Fatalf("계속 붙어 있던 터미널이 %q 를 받지 못했다 — 이 시험의 기준이 성립하지 않는다", mode)
		}
	}

	// 같은 크기로 다시 붙는다. 크기가 그대로라 커널은 SIGWINCH 를 보내지 않는다.
	again := attachToSession(t, sessionName, 120, 40)
	replay := again.drainUntilQuiet(2 * time.Second)

	if len(replay) >= len(whole) {
		t.Fatalf("재생 %d 바이트가 전체 %d 바이트보다 짧지 않다 — 링이 감기지 않았으므로 이 시험은 아무것도 재지 못한다",
			len(replay), len(whole))
	}
	for _, mode := range []string{altScreenEnter, bracketedPasteOn} {
		if strings.Contains(replay, mode) {
			t.Errorf("같은 크기 재접속의 재생에 %q 가 남아 있다 — 링이 감겼는데도 남았다면 이 시험의 전제를 다시 봐야 한다", mode)
		}
	}
	if strings.Contains(replay, redrawMarker) {
		t.Errorf("같은 크기 재접속인데 세션이 다시 그렸다 — 크기가 그대로면 SIGWINCH 가 가지 않아야 한다")
	}

	// 크기를 바꿔 다시 그리게 한다. 그려지기는 하지만 모드는 돌아오지 않는다.
	again.collected.Reset()
	again.sendResize(100, 30)
	afterResize := again.drainUntilQuiet(2 * time.Second)

	if !strings.Contains(afterResize, redrawMarker) {
		t.Fatalf("크기를 바꿨는데 세션이 다시 그리지 않았다 (받은 것 %q) — 이 시험의 뒷부분이 성립하지 않는다", afterResize)
	}
	for _, mode := range []string{altScreenEnter, bracketedPasteOn} {
		if strings.Contains(afterResize, mode) {
			t.Errorf("다시 그리기가 %q 를 되세웠다 — 그렇다면 크기가 다른 재접속은 구제된다", mode)
		}
	}
}

// TestReattachAfterTheRingWrapsStillCarriesTheNewestBytesFromALineBoundary 는 관문이 막지 않는 쪽이다.
//
// 줄 단위로 흐르기만 하는 출력(셸·빌드 로그)은 링이 감긴 뒤에도 온전하다. 재생은 계속 붙어
// 있던 터미널이 받았을 것의 **꼬리 그대로**이고, 그 꼬리는 줄 경계에서 시작한다 — 링이 버린
// 자리가 이스케이프 시퀀스나 UTF-8 문자의 한가운데였더라도 잘린 조각이 화면에 찍히지 않는다
// (설계 5.7 대응 1, `replayStartOffset`).
func TestReattachAfterTheRingWrapsStillCarriesTheNewestBytesFromALineBoundary(t *testing.T) {
	const sessionName = "kyu-test-sv-wrap-tail"

	backend, whole := watchASessionUntilItHasFloodedTheRing(t, sessionName, sessionThatFloodsTheRingWithPlainLines)
	defer backend.Kill(sessionName)

	again := attachToSession(t, sessionName, 120, 40)
	replay := again.drainUntilQuiet(2 * time.Second)

	if len(replay) >= len(whole) {
		t.Fatalf("재생 %d 바이트가 전체 %d 바이트보다 짧지 않다 — 링이 감기지 않았다", len(replay), len(whole))
	}

	replayStart := len(whole) - len(replay)
	if got := whole[replayStart:]; got != replay {
		t.Errorf("재생이 계속 붙어 있던 터미널이 받은 것의 꼬리와 다르다 (재생 앞 60 바이트 %q, 같은 자리의 원본 %q)",
			replay[:min(60, len(replay))], got[:min(60, len(got))])
	}
	if whole[replayStart-1] != '\n' {
		t.Errorf("재생이 줄 한가운데에서 시작한다 (앞 바이트 %#x, 재생 앞 60 바이트 %q)",
			whole[replayStart-1], replay[:min(60, len(replay))])
	}
	if !strings.HasSuffix(strings.TrimRight(replay, "\r\n"), gateDoneMarker) {
		t.Errorf("재생이 세션의 마지막 출력으로 끝나지 않는다 (끝 60 바이트 %q)", replay[max(0, len(replay)-60):])
	}
}
