package cli

import (
	"fmt"
	"io"
	"slices"

	"github.com/charmbracelet/bubbles/cursor"
	"github.com/charmbracelet/bubbles/key"
	"github.com/charmbracelet/bubbles/list"
	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"

	"github.com/maximinhan/Kyuchestration/internal/github"
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

// repositoryPickerKeyMap 은 이 화면이 리스트 위에 얹은 키들이다.
//
// 리스트가 모르는 키라 도움말도 이쪽에서 낸다. 안내를 적지 않으면 화면 아래에는 "위/아래/검색"
// 만 남고, 사용자는 여러 개를 고를 수 있다는 사실을 알 방법이 없다.
type repositoryPickerKeyMap struct {
	toggleRow key.Binding
	confirm   key.Binding
	cancel    key.Binding
}

func newRepositoryPickerKeyMap() repositoryPickerKeyMap {
	return repositoryPickerKeyMap{
		toggleRow: key.NewBinding(key.WithKeys(" "), key.WithHelp("space", "선택")),
		confirm:   key.NewBinding(key.WithKeys("enter"), key.WithHelp("enter", "클론")),

		// esc 도 취소지만 안내에는 q 만 적는다. 같은 자리의 esc 는 "검색 해제" 로 이미 안내되고
		// 있어서, 한 줄에 esc 가 두 번 나오면 어느 쪽이 지금 먹히는지 되레 알기 어렵다.
		cancel: key.NewBinding(key.WithKeys("q"), key.WithHelp("q", "취소")),
	}
}

// koreanRepositoryListKeyMap 은 bubbles 기본 키맵에서 안내 문구만 한국어로 바꾼 것이다.
//
// 키 자체는 그대로 둔다. 화살표·j/k·/ 는 이런 화면의 관습이고, 이 도구가 그것을 다시 정하면
// 사용자는 여기서만 쓰는 키를 따로 외워야 한다. 바꾸는 것은 화면 아래에 찍히는 말뿐이다.
func koreanRepositoryListKeyMap() list.KeyMap {
	keyMap := list.DefaultKeyMap()

	keyMap.CursorUp.SetHelp("↑/k", "위")
	keyMap.CursorDown.SetHelp("↓/j", "아래")
	keyMap.PrevPage.SetHelp("←/h", "이전 쪽")
	keyMap.NextPage.SetHelp("→/l", "다음 쪽")
	keyMap.GoToStart.SetHelp("g", "처음")
	keyMap.GoToEnd.SetHelp("G", "끝")
	keyMap.Filter.SetHelp("/", "검색")
	keyMap.ClearFilter.SetHelp("esc", "검색 해제")
	keyMap.CancelWhileFiltering.SetHelp("esc", "검색 취소")
	keyMap.AcceptWhileFiltering.SetHelp("enter", "검색 확정")
	keyMap.ShowFullHelp.SetHelp("?", "더 보기")
	keyMap.CloseFullHelp.SetHelp("?", "닫기")

	return keyMap
}

// repositoryPickerModel 은 레포를 골라 확정하기까지의 화면 하나다.
type repositoryPickerModel struct {
	repositoryList list.Model
	keyMap         repositoryPickerKeyMap

	// toggledRowIndexes 는 스페이스로 켜둔 줄의 원본 인덱스다.
	//
	// 화면 위치가 아니라 원본 인덱스로 기억한다. 검색으로 좁히면 화면에 남는 줄과 그 순서가
	// 바뀌는데, 화면 위치로 기억하면 검색어를 지우는 순간 엉뚱한 레포가 켜져 있게 된다.
	toggledRowIndexes map[int]bool

	// isConfirmed 는 엔터로 확정하고 닫았는지다.
	//
	// 취소와 갈라 두어야 한다. 둘 다 화면을 닫지만 확정은 클론으로 이어지고 취소는 아무것도
	// 하지 않는데, 그 구분이 없으면 q 로 나간 사용자의 커서 아래 레포가 클론된다.
	isConfirmed bool
}

var _ tea.Model = repositoryPickerModel{}

func newRepositoryPickerModel(owner github.Owner, rows []repositoryRow) repositoryPickerModel {
	toggledRowIndexes := map[int]bool{}
	keyMap := newRepositoryPickerKeyMap()

	repositoryList := list.New(repositoryPickerItems(rows), newRepositoryPickerDelegate(rows, toggledRowIndexes), 0, 0)
	repositoryList.Title = fmt.Sprintf("%s 의 레포 %d 개 — 최근 갱신 순", owner.Login, len(rows))
	repositoryList.KeyMap = koreanRepositoryListKeyMap()
	repositoryList.FilterInput.Prompt = "검색: "

	// 상태 줄을 끈다. bubbles 가 거기에 찍는 말("Nothing matched", "3 filtered")은 영어인데
	// 이 도구의 화면은 모두 한국어이고, 개수는 제목에 이미 있다.
	repositoryList.SetShowStatusBar(false)

	// 검색창의 커서를 깜빡이지 않게 둔다. 깜빡임은 타이머 명령을 끊임없이 만들어 내는데,
	// 검색창은 열려 있는 동안 늘 화면 맨 위에 있어 깜빡임으로 얻을 것이 없다.
	repositoryList.FilterInput.Cursor.SetMode(cursor.CursorStatic)

	// q·esc 를 리스트에 맡기지 않는다. 리스트는 그 자리에서 프로그램을 끝내버려서, 사용자가
	// 취소한 것인지 확정한 것인지 이쪽에서 구분할 수 없게 된다.
	repositoryList.DisableQuitKeybindings()

	repositoryList.AdditionalShortHelpKeys = func() []key.Binding {
		return []key.Binding{keyMap.toggleRow, keyMap.confirm, keyMap.cancel}
	}
	repositoryList.AdditionalFullHelpKeys = repositoryList.AdditionalShortHelpKeys

	return repositoryPickerModel{
		repositoryList:    repositoryList,
		keyMap:            keyMap,
		toggledRowIndexes: toggledRowIndexes,
	}
}

func (model repositoryPickerModel) Init() tea.Cmd { return nil }

// Update 는 키 하나를 받아 다음 상태를 돌려준다.
//
// 이 화면만의 키(스페이스·엔터·q·esc)를 먼저 집어내고 나머지는 리스트에 넘긴다. 커서 이동·
// 쪽 넘김·검색은 리스트가 이미 하는 일이고, 그것을 다시 구현하면 관습과 어긋난 키가 생긴다.
func (model repositoryPickerModel) Update(message tea.Msg) (tea.Model, tea.Cmd) {
	switch typedMessage := message.(type) {
	case tea.WindowSizeMsg:
		model.repositoryList.SetSize(typedMessage.Width, typedMessage.Height)
		return model, nil

	case tea.KeyMsg:
		// 검색어를 치는 중에는 한 글자도 가로채지 않는다. 그 상태의 스페이스는 검색어의 공백이고
		// q 는 레포 이름의 글자이며 엔터는 검색어 확정이다 — 여기서 집어가면 사용자가 적으려던
		// 글자가 사라지고, 보이지도 않는 줄이 조용히 켜진다.
		if model.repositoryList.FilterState() == list.Filtering {
			break
		}

		switch {
		case key.Matches(typedMessage, model.keyMap.cancel), typedMessage.Type == tea.KeyCtrlC:
			return model, tea.Quit

		case typedMessage.Type == tea.KeyEsc:
			// 검색이 걸려 있으면 esc 는 그것을 푸는 키다(리스트가 처리한다). 좁힌 목록을
			// 되돌리려던 사용자를 명령 밖으로 내보내면 프로필 선택부터 다시 지나야 한다.
			if model.repositoryList.FilterState() == list.FilterApplied {
				break
			}
			return model, tea.Quit

		case key.Matches(typedMessage, model.keyMap.toggleRow):
			model.toggleRowUnderCursor()
			return model, nil

		case key.Matches(typedMessage, model.keyMap.confirm):
			model.isConfirmed = true
			return model, tea.Quit
		}
	}

	updatedList, listCommand := model.repositoryList.Update(message)
	model.repositoryList = updatedList

	// 한 쪽에 담을 줄 수를 다시 세게 한다.
	//
	// bubbles 는 그 수를 "화면 높이 - 제목 - 쪽 표시 - 도움말" 로 계산하는데, 쪽 표시의 높이를
	// 재는 시점의 쪽 수가 아직 옛 값이다. 검색으로 한 쪽까지 좁혔다가 풀면 쪽 표시가 없던 때의
	// 높이로 계산해 줄이 하나 더 들어가고, 화면이 터미널보다 한 줄 길어져 터미널이 위로 밀어
	// 올린다 — 그 순간 맨 위의 제목 줄이 사라진다.
	//
	// 크기를 그대로 다시 알려 주면 이제 맞는 쪽 수로 한 번 더 계산한다.
	model.repositoryList.SetSize(model.repositoryList.Width(), model.repositoryList.Height())

	return model, listCommand
}

func (model repositoryPickerModel) View() string {
	return model.repositoryList.View()
}

// toggleRowUnderCursor 는 커서가 있는 줄의 선택을 켜고 끈다.
func (model *repositoryPickerModel) toggleRowUnderCursor() {
	pickerItem, isPickerItem := model.repositoryList.SelectedItem().(repositoryPickerItem)
	if !isPickerItem {
		return
	}

	if model.toggledRowIndexes[pickerItem.rowIndex] {
		// 꺼진 줄을 false 로 남기지 않고 지운다. 남기면 "고른 것이 하나도 없다" 를 개수로 판정할
		// 수 없어, 켰다가 끈 사용자의 엔터가 커서 한 줄이 아니라 빈 선택이 된다.
		delete(model.toggledRowIndexes, pickerItem.rowIndex)
		return
	}
	model.toggledRowIndexes[pickerItem.rowIndex] = true
}

// selectedRowIndexes 는 확정된 선택을 원본 목록 순서의 인덱스로 돌려준다.
//
// 아무것도 켜지 않았으면 커서가 있는 한 줄이다. 하나만 클론하려고 스페이스를 먼저 눌러야 한다면
// 가장 흔한 사용이 두 번의 키를 요구하게 된다.
//
// 취소했으면 빈 목록이다 — 번호를 적던 때의 빈 줄과 같은 뜻이라, 부르는 쪽은 두 방식을
// 구분하지 않아도 된다.
func (model repositoryPickerModel) selectedRowIndexes() []int {
	if !model.isConfirmed {
		return nil
	}

	if len(model.toggledRowIndexes) == 0 {
		pickerItem, isPickerItem := model.repositoryList.SelectedItem().(repositoryPickerItem)
		if !isPickerItem {
			return nil
		}
		return []int{pickerItem.rowIndex}
	}

	selectedRowIndexes := make([]int, 0, len(model.toggledRowIndexes))
	for rowIndex := range model.toggledRowIndexes {
		selectedRowIndexes = append(selectedRowIndexes, rowIndex)
	}

	// 목록에 보이던 순서로 클론한다. map 의 순회 순서는 실행마다 달라서, 그대로 두면 같은 선택이
	// 매번 다른 순서로 진행되어 어디까지 됐는지 눈으로 따라갈 수 없다.
	slices.Sort(selectedRowIndexes)
	return selectedRowIndexes
}

// pickRepositoriesWithArrowKeys 는 선택 화면을 띄워 고른 줄의 인덱스를 돌려준다.
func pickRepositoriesWithArrowKeys(in io.Reader, out io.Writer, owner github.Owner, rows []repositoryRow) ([]int, error) {
	program := tea.NewProgram(
		newRepositoryPickerModel(owner, rows),
		tea.WithInput(in),
		tea.WithOutput(out),

		// 대체 화면에 그린다. 수십 줄짜리 목록을 평소 화면에 남기면 뒤이은 클론 결과가 그 아래로
		// 밀려나고, 대체 화면이라야 터미널 높이를 통째로 쓸 수 있어 한 쪽에 담기는 줄이 늘어난다.
		tea.WithAltScreen(),
	)

	finishedModel, err := program.Run()
	if err != nil {
		return nil, fmt.Errorf("레포 선택 화면 실행 실패: %w", err)
	}

	picker, isPicker := finishedModel.(repositoryPickerModel)
	if !isPicker {
		return nil, fmt.Errorf("레포 선택 화면이 다른 모델로 끝났습니다: %T", finishedModel)
	}
	return picker.selectedRowIndexes(), nil
}
