package cli

import (
	"bytes"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/charmbracelet/bubbles/list"
	"github.com/charmbracelet/lipgloss"
)

// ansiStylingSequence 는 lipgloss 가 색·굵기를 넣으려고 붙이는 이스케이프다.
var ansiStylingSequence = regexp.MustCompile("\x1b\\[[0-9;]*m")

// withoutStyling 은 그려진 화면에서 스타일 이스케이프를 걷어낸다.
//
// 걷어내지 않으면 테스트가 "흐리게" 를 어떤 코드로 표현하는지까지 못박게 되는데, 그것은
// 사용자의 결정도 이 코드의 결정도 아니고 lipgloss 의 것이다. 여기서 검증할 것은 어떤 글자가
// 어떤 자리에 찍혔는가다.
func withoutStyling(rendered string) string {
	return ansiStylingSequence.ReplaceAllString(rendered, "")
}

// makePickerRow 는 선택 화면의 한 줄을 만든다.
func makePickerRow(name string, isPrivate, isAlreadyInWorkDir bool) repositoryRow {
	return repositoryRow{
		repository:         makeRepository(name, isPrivate, time.Date(2026, 8, 27, 12, 0, 0, 0, time.Local)),
		destinationPath:    filepath.Join("workdir", name),
		isAlreadyInWorkDir: isAlreadyInWorkDir,
	}
}

// renderPickerRows 는 행들을 델리게이트로 그려 줄 단위로 돌려준다.
//
// 리스트를 실제로 하나 만들어 넘긴다 — 델리게이트가 커서 위치와 화면 너비를 리스트에서 읽기
// 때문에, 그것을 흉내 낸 값으로 대신하면 검증한 것과 사용자가 보는 것이 갈라진다.
func renderPickerRows(t *testing.T, rows []repositoryRow, toggledRowIndexes map[int]bool, cursorIndex int) []string {
	t.Helper()

	items := repositoryPickerItems(rows)
	delegate := newRepositoryPickerDelegate(rows, toggledRowIndexes)

	repositoryList := list.New(items, delegate, 80, 20)
	repositoryList.Select(cursorIndex)

	renderedRows := make([]string, 0, len(rows))
	for rowIndex, item := range items {
		var rendered bytes.Buffer
		delegate.Render(&rendered, repositoryList, rowIndex, item)
		renderedRows = append(renderedRows, withoutStyling(rendered.String()))
	}
	return renderedRows
}

func TestPickerRowShowsTheNameVisibilityAndUpdatedDateInFixedColumns(t *testing.T) {
	// 이름만 나열하면 사용자는 자기가 찾는 레포를 목록에서 알아보지 못한다. 열이 고정이어야
	// 눈이 한 자리만 따라 내려간다 — 이름 길이에 따라 날짜가 좌우로 흔들리면 훑을 수 없다.
	rows := []repositoryRow{
		makePickerRow("proj-a", true, false),
		makePickerRow("아주-긴-이름의-레포", false, false),
	}

	renderedRows := renderPickerRows(t, rows, map[int]bool{}, 0)

	if !strings.Contains(renderedRows[0], "proj-a") || !strings.Contains(renderedRows[0], "private") {
		t.Errorf("첫 줄 = %q, 이름과 private 표시를 기대", renderedRows[0])
	}
	if !strings.Contains(renderedRows[0], "2026-08-27") {
		t.Errorf("첫 줄 = %q, 마지막 갱신 날짜를 기대", renderedRows[0])
	}
	if strings.Contains(renderedRows[1], "private") {
		t.Errorf("둘째 줄 = %q, 공개 레포에는 표시를 붙이지 않기를 기대", renderedRows[1])
	}

	// 열이 고정이라면 두 줄의 날짜는 같은 칸에서 시작한다.
	if displayColumnOf(t, renderedRows[0], "2026-08-27") != displayColumnOf(t, renderedRows[1], "2026-08-27") {
		t.Errorf("날짜 자리가 줄마다 다릅니다:\n%q\n%q", renderedRows[0], renderedRows[1])
	}
}

// displayColumnOf 는 줄 안에서 그 글자가 시작하는 화면 칸을 돌려준다.
//
// 바이트 위치로 재지 않는다. 한글은 한 글자가 세 바이트에 두 칸이라, 바이트로 재면 열이 맞는
// 두 줄을 어긋났다고 읽는다 — 이 목록에는 한글 이름의 레포가 섞인다.
func displayColumnOf(t *testing.T, line, text string) int {
	t.Helper()

	byteIndex := strings.Index(line, text)
	if byteIndex < 0 {
		t.Fatalf("줄 %q 안에 %q 가 없습니다", line, text)
	}
	return lipgloss.Width(line[:byteIndex])
}

func TestPickerRowMarksTheRowUnderTheCursor(t *testing.T) {
	// 커서가 어디 있는지 보이지 않으면 화살표로 옮기는 화면은 아무 소용이 없다.
	rows := []repositoryRow{
		makePickerRow("proj-a", false, false),
		makePickerRow("proj-b", false, false),
	}

	renderedRows := renderPickerRows(t, rows, map[int]bool{}, 1)

	if strings.Contains(renderedRows[0], cursorMarker) {
		t.Errorf("커서가 없는 줄 = %q, 표시가 없기를 기대", renderedRows[0])
	}
	if !strings.Contains(renderedRows[1], cursorMarker) {
		t.Errorf("커서가 있는 줄 = %q, 커서 표시를 기대", renderedRows[1])
	}
}

func TestPickerRowShowsWhichRowsAreToggledOn(t *testing.T) {
	// 여러 개를 고르는 화면에서 무엇이 켜져 있는지 보이지 않으면, 사용자는 엔터를 누르기 전까지
	// 자기가 무엇을 클론하려는지 알 수 없다.
	rows := []repositoryRow{
		makePickerRow("proj-a", false, false),
		makePickerRow("proj-b", false, false),
	}

	renderedRows := renderPickerRows(t, rows, map[int]bool{1: true}, 0)

	if !strings.Contains(renderedRows[0], unselectedRowMark) {
		t.Errorf("고르지 않은 줄 = %q, %s 를 기대", renderedRows[0], unselectedRowMark)
	}
	if !strings.Contains(renderedRows[1], selectedRowMark) {
		t.Errorf("고른 줄 = %q, %s 를 기대", renderedRows[1], selectedRowMark)
	}
}

func TestPickerRowSaysWhichRepositoriesAreAlreadyInTheWorkDir(t *testing.T) {
	// 이미 있는 레포를 목록에서 빼면 사용자는 "그 레포가 왜 안 보이지" 를 묻게 되고, 그대로 두면
	// 없는 것과 구분되지 않는다. 남기되 흐리게 두고 이유를 적는다.
	rows := []repositoryRow{
		makePickerRow("proj-a", false, true),
		makePickerRow("proj-b", false, false),
	}

	renderedRows := renderPickerRows(t, rows, map[int]bool{}, 0)

	if !strings.Contains(renderedRows[0], alreadyInWorkDirNote) {
		t.Errorf("이미 있는 레포의 줄 = %q, %s 표시를 기대", renderedRows[0], alreadyInWorkDirNote)
	}
	if strings.Contains(renderedRows[1], alreadyInWorkDirNote) {
		t.Errorf("없는 레포의 줄 = %q, 표시가 없기를 기대", renderedRows[1])
	}
}
