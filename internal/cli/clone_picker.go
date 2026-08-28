package cli

import (
	"fmt"
	"io"

	"github.com/charmbracelet/bubbles/list"
	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
)

// 이 파일은 kyu clone 의 레포 선택 화면이다 — 설계 문서 10절의 v2("자체 TUI (bubbletea)")가
// 여기서 시작한다.
//
// 번호를 적는 방식은 레포가 대여섯 개일 때의 이야기였다. 실제로 쓰는 계정에는 수십 개가 있고,
// 그때 사용자가 하는 일은 "고르기" 가 아니라 "이름을 눈으로 훑어 그 앞의 번호를 찾기" 가 된다.
// 화살표로 커서를 옮기고 / 로 좁히는 화면은 그 찾는 일 자체를 없앤다.

// cursorMarker 는 지금 커서가 있는 줄 앞에 붙는 표시다.
const cursorMarker = "❯"

// selectedRowMark 와 unselectedRowMark 는 스페이스로 켠 줄과 끈 줄의 표시다.
//
// 색이 아니라 글자로 표시한다. 색만으로 가르면 색을 구분하기 어려운 눈과 색을 끄고 쓰는
// 터미널에서 무엇을 골랐는지 알 방법이 없어진다.
const (
	selectedRowMark   = "[x]"
	unselectedRowMark = "[ ]"
)

// columnGap 은 이름·공개 여부·날짜 열 사이의 간격이다.
const columnGap = "  "

// alreadyInWorkDirRowStyle 은 이미 워크디렉토리에 있는 레포의 줄이다.
//
// 목록에서 빼지 않고 흐리게만 둔다. 빼면 사용자는 "그 레포가 왜 안 보이지" 를 묻게 되고,
// 그냥 두면 아직 없는 레포와 구분되지 않아 골랐다가 건너뛰어진다.
var alreadyInWorkDirRowStyle = lipgloss.NewStyle().
	Faint(true).
	Foreground(lipgloss.AdaptiveColor{Light: "#A49FA5", Dark: "#777777"})

// rowUnderCursorStyle 은 지금 커서가 있는 줄이다.
var rowUnderCursorStyle = lipgloss.NewStyle().
	Bold(true).
	Foreground(lipgloss.AdaptiveColor{Light: "#7D3FC4", Dark: "#C9A0FF"})

// repositoryPickerItem 은 선택 화면의 한 줄이다.
type repositoryPickerItem struct {
	row repositoryRow

	// rowIndex 는 원본 목록에서의 자리다.
	//
	// 필터를 걸면 화면에 남는 줄과 그 순서가 원본과 갈라진다. 고른 것을 화면 위치로 돌려주면
	// 검색으로 좁힌 뒤 고른 레포와 실제로 클론되는 레포가 어긋난다.
	rowIndex int
}

var _ list.Item = repositoryPickerItem{}

// FilterValue 는 / 로 좁힐 때 대조하는 값이다.
//
// 이름으로만 찾는다. 사용자가 기억하고 치는 것은 이름이고, 날짜나 private 표시까지 섞으면
// "priv" 같은 검색어가 엉뚱한 레포를 끌고 온다.
func (item repositoryPickerItem) FilterValue() string {
	return item.row.repository.Name
}

// repositoryPickerItems 는 행 목록을 리스트가 받는 항목으로 옮긴다.
func repositoryPickerItems(rows []repositoryRow) []list.Item {
	items := make([]list.Item, 0, len(rows))
	for rowIndex, row := range rows {
		items = append(items, repositoryPickerItem{row: row, rowIndex: rowIndex})
	}
	return items
}

// repositoryPickerDelegate 는 한 줄을 어떻게 그릴지다.
//
// bubbles 의 DefaultDelegate 를 쓰지 않는다. 그것은 한 항목을 제목·설명 두 줄에 빈 줄까지
// 세 줄로 그리는데, 이 화면에서 사용자가 하는 일은 수십 개를 훑는 것이라 한 화면에 담기는 수가
// 그만큼 중요하다. 레포 한 개의 정보는 한 줄에 다 들어간다.
type repositoryPickerDelegate struct {
	// toggledRowIndexes 는 모델이 들고 있는 것과 같은 map 이다.
	//
	// 복사본을 두면 스페이스를 눌러도 화면은 그대로다 — 리스트는 델리게이트를 값으로 담고 있어,
	// 모델이 자기 쪽 사본만 고치면 그리는 쪽은 그 사실을 모른다.
	toggledRowIndexes map[int]bool

	// nameColumnWidth 는 이름 열의 너비다.
	//
	// 화면을 그릴 때마다 재지 않고 만들 때 한 번 잰다. 보이는 줄만 보고 재면 쪽을 넘길 때마다
	// 열의 자리가 달라져, 눈으로 한 자리를 따라 내려갈 수 없다.
	nameColumnWidth int
}

var _ list.ItemDelegate = repositoryPickerDelegate{}

func newRepositoryPickerDelegate(rows []repositoryRow, toggledRowIndexes map[int]bool) repositoryPickerDelegate {
	nameColumnWidth := 0
	for _, row := range rows {
		// 글자 수가 아니라 화면 폭으로 잰다. 한글 이름은 한 글자가 두 칸을 차지해서, 글자 수로
		// 재면 한글이 섞인 목록의 열이 어긋난다.
		nameColumnWidth = max(nameColumnWidth, lipgloss.Width(row.repository.Name))
	}
	return repositoryPickerDelegate{toggledRowIndexes: toggledRowIndexes, nameColumnWidth: nameColumnWidth}
}

// Height 와 Spacing 은 한 항목이 한 줄이고 줄 사이를 비우지 않는다는 뜻이다.
func (delegate repositoryPickerDelegate) Height() int  { return 1 }
func (delegate repositoryPickerDelegate) Spacing() int { return 0 }

// Update 는 아무것도 하지 않는다 — 이 델리게이트는 그리기만 한다.
//
// 키 처리를 모델 한 곳에 모아 둔다. 그리는 쪽과 고르는 쪽이 각각 키를 집어가면, 어떤 키가
// 어디서 처리되는지 두 곳을 다 읽어야 알 수 있다.
func (delegate repositoryPickerDelegate) Update(tea.Msg, *list.Model) tea.Cmd { return nil }

// Render 는 레포 한 개를 한 줄로 그린다.
func (delegate repositoryPickerDelegate) Render(writer io.Writer, repositoryList list.Model, itemIndex int, item list.Item) {
	pickerItem, isPickerItem := item.(repositoryPickerItem)
	if !isPickerItem {
		return
	}

	isUnderCursor := itemIndex == repositoryList.Index()
	repository := pickerItem.row.repository

	line := toggleMark(delegate.toggledRowIndexes[pickerItem.rowIndex]) + " " +
		lipgloss.NewStyle().Width(delegate.nameColumnWidth).Render(repository.Name) + columnGap +
		lipgloss.NewStyle().Width(lipgloss.Width(privateRepositoryMark)).Render(visibilityMark(repository)) + columnGap +
		updatedDate(repository)
	if pickerItem.row.isAlreadyInWorkDir {
		line += columnGap + alreadyInWorkDirNote
	}

	// 이미 있다는 사실이 커서 표시보다 우선한다. 커서가 그 줄에 있을 때만 흐리게 그리기를
	// 그만두면, 커서를 옮기는 동안 "이미 있음" 인 줄이 보통 줄처럼 보였다 말았다 한다.
	switch {
	case pickerItem.row.isAlreadyInWorkDir:
		line = alreadyInWorkDirRowStyle.Render(line)
	case isUnderCursor:
		line = rowUnderCursorStyle.Render(line)
	}

	// 커서 표시는 흐리게 그리는 스타일 밖에 둔다. 이미 있는 레포 위에 커서가 있을 때도
	// 커서가 어디인지는 보여야 한다.
	cursorColumn := "  "
	if isUnderCursor {
		cursorColumn = rowUnderCursorStyle.Render(cursorMarker) + " "
	}

	// 화면보다 긴 줄은 잘라낸다. 넘치면 터미널이 다음 줄로 접어버리는데, 그러면 한 항목이
	// 두 줄을 차지해 이 아래의 모든 줄이 한 칸씩 밀린다.
	fmt.Fprint(writer, lipgloss.NewStyle().MaxWidth(repositoryList.Width()).Render(cursorColumn+line))
}

func toggleMark(isToggledOn bool) string {
	if isToggledOn {
		return selectedRowMark
	}
	return unselectedRowMark
}
