package session

import (
	"bytes"
	"encoding/binary"
	"errors"
	"io"
	"strings"
	"testing"
)

func TestAttachFrameSurvivesARoundTrip(t *testing.T) {
	tests := []struct {
		scenario  string
		frameType byte
		payload   []byte
	}{
		{scenario: "붙기 요청", frameType: attachFrameType, payload: []byte(`{"v":1,"cols":120,"rows":40}`)},
		{scenario: "키 입력", frameType: inputFrameType, payload: []byte{0x1b, 0x5b, 0x41}},
		{scenario: "크기 변경", frameType: resizeFrameType, payload: encodeWindowSize(120, 40)},
		{scenario: "세션 출력", frameType: outputFrameType, payload: []byte("색이 섞인 바이트\x1b[0m")},
		{scenario: "빈 페이로드", frameType: exitFrameType, payload: nil},
	}

	for _, tt := range tests {
		t.Run(tt.scenario, func(t *testing.T) {
			var wire bytes.Buffer
			if err := writeAttachFrame(&wire, tt.frameType, tt.payload); err != nil {
				t.Fatalf("writeAttachFrame() 실패: %v", err)
			}

			frameType, payload, err := readAttachFrame(&wire)
			if err != nil {
				t.Fatalf("readAttachFrame() 실패: %v", err)
			}
			if frameType != tt.frameType {
				t.Errorf("프레임 종류 = %#x, want %#x", frameType, tt.frameType)
			}
			if !bytes.Equal(payload, tt.payload) {
				t.Errorf("페이로드 = %q, want %q", payload, tt.payload)
			}
			if wire.Len() != 0 {
				t.Errorf("프레임 하나를 읽고 %d 바이트가 남았다, 경계가 정확히 맞아야 한다", wire.Len())
			}
		})
	}
}

func TestAttachFramesKeepTheirOrderAndBoundariesInOneStream(t *testing.T) {
	// 입력과 크기 변경은 순서가 있다. 같은 스트림에 섞여 순서대로 처리되어야 한다(설계 문서 5.6).
	var wire bytes.Buffer
	writes := []struct {
		frameType byte
		payload   []byte
	}{
		{inputFrameType, []byte("ab")},
		{resizeFrameType, encodeWindowSize(80, 24)},
		{inputFrameType, []byte("cd")},
	}
	for _, write := range writes {
		if err := writeAttachFrame(&wire, write.frameType, write.payload); err != nil {
			t.Fatalf("writeAttachFrame() 실패: %v", err)
		}
	}

	for i, want := range writes {
		frameType, payload, err := readAttachFrame(&wire)
		if err != nil {
			t.Fatalf("%d 번째 readAttachFrame() 실패: %v", i, err)
		}
		if frameType != want.frameType || !bytes.Equal(payload, want.payload) {
			t.Errorf("%d 번째 프레임 = (%#x, %q), want (%#x, %q)", i, frameType, payload, want.frameType, want.payload)
		}
	}
}

func TestReadAttachFrameRefusesALengthItWouldHaveToTrust(t *testing.T) {
	// 길이 필드는 상대가 적은 숫자다. 그대로 믿고 자리를 잡으면 프레임 하나가 메모리를 통째로
	// 달라고 할 수 있다 — 감독은 세션마다 하나씩 도는 프로세스라 그 요구가 그대로 통한다.
	var wire bytes.Buffer
	wire.WriteByte(inputFrameType)
	binary.Write(&wire, binary.BigEndian, uint32(maxAttachFramePayloadLength+1))

	if _, _, err := readAttachFrame(&wire); err == nil {
		t.Fatalf("readAttachFrame() = nil, 한도를 넘는 길이를 거절해야 한다")
	}
}

func TestReadAttachFrameReportsAStreamThatEndedMidFrame(t *testing.T) {
	// 세션 안 프로그램이 정상 종료한 것과 연결이 깨진 것은 사용자에게 다른 말을 해야 하는
	// 사건이다(설계 문서 5.6). 잘린 프레임을 정상 종료로 읽으면 그 둘이 뭉개진다.
	var wire bytes.Buffer
	if err := writeAttachFrame(&wire, inputFrameType, []byte("잘릴 페이로드")); err != nil {
		t.Fatalf("writeAttachFrame() 실패: %v", err)
	}
	truncated := bytes.NewReader(wire.Bytes()[:wire.Len()-3])

	_, _, err := readAttachFrame(truncated)
	if err == nil {
		t.Fatalf("잘린 프레임에서 readAttachFrame() = nil, 오류여야 한다")
	}
	if errors.Is(err, io.EOF) {
		t.Errorf("잘린 프레임의 오류 = %v, 깨끗한 EOF 와 구별되어야 한다", err)
	}
}

func TestReadAttachFrameReportsACleanEndOfStreamAsEOF(t *testing.T) {
	// 프레임 경계에서 끝난 연결은 detach 다 — 연결 자체가 곧 붙어 있음이므로(설계 문서 5.6)
	// 그 끝이 오류로 읽히면 정상적인 detach 가 기록에 실패로 쌓인다.
	_, _, err := readAttachFrame(bytes.NewReader(nil))
	if !errors.Is(err, io.EOF) {
		t.Errorf("빈 스트림의 readAttachFrame() = %v, io.EOF 여야 한다", err)
	}
}

func TestWindowSizeSurvivesARoundTrip(t *testing.T) {
	columns, rows, err := decodeWindowSize(encodeWindowSize(203, 61))
	if err != nil {
		t.Fatalf("decodeWindowSize() 실패: %v", err)
	}
	if columns != 203 || rows != 61 {
		t.Errorf("크기 = %d 열 %d 행, want 203 열 61 행", columns, rows)
	}
}

func TestDecodeWindowSizeRefusesAPayloadThatIsNotFourBytes(t *testing.T) {
	if _, _, err := decodeWindowSize([]byte{0, 80, 0}); err == nil {
		t.Errorf("decodeWindowSize() = nil, 네 바이트가 아니면 거절해야 한다")
	}
}

func TestEncodeWindowSizeClampsSizesThatDoNotFitTheWire(t *testing.T) {
	// 크기는 두 바이트씩 실려 간다. 터미널이 말도 안 되는 값을 주더라도(음수·65536 이상)
	// 다른 필드로 흘러넘치면 프레임 자체가 어긋나므로 실을 수 있는 값으로 자른다.
	columns, rows, err := decodeWindowSize(encodeWindowSize(-1, 1<<20))
	if err != nil {
		t.Fatalf("decodeWindowSize() 실패: %v", err)
	}
	if columns != 0 || rows != 0xffff {
		t.Errorf("자른 크기 = %d 열 %d 행, want 0 열 65535 행", columns, rows)
	}
}

func TestAttachErrorPayloadCarriesAStableCode(t *testing.T) {
	// code 는 안정된 문자열이고 msg 는 아니다 — 제어 연결과 같은 규율이다(설계 문서 5.5).
	payload, err := encodeAttachErrorPayload(badProtocolVersionCode, "이 세션은 옛 판입니다")
	if err != nil {
		t.Fatalf("encodeAttachErrorPayload() 실패: %v", err)
	}
	if !strings.Contains(string(payload), badProtocolVersionCode) {
		t.Errorf("오류 페이로드 = %q, 코드 %q 를 담아야 한다", payload, badProtocolVersionCode)
	}
}
