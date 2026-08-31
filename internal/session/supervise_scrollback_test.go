package session

import (
	"bytes"
	"testing"
	"time"
)

// readAllAvailable 은 커서에서 시작해 링에 지금 있는 것을 전부 읽어 모은다.
//
// read 는 링의 감김 지점에서 끊어 돌려주므로, 부르는 쪽은 커서를 물려가며 이어 붙인다 —
// 감독의 출력 펌프가 하는 일과 같은 모양이다.
func readAllAvailable(t *testing.T, scrollback *sessionScrollback, cursor int64) ([]byte, int64) {
	t.Helper()

	var collected []byte
	buffer := make([]byte, 7) // 한 번에 다 담기지 않는 크기라야 이어 붙이는 자리가 시험된다
	for {
		if cursor >= scrollback.writtenBytes() {
			return collected, cursor
		}
		read, next, ok := scrollback.read(cursor, buffer, nil)
		if !ok {
			return collected, cursor
		}
		collected = append(collected, buffer[:read]...)
		cursor = next
	}
}

func TestScrollbackReplaysEverythingWhileItStillFits(t *testing.T) {
	scrollback := newSessionScrollback(64)
	scrollback.append([]byte("첫 줄\n"))
	scrollback.append([]byte("둘째 줄\n"))

	got, _ := readAllAvailable(t, scrollback, scrollback.replayStartOffset())
	const want = "첫 줄\n둘째 줄\n"
	if string(got) != want {
		t.Errorf("재생 = %q, want %q", got, want)
	}
}

func TestScrollbackReplayStartsAtTheBeginningWhileNothingHasBeenDropped(t *testing.T) {
	scrollback := newSessionScrollback(64)
	scrollback.append([]byte("no newline yet"))

	// 아직 버린 바이트가 없으면 재생은 세션의 첫 바이트부터다. 잘린 조각이 있을 수 없으므로
	// 경계를 다듬을 이유도 없다.
	if got := scrollback.replayStartOffset(); got != 0 {
		t.Errorf("replayStartOffset() = %d, 버린 바이트가 없으면 0 이어야 한다", got)
	}
}

func TestScrollbackKeepsTheNewestBytesWhenItOverflows(t *testing.T) {
	scrollback := newSessionScrollback(16)
	scrollback.append([]byte("0123456789"))
	scrollback.append([]byte("abcdefghij"))

	// 링은 고정 크기다. 넘치면 오래된 것부터 버리고 최신 16 바이트가 남는다.
	got, _ := readAllAvailable(t, scrollback, scrollback.oldestOffset())
	const want = "456789abcdefghij"
	if string(got) != want {
		t.Errorf("남은 내용 = %q, want %q", got, want)
	}
}

func TestScrollbackKeepsOnlyTheTailOfAChunkBiggerThanItself(t *testing.T) {
	scrollback := newSessionScrollback(8)
	scrollback.append([]byte("0123456789abcdef"))

	got, _ := readAllAvailable(t, scrollback, scrollback.oldestOffset())
	const want = "89abcdef"
	if string(got) != want {
		t.Errorf("남은 내용 = %q, want %q", got, want)
	}
	if got := scrollback.writtenBytes(); got != 16 {
		t.Errorf("세션이 뱉은 총 바이트 = %d, want 16", got)
	}
}

func TestScrollbackReplaySkipsAPartialLineLeftByDroppedBytes(t *testing.T) {
	// 링이 오래된 바이트를 버릴 때 하필 문자나 이스케이프 시퀀스의 한가운데를 자를 수 있다.
	// 재생을 첫 개행 다음부터 시작하면 그 조각이 화면에 찍히지 않는다 — 개행은 CSI 시퀀스
	// 안에 들어가지 않고, ASCII 라 UTF-8 문자 경계이기도 하다(설계 문서 5.7 대응 1).
	scrollback := newSessionScrollback(16)
	scrollback.append([]byte("\x1b[38;5;204m잘릴 시퀀스\n온전한 줄\n"))

	got, _ := readAllAvailable(t, scrollback, scrollback.replayStartOffset())
	if string(got) != "온전한 줄\n" {
		t.Errorf("재생 = %q, want %q", got, "온전한 줄\n")
	}
}

func TestScrollbackReplayFallsBackToWhatItHasWhenNoLineBoundaryIsLeft(t *testing.T) {
	// 화면 전체를 그리는 프로그램의 출력에는 개행이 없을 수 있다. 그때 재생을 포기하는 것보다
	// 첫 조각을 감수하고 내보내는 편이 낫다 — 화면이 비는 것이 더 나쁘다.
	scrollback := newSessionScrollback(8)
	scrollback.append([]byte("0123456789abcdef"))

	if got := scrollback.replayStartOffset(); got != scrollback.oldestOffset() {
		t.Errorf("replayStartOffset() = %d, 개행이 없으면 가장 오래된 바이트(%d)여야 한다",
			got, scrollback.oldestOffset())
	}
}

func TestScrollbackPullsALaggingCursorUpToWhatItStillHas(t *testing.T) {
	// 느린 클라이언트는 링이 감기는 것보다 뒤처질 수 있다. 그때 없는 바이트를 기다리며 멈추면
	// 그 연결은 영영 끝나지 않는다 — 남아 있는 가장 오래된 바이트로 당겨 이어 보낸다.
	scrollback := newSessionScrollback(8)
	scrollback.append([]byte("01234567"))
	scrollback.append([]byte("89abcdef"))

	buffer := make([]byte, 8)
	read, next, ok := scrollback.read(0, buffer, nil)
	if !ok {
		t.Fatalf("read() = ok false, 남은 내용을 돌려줘야 한다")
	}
	if string(buffer[:read]) != "89abcdef" {
		t.Errorf("뒤처진 커서로 읽은 것 = %q, want %q", buffer[:read], "89abcdef")
	}
	if next != 16 {
		t.Errorf("다음 커서 = %d, want 16", next)
	}
}

func TestScrollbackHandsOutEveryByteExactlyOnceAcrossTheReplayAndLiveBoundary(t *testing.T) {
	// 재생과 실시간이 같은 링에서 나온다는 주장을 확인한다. 붙는 도중에 출력이 계속 들어와도
	// 커서가 이어지므로 경계에서 잃거나 겹치는 바이트가 없다(설계 문서 5.7).
	scrollback := newSessionScrollback(1024)
	scrollback.append([]byte("붙기 전에 찍힌 것\n"))

	cursor := scrollback.replayStartOffset()
	scrollback.append([]byte("붙는 사이에 찍힌 것\n"))
	scrollback.append([]byte("붙은 뒤에 찍힌 것\n"))
	scrollback.finish()

	var collected bytes.Buffer
	buffer := make([]byte, 5)
	for {
		read, next, ok := scrollback.read(cursor, buffer, nil)
		if !ok {
			break
		}
		collected.Write(buffer[:read])
		cursor = next
	}

	const want = "붙기 전에 찍힌 것\n붙는 사이에 찍힌 것\n붙은 뒤에 찍힌 것\n"
	if collected.String() != want {
		t.Errorf("받은 전부 = %q, want %q", collected.String(), want)
	}
}

func TestScrollbackWakesAWaitingReaderWhenNewBytesArrive(t *testing.T) {
	scrollback := newSessionScrollback(64)

	woke := make(chan string, 1)
	go func() {
		buffer := make([]byte, 32)
		read, _, ok := scrollback.read(0, buffer, nil)
		if !ok {
			woke <- ""
			return
		}
		woke <- string(buffer[:read])
	}()

	// 읽는 쪽이 기다리는 자리에 실제로 들어가는지는 조건 없이 기다려 보는 수밖에 없다.
	// 이 잠깐이 지나기 전에 append 가 나가면 시험은 대기 경로를 지나지 않을 뿐 실패하지는 않는다.
	time.Sleep(20 * time.Millisecond)
	scrollback.append([]byte("나중에 온 바이트"))

	select {
	case got := <-woke:
		if got != "나중에 온 바이트" {
			t.Errorf("깨어난 읽기 = %q, want %q", got, "나중에 온 바이트")
		}
	case <-time.After(5 * time.Second):
		t.Fatalf("append 가 기다리던 읽기를 깨우지 못했다")
	}
}

func TestScrollbackEndsWaitingReadersWhenTheSessionOutputEnds(t *testing.T) {
	// 세션 안 프로그램이 끝나면 더 올 바이트가 없다. 기다리던 읽기를 깨워 끝을 알려야
	// 붙어 있는 클라이언트가 EXIT 를 받고 나갈 수 있다.
	scrollback := newSessionScrollback(64)

	ended := make(chan bool, 1)
	go func() {
		buffer := make([]byte, 32)
		_, _, ok := scrollback.read(0, buffer, nil)
		ended <- ok
	}()

	time.Sleep(20 * time.Millisecond)
	scrollback.finish()

	select {
	case ok := <-ended:
		if ok {
			t.Errorf("출력이 끝난 뒤 read() = ok true, false 여야 한다")
		}
	case <-time.After(5 * time.Second):
		t.Fatalf("finish() 가 기다리던 읽기를 깨우지 못했다")
	}
}

func TestScrollbackStopsWaitingWhenTheClientIsGone(t *testing.T) {
	// 새 클라이언트가 오면 앞의 것을 끊는다(설계 문서 5.6). 끊긴 쪽이 링에서 기다리고 있으면
	// 소켓을 닫는 것만으로는 깨어나지 못하므로, 그 연결의 취소 신호로도 깨워야 한다.
	scrollback := newSessionScrollback(64)
	gone := make(chan struct{})

	stopped := make(chan bool, 1)
	go func() {
		buffer := make([]byte, 32)
		_, _, ok := scrollback.read(0, buffer, gone)
		stopped <- ok
	}()

	time.Sleep(20 * time.Millisecond)
	close(gone)

	select {
	case ok := <-stopped:
		if ok {
			t.Errorf("끊긴 클라이언트의 read() = ok true, false 여야 한다")
		}
	case <-time.After(5 * time.Second):
		t.Fatalf("취소 신호가 기다리던 읽기를 깨우지 못했다")
	}
}
