package cli

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// writePlanFileForTest 는 워크디렉토리에 계획 파일을 만든다.
func writePlanFileForTest(t *testing.T, workDirPath, planFileContent string) {
	t.Helper()

	coordDirectoryPath := filepath.Join(workDirPath, ".coord")
	if err := os.MkdirAll(coordDirectoryPath, 0o755); err != nil {
		t.Fatalf("테스트용 조율 디렉토리 생성 실패: %v", err)
	}
	if err := os.WriteFile(filepath.Join(coordDirectoryPath, "plan.md"), []byte(planFileContent), 0o644); err != nil {
		t.Fatalf("테스트용 계획 파일 쓰기 실패: %v", err)
	}
}

// assertListRow 는 열 사이 공백을 한 칸으로 접어 목록의 한 행을 비교한다.
//
// 이 파일의 테스트가 보는 것은 계획 칸에 적히는 문구다. 열 너비까지 함께 고정하면 레포 이름을
// 하나 바꿀 때마다 상관없는 기대값이 무너진다. 정렬 자체는 전체 출력을 통째로 비교하는
// TestListWorkDirPutsThePlanTaskAfterEachRepoState 가 지킨다.
func assertListRow(t *testing.T, listOutput, wantRow string) {
	t.Helper()

	for _, line := range strings.Split(listOutput, "\n") {
		if strings.Join(strings.Fields(line), " ") == wantRow {
			return
		}
	}
	t.Errorf("목록에 %q 행이 없습니다.\n--- 목록 ---\n%s", wantRow, listOutput)
}

func TestListWorkDirPutsThePlanTaskAfterEachRepoState(t *testing.T) {
	// 설계 문서 9.3 의 출력 예를 그대로 재현한다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	makeRepoWithUncommittedChange(t, workDirPath, "beta-gateway")
	makeCleanRepo(t, workDirPath, "zeta-service")

	writePlanFileForTest(t, workDirPath, `---
tasks:
  - id: commons-event
    repo: alpha-commons
    title: 이벤트 클래스 추가
    needs: []
    status: done

  - id: publish
    repo: beta-gateway
    title: 발행 로직 구현
    needs: [commons-event]
    status: ready

  - id: consume
    repo: zeta-service
    title: 컨슈머 구현
    needs: [commons-event, publish]
    status: blocked
---

## 배경

본문은 도구가 읽지 않는다.
`)

	assertListOutput(t, runListForTest(t, []string{workDirPath}), `WorkDir: WorkDir-featureX   (3 repos)

  alpha-commons  IDLE   [commons-event] done
  beta-gateway   DIRTY  [publish] ready
  zeta-service   IDLE   [consume] blocked ← needs publish
`)
}

func TestListWorkDirShowsOnlyUnfinishedPrerequisitesOnABlockedTask(t *testing.T) {
	// 이미 끝난 선행까지 늘어놓으면 사용자가 그중 무엇이 남았는지 다시 골라내야 한다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	makeCleanRepo(t, workDirPath, "beta-gateway")
	makeCleanRepo(t, workDirPath, "zeta-service")

	writePlanFileForTest(t, workDirPath, `---
tasks:
  - id: base-a
    repo: alpha-commons
    title: 끝난 선행
    status: done
  - id: base-b
    repo: beta-gateway
    title: 아직 안 끝난 선행
    status: ready
  - id: consume
    repo: zeta-service
    title: 컨슈머 구현
    needs: [base-a, base-b]
    status: blocked
---
`)

	assertListRow(t, runListForTest(t, []string{workDirPath}),
		"zeta-service IDLE [consume] blocked ← needs base-b")
}

func TestListWorkDirOmitsTheNeedsArrowWhenEveryPrerequisiteIsDone(t *testing.T) {
	// 선행이 다 끝났는데 status 가 blocked 로 남아 있는 것은 사람이 아직 갱신하지 않았다는 뜻이다.
	// 도구는 선언을 고쳐 쓰지 않지만(설계 문서 5.3), 남은 선행이 없다는 사실은 그대로 보인다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	makeCleanRepo(t, workDirPath, "zeta-service")

	writePlanFileForTest(t, workDirPath, `---
tasks:
  - id: base-a
    repo: alpha-commons
    title: 끝난 선행
    status: done
  - id: consume
    repo: zeta-service
    title: 컨슈머 구현
    needs: [base-a]
    status: blocked
---
`)

	assertListRow(t, runListForTest(t, []string{workDirPath}),
		"zeta-service IDLE [consume] blocked")
}

func TestListWorkDirShowsTheTaskToLookAtWhenARepoHasSeveral(t *testing.T) {
	// 한 레포에 작업이 여럿이면 "지금 볼 작업" 하나만 보여준다. 진행 중인 것이 가장 앞이다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")

	writePlanFileForTest(t, workDirPath, `---
tasks:
  - id: first-done
    repo: alpha-commons
    title: 끝난 작업
    status: done
  - id: waiting
    repo: alpha-commons
    title: 막힌 작업
    needs: [first-done]
    status: blocked
  - id: startable
    repo: alpha-commons
    title: 시작 가능한 작업
    status: ready
  - id: in-progress
    repo: alpha-commons
    title: 진행 중인 작업
    status: doing
---
`)

	assertListRow(t, runListForTest(t, []string{workDirPath}),
		"alpha-commons IDLE [in-progress] doing (done 1/4)")
}

func TestListWorkDirPrefersTheReadyTaskWhenNothingIsInProgress(t *testing.T) {
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")

	writePlanFileForTest(t, workDirPath, `---
tasks:
  - id: waiting
    repo: alpha-commons
    title: 막힌 작업
    status: blocked
  - id: startable
    repo: alpha-commons
    title: 시작 가능한 작업
    status: ready
---
`)

	assertListRow(t, runListForTest(t, []string{workDirPath}),
		"alpha-commons IDLE [startable] ready (done 0/2)")
}

func TestListWorkDirSummarizesWhenEveryTaskOfARepoIsDone(t *testing.T) {
	// 전부 끝났으면 그중 하나를 고르는 것이 의미가 없다. 남은 정보는 "몇 개를 끝냈는가" 뿐이다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")

	writePlanFileForTest(t, workDirPath, `---
tasks:
  - id: first
    repo: alpha-commons
    title: 첫 작업
    status: done
  - id: second
    repo: alpha-commons
    title: 둘째 작업
    status: done
---
`)

	assertListRow(t, runListForTest(t, []string{workDirPath}),
		"alpha-commons IDLE done 2/2")
}

func TestListWorkDirLeavesTheTaskColumnEmptyForRepoWithoutTask(t *testing.T) {
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")
	makeCleanRepo(t, workDirPath, "zeta-service")

	writePlanFileForTest(t, workDirPath, `---
tasks:
  - id: only-one
    repo: zeta-service
    title: 이 레포에만 있는 작업
    status: ready
---
`)

	listOutput := runListForTest(t, []string{workDirPath})
	assertListRow(t, listOutput, "alpha-commons IDLE")
	assertListRow(t, listOutput, "zeta-service IDLE [only-one] ready")
}

func TestListWorkDirWarnsToStderrAboutPlanTasksPointingAtAMissingRepo(t *testing.T) {
	// 계획에는 있는데 클론되지 않은 레포다. 목록에는 그 행 자체가 없으므로, 경고하지 않으면
	// 사용자는 자기가 적어둔 작업이 화면에서 사라진 이유를 알 수 없다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")

	writePlanFileForTest(t, workDirPath, `---
tasks:
  - id: here
    repo: alpha-commons
    title: 있는 레포의 작업
    status: ready
  - id: elsewhere
    repo: not-cloned-yet
    title: 없는 레포의 작업
    status: ready
  - id: elsewhere-too
    repo: not-cloned-yet
    title: 같은 레포의 다른 작업
    status: blocked
    needs: [elsewhere]
---
`)

	var out, errOut bytes.Buffer
	if err := ListWorkDir(&out, &errOut, []string{workDirPath}); err != nil {
		t.Fatalf("ListWorkDir() 실패: %v", err)
	}

	warningText := errOut.String()
	if !strings.Contains(warningText, "not-cloned-yet") {
		t.Errorf("stderr = %q, 없는 레포 이름을 포함하기를 기대", warningText)
	}
	if !strings.Contains(warningText, "elsewhere") || !strings.Contains(warningText, "elsewhere-too") {
		t.Errorf("stderr = %q, 그 레포를 가리키는 작업 id 를 모두 포함하기를 기대", warningText)
	}
	// 같은 사실을 작업 수만큼 되풀이하면 경고가 길어져 정작 읽히지 않는다.
	if got := strings.Count(warningText, "not-cloned-yet"); got != 1 {
		t.Errorf("없는 레포 %q 에 대한 경고 줄 수 = %d, 레포당 한 줄을 기대\n--- stderr ---\n%s", "not-cloned-yet", got, warningText)
	}

	// 계획이 없는 레포를 가리켜도 목록 자체는 그대로 동작한다.
	assertListRow(t, out.String(), "alpha-commons IDLE [here] ready")
}

func TestListWorkDirWritesBrokenPlanWarningsToStderrAndKeepsListing(t *testing.T) {
	// 계획 파싱 실패가 도구 전체를 막지 않는다(설계 문서 6.1). LoadPlan 이 Warnings 에 담아둔 이유를
	// 표시 계층이 소비하지 않으면 사용자는 계획이 왜 사라졌는지 끝내 알 수 없다.
	workDirPath := makeWorkDir(t)
	makeCleanRepo(t, workDirPath, "alpha-commons")

	writePlanFileForTest(t, workDirPath, `---
tasks:
  - id: broken
    repo: alpha-commons
    title: 상태가 빠진 작업
---
`)

	var out, errOut bytes.Buffer
	if err := ListWorkDir(&out, &errOut, []string{workDirPath}); err != nil {
		t.Fatalf("ListWorkDir() 실패: %v", err)
	}

	if !strings.Contains(errOut.String(), "status") {
		t.Errorf("stderr = %q, 계획이 깨진 이유를 포함하기를 기대", errOut.String())
	}
	if !strings.Contains(errOut.String(), filepath.Join(".coord", "plan.md")) {
		t.Errorf("stderr = %q, 고칠 파일의 경로를 포함하기를 기대", errOut.String())
	}

	// 계획은 버렸지만 목록은 계획이 없을 때와 똑같이 나온다.
	assertListOutput(t, out.String(), `WorkDir: WorkDir-featureX   (1 repo)

  alpha-commons  IDLE
`)
}
