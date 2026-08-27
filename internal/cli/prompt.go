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

// interactivePrompt 는 대화형 명령이 사용자와 주고받는 한 벌이다.
//
// io.Reader 와 io.Writer 로 받는 이유는 테스트다. kyu clone 의 흐름 — 프로필을 고르고, 소유자를
// 고르고, 번호를 적는 — 은 실제 터미널 없이는 한 줄도 검증할 수 없게 되기 쉬운데, 그 흐름이야말로
// 사용자가 매번 지나는 자리다.
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

// ask 는 물음을 찍고 한 줄을 읽는다. 앞뒤 공백은 걷어낸다.
func (prompt *interactivePrompt) ask(question string) (string, error) {
	if _, err := fmt.Fprint(prompt.out, question); err != nil {
		return "", fmt.Errorf("물음 출력 실패: %w", err)
	}

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
// 터미널이 아니면 그냥 읽는다. 파이프로 넘어온 입력에는 감출 에코가 없고, 여기서 거절하면
// 테스트가 이 흐름을 지나갈 수 없다.
func (prompt *interactivePrompt) askHidden(question string) (string, error) {
	if prompt.inputFile == nil {
		return prompt.ask(question)
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

// askForNumberedChoice 는 1~optionCount 중 하나를 고르게 한다.
//
// 되묻지 않고 한 번에 끝낸다. 잘못 적은 번호로 다시 묻는 고리를 만들면 파이프로 입력을 넘긴
// 경우에 무한히 돌 수 있고, 사용자는 명령을 다시 실행하면 그만이다.
func (prompt *interactivePrompt) askForNumberedChoice(question string, optionCount int) (int, error) {
	answer, err := prompt.ask(question)
	if err != nil {
		return 0, err
	}

	// 고르지 않은 것을 1 번으로 대신 정해주지 않는다. 이 물음의 답은 곧 무엇을 클론할지로 이어진다.
	if answer == "" {
		return 0, errInputClosed
	}

	chosenNumbers, err := parseRepositorySelection(answer, optionCount)
	if err != nil {
		return 0, err
	}
	if len(chosenNumbers) != 1 {
		return 0, fmt.Errorf("번호 하나만 고를 수 있습니다: %s", answer)
	}
	return chosenNumbers[0], nil
}
