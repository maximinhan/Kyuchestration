package cli

import (
	"fmt"
	"slices"
	"strings"

	"github.com/maximinhan/Kyuchestration/internal/workdir"
)

// 이 파일은 계획에 선언된 작업들을 레포 한 개 분량으로 줄이는 자리다.
//
// 무엇을 보여줄지 고르는 일은 표시 계층의 결정이다. 조율 계층은 계획에 적힌 것을 그대로 돌려주고
// (internal/workdir 의 LoadPlan), 그중 무엇이 한 줄의 값어치가 있는지는 보여주는 쪽이 정한다.

// taskDisplayPriority 는 한 레포에 작업이 여럿일 때 어느 것을 보여줄지의 순서다.
//
// 목록을 여는 이유가 "지금 이 레포에서 뭘 해야 하지?" 라서 진행 관점으로 줄을 세운다.
// 진행 중인 것(doing)이 가장 앞이고, 없으면 바로 시작할 수 있는 것(ready), 그것도 없으면
// 무엇을 기다리는지 알려주는 막힌 것(blocked) 순이다. done 은 이 목록에 없다 — 끝난 작업은
// 다음 행동을 알려주지 않으므로, 미완료가 하나라도 있으면 그쪽이 화면을 차지해야 한다.
//
// 여럿을 한 칸에 늘어놓지 않는 이유는 목록의 값어치가 "한 화면에 읽히는가" 에 있어서다.
// 레포마다 작업 목록이 통째로 펼쳐지면 레포 수가 늘어날수록 그 성질이 먼저 무너진다.
// 전체 작업은 계획 파일을 열어서 본다 — 그것이 파일을 진실의 근원으로 둔 이유다(설계 문서 5.5).
var taskDisplayPriority = []workdir.TaskStatus{
	workdir.TaskStatusDoing,
	workdir.TaskStatusReady,
	workdir.TaskStatusBlocked,
}

// repoPlanSummary 는 계획에서 레포 하나를 보고 뽑은 결과다.
//
// 문구가 아니라 구조로 둔다. 같은 사실을 사람용 표는 한 칸의 문장으로, --json 은 필드 몇 개로
// 내보내는데, 여기서 문자열로 굳혀 넘기면 JSON 쪽이 그 문장을 되파싱해야 한다. 되파싱하는 순간
// 표의 문구를 다듬는 일이 곧 계약을 깨는 일이 된다.
//
// 어느 작업을 보여줄지 고르는 판단은 이 값을 만들 때 한 번만 한다. 그것을 표의 한 칸으로 옮길지
// JSON 의 필드로 옮길지는 각 출력이 정한다.
type repoPlanSummary struct {
	// taskToShow 는 이 레포에서 지금 볼 작업이다. hasTaskToShow 가 false 면 고를 것이 없었다는 뜻이다.
	taskToShow workdir.Task

	// hasTaskToShow 는 taskToShow 에 값이 들었는지다. Task 의 영 값은 id 가 빈 작업과 구분되지 않아
	// 값 자체로는 "없음" 을 나타낼 수 없다.
	hasTaskToShow bool

	// unfinishedNeeds 는 taskToShow 가 기다리는 선행 중 아직 끝나지 않은 것의 id 다.
	//
	// 막힌 작업일 때만 채운다. 다른 상태에서는 이미 손댈 수 있는 작업이라 "무엇을 기다리는가" 가
	// 다음 행동을 바꾸지 못하고, 채워두면 읽는 쪽이 그것을 막힌 근거로 오해한다.
	unfinishedNeeds []string

	// doneTaskCount 와 totalTaskCount 는 이 레포를 가리키는 작업 전체의 진척이다.
	// 보여줄 작업을 하나로 줄인 뒤에도 "그 레포에 작업이 이것뿐인가" 를 알 수 있게 남긴다.
	doneTaskCount  int
	totalTaskCount int
}

// summarizeRepoPlan 은 계획에서 이 레포를 가리키는 작업들을 한 레포 분량으로 줄인다.
//
// 그 레포를 가리키는 작업이 계획에 없으면 영 값이다. 계획이 아예 없는 워크디렉토리에서는
// 모든 레포가 영 값이 되어 목록이 계획 도입 전과 똑같이 나온다.
func summarizeRepoPlan(plan workdir.Plan, repoName string) repoPlanSummary {
	repoTasks := tasksOfRepo(plan, repoName)

	summary := repoPlanSummary{totalTaskCount: len(repoTasks)}
	if len(repoTasks) == 0 {
		return summary
	}

	for _, task := range repoTasks {
		if task.Status == workdir.TaskStatusDone {
			summary.doneTaskCount++
		}
	}

	taskToShow, hasTaskToShow := chooseTaskToShow(repoTasks)
	if !hasTaskToShow {
		// 전부 끝났으면 그중 하나를 고르는 것이 의미가 없다. 작업이 하나뿐일 때만 그것을 남긴다 —
		// 설계 문서 9.3 의 [commons-event] done 이 그 경우이고, 여럿이면 어느 것을 골라도 자의적이다.
		if len(repoTasks) == 1 {
			summary.taskToShow, summary.hasTaskToShow = repoTasks[0], true
		}
		return summary
	}

	summary.taskToShow, summary.hasTaskToShow = taskToShow, true

	// 막힌 작업은 "무엇을 기다리는가" 까지 있어야 다음 행동이 정해진다. 그 답이 없으면
	// 사용자는 계획 파일을 열어 선행 관계를 되짚게 되고, 그러면 목록을 볼 이유가 없다.
	if taskToShow.Status == workdir.TaskStatusBlocked {
		summary.unfinishedNeeds = unfinishedNeedsOf(plan, taskToShow)
	}
	return summary
}

// note 는 요약을 목록의 계획 칸에 들어갈 한 줄로 옮긴다.
func (summary repoPlanSummary) note() string {
	if summary.totalTaskCount == 0 {
		return ""
	}

	if !summary.hasTaskToShow {
		return fmt.Sprintf("done %d/%d", summary.doneTaskCount, summary.totalTaskCount)
	}

	note := fmt.Sprintf("[%s] %s", summary.taskToShow.ID, summary.taskToShow.Status)

	if len(summary.unfinishedNeeds) > 0 {
		note += " ← needs " + strings.Join(summary.unfinishedNeeds, ", ")
	}

	// 보여준 것 말고도 작업이 있다는 사실은 숫자 하나로만 알린다. 이것이 없으면 사용자는
	// 그 레포에 작업이 하나뿐이라고 믿게 된다.
	if summary.totalTaskCount > 1 {
		note += fmt.Sprintf(" (done %d/%d)", summary.doneTaskCount, summary.totalTaskCount)
	}
	return note
}

// tasksOfRepo 는 계획에서 이 레포를 가리키는 작업만 파일에 적힌 순서 그대로 골라낸다.
func tasksOfRepo(plan workdir.Plan, repoName string) []workdir.Task {
	var repoTasks []workdir.Task
	for _, task := range plan.Tasks {
		if task.Repo == repoName {
			repoTasks = append(repoTasks, task)
		}
	}
	return repoTasks
}

// chooseTaskToShow 는 미완료 작업 중 화면에 올릴 하나를 고른다. 전부 끝났으면 고르지 않는다.
//
// 같은 상태가 여럿이면 계획 파일에 먼저 적힌 것을 고른다. 계획을 쓰는 사람이 순서대로 적는다는
// 전제이고, 그렇지 않더라도 같은 계획에서 같은 답이 나오는 편이 매번 달라지는 것보다 낫다.
func chooseTaskToShow(repoTasks []workdir.Task) (workdir.Task, bool) {
	for _, wantedStatus := range taskDisplayPriority {
		for _, task := range repoTasks {
			if task.Status == wantedStatus {
				return task, true
			}
		}
	}
	return workdir.Task{}, false
}

// unfinishedNeedsOf 는 선행 작업 중 아직 끝나지 않은 것의 id 를 needs 에 적힌 순서대로 돌려준다.
//
// 끝난 선행까지 늘어놓으면 사용자가 그중 무엇이 남았는지 다시 골라내야 한다.
// LoadPlan 이 계획에 없는 id 를 가리키는 계획을 이미 버리므로, 여기서 찾지 못하는 id 는 없다.
func unfinishedNeedsOf(plan workdir.Plan, task workdir.Task) []string {
	statusByTaskID := make(map[string]workdir.TaskStatus, len(plan.Tasks))
	for _, declaredTask := range plan.Tasks {
		statusByTaskID[declaredTask.ID] = declaredTask.Status
	}

	var unfinished []string
	for _, neededTaskID := range task.Needs {
		if statusByTaskID[neededTaskID] != workdir.TaskStatusDone {
			unfinished = append(unfinished, neededTaskID)
		}
	}
	return unfinished
}

// missingRepoWarnings 는 계획이 가리키는데 이 워크디렉토리에는 없는 레포를 경고 문장으로 만든다.
//
// 그런 작업은 붙일 행이 없어 목록에서 통째로 사라진다. 조용히 사라지면 사용자는 자기가 적어둔
// 작업이 왜 안 보이는지 알 수 없고, 대개는 아직 클론하지 않았거나 레포 이름을 잘못 적은 것이다.
//
// 레포 하나에 한 줄만 낸다. 같은 사실을 그 레포의 작업 수만큼 되풀이하면 경고가 길어져
// 정작 읽히지 않는다. 대신 어느 작업들이 걸렸는지는 문장 안에 함께 적는다.
func missingRepoWarnings(plan workdir.Plan, repos []workdir.Repo, planFilePath string) []string {
	var missingRepoNames []string
	taskIDsByMissingRepo := make(map[string][]string)

	for _, task := range plan.Tasks {
		if slices.ContainsFunc(repos, func(repo workdir.Repo) bool { return repo.Name == task.Repo }) {
			continue
		}
		if _, alreadySeen := taskIDsByMissingRepo[task.Repo]; !alreadySeen {
			// 경고 순서를 계획에 처음 나온 순서로 고정한다. 맵을 그대로 훑으면 실행할 때마다 순서가 바뀐다.
			missingRepoNames = append(missingRepoNames, task.Repo)
		}
		taskIDsByMissingRepo[task.Repo] = append(taskIDsByMissingRepo[task.Repo], task.ID)
	}

	warnings := make([]string, 0, len(missingRepoNames))
	for _, missingRepoName := range missingRepoNames {
		warnings = append(warnings, fmt.Sprintf("%s: 계획이 가리키는 레포가 이 워크디렉토리에 없습니다 (repo: %s, 작업: %s)",
			planFilePath, missingRepoName, strings.Join(taskIDsByMissingRepo[missingRepoName], ", ")))
	}
	return warnings
}
