package cli

import (
	"fmt"
	"slices"
	"strings"

	"github.com/maximinhan/Kyuchestration/internal/workdir"
)

// 이 파일은 계획에 선언된 작업들을 목록 한 칸에 들어갈 한 줄로 줄이는 자리다.
//
// 무엇을 보여줄지 고르는 일은 표시 계층의 결정이다. 조율 계층은 계획에 적힌 것을 그대로 돌려주고
// (internal/workdir 의 LoadPlan), 그중 무엇이 화면 한 칸에 값어치가 있는지는 화면을 아는 쪽이 정한다.

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

// planNoteForRepo 는 레포 한 행의 끝에 붙일 계획 한 줄을 만든다.
//
// 그 레포를 가리키는 작업이 계획에 없으면 빈 문자열이다. 계획이 아예 없는 워크디렉토리에서는
// 모든 행이 빈 문자열이 되어 목록이 계획 도입 전과 똑같이 나온다.
func planNoteForRepo(plan workdir.Plan, repoName string) string {
	repoTasks := tasksOfRepo(plan, repoName)
	if len(repoTasks) == 0 {
		return ""
	}

	doneCount := 0
	for _, task := range repoTasks {
		if task.Status == workdir.TaskStatusDone {
			doneCount++
		}
	}

	taskToShow, hasTaskToShow := chooseTaskToShow(repoTasks)
	if !hasTaskToShow {
		// 전부 끝났으면 그중 하나를 고르는 것이 의미가 없다. 작업이 하나뿐일 때만 id 를 남긴다 —
		// 설계 문서 9.3 의 [commons-event] done 이 그 경우이고, 여럿이면 어느 id 를 골라도 자의적이다.
		if len(repoTasks) == 1 {
			return fmt.Sprintf("[%s] %s", repoTasks[0].ID, workdir.TaskStatusDone)
		}
		return fmt.Sprintf("done %d/%d", doneCount, len(repoTasks))
	}

	note := fmt.Sprintf("[%s] %s", taskToShow.ID, taskToShow.Status)

	// 막힌 작업은 "무엇을 기다리는가" 까지 있어야 다음 행동이 정해진다. 그 답이 없으면
	// 사용자는 계획 파일을 열어 선행 관계를 되짚게 되고, 그러면 목록을 볼 이유가 없다.
	if taskToShow.Status == workdir.TaskStatusBlocked {
		if unfinished := unfinishedNeedsOf(plan, taskToShow); len(unfinished) > 0 {
			note += " ← needs " + strings.Join(unfinished, ", ")
		}
	}

	// 보여준 것 말고도 작업이 있다는 사실은 숫자 하나로만 알린다. 이것이 없으면 사용자는
	// 그 레포에 작업이 하나뿐이라고 믿게 된다.
	if len(repoTasks) > 1 {
		note += fmt.Sprintf(" (done %d/%d)", doneCount, len(repoTasks))
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
