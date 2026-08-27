package workdir

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strings"

	"gopkg.in/yaml.v3"
)

// TaskStatus 는 계획 파일에 사람 또는 세션이 선언한 작업 상태다(설계 문서 6.1).
//
// RepoState 와 이름이 비슷하지만 출처가 다르다. RepoState 는 도구가 tmux 와 git 을 관찰해
// 매 조회마다 다시 계산하는 값이고, 이 값은 사람이 파일에 적어둔 선언이다. 도구는 v1 에서
// 이 값을 읽어 보여주기만 하고 고쳐 쓰지 않는다(설계 문서 5.3 의 두 상태 구분).
//
// 값을 string 으로 둔 이유도 RepoState 와 같다 — 파일에 적힌 문자열과 상수가 일대일로
// 대응하므로 파싱과 표시 어느 쪽에도 변환표가 필요 없고, 영 값이 유효한 상태와 겹치지 않는다.
type TaskStatus string

const (
	// TaskStatusBlocked 는 선행 작업이 끝나지 않아 아직 시작할 수 없는 작업이다.
	TaskStatusBlocked TaskStatus = "blocked"

	// TaskStatusReady 는 선행 작업이 모두 끝나 바로 시작할 수 있는 작업이다.
	TaskStatusReady TaskStatus = "ready"

	// TaskStatusDoing 은 진행 중인 작업이다.
	TaskStatusDoing TaskStatus = "doing"

	// TaskStatusDone 은 끝난 작업이다.
	TaskStatusDone TaskStatus = "done"
)

// Task 는 계획에 선언된 작업 하나다(설계 문서 6.1).
type Task struct {
	// ID 는 작업 식별자다. 다른 작업의 Needs 가 이 값을 가리킨다.
	ID string

	// Repo 는 이 작업을 수행할 레포 디렉토리명이다. Repo.Name 과 같은 이름 공간이다.
	Repo string

	// Title 은 한 줄 설명이다.
	Title string

	// Needs 는 선행 작업의 ID 목록이다. 비어있으면 즉시 실행 가능하다.
	Needs []string

	// Status 는 사람 또는 세션이 선언한 작업 상태다.
	Status TaskStatus
}

// Plan 은 워크디렉토리의 계획 파일을 읽은 결과다.
//
// 결과는 셋 중 하나이며 셋 다 에러가 아니다.
//
//	Tasks 있음 · Warnings 없음  — 계획을 읽었다
//	Tasks 없음 · Warnings 없음  — 계획이 없다 (파일이 없거나 frontmatter 가 없다)
//	Tasks 없음 · Warnings 있음  — 계획이 깨져서 버렸다. 이유는 Warnings 에 있다
//
// 깨진 계획을 에러로 올리지 않는 이유는 설계 문서 6.1 의 마지막 문단이다. "계획 파싱 실패가
// 도구 전체를 막지 않는다" — 레포 목록과 상태 표시는 계획 없이도 성립하는데, 계획 파일의
// 오타 하나로 그것까지 죽으면 사용자는 파일을 고치기 전까지 도구를 쓰지 못한다.
//
// 그렇다고 조용히 넘기지도 않는다. 계획을 적어둔 사람은 그것이 반영되기를 기대하고 있으므로,
// 아무 말 없이 빈 계획으로 도는 것이 가장 나쁜 결말이다. Warnings 가 비어있지 않으면
// 호출부는 그것을 사용자에게 보여야 한다.
//
// 깨진 계획을 부분적으로 살리지 않는 것도 같은 이유다. 작업 하나를 빼고 보여주면 남은 작업의
// 선행 관계가 실제와 달라지고, 사용자는 그 화면을 계획 전체라고 믿는다.
type Plan struct {
	// Tasks 는 계획에 적힌 작업들이며, 파일에 적힌 순서를 그대로 유지한다.
	Tasks []Task

	// Warnings 는 계획을 버린 이유를 사용자에게 보일 문장으로 담은 것이다.
	// 고칠 자리를 찾을 수 있도록 계획 파일 경로를 문장 안에 포함한다.
	Warnings []string
}

// coordDirectoryName 과 planFileName 은 설계 문서 6절이 정한 파일 규약이다.
//
// 레포 안에는 아무것도 넣지 않으므로(설계 원칙 2) 이 경로는 언제나 워크디렉토리 최상위 기준이다.
const (
	coordDirectoryName = ".coord"
	planFileName       = "plan.md"
)

// PlanFilePath 는 워크디렉토리의 계획 파일 경로를 만든다: <workdir>/.coord/plan.md.
//
// 경로 규약을 이 함수 하나로 모은다. 계획을 읽는 쪽(LoadPlan)과 만드는 쪽(kyu init)이 각자
// 경로를 조립하면 한쪽만 고쳤을 때 도구가 자기가 만든 파일을 읽지 못하게 되고,
// 그 어긋남은 "계획이 없다" 는 정상 결과로 보여서 드러나지 않는다.
func PlanFilePath(workDirPath string) string {
	return filepath.Join(workDirPath, coordDirectoryName, planFileName)
}

// LoadPlan 은 워크디렉토리의 .coord/plan.md 를 읽어 작업 목록을 돌려준다.
//
// frontmatter 만 해석하고 본문은 한 글자도 YAML 에 넘기지 않는다. 본문은 사람과 세션이 읽는
// 자리이고(설계 문서 6.1), 도구가 본문의 형식까지 요구하기 시작하면 거기에 자유롭게 적을 수 없게 된다.
func LoadPlan(workDirPath string) (Plan, error) {
	planFilePath := PlanFilePath(workDirPath)

	planFileContent, err := os.ReadFile(planFilePath)
	if errors.Is(err, os.ErrNotExist) {
		// kyu init 을 아직 하지 않았거나 조율할 것이 없는 워크디렉토리다. 흔한 정상 상태다.
		return Plan{}, nil
	}
	if err != nil {
		// 파일이 거기 있는데 읽지 못한 것(권한 등)은 관찰 자체가 실패한 것이라 계획 없음과 다르다.
		// 없는 계획으로 뭉뚱그리면 사용자는 자기가 적어둔 계획이 왜 사라졌는지 알 길이 없다.
		return Plan{}, fmt.Errorf("계획 파일 읽기 실패 (%s): %w", planFilePath, err)
	}

	frontMatterYAML, err := extractFrontMatterYAML(string(planFileContent))
	if errors.Is(err, errFrontMatterAbsent) {
		return Plan{}, nil
	}
	if err != nil {
		return planDroppedWithWarnings(planFilePath, err.Error()), nil
	}

	frontMatter, err := decodeFrontMatter(frontMatterYAML)
	if err != nil {
		// yaml 라이브러리의 메시지를 그대로 붙인다. 어느 줄이 문제인지는 그 메시지에만 들어있고,
		// 우리 말로 바꿔 적으면 그 정보가 사라진다. 다만 필드 오타를 알릴 때는 여러 줄로 오므로
		// 공백으로 접는다 — 경고 하나가 한 줄이어야 호출부가 붙이는 들여쓰기가 어긋나지 않는다.
		singleLineDecodeFailure := strings.Join(strings.Fields(err.Error()), " ")
		return planDroppedWithWarnings(planFilePath,
			fmt.Sprintf("frontmatter 의 YAML 을 해석하지 못했습니다 (%s)", singleLineDecodeFailure)), nil
	}

	tasks := toTasks(frontMatter.Tasks)
	if problems := findTaskProblems(tasks); len(problems) > 0 {
		return planDroppedWithWarnings(planFilePath, problems...), nil
	}

	return Plan{Tasks: tasks}, nil
}

// planDroppedWithWarnings 는 계획을 버리고 그 이유만 담은 결과를 만든다.
//
// 이유마다 계획 파일 경로를 앞에 붙인다. 경고는 호출부가 그대로 출력하는 문장이라,
// 어느 파일을 열어야 하는지가 문장 안에 들어있지 않으면 사용자가 워크디렉토리를 뒤지게 된다.
func planDroppedWithWarnings(planFilePath string, reasons ...string) Plan {
	warnings := make([]string, 0, len(reasons))
	for _, reason := range reasons {
		warnings = append(warnings, fmt.Sprintf("%s: %s", planFilePath, reason))
	}
	return Plan{Warnings: warnings}
}

// frontMatterDelimiter 는 frontmatter 의 시작과 끝을 나타내는 줄이다.
const frontMatterDelimiter = "---"

// errFrontMatterAbsent 는 파일이 --- 로 시작하지 않아 계획이 적혀있지 않다는 뜻이다.
//
// 닫는 --- 가 없는 경우와는 다르게 다룬다. 여는 --- 조차 없으면 사람이 메모만 적어둔 파일이라
// 도구가 읽을 것이 없을 뿐이지만, 여는 --- 를 적었다면 계획을 적으려던 것이므로 그 사람에게
// 무엇이 잘못됐는지 알려야 한다. 호출부가 errors.Is 로 갈라낼 수 있도록 센티널 에러로 둔다.
var errFrontMatterAbsent = errors.New("frontmatter 가 없습니다")

// extractFrontMatterYAML 은 파일 맨 앞의 --- 로 감싼 구간을 YAML 텍스트로 잘라낸다.
//
// 여는 --- 줄을 잘라내지 않고 함께 넘긴다. YAML 에서 --- 는 문서의 시작을 알리는 표시라
// 파싱 결과에 영향을 주지 않으면서, 넘기는 텍스트의 줄 번호를 파일의 줄 번호와 일치시킨다.
// 이 줄을 빼면 yaml 라이브러리가 알려주는 "line N" 이 실제 파일보다 한 줄씩 앞서게 되고,
// 사용자는 경고를 보고 엉뚱한 줄을 열게 된다.
func extractFrontMatterYAML(planFileContent string) (string, error) {
	// strings.Split 은 빈 문자열에도 요소 하나짜리 슬라이스를 돌려주므로 lines[0] 은 언제나 안전하다.
	lines := strings.Split(planFileContent, "\n")

	if !isFrontMatterDelimiter(lines[0]) {
		return "", errFrontMatterAbsent
	}

	for lineIndex := 1; lineIndex < len(lines); lineIndex++ {
		if isFrontMatterDelimiter(lines[lineIndex]) {
			return strings.Join(lines[:lineIndex], "\n"), nil
		}
	}

	return "", fmt.Errorf("여는 %s 뒤에 닫는 %s 가 없습니다", frontMatterDelimiter, frontMatterDelimiter)
}

// isFrontMatterDelimiter 는 그 줄이 구분자인지 본다.
//
// 줄 끝의 \r 을 떼고 비교한다. 계획 파일은 도구가 아니라 사람이 자기 에디터로 고치는 파일이라
// Windows 에서 CRLF 로 저장될 수 있고, 그때 구분자 줄은 "---\r" 이 된다.
func isFrontMatterDelimiter(line string) bool {
	return strings.TrimRight(line, "\r") == frontMatterDelimiter
}

// planFrontMatter 와 planTaskEntry 는 frontmatter 의 YAML 구조를 그대로 받는 타입이다.
//
// Task 에 직접 언마샬하지 않는 이유가 둘 있다.
//
//  1. status 를 string 으로 받아야 정의되지 않은 값을 검출할 수 있다. TaskStatus 로 바로 받으면
//     "reviewing" 같은 오타도 그대로 담겨, 어느 상태에도 해당하지 않는 작업이 조용히 통과한다.
//  2. yaml 태그는 파일 형식의 사정이지 작업이라는 개념의 사정이 아니다. Task 를 태그 없이 두면
//     표시 계층이 붙든 저장 형식이 바뀌든 Task 는 그대로다.
type planFrontMatter struct {
	Tasks []planTaskEntry `yaml:"tasks"`
}

type planTaskEntry struct {
	ID     string   `yaml:"id"`
	Repo   string   `yaml:"repo"`
	Title  string   `yaml:"title"`
	Needs  []string `yaml:"needs"`
	Status string   `yaml:"status"`
}

// decodeFrontMatter 는 frontmatter 의 YAML 을 해석한다.
func decodeFrontMatter(frontMatterYAML string) (planFrontMatter, error) {
	decoder := yaml.NewDecoder(strings.NewReader(frontMatterYAML))

	// 정의되지 않은 필드를 에러로 만든다. "neeeds" 처럼 한 글자 틀린 키를 조용히 버리면
	// 선행 관계가 통째로 사라진 계획이 아무 말 없이 그럴듯하게 표시된다. 쓸 수 있는 필드는
	// 설계 문서 6.1 의 표가 전부이므로 모르는 키는 확장이 아니라 오타로 본다.
	decoder.KnownFields(true)

	var frontMatter planFrontMatter
	if err := decoder.Decode(&frontMatter); err != nil {
		return planFrontMatter{}, err
	}
	return frontMatter, nil
}

// definedTaskStatuses 는 status 에 쓸 수 있는 값 전부다(설계 문서 6.1).
//
// 상수를 늘어놓은 슬라이스를 따로 두는 이유는 검사와 안내 문구가 같은 목록을 보게 하기 위해서다.
// 검사는 switch 로, 문구는 손으로 적은 목록으로 두면 값이 하나 늘 때 둘 중 하나만 고치게 된다.
var definedTaskStatuses = []TaskStatus{TaskStatusBlocked, TaskStatusReady, TaskStatusDoing, TaskStatusDone}

// findTaskProblems 는 계획을 쓸 수 있는지 확인하고, 쓸 수 없는 이유를 모두 모아 돌려준다.
//
// 첫 문제에서 멈추지 않는다. 계획을 고치는 사람은 파일을 열어 한 번에 고치는데, 문제를 하나씩만
// 알려주면 고치고 다시 돌리기를 문제 수만큼 반복하게 된다.
func findTaskProblems(tasks []Task) []string {
	var problems []string

	declaredTaskIDs := make(map[string]bool, len(tasks))
	for taskIndex, task := range tasks {
		taskLocation := describeTaskLocation(taskIndex, task.ID)

		if isBlank(task.ID) {
			problems = append(problems, fmt.Sprintf("%s: 필수 필드 id 가 없습니다", taskLocation))
		}
		if isBlank(task.Repo) {
			problems = append(problems, fmt.Sprintf("%s: 필수 필드 repo 가 없습니다", taskLocation))
		}
		if isBlank(task.Title) {
			problems = append(problems, fmt.Sprintf("%s: 필수 필드 title 이 없습니다", taskLocation))
		}

		switch {
		case isBlank(string(task.Status)):
			problems = append(problems, fmt.Sprintf("%s: 필수 필드 status 가 없습니다", taskLocation))
		case !slices.Contains(definedTaskStatuses, task.Status):
			problems = append(problems, fmt.Sprintf("%s: status 값이 정의되지 않았습니다 (적힌 값: %q, 쓸 수 있는 값: %v)",
				taskLocation, task.Status, definedTaskStatuses))
		}

		if !isBlank(task.ID) {
			if declaredTaskIDs[task.ID] {
				// id 가 겹치면 needs 가 어느 작업을 가리키는지 정해지지 않는다.
				// 계획의 선행 관계가 읽는 사람마다 달라지므로 통과시킬 수 없다.
				problems = append(problems, fmt.Sprintf("%s: id 가 앞의 작업과 중복됩니다", taskLocation))
			}
			declaredTaskIDs[task.ID] = true
		}
	}

	// needs 검사는 id 를 다 모은 뒤에 한다. needs 는 파일에서 뒤에 오는 작업을 가리켜도 되므로
	// (계획을 적는 사람에게 위상 순서로 늘어놓으라고 요구하지 않는다) 한 번 훑는 것으로는 판정할 수 없다.
	for taskIndex, task := range tasks {
		for _, neededTaskID := range task.Needs {
			if !declaredTaskIDs[neededTaskID] {
				problems = append(problems, fmt.Sprintf("%s: needs 가 계획에 없는 id 를 가리킵니다 (없는 id: %q)",
					describeTaskLocation(taskIndex, task.ID), neededTaskID))
			}
		}
	}

	return problems
}

// describeTaskLocation 은 경고 문구에서 작업을 가리키는 말을 만든다.
//
// 순번과 id 를 함께 적는다. id 만으로는 그 id 자체가 빠졌거나 중복일 때 가리키지 못하고,
// 순번만으로는 사용자가 파일에서 몇 번째 항목인지 세어야 한다.
func describeTaskLocation(taskIndex int, taskID string) string {
	if isBlank(taskID) {
		return fmt.Sprintf("%d 번째 작업", taskIndex+1)
	}
	return fmt.Sprintf("%d 번째 작업(%s)", taskIndex+1, taskID)
}

// isBlank 는 값이 비어있거나 공백뿐인지 본다.
//
// 공백뿐인 값을 있는 것으로 치지 않는다. YAML 에서 따옴표로 감싼 공백(id: "   ")은 값으로
// 인정되므로, 필드가 있는지만 보면 아무것도 가리키지 못하는 id 가 그대로 통과한다.
func isBlank(value string) bool {
	return strings.TrimSpace(value) == ""
}

// toTasks 는 YAML 에서 받은 항목을 계층 밖으로 나가는 Task 로 옮긴다.
func toTasks(entries []planTaskEntry) []Task {
	var tasks []Task
	for _, entry := range entries {
		tasks = append(tasks, Task{
			ID:     entry.ID,
			Repo:   entry.Repo,
			Title:  entry.Title,
			Needs:  entry.Needs,
			Status: TaskStatus(entry.Status),
		})
	}
	return tasks
}
