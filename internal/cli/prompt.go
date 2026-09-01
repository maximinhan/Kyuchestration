package cli

import (
	"bufio"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"

	"golang.org/x/term"
)

// errInputClosed 는 사용자가 더 답하지 않고 입력을 끝냈을 때(Ctrl-D) 반환한다.
//
// 실패가 아니라 의사 표시다. 부르는 쪽이 이것을 취소로 옮긴다 — 물음에 답하지 않은 것을
// 에러로 끝내면 종료 코드 1 이 나가고, 스크립트가 그것을 실패로 읽는다.
var errInputClosed = errors.New("입력이 끝났습니다")

// interactivePrompt 는 kyu auth add 가 토큰 한 줄을 받는 자리다.
//
// io.Reader 와 io.Writer 로 받는 이유는 테스트다. 토큰을 받는 흐름은 실제 터미널 없이는
// 한 줄도 검증할 수 없게 되기 쉬운데, 등록은 이 도구를 처음 쓰는 사람이 반드시 지나는 자리다.
type interactivePrompt struct {
	// reader 는 명령 전체에서 하나만 만든다. 물음마다 새로 만들면 앞의 읽기가 버퍼에 남겨둔 입력이
	// 함께 버려져, 한 번에 여러 줄을 붙여넣은 사용자의 답이 사라진다.
	reader *bufio.Reader

	// inputFile 은 입력이 실제 터미널일 때의 그 터미널이다. 아니면 nil 이다.
	//
	// 토큰을 숨겨 받으려면 터미널의 에코를 꺼야 하는데, 그것은 파일 서술자에 대고 하는 일이라
	// io.Reader 만으로는 할 수 없다. 파이프나 테스트 입력이면 끌 에코 자체가 없다.
	inputFile *os.File

	out io.Writer
}

func newInteractivePrompt(in io.Reader, out io.Writer) *interactivePrompt {
	prompt := &interactivePrompt{reader: bufio.NewReader(in), out: out}

	if inputFile, isFile := in.(*os.File); isFile && term.IsTerminal(int(inputFile.Fd())) {
		prompt.inputFile = inputFile
	}
	return prompt
}

// readAnswerLine 은 아무것도 찍지 않고 한 줄만 읽는다. 앞뒤 공백은 걷어낸다.
func (prompt *interactivePrompt) readAnswerLine() (string, error) {
	line, err := prompt.reader.ReadString('\n')

	if errors.Is(err, io.EOF) {
		// 마지막 줄에 개행이 없는 입력(파일·테스트)도 답으로 받는다. 정말 아무것도 오지 않았을 때만
		// 끝난 것으로 본다.
		if strings.TrimSpace(line) == "" {
			return "", errInputClosed
		}
		return strings.TrimSpace(line), nil
	}
	if err != nil {
		return "", fmt.Errorf("입력을 읽지 못했습니다: %w", err)
	}
	return strings.TrimSpace(line), nil
}

// askHidden 은 화면에 남지 않게 한 줄을 읽는다. 토큰을 받는 자리다.
//
// 터미널이 아니면 묻지 않고 읽기만 한다. 파이프 너머에는 이 물음에 답할 상대가 없고 — 토큰은
// 이미 넘어와 있다 — 우리 stderr 를 오류 화면에 그대로 올리는 GUI 에서는, 답할 수 없는 물음이
// 실패 이유인 것처럼 사용자 앞에 놓인다. 감출 에코가 없다는 것도 같은 자리에서 갈린다.
func (prompt *interactivePrompt) askHidden(question string) (string, error) {
	if prompt.inputFile == nil {
		return prompt.readAnswerLine()
	}

	if _, err := fmt.Fprint(prompt.out, question); err != nil {
		return "", fmt.Errorf("물음 출력 실패: %w", err)
	}

	// 버퍼를 거치지 않고 터미널에서 직접 읽는다. 터미널은 한 줄씩 넘겨주므로 앞의 읽기가
	// 다음 줄까지 버퍼에 담아둘 일이 없다 — 파이프였다면 위에서 이미 갈라졌다.
	typed, err := term.ReadPassword(int(prompt.inputFile.Fd()))

	// 에코가 꺼져 있어 사용자가 친 개행이 화면에 남지 않는다. 우리가 줄을 바꾸지 않으면
	// 다음 출력이 물음 뒤에 그대로 이어 붙는다.
	fmt.Fprintln(prompt.out)

	if err != nil {
		if errors.Is(err, io.EOF) {
			return "", errInputClosed
		}
		return "", fmt.Errorf("입력을 읽지 못했습니다: %w", err)
	}
	return strings.TrimSpace(string(typed)), nil
}
