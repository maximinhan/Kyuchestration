package cli

import (
	"slices"
	"strings"
	"testing"
)

func TestParseRepositorySelectionAcceptsNumbersCommasAndRanges(t *testing.T) {
	// 이 문법이 kyu clone 의 유일한 입력이다. 여기서 한 칸이라도 어긋나면 사용자는 자기가 고르지 않은
	// 레포를 클론하게 되고, 그 사실은 디렉토리가 생긴 뒤에야 드러난다.
	const listedRepositoryCount = 12

	testCases := []struct {
		name  string
		input string
		want  []int
	}{
		{name: "번호 하나", input: "1", want: []int{0}},
		{name: "쉼표로 나열", input: "1,3", want: []int{0, 2}},
		{name: "범위", input: "5-8", want: []int{4, 5, 6, 7}},
		{name: "쉼표와 범위를 섞음", input: "1,3,5-8", want: []int{0, 2, 4, 5, 6, 7}},
		{name: "시작과 끝이 같은 범위", input: "4-4", want: []int{3}},
		{name: "공백을 섞어 적음", input: " 1 , 3 - 4 ", want: []int{0, 2, 3}},
		{name: "목록의 마지막 번호", input: "12", want: []int{11}},

		// 사용자는 목록을 훑으며 눈에 띄는 대로 번호를 적는다. 그 순서와 중복을 그대로 클론 순서로
		// 삼으면 같은 레포를 두 번 클론하려 들고, 진행 상황을 목록과 대조할 수도 없다.
		{name: "중복은 한 번만", input: "1,1,2", want: []int{0, 1}},
		{name: "겹치는 범위", input: "1-3,2-4", want: []int{0, 1, 2, 3}},
		{name: "적은 순서가 아니라 목록 순서로", input: "3,1", want: []int{0, 2}},

		// 빈 입력은 실패가 아니라 "아무것도 고르지 않았다" 이다. 그것을 취소로 읽을지는 부르는 쪽이 정한다.
		{name: "빈 입력", input: "", want: nil},
		{name: "공백뿐인 입력", input: "   ", want: nil},
	}

	for _, testCase := range testCases {
		t.Run(testCase.name, func(t *testing.T) {
			got, err := parseRepositorySelection(testCase.input, listedRepositoryCount)
			if err != nil {
				t.Fatalf("parseRepositorySelection(%q, %d) 실패: %v", testCase.input, listedRepositoryCount, err)
			}
			if !slices.Equal(got, testCase.want) {
				t.Errorf("parseRepositorySelection(%q, %d) = %v, want %v",
					testCase.input, listedRepositoryCount, got, testCase.want)
			}
		})
	}
}

func TestParseRepositorySelectionRejectsInputItCannotReadExactly(t *testing.T) {
	// 읽지 못한 입력을 그럴듯하게 메우지 않는다. "8-5" 를 5-8 로 고쳐 읽거나 "13" 을 12 로 잘라 읽으면
	// 사용자가 적은 것과 클론되는 것이 달라지는데, 그 차이는 되돌리기 전에는 드러나지 않는다.
	const listedRepositoryCount = 12

	testCases := []struct {
		name              string
		input             string
		wantErrorContains string
	}{
		{name: "역순 범위", input: "8-5", wantErrorContains: "8-5"},
		{name: "0 번", input: "0", wantErrorContains: "1~12"},
		{name: "목록에 없는 번호", input: "13", wantErrorContains: "1~12"},
		{name: "범위 끝이 목록 밖", input: "10-13", wantErrorContains: "1~12"},
		{name: "숫자가 아님", input: "abc", wantErrorContains: "abc"},
		{name: "빈 항목", input: "1,,2", wantErrorContains: "빈"},
		{name: "끝이 없는 범위", input: "1-", wantErrorContains: "1-"},
		{name: "시작이 없는 범위", input: "-3", wantErrorContains: "-3"},
		{name: "구분자가 셋", input: "1-2-3", wantErrorContains: "1-2-3"},
	}

	for _, testCase := range testCases {
		t.Run(testCase.name, func(t *testing.T) {
			got, err := parseRepositorySelection(testCase.input, listedRepositoryCount)
			if err == nil {
				t.Fatalf("parseRepositorySelection(%q, %d) = %v, 거절하기를 기대",
					testCase.input, listedRepositoryCount, got)
			}
			if !strings.Contains(err.Error(), testCase.wantErrorContains) {
				t.Errorf("에러 = %q, %q 를 포함하기를 기대 — 사용자가 자기가 적은 것을 알아볼 수 있어야 한다",
					err.Error(), testCase.wantErrorContains)
			}
		})
	}
}
