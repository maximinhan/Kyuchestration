package cli

import (
	"fmt"
	"slices"
	"strconv"
	"strings"
)

// selectionRangeSeparator 는 "5-8" 처럼 연속한 번호를 한 번에 고르는 구분자다.
const selectionRangeSeparator = "-"

// selectionItemSeparator 는 고른 것들을 잇는 구분자다.
const selectionItemSeparator = ","

// selectionSyntaxGuidance 는 사용자에게 보여주는 이 문법의 예시다.
//
// 거절할 때마다 다시 싣는다. 문법을 틀린 사람이 알아야 하는 것은 "틀렸다" 가 아니라 "이렇게 적는다" 이고,
// 목록이 길면 위에 찍힌 안내는 이미 화면 밖으로 밀려나 있다.
const selectionSyntaxGuidance = "번호는 1,3,5-8 처럼 적습니다"

// parseRepositorySelection 은 사용자가 적은 "1,3,5-8" 을 0 기반 인덱스 목록으로 옮긴다.
//
// 화면의 번호는 1 부터지만 돌려주는 것은 슬라이스 인덱스다. 두 좌표계를 한 함수 안에서 끝내지 않으면
// 부르는 쪽마다 ±1 을 다시 하게 되고, 그 실수는 사용자가 옆 레포를 클론한 뒤에야 드러난다.
//
// 결과는 오름차순이고 중복이 없다. 적은 순서대로 돌려주면 같은 레포를 두 번 클론하려 들고,
// 클론이 화면의 목록 순서와 다른 순서로 진행되어 어디까지 됐는지 눈으로 따라갈 수 없다.
//
// 빈 입력은 에러가 아니라 빈 선택이다. 그것을 취소로 읽을지 다시 묻을지는 부르는 쪽의 결정이라
// 여기서 정하지 않는다.
func parseRepositorySelection(input string, listedRepositoryCount int) ([]int, error) {
	if strings.TrimSpace(input) == "" {
		return nil, nil
	}

	var selectedIndexes []int
	for _, item := range strings.Split(input, selectionItemSeparator) {
		itemIndexes, err := parseSelectionItem(strings.TrimSpace(item), listedRepositoryCount)
		if err != nil {
			return nil, err
		}
		selectedIndexes = append(selectedIndexes, itemIndexes...)
	}

	slices.Sort(selectedIndexes)
	return slices.Compact(selectedIndexes), nil
}

// parseSelectionItem 은 쉼표로 나뉜 항목 하나("3" 또는 "5-8")를 인덱스로 편다.
func parseSelectionItem(item string, listedRepositoryCount int) ([]int, error) {
	if item == "" {
		return nil, fmt.Errorf("빈 항목이 있습니다 — %s", selectionSyntaxGuidance)
	}

	if !strings.Contains(item, selectionRangeSeparator) {
		selectedNumber, err := parseSelectionNumber(item, listedRepositoryCount)
		if err != nil {
			return nil, err
		}
		return []int{selectedNumber - 1}, nil
	}

	// 구분자가 둘 이상이면("1-2-3") 어느 것이 범위인지 정할 수 없다. 하나를 골라 읽으면
	// 사용자가 적지 않은 범위를 클론하게 되므로, 고르지 않고 되묻는다.
	rangeEnds := strings.Split(item, selectionRangeSeparator)
	if len(rangeEnds) != 2 {
		return nil, fmt.Errorf("범위를 읽지 못했습니다: %s — %s", item, selectionSyntaxGuidance)
	}

	firstNumber, err := parseSelectionNumber(strings.TrimSpace(rangeEnds[0]), listedRepositoryCount)
	if err != nil {
		return nil, fmt.Errorf("범위를 읽지 못했습니다: %s — %w", item, err)
	}
	lastNumber, err := parseSelectionNumber(strings.TrimSpace(rangeEnds[1]), listedRepositoryCount)
	if err != nil {
		return nil, fmt.Errorf("범위를 읽지 못했습니다: %s — %w", item, err)
	}

	// 역순 범위를 뒤집어 읽지 않는다. 8-5 는 오타일 가능성이 높은데, 뒤집어 읽으면 그 오타가
	// 네 개의 레포로 자란 뒤에야 드러난다.
	if firstNumber > lastNumber {
		return nil, fmt.Errorf("범위의 시작이 끝보다 큽니다: %s", item)
	}

	rangeIndexes := make([]int, 0, lastNumber-firstNumber+1)
	for number := firstNumber; number <= lastNumber; number++ {
		rangeIndexes = append(rangeIndexes, number-1)
	}
	return rangeIndexes, nil
}

// parseSelectionNumber 는 화면에 찍힌 번호 하나를 읽는다. 목록 밖의 번호는 거절한다.
func parseSelectionNumber(text string, listedRepositoryCount int) (int, error) {
	number, err := strconv.Atoi(text)
	if err != nil {
		// 원래 에러(strconv.ErrSyntax)를 감싸지 않는다. 사용자가 알아야 하는 것은 자기가 적은
		// 글자이지 파싱 라이브러리의 말이 아니다.
		return 0, fmt.Errorf("번호를 읽지 못했습니다: %s — %s", text, selectionSyntaxGuidance)
	}

	if number < 1 || number > listedRepositoryCount {
		return 0, fmt.Errorf("목록에 없는 번호입니다: %d — 1~%d 사이의 번호여야 합니다", number, listedRepositoryCount)
	}
	return number, nil
}
