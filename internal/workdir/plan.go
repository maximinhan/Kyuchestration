package workdir

import (
	"fmt"
	"os"
	"path/filepath"
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
type Plan struct {
	// Tasks 는 계획에 적힌 작업들이며, 파일에 적힌 순서를 그대로 유지한다.
	Tasks []Task
}

// coordDirectoryName 과 planFileName 은 설계 문서 6절이 정한 파일 규약이다.
//
// 레포 안에는 아무것도 넣지 않으므로(설계 원칙 2) 이 경로는 언제나 워크디렉토리 최상위 기준이다.
const (
	coordDirectoryName = ".coord"
	planFileName       = "plan.md"
)

// LoadPlan 은 워크디렉토리의 .coord/plan.md 를 읽어 작업 목록을 돌려준다.
//
// frontmatter 만 해석하고 본문은 한 글자도 YAML 에 넘기지 않는다. 본문은 사람과 세션이 읽는
// 자리이고(설계 문서 6.1), 도구가 본문의 형식까지 요구하기 시작하면 거기에 자유롭게 적을 수 없게 된다.
func LoadPlan(workDirPath string) (Plan, error) {
	planFilePath := filepath.Join(workDirPath, coordDirectoryName, planFileName)

	planFileContent, err := os.ReadFile(planFilePath)
	if err != nil {
		return Plan{}, fmt.Errorf("계획 파일 읽기 실패 (%s): %w", planFilePath, err)
	}

	frontMatterYAML, err := extractFrontMatterYAML(string(planFileContent))
	if err != nil {
		return Plan{}, fmt.Errorf("계획 파일의 frontmatter 를 찾지 못했습니다 (%s): %w", planFilePath, err)
	}

	frontMatter, err := decodeFrontMatter(frontMatterYAML)
	if err != nil {
		return Plan{}, fmt.Errorf("계획 파일의 frontmatter 해석 실패 (%s): %w", planFilePath, err)
	}

	return Plan{Tasks: toTasks(frontMatter.Tasks)}, nil
}

// frontMatterDelimiter 는 frontmatter 의 시작과 끝을 나타내는 줄이다.
const frontMatterDelimiter = "---"

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
		return "", fmt.Errorf("파일이 %s 로 시작하지 않습니다", frontMatterDelimiter)
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
