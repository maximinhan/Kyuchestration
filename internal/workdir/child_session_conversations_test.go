package workdir

import (
	"encoding/json"
	"os"
	"strings"
	"testing"
	"time"
)

// conversationsFileWithChildSessionsForTest 는 하위 세션까지 담은 기록 파일의 모양이다
// (session-tree-design.md 6.14). 앞선 두 모양과 나란히 둔다 — 필드를 더하는 변경이 판을 올리지
// 않는다는 규약이 여기 걸린다.
type conversationsFileWithChildSessionsForTest struct {
	SchemaVersion int                                                 `json:"schemaVersion"`
	Conversations map[string]string                                   `json:"conversations"`
	Delegations   map[string]string                                   `json:"delegations"`
	ChildSessions map[string]map[string]childSessionRecordFileForTest `json:"childSessions"`
}

type childSessionRecordFileForTest struct {
	ConversationID string  `json:"conversationId"`
	Cwd            string  `json:"cwd"`
	Title          string  `json:"title"`
	StartedAt      string  `json:"startedAt"`
	EndedAt        *string `json:"endedAt"`
}

func readConversationsFileWithChildSessionsForTest(t *testing.T, workDirPath string) (conversationsFileWithChildSessionsForTest, string) {
	t.Helper()

	fileContent, err := os.ReadFile(conversationsFilePathForTest(workDirPath))
	if err != nil {
		t.Fatalf("기록 파일을 읽지 못했습니다: %v", err)
	}

	var record conversationsFileWithChildSessionsForTest
	if err := json.Unmarshal(fileContent, &record); err != nil {
		t.Fatalf("기록 파일을 JSON 으로 읽지 못했습니다: %v\n--- 파일 ---\n%s", err, fileContent)
	}
	return record, string(fileContent)
}

func startChildForTest(t *testing.T, workDirPath, parentLabel, childID, cwd, title string, startedAt time.Time) ChildSessionAssignment {
	t.Helper()

	assignment, err := StartChildSessionConversation(workDirPath, parentLabel, childID,
		ChildSessionStart{Cwd: cwd, Title: title, StartedAt: startedAt})
	if err != nil {
		t.Fatalf("StartChildSessionConversation(%s, %s) 실패: %v", parentLabel, childID, err)
	}
	return assignment
}

func TestAChildSessionIsRecordedUnderItsParentLabel(t *testing.T) {
	// 트리를 그대로 적는다(6.14). 상위 라벨 아래에 하위를 두어야, 사람이 연 레포 세션이 상위가 되는
	// 날(8 절 4 번) 자리를 다시 정하지 않는다.
	workDirPath := t.TempDir()
	startedAt := time.Date(2026, 10, 20, 10, 1, 12, 0, time.UTC)

	first := startChildForTest(t, workDirPath, "main", "child-1", "proj-a", "proj-a 포트 수정", startedAt)
	second := startChildForTest(t, workDirPath, "main", "child-2", "scratch-x", "scratch-x", startedAt.Add(2*time.Second))

	if first.ConversationID == second.ConversationID {
		t.Fatalf("두 하위의 대화 ID 가 %q 로 같습니다", first.ConversationID)
	}

	record, fileContent := readConversationsFileWithChildSessionsForTest(t, workDirPath)
	child := record.ChildSessions["main"]["child-1"]
	if child.ConversationID != first.ConversationID || child.Cwd != "proj-a" || child.Title != "proj-a 포트 수정" {
		t.Errorf("childSessions[main][child-1] = %+v, 배정한 대화·cwd·title 을 기대", child)
	}
	if child.StartedAt != "2026-10-20T10:01:12Z" {
		t.Errorf("startedAt = %q, want 2026-10-20T10:01:12Z", child.StartedAt)
	}
	// 끝난 시각은 앱만 안다. 모르는 동안은 빈 문자열이 아니라 null 이다 — "모름" 을 두 모양으로 두지 않는다.
	if child.EndedAt != nil || !strings.Contains(fileContent, `"endedAt": null`) {
		t.Errorf("endedAt = %v, 막 뜬 하위는 null 이기를 기대\n--- 파일 ---\n%s", child.EndedAt, fileContent)
	}
	if record.ChildSessions["main"]["child-2"].ConversationID != second.ConversationID {
		t.Errorf("child-2 가 같은 상위 아래 함께 적히기를 기대 (파일 = %+v)", record.ChildSessions)
	}
}

func TestAChildSessionDoesNotTakeTheParentsConversation(t *testing.T) {
	// 키 공간을 가르는 이유다(6.14). 상위와 하위가 하나의 UUID 를 쓰면 뒤에 오는 쪽이 already in use
	// 로 거절된다 — 같은 라벨 이름이 두 지도에 있어도 서로를 덮지 않아야 한다.
	workDirPath := t.TempDir()

	parent, err := AssignConversation(workDirPath, "main", SessionConversation)
	if err != nil {
		t.Fatalf("AssignConversation() 실패: %v", err)
	}
	child := startChildForTest(t, workDirPath, "main", "child-1", ".", "루트", time.Now())

	if child.ConversationID == parent.ConversationID {
		t.Fatalf("하위가 상위의 대화 %q 를 받았습니다", parent.ConversationID)
	}
	record, _ := readConversationsFileWithChildSessionsForTest(t, workDirPath)
	if record.Conversations["main"] != parent.ConversationID {
		t.Errorf("conversations[main] = %q, 하위를 적은 뒤에도 상위의 대화가 남기를 기대", record.Conversations["main"])
	}
}

func TestRecordingASessionOrDelegationKeepsTheChildSessions(t *testing.T) {
	// 한 파일에 세 지도가 산다. 세션 대화를 하나 배정하는 쓰기가 읽어온 두 지도만 다시 적으면
	// 하위 세션의 기록이 통째로 사라진다 — 재시작 뒤 상위가 맥락을 되찾을 재료다(6.15).
	workDirPath := t.TempDir()

	child := startChildForTest(t, workDirPath, "main", "child-1", "proj-a", "proj-a", time.Now())
	if _, err := StartNewConversation(workDirPath, "main", SessionConversation); err != nil {
		t.Fatalf("StartNewConversation() 실패: %v", err)
	}
	if _, err := AssignConversation(workDirPath, "proj-a", DelegationConversation); err != nil {
		t.Fatalf("AssignConversation() 실패: %v", err)
	}

	record, _ := readConversationsFileWithChildSessionsForTest(t, workDirPath)
	if record.ChildSessions["main"]["child-1"].ConversationID != child.ConversationID {
		t.Errorf("childSessions = %+v, 다른 지도를 적은 뒤에도 child-1 이 남기를 기대", record.ChildSessions)
	}
}

func TestAChildIdAlreadyRecordedUnderTheParentIsRefused(t *testing.T) {
	// 하위는 v1 에서 이어가지 않는다(6.15). 같은 id 로 다시 적으면 앞 하위의 대화 ID 가 사라지고,
	// 상위의 전사에 남은 "child-1" 이 가리킬 기록이 없어진다. 앱이 id 를 다시 쓴 것은 앱의 실수라
	// 소리 내어 거절한다.
	workDirPath := t.TempDir()
	first := startChildForTest(t, workDirPath, "main", "child-1", "proj-a", "proj-a", time.Now())

	_, err := StartChildSessionConversation(workDirPath, "main", "child-1",
		ChildSessionStart{Cwd: "proj-b", Title: "proj-b", StartedAt: time.Now()})
	if err == nil {
		t.Fatal("이미 적힌 child-1 을 다시 받았습니다 — 거절을 기대")
	}
	if !strings.Contains(err.Error(), "child-1") {
		t.Errorf("거절 문구 = %q, 어느 id 가 겹쳤는지 담기를 기대", err)
	}

	record, _ := readConversationsFileWithChildSessionsForTest(t, workDirPath)
	if record.ChildSessions["main"]["child-1"].ConversationID != first.ConversationID {
		t.Errorf("거절한 요청이 앞 기록을 바꿨습니다: %+v", record.ChildSessions["main"]["child-1"])
	}
}

func TestTheFirstChildWithoutAnIdIsNamedChildOne(t *testing.T) {
	// id 를 비워 물으면 엔진이 짓는다. 기록을 읽는 쪽이 엔진 하나여야 앱이 파일 형식을 알 필요가 없다.
	workDirPath := t.TempDir()

	assignment := startChildForTest(t, workDirPath, "main", "", "proj-a", "proj-a", time.Now())

	if assignment.ChildID != "child-1" {
		t.Errorf("ChildID = %q, 빈 기록에서는 child-1 을 기대", assignment.ChildID)
	}
	record, _ := readConversationsFileWithChildSessionsForTest(t, workDirPath)
	if record.ChildSessions["main"]["child-1"].ConversationID != assignment.ConversationID {
		t.Errorf("childSessions = %+v, 지은 id child-1 아래 배정한 대화가 적히기를 기대", record.ChildSessions)
	}
}

func TestAnUnnamedChildTakesOneMoreThanTheLargestNumberNotAGap(t *testing.T) {
	// 빈 번호(child-2)를 다시 쓰지 않는다. 상위의 전사에 남은 id 는 한 하위만 가리켜야 한다 — 빈 자리가
	// 왜 비었는지 엔진은 모르고, 그 자리를 채우면 같은 이름이 두 하위를 가리키게 될 수 있다.
	workDirPath := t.TempDir()
	startChildForTest(t, workDirPath, "main", "child-1", "proj-a", "proj-a", time.Now())
	startChildForTest(t, workDirPath, "main", "child-3", "proj-b", "proj-b", time.Now())
	startChildForTest(t, workDirPath, "main", "reviewer", "proj-c", "proj-c", time.Now())

	assignment := startChildForTest(t, workDirPath, "main", "", "proj-d", "proj-d", time.Now())

	if assignment.ChildID != "child-4" {
		t.Errorf("ChildID = %q, 가장 큰 번호 3 다음인 child-4 를 기대", assignment.ChildID)
	}
}

func TestChildNumbersAreCountedPerParent(t *testing.T) {
	// id 는 상위 하나 안에서만 유일하다(6.14). 다른 상위의 번호를 세면 기록이 트리라는 뜻이 흐려진다.
	workDirPath := t.TempDir()
	startChildForTest(t, workDirPath, "proj-a", "child-7", "proj-a", "proj-a", time.Now())

	assignment := startChildForTest(t, workDirPath, "main", "", "proj-b", "proj-b", time.Now())

	if assignment.ChildID != "child-1" {
		t.Errorf("ChildID = %q, main 아래 첫 하위라 child-1 을 기대", assignment.ChildID)
	}
}
