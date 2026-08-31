package session

import (
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
)

// attachProtocolVersion 은 attach 연결이 쓰는 판 번호다.
//
// 제어 연결의 판과 따로 센다. 둘은 같은 소켓 위에 있지만 서로 다른 말이고, 한쪽만 바뀌는 일이
// 실제로 생긴다 — attach 에 프레임을 하나 더하는 것이 alive·kill 의 모양을 바꾸지는 않는다.
const attachProtocolVersion = 1

// attach 프레임의 종류다(설계 문서 5.6).
//
// 클라이언트가 보내는 것은 0x00 대, 감독이 보내는 것은 0x80 대다. 손으로 덤프를 들여다볼 때
// 방향이 바이트 하나로 갈리면 읽는 사람이 헷갈릴 자리가 하나 줄어든다.
const (
	attachFrameType byte = 0x01
	inputFrameType  byte = 0x02
	resizeFrameType byte = 0x03
	outputFrameType byte = 0x81
	exitFrameType   byte = 0x82
	errorFrameType  byte = 0x83
)

// attachFrameHeaderLength 는 [종류 1][길이 4] 머리말의 크기다.
const attachFrameHeaderLength = 5

// maxAttachFramePayloadLength 는 프레임 하나가 실을 수 있는 페이로드의 한도다.
//
// 길이 필드는 상대가 적은 숫자다. 그대로 믿고 자리를 잡으면 프레임 하나가 4 GiB 를 달라고 할 수
// 있고, 감독은 세션마다 하나씩 도는 프로세스라 그 요구가 그대로 통한다. 우리가 실제로 보내는
// 것은 출력 덩어리 하나(32 KiB)와 작은 JSON 뿐이라 이 한도는 넉넉하다.
const maxAttachFramePayloadLength = 1 << 20

// attachRequest 는 첫 프레임에 실려 가는 붙기 요청이다.
//
// 세션 이름이 없다. 소켓이 세션을 특정한다. TERM 도 없다 — 그것은 자식이 태어날 때 정해지는
// 값이라 이미 뜬 자식에게 전할 방법이 없다(설계 문서 5.6).
type attachRequest struct {
	Version int `json:"v"`
	Columns int `json:"cols"`
	Rows    int `json:"rows"`
}

// sessionExitPayload 는 EXIT 프레임의 내용이다 — 세션 안 프로그램이 끝났다.
type sessionExitPayload struct {
	Code int `json:"code"`
}

// attachErrorPayload 는 ERROR 프레임의 내용이다.
//
// code 는 안정된 문자열이고 msg 는 아니다. 제어 연결과 같은 어휘를 쓰므로 되살리는 규칙도
// 하나로 남는다(설계 문서 5.5).
type attachErrorPayload struct {
	Code    string `json:"code"`
	Message string `json:"msg,omitempty"`
}

// writeAttachFrame 은 프레임 하나를 스트림에 쓴다.
//
// 머리말과 페이로드를 한 번에 쓴다. 둘로 나눠 쓰면 같은 연결에 쓰는 다른 고루틴이 그 사이에
// 끼어들 수 있고, 그러면 길이가 남의 페이로드를 가리킨다.
func writeAttachFrame(writer io.Writer, frameType byte, payload []byte) error {
	if len(payload) > maxAttachFramePayloadLength {
		return fmt.Errorf("attach 프레임 페이로드가 한도를 넘습니다 (%d 바이트, 한도 %d)",
			len(payload), maxAttachFramePayloadLength)
	}

	frame := make([]byte, attachFrameHeaderLength+len(payload))
	frame[0] = frameType
	binary.BigEndian.PutUint32(frame[1:attachFrameHeaderLength], uint32(len(payload)))
	copy(frame[attachFrameHeaderLength:], payload)

	if _, err := writer.Write(frame); err != nil {
		return fmt.Errorf("attach 프레임 전송 실패 (종류 %#x): %w", frameType, err)
	}
	return nil
}

// readAttachFrame 은 프레임 하나를 읽는다.
//
// 프레임 경계에서 끝난 스트림은 io.EOF 로 돌려준다. 그것이 detach 이기 때문이다 — 연결 자체가
// 곧 붙어 있음이라 닫힘이 곧 떨어짐이고, 그것을 오류로 다루면 정상적인 일이 기록에 실패로
// 쌓인다(설계 문서 5.6). 반대로 프레임 중간에서 끊긴 것은 오류다.
func readAttachFrame(reader io.Reader) (byte, []byte, error) {
	var header [attachFrameHeaderLength]byte
	if _, err := io.ReadFull(reader, header[:]); err != nil {
		if err == io.EOF {
			return 0, nil, io.EOF
		}
		return 0, nil, fmt.Errorf("attach 프레임 머리말 읽기 실패: %w", err)
	}

	frameType := header[0]
	payloadLength := binary.BigEndian.Uint32(header[1:])
	if payloadLength > maxAttachFramePayloadLength {
		return 0, nil, fmt.Errorf("attach 프레임이 한도를 넘는 길이를 실었습니다 (%d 바이트, 한도 %d)",
			payloadLength, maxAttachFramePayloadLength)
	}
	if payloadLength == 0 {
		return frameType, nil, nil
	}

	payload := make([]byte, payloadLength)
	if _, err := io.ReadFull(reader, payload); err != nil {
		return 0, nil, fmt.Errorf("attach 프레임 페이로드 읽기 실패 (종류 %#x, %d 바이트): %w",
			frameType, payloadLength, err)
	}
	return frameType, payload, nil
}

// encodeWindowSize 는 RESIZE 페이로드를 만든다: cols 2 바이트 + rows 2 바이트.
//
// 실을 수 있는 범위로 자른다. 터미널이 말도 안 되는 값을 주더라도 두 바이트 밖으로 넘치면
// 다른 필드를 침범해 프레임 자체가 어긋난다.
func encodeWindowSize(columns, rows int) []byte {
	payload := make([]byte, 4)
	binary.BigEndian.PutUint16(payload[0:2], clampToWindowSizeField(columns))
	binary.BigEndian.PutUint16(payload[2:4], clampToWindowSizeField(rows))
	return payload
}

// decodeWindowSize 는 RESIZE 페이로드를 읽는다.
func decodeWindowSize(payload []byte) (int, int, error) {
	if len(payload) != 4 {
		return 0, 0, fmt.Errorf("크기 변경 페이로드는 4 바이트여야 합니다 (받은 것 %d 바이트)", len(payload))
	}
	return int(binary.BigEndian.Uint16(payload[0:2])), int(binary.BigEndian.Uint16(payload[2:4])), nil
}

func clampToWindowSizeField(value int) uint16 {
	if value < 0 {
		return 0
	}
	if value > 0xffff {
		return 0xffff
	}
	return uint16(value)
}

// encodeAttachErrorPayload 는 ERROR 프레임의 내용을 만든다.
func encodeAttachErrorPayload(code, message string) ([]byte, error) {
	payload, err := json.Marshal(attachErrorPayload{Code: code, Message: message})
	if err != nil {
		return nil, fmt.Errorf("attach 오류 페이로드 인코딩 실패 (코드 %s): %w", code, err)
	}
	return payload, nil
}
