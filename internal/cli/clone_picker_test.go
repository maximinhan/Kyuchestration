package cli

import (
	"bytes"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/charmbracelet/bubbles/list"
	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"

	"github.com/maximinhan/Kyuchestration/internal/github"
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

// 아래는 화면의 키 조작 검증이다. bubbletea 의 Update 는 메시지 하나를 받아 다음 상태를
// 돌려주는 함수라, 실제 터미널 없이 키 순서를 그대로 넣어볼 수 있다.

var (
	upKey     = tea.KeyMsg{Type: tea.KeyUp}
	downKey   = tea.KeyMsg{Type: tea.KeyDown}
	enterKey  = tea.KeyMsg{Type: tea.KeyEnter}
	escapeKey = tea.KeyMsg{Type: tea.KeyEsc}

	// spaceKey 에 Runes 를 함께 담는 이유는 검색창 때문이다. bubbletea 는 사용자가 친 스페이스를
	// Type=KeySpace 로 주면서 Runes 에도 그 글자를 담는데, 검색창은 Runes 를 읽어 글자를 넣는다.
	spaceKey = tea.KeyMsg{Type: tea.KeySpace, Runes: []rune{' '}}
)

// typedKeys 는 글자를 한 글자씩 친 키 순서로 옮긴다.
func typedKeys(typed string) []tea.KeyMsg {
	keys := make([]tea.KeyMsg, 0, len(typed))
	for _, typedRune := range typed {
		keys = append(keys, tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune{typedRune}})
	}
	return keys
}

// newTestPicker 는 화면 크기까지 받은 선택 화면을 만든다.
//
// 크기를 먼저 넣는다. 실제 프로그램은 뜨자마자 터미널 크기를 알려주는데, 그것 없이 시작하면
// 한 쪽에 한 줄만 담기는 목록이 되어 쪽 넘김이 검증에서 빠진다.
func newTestPicker(t *testing.T, rows []repositoryRow) repositoryPickerModel {
	t.Helper()

	sized, _ := newRepositoryPickerModel(github.Owner{Login: "maximinhan"}, rows).
		Update(tea.WindowSizeMsg{Width: 80, Height: 24})
	return sized.(repositoryPickerModel)
}

// pressKeys 는 키를 차례로 넣고, 화면이 닫혔는지까지 돌려준다.
func pressKeys(t *testing.T, model repositoryPickerModel, keys ...tea.KeyMsg) (repositoryPickerModel, bool) {
	t.Helper()

	for _, pressedKey := range keys {
		var hasClosed bool
		model, hasClosed = sendMessage(t, model, pressedKey)
		if hasClosed {
			return model, true
		}
	}
	return model, false
}

// sendMessage 는 메시지 하나를 넣고, 그것이 낳은 명령의 결과 메시지까지 이어서 넣는다.
//
// bubbletea 프로그램이 하는 일을 테스트가 대신한다. / 로 좁히는 동작은 명령이 돌려주는
// 메시지(검색 결과)를 다시 받아야 완성되므로, 키만 넣고 끝내면 검색은 한 글자도 걸리지 않는다.
func sendMessage(t *testing.T, model repositoryPickerModel, message tea.Msg) (repositoryPickerModel, bool) {
	t.Helper()

	updatedModel, command := model.Update(message)
	return runCommand(t, updatedModel.(repositoryPickerModel), command)
}

// runCommand 는 명령을 실행해 나온 메시지를 화면에 되먹인다. 화면을 닫는 명령이면 거기서 멈춘다.
func runCommand(t *testing.T, model repositoryPickerModel, command tea.Cmd) (repositoryPickerModel, bool) {
	t.Helper()

	if command == nil {
		return model, false
	}

	switch producedMessage := command().(type) {
	case tea.QuitMsg:
		return model, true

	case tea.BatchMsg:
		for _, batchedCommand := range producedMessage {
			var hasClosed bool
			model, hasClosed = runCommand(t, model, batchedCommand)
			if hasClosed {
				return model, true
			}
		}
		return model, false

	case list.FilterMatchesMsg:
		return sendMessage(t, model, producedMessage)

	default:
		// 나머지는 흘려보낸다. 이 화면의 상태를 바꾸는 것은 검색 결과뿐이고, 전부 따라가면
		// 자기 자신을 다시 부르는 메시지(커서 깜빡임 같은)에서 테스트가 끝나지 않는다.
		return model, false
	}
}

// threeRepositoryRows 는 검증에 쓰는 세 줄이다.
func threeRepositoryRows() []repositoryRow {
	return []repositoryRow{
		makePickerRow("proj-a", false, false),
		makePickerRow("other-b", false, false),
		makePickerRow("proj-c", false, false),
	}
}

func TestPickerMovesTheCursorWithArrowKeysAndWithJK(t *testing.T) {
	// 화살표만 받으면 손을 홈 포지션에서 떼야 한다. j/k 는 이런 화면의 관습이라 둘 다 받는다.
	model := newTestPicker(t, threeRepositoryRows())

	model, _ = pressKeys(t, model, downKey, downKey)
	if model.repositoryList.Index() != 2 {
		t.Errorf("아래 두 번 뒤 커서 = %d, want 2", model.repositoryList.Index())
	}

	model, _ = pressKeys(t, model, typedKeys("k")...)
	if model.repositoryList.Index() != 1 {
		t.Errorf("k 뒤 커서 = %d, want 1", model.repositoryList.Index())
	}

	model, _ = pressKeys(t, model, typedKeys("j")...)
	if model.repositoryList.Index() != 2 {
		t.Errorf("j 뒤 커서 = %d, want 2", model.repositoryList.Index())
	}
}

func TestPickerEnterClonesTheRowUnderTheCursorWhenNothingIsSelected(t *testing.T) {
	// 하나만 클론하는 것이 가장 흔한 사용이다. 그때까지 스페이스를 먼저 누르게 하면 가장 흔한
	// 일이 두 번의 키를 요구하게 된다.
	model := newTestPicker(t, threeRepositoryRows())

	model, hasClosed := pressKeys(t, model, downKey, enterKey)
	if !hasClosed {
		t.Fatal("엔터를 눌렀는데 화면이 닫히지 않았습니다")
	}

	if got := model.selectedRowIndexes(); len(got) != 1 || got[0] != 1 {
		t.Errorf("고른 줄 = %v, want [1]", got)
	}
}

func TestPickerSpaceSelectsSeveralRowsAndEnterClonesThemInListOrder(t *testing.T) {
	// 여러 레포를 한 번에 클론하는 것이 이 도구의 존재 이유다(설계 문서 1.1). 클론 순서는 목록
	// 순서여야 어디까지 됐는지 눈으로 따라갈 수 있다 — 고른 순서로 하면 위아래로 튄다.
	model := newTestPicker(t, threeRepositoryRows())

	model, hasClosed := pressKeys(t, model,
		downKey, downKey, spaceKey, // proj-c 를 먼저 고르고
		upKey, upKey, spaceKey, // proj-a 를 나중에 고른다
		enterKey)
	if !hasClosed {
		t.Fatal("엔터를 눌렀는데 화면이 닫히지 않았습니다")
	}

	got := model.selectedRowIndexes()
	if len(got) != 2 || got[0] != 0 || got[1] != 2 {
		t.Errorf("고른 줄 = %v, want [0 2]", got)
	}
}

func TestPickerSpaceTwiceOnTheSameRowLeavesItUnselected(t *testing.T) {
	// 잘못 고른 것을 되돌리는 방법이 없으면 사용자는 화면을 닫고 처음부터 다시 해야 한다.
	model := newTestPicker(t, threeRepositoryRows())

	model, _ = pressKeys(t, model, spaceKey, spaceKey)
	if len(model.toggledRowIndexes) != 0 {
		t.Errorf("고른 줄 = %v, 다시 눌러 꺼지기를 기대", model.toggledRowIndexes)
	}

	// 아무것도 켜지지 않았으므로 엔터는 커서가 있는 한 줄만 고른다.
	model, _ = pressKeys(t, model, enterKey)
	if got := model.selectedRowIndexes(); len(got) != 1 || got[0] != 0 {
		t.Errorf("고른 줄 = %v, want [0]", got)
	}
}

func TestPickerQuitsWithoutSelectingAnythingOnQ(t *testing.T) {
	// 목록을 보고 그만두는 것은 실패가 아니다 — 번호를 적던 때의 빈 줄과 같은 뜻이어야 한다.
	model := newTestPicker(t, threeRepositoryRows())

	model, hasClosed := pressKeys(t, model, spaceKey, typedKeys("q")[0])
	if !hasClosed {
		t.Fatal("q 를 눌렀는데 화면이 닫히지 않았습니다")
	}

	if got := model.selectedRowIndexes(); len(got) != 0 {
		t.Errorf("고른 줄 = %v, 취소했으므로 아무것도 아니기를 기대", got)
	}
}

func TestPickerQuitsWithoutSelectingAnythingOnEscapeWhenNoFilterIsSet(t *testing.T) {
	// 검색이 걸려 있지 않은 esc 는 나가겠다는 뜻이다. 아무 일도 하지 않으면 사용자는 q 를 따로
	// 찾아야 한다.
	model := newTestPicker(t, threeRepositoryRows())

	model, hasClosed := pressKeys(t, model, escapeKey)
	if !hasClosed {
		t.Fatal("esc 를 눌렀는데 화면이 닫히지 않았습니다")
	}
	if got := model.selectedRowIndexes(); len(got) != 0 {
		t.Errorf("고른 줄 = %v, 취소했으므로 아무것도 아니기를 기대", got)
	}
}

func TestPickerEscapeClearsTheFilterInsteadOfQuittingWhileAFilterIsApplied(t *testing.T) {
	// 좁혀 놓은 목록을 되돌리려던 사용자를 명령 밖으로 내보내면, 프로필 선택부터 다시 지나야 한다.
	model := newTestPicker(t, threeRepositoryRows())

	keys := append([]tea.KeyMsg{typedKeys("/")[0]}, typedKeys("other")...)
	keys = append(keys, enterKey, escapeKey)

	model, hasClosed := pressKeys(t, model, keys...)
	if hasClosed {
		t.Fatal("검색을 풀려던 esc 가 화면을 닫았습니다")
	}
	if model.repositoryList.FilterState() != list.Unfiltered {
		t.Errorf("검색 상태 = %v, want %v", model.repositoryList.FilterState(), list.Unfiltered)
	}
	if len(model.repositoryList.VisibleItems()) != 3 {
		t.Errorf("보이는 줄 = %d, 검색을 풀어 세 줄 모두 보이기를 기대", len(model.repositoryList.VisibleItems()))
	}
}

func TestPickerFilterNarrowsTheListAndEnterClonesTheMatchedRepository(t *testing.T) {
	// 레포가 많으면 눈으로 훑는 것보다 이름을 치는 것이 빠르다. 좁힌 뒤 고른 것이 원본 목록의
	// 어느 줄인지 잃지 않아야 엉뚱한 레포를 클론하지 않는다.
	model := newTestPicker(t, threeRepositoryRows())

	keys := append([]tea.KeyMsg{typedKeys("/")[0]}, typedKeys("other")...)

	model, _ = pressKeys(t, model, keys...)
	if len(model.repositoryList.VisibleItems()) != 1 {
		t.Fatalf("보이는 줄 = %d, other-b 하나로 좁혀지기를 기대", len(model.repositoryList.VisibleItems()))
	}

	// 검색 중의 엔터는 검색어를 확정하는 키다. 클론까지 가려면 한 번 더 눌러야 한다.
	model, hasClosed := pressKeys(t, model, enterKey)
	if hasClosed {
		t.Fatal("검색어를 확정하려던 엔터가 화면을 닫았습니다")
	}

	model, hasClosed = pressKeys(t, model, enterKey)
	if !hasClosed {
		t.Fatal("두 번째 엔터에서도 화면이 닫히지 않았습니다")
	}
	if got := model.selectedRowIndexes(); len(got) != 1 || got[0] != 1 {
		t.Errorf("고른 줄 = %v, want [1] (other-b 의 원본 자리)", got)
	}
}

func TestPickerSpaceTypesIntoTheFilterInsteadOfSelectingWhileTyping(t *testing.T) {
	// 검색어를 치는 중의 스페이스는 검색어의 공백이다. 그것을 선택 키로 집어가면 사용자가
	// 적으려던 글자가 사라지고, 보이지도 않는 줄이 조용히 켜진다.
	model := newTestPicker(t, threeRepositoryRows())

	keys := append([]tea.KeyMsg{typedKeys("/")[0]}, typedKeys("proj")...)
	keys = append(keys, spaceKey)

	model, hasClosed := pressKeys(t, model, keys...)
	if hasClosed {
		t.Fatal("검색어를 치던 중 화면이 닫혔습니다")
	}

	if len(model.toggledRowIndexes) != 0 {
		t.Errorf("고른 줄 = %v, 검색 중에는 아무것도 골라지지 않기를 기대", model.toggledRowIndexes)
	}
	if model.repositoryList.FilterValue() != "proj " {
		t.Errorf("검색어 = %q, want %q", model.repositoryList.FilterValue(), "proj ")
	}
}

func TestPickerQAndEnterAreTypedIntoTheFilterWhileTyping(t *testing.T) {
	// q 와 엔터도 마찬가지다 — q 는 레포 이름의 글자이고, 검색 중의 엔터는 검색어 확정이다.
	model := newTestPicker(t, []repositoryRow{
		makePickerRow("query-runner", false, false),
		makePickerRow("other-b", false, false),
	})

	keys := append([]tea.KeyMsg{typedKeys("/")[0]}, typedKeys("q")...)

	model, hasClosed := pressKeys(t, model, keys...)
	if hasClosed {
		t.Fatal("검색어로 친 q 가 화면을 닫았습니다")
	}
	if model.repositoryList.FilterValue() != "q" {
		t.Errorf("검색어 = %q, want %q", model.repositoryList.FilterValue(), "q")
	}
}

func TestPickerHelpLineNamesTheKeysThatAreNotTheListsOwn(t *testing.T) {
	// 스페이스·엔터·q 는 리스트가 모르는 키라, 적지 않으면 화면 아래에는 "위/아래/검색" 만 남는다.
	// 그러면 여러 개를 고를 수 있다는 사실을 알 방법이 없다.
	model := newTestPicker(t, threeRepositoryRows())

	rendered := withoutStyling(model.View())
	for _, expectedHelp := range []string{"space", "선택", "enter", "클론", "q", "취소", "/", "검색"} {
		if !strings.Contains(rendered, expectedHelp) {
			t.Errorf("화면에 %q 안내가 없습니다:\n%s", expectedHelp, rendered)
		}
	}
}
