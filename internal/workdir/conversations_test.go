package workdir

import (
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

// conversationsFilePathForTest 는 기록 파일의 자리를 테스트가 직접 다시 적어둔 것이다.
//
// 생산 코드의 상수로 조립하지 않는다. 그것을 쓰면 이름이 바뀔 때 테스트가 함께 따라가
// 규약이 바뀐 사실이 어디에서도 드러나지 않는다. 이 경로는 설계 문서 5.5.2 가 정한 규약이라
// 테스트가 따로 고정하고 있어야 한다.
func conversationsFilePathForTest(workDirPath string) string {
	return filepath.Join(workDirPath, ".coord", "conversations.json")
}

// conversationsFileForTest 는 기록 파일이 내보내는 모양을 테스트가 직접 다시 적어둔 것이다.
//
// 이 파일은 도구가 쓰고 도구가 읽는다(설계 문서 5.5.2). 그래도 계약인 것은 같다 — 앞선 실행이
// 적어둔 것을 다음 실행이 읽고, 사람이 열어 확인한다. 모양이 조용히 바뀌면 그 두 가지가 함께 끊긴다.
type conversationsFileForTest struct {
	SchemaVersion int               `json:"schemaVersion"`
	Conversations map[string]string `json:"conversations"`
}

func readConversationsFileForTest(t *testing.T, workDirPath string) conversationsFileForTest {
	t.Helper()

	fileContent, err := os.ReadFile(conversationsFilePathForTest(workDirPath))
	if err != nil {
		t.Fatalf("기록 파일을 읽지 못했습니다: %v", err)
	}

	var record conversationsFileForTest
	if err := json.Unmarshal(fileContent, &record); err != nil {
		t.Fatalf("기록 파일을 JSON 으로 읽지 못했습니다: %v\n--- 파일 ---\n%s", err, fileContent)
	}
	return record
}

// writeConversationsFileForTest 는 이미 있는 기록 파일을 흉내 낸다. 내용을 그대로 받아
// 깨진 파일과 모르는 판까지 만들 수 있게 한다.
func writeConversationsFileForTest(t *testing.T, workDirPath, fileContent string) {
	t.Helper()

	coordDirectoryPath := filepath.Dir(conversationsFilePathForTest(workDirPath))
	if err := os.MkdirAll(coordDirectoryPath, 0o755); err != nil {
		t.Fatalf("조율 디렉토리 생성 실패: %v", err)
	}
	if err := os.WriteFile(conversationsFilePathForTest(workDirPath), []byte(fileContent), 0o644); err != nil {
		t.Fatalf("기록 파일 생성 실패: %v", err)
	}
}

func assertNoWarnings(t *testing.T, assignment ConversationAssignment) {
	t.Helper()

	if len(assignment.Warnings) != 0 {
		t.Errorf("경고 = %q, 정상 기록에는 경고가 없기를 기대", assignment.Warnings)
	}
}

func TestAssignConversationMakesAndRecordsAConversationTheFirstTime(t *testing.T) {
	// 만드는 것과 기록하는 것을 갈라둘 수 없다(설계 문서 5.5.3). 만들어 놓고 적지 않으면
	// 다음 물음이 다른 ID 를 만들고, 그러면 이어갈 대화가 매번 새로 생겨 이 기능이 성립하지 않는다.
	workDirPath := t.TempDir()

	assignment, err := AssignConversation(workDirPath, "main")
	if err != nil {
		t.Fatalf("AssignConversation() 실패: %v", err)
	}

	if !assignment.IsNewConversation {
		t.Error("IsNewConversation = false, 기록이 없던 라벨은 새 대화이기를 기대")
	}
	assertNoWarnings(t, assignment)

	record := readConversationsFileForTest(t, workDirPath)
	if record.SchemaVersion != 1 {
		t.Errorf("파일의 schemaVersion = %d, want 1", record.SchemaVersion)
	}
	if record.Conversations["main"] != assignment.ConversationID {
		t.Errorf("파일의 main = %q, 답으로 준 %q 와 같기를 기대",
			record.Conversations["main"], assignment.ConversationID)
	}
}

func TestAssignConversationAnswersTheSameConversationOnTheNextCall(t *testing.T) {
	// 설계 문서 7절 1 단계의 완료 확인 항목이다 — "두 번 불러도 같은 UUID 를 답한다".
	// 이것이 깨지면 앱을 껐다 켠 사용자가 이어갈 대화를 잃는다.
	workDirPath := t.TempDir()

	first, err := AssignConversation(workDirPath, "proj-a")
	if err != nil {
		t.Fatalf("첫 AssignConversation() 실패: %v", err)
	}
	second, err := AssignConversation(workDirPath, "proj-a")
	if err != nil {
		t.Fatalf("둘째 AssignConversation() 실패: %v", err)
	}

	if second.ConversationID != first.ConversationID {
		t.Errorf("둘째 ID = %q, 첫 ID %q 와 같기를 기대", second.ConversationID, first.ConversationID)
	}
	if second.IsNewConversation {
		t.Error("둘째 IsNewConversation = true, 이미 기록이 있으면 새 대화가 아니기를 기대")
	}
	assertNoWarnings(t, second)
}

func TestAssignConversationKeepsWhatTheOtherLabelsAlreadyHad(t *testing.T) {
	// 한 워크디렉토리에 세션이 여럿이고 카드를 누르는 순서는 사용자가 정한다. 새 라벨을 적으며
	// 앞의 것을 지우면 먼저 열었던 세션이 다음 실행에서 대화를 잃는다.
	workDirPath := t.TempDir()

	mainConversation, err := AssignConversation(workDirPath, "main")
	if err != nil {
		t.Fatalf("main AssignConversation() 실패: %v", err)
	}
	repoConversation, err := AssignConversation(workDirPath, "proj-a")
	if err != nil {
		t.Fatalf("proj-a AssignConversation() 실패: %v", err)
	}

	if repoConversation.ConversationID == mainConversation.ConversationID {
		t.Errorf("두 라벨의 ID 가 %q 로 같습니다, 라벨마다 다른 대화이기를 기대", repoConversation.ConversationID)
	}

	record := readConversationsFileForTest(t, workDirPath)
	if record.Conversations["main"] != mainConversation.ConversationID {
		t.Errorf("파일의 main = %q, 앞서 적은 %q 가 남아 있기를 기대",
			record.Conversations["main"], mainConversation.ConversationID)
	}
	if record.Conversations["proj-a"] != repoConversation.ConversationID {
		t.Errorf("파일의 proj-a = %q, want %q", record.Conversations["proj-a"], repoConversation.ConversationID)
	}
}

func TestAssignConversationMakesAnIdClaudeAcceptsAsAUuid(t *testing.T) {
	// claude 는 --session-id 에 "must be a valid UUID" 를 요구한다(설계 문서 5.5.1).
	// 모양이 어긋나면 앱이 띄운 세션이 그 자리에서 끝나고, 사용자가 보는 것은 한 줄 남기고 닫힌 터미널이다.
	validUUIDVersion4 := regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)

	// 한 번 만든 값이 우연히 맞을 수 있으므로 여러 번 만들어 본다. 비트 자리를 정해주는 두 줄이
	// 빠지면 대체로는 통과하고 가끔 틀리는 값이 나온다 — 그 가끔이 사용자 앞에서 터진다.
	workDirPath := t.TempDir()
	for callIndex := range 32 {
		assignment, err := AssignConversation(workDirPath, "레포-"+string(rune('a'+callIndex)))
		if err != nil {
			t.Fatalf("AssignConversation() 실패: %v", err)
		}
		if !validUUIDVersion4.MatchString(assignment.ConversationID) {
			t.Fatalf("대화 ID = %q, RFC 4122 버전 4 UUID 이기를 기대", assignment.ConversationID)
		}
	}
}

func TestAssignConversationLeavesTheRecordFileReadableByAPerson(t *testing.T) {
	// 설계 문서 5.5.2 — "사람이 열어볼 수 있다". 디버깅이 곧 파일 열기인 도구라
	// 이 파일이 한 줄로 뭉쳐 있으면 사람이 어느 라벨의 대화인지 눈으로 찾지 못한다.
	workDirPath := t.TempDir()

	if _, err := AssignConversation(workDirPath, "main"); err != nil {
		t.Fatalf("AssignConversation() 실패: %v", err)
	}

	fileContent, err := os.ReadFile(conversationsFilePathForTest(workDirPath))
	if err != nil {
		t.Fatalf("기록 파일을 읽지 못했습니다: %v", err)
	}
	if !strings.Contains(string(fileContent), "\n  \"conversations\"") {
		t.Errorf("파일 = %q, 사람이 읽을 들여쓰기를 기대", fileContent)
	}
	if !strings.HasSuffix(string(fileContent), "\n") {
		t.Errorf("파일 = %q, 마지막 줄바꿈을 기대", fileContent)
	}
}

func TestAssignConversationKeepsGoingWhenTheRecordFileIsBroken(t *testing.T) {
	// 계획 파일과 같은 규율이다(설계 문서 6.1) — 파싱 실패가 도구 전체를 막지 않는다.
	// 여기서 실패로 끝내면 사용자는 자기가 쓴 적도 없는 파일을 손으로 지우기 전까지
	// 앱에서 어떤 세션도 열지 못한다.
	workDirPath := t.TempDir()
	writeConversationsFileForTest(t, workDirPath, "{ 이건 JSON 이 아니다")

	assignment, err := AssignConversation(workDirPath, "main")
	if err != nil {
		t.Fatalf("AssignConversation() 가 깨진 기록 파일에서 실패했습니다: %v", err)
	}

	if !assignment.IsNewConversation || assignment.ConversationID == "" {
		t.Errorf("배정 = %+v, 깨진 기록을 버리고 새 대화를 열기를 기대", assignment)
	}

	// 조용히 넘기지도 않는다. 앞선 대화를 버린 것은 사용자가 알아야 하는 사실이고,
	// 고칠 자리를 찾을 수 있도록 경고에 파일 경로가 들어 있어야 한다.
	if len(assignment.Warnings) == 0 {
		t.Fatal("경고 = 없음, 기록을 버렸다는 사실을 알리기를 기대")
	}
	if !strings.Contains(assignment.Warnings[0], "conversations.json") {
		t.Errorf("경고 = %q, 고칠 파일의 경로를 포함하기를 기대", assignment.Warnings[0])
	}

	// 버린 기록 위에 새 문서가 놓여야 다음 실행이 이 대화를 이어간다.
	record := readConversationsFileForTest(t, workDirPath)
	if record.Conversations["main"] != assignment.ConversationID {
		t.Errorf("파일의 main = %q, want %q", record.Conversations["main"], assignment.ConversationID)
	}
}

func TestAssignConversationKeepsGoingWhenTheRecordFileIsFromASchemaItDoesNotKnow(t *testing.T) {
	// 판이 오르는 것은 필드를 빼거나 뜻을 바꿀 때다. 모르는 판의 값을 아는 판인 척 읽으면
	// 엉뚱한 UUID 로 resume 을 시도하게 되고, 그 실패는 세션 안에서야 드러난다.
	workDirPath := t.TempDir()
	writeConversationsFileForTest(t, workDirPath,
		`{"schemaVersion": 99, "conversations": {"main": "이 판의 뜻을 모른다"}}`)

	assignment, err := AssignConversation(workDirPath, "main")
	if err != nil {
		t.Fatalf("AssignConversation() 가 모르는 판에서 실패했습니다: %v", err)
	}

	if assignment.ConversationID == "이 판의 뜻을 모른다" {
		t.Error("모르는 판의 값을 그대로 썼습니다")
	}
	if !assignment.IsNewConversation {
		t.Error("IsNewConversation = false, 모르는 판을 버리고 새 대화를 열기를 기대")
	}
	if len(assignment.Warnings) == 0 {
		t.Error("경고 = 없음, 모르는 판을 버렸다는 사실을 알리기를 기대")
	}
}

func TestAssignConversationTreatsABlankRecordAsNoRecord(t *testing.T) {
	// 빈 값을 그대로 쓰면 claude 가 받는 인자가 --resume "" 가 된다.
	// 그 실패는 앱 안에서 곧바로 닫히는 터미널로만 보인다.
	workDirPath := t.TempDir()
	writeConversationsFileForTest(t, workDirPath, `{"schemaVersion": 1, "conversations": {"main": "   "}}`)

	assignment, err := AssignConversation(workDirPath, "main")
	if err != nil {
		t.Fatalf("AssignConversation() 실패: %v", err)
	}

	if !assignment.IsNewConversation || strings.TrimSpace(assignment.ConversationID) == "" {
		t.Errorf("배정 = %+v, 빈 기록을 기록 없음으로 보기를 기대", assignment)
	}
	if len(assignment.Warnings) == 0 {
		t.Error("경고 = 없음, 빈 기록을 버렸다는 사실을 알리기를 기대")
	}
}

func TestAssignConversationCreatesTheCoordDirectoryWhenItIsNotThereYet(t *testing.T) {
	// 이 파일은 kyu init 이 만드는 템플릿에 없다 — 필요할 때 생긴다(설계 문서 5.5.2).
	// 초기화하지 않은 워크디렉토리를 앱에서 여는 것도 실제로 있는 순서다.
	workDirPath := t.TempDir()

	if _, err := AssignConversation(workDirPath, "main"); err != nil {
		t.Fatalf("AssignConversation() 실패: %v", err)
	}

	coordDirectory, err := os.Stat(filepath.Join(workDirPath, ".coord"))
	if err != nil {
		t.Fatalf("조율 디렉토리를 찾지 못했습니다: %v", err)
	}
	if !coordDirectory.IsDir() {
		t.Error(".coord 가 디렉토리가 아닙니다")
	}
}

func TestAssignConversationStopsWhenTheRecordFileIsThereButUnreadable(t *testing.T) {
	// 파일이 거기 있는데 읽지 못한 것은 기록 없음과 다른 사실이다. 없는 기록으로 뭉뚱그리면
	// 이어갈 수 있었던 대화를 새 대화로 덮어쓰고, 사용자는 그 대화가 왜 사라졌는지 알 길이 없다.
	workDirPath := t.TempDir()
	if err := os.MkdirAll(conversationsFilePathForTest(workDirPath), 0o755); err != nil {
		t.Fatalf("준비 실패: %v", err)
	}

	if _, err := AssignConversation(workDirPath, "main"); err == nil {
		t.Fatal("AssignConversation() 가 읽을 수 없는 기록 파일에서 성공했습니다")
	}
}

func TestLabelsWithRecordedConversationAnswersWhatTheNextCallWillResume(t *testing.T) {
	// 카드가 "이어갈 대화 있음" 이라고 말한 자리에서 --session-id 가 나가면, 사용자는 이어진다고
	// 믿은 대화를 잃는다. 두 함수가 같은 판단을 쓰는지를 여기서 고정한다.
	workDirPath := t.TempDir()
	if _, err := AssignConversation(workDirPath, "main"); err != nil {
		t.Fatalf("준비 실패: %v", err)
	}
	if _, err := AssignConversation(workDirPath, "proj-a"); err != nil {
		t.Fatalf("준비 실패: %v", err)
	}

	labels, err := LabelsWithRecordedConversation(workDirPath)
	if err != nil {
		t.Fatalf("LabelsWithRecordedConversation() 실패: %v", err)
	}

	if !labels["main"] || !labels["proj-a"] {
		t.Errorf("라벨 = %v, 적어둔 두 라벨 모두를 기대", labels)
	}
	if labels["proj-b"] {
		t.Errorf("라벨 = %v, 적은 적 없는 라벨은 없기를 기대", labels)
	}
}

func TestLabelsWithRecordedConversationRecordsNothingItself(t *testing.T) {
	// 목록은 워크디렉토리를 열어둔 동안 3 초마다 도는 물음이다. 여기에 부작용이 있으면 화면을
	// 켜둔 것만으로 대화가 만들어지고, 그 대화는 아무도 연 적이 없다.
	workDirPath := t.TempDir()

	labels, err := LabelsWithRecordedConversation(workDirPath)
	if err != nil {
		t.Fatalf("LabelsWithRecordedConversation() 실패: %v", err)
	}

	if len(labels) != 0 {
		t.Errorf("라벨 = %v, 기록이 없으면 비어 있기를 기대", labels)
	}
	if _, statErr := os.Stat(conversationsFilePathForTest(workDirPath)); statErr == nil {
		t.Error("조회가 기록 파일을 만들었습니다")
	}
}

func TestLabelsWithRecordedConversationTreatsARecordItCannotUseAsNothingToResume(t *testing.T) {
	// 읽지 못한 기록으로는 어떤 대화도 이어갈 수 없다 — "이어갈 대화 없음" 은 그 자체로 참인 답이다.
	// 버렸다는 말은 AssignConversation 이 카드를 누른 그 자리에서 한다.
	for testName, fileContent := range map[string]string{
		"깨진 기록":   "{ 이건 JSON 이 아니다",
		"모르는 판":   `{"schemaVersion": 99, "conversations": {"main": "이 판의 뜻을 모른다"}}`,
		"빈 값의 대화": `{"schemaVersion": 1, "conversations": {"main": "   "}}`,
	} {
		t.Run(testName, func(t *testing.T) {
			workDirPath := t.TempDir()
			writeConversationsFileForTest(t, workDirPath, fileContent)

			labels, err := LabelsWithRecordedConversation(workDirPath)
			if err != nil {
				t.Fatalf("LabelsWithRecordedConversation() 실패: %v", err)
			}

			if labels["main"] {
				t.Errorf("라벨 = %v, 이어갈 수 없는 기록은 없는 것으로 보기를 기대", labels)
			}
		})
	}
}

func TestLabelsWithRecordedConversationStopsWhenTheRecordFileIsThereButUnreadable(t *testing.T) {
	// 파일이 거기 있는데 읽지 못한 것은 기록 없음과 다른 사실이다. 없는 기록으로 뭉뚱그리면
	// 카드가 "이어갈 대화 없음" 이라고 말하고, 사용자는 대화가 왜 사라졌는지 알 길이 없다.
	workDirPath := t.TempDir()
	if err := os.MkdirAll(conversationsFilePathForTest(workDirPath), 0o755); err != nil {
		t.Fatalf("준비 실패: %v", err)
	}

	if _, err := LabelsWithRecordedConversation(workDirPath); err == nil {
		t.Fatal("LabelsWithRecordedConversation() 이 읽을 수 없는 기록 파일에서 성공했습니다")
	}
}

func TestStartNewConversationReplacesTheRecordSoTheNextCallResumesTheNewOne(t *testing.T) {
	// 화면의 "새 대화로 시작" 이 여기로 온다. 기록을 교체하지 않으면 다음에 카드를 누른 사용자가
	// 방금 버린 대화로 되돌아간다.
	workDirPath := t.TempDir()
	recorded, err := AssignConversation(workDirPath, "proj-a")
	if err != nil {
		t.Fatalf("준비 실패: %v", err)
	}

	fresh, err := StartNewConversation(workDirPath, "proj-a")
	if err != nil {
		t.Fatalf("StartNewConversation() 실패: %v", err)
	}

	if !fresh.IsNewConversation {
		t.Error("IsNewConversation = false, 새로 시작한 대화이기를 기대")
	}
	if fresh.ConversationID == recorded.ConversationID {
		t.Errorf("대화 ID = %q, 앞서 적혀 있던 것과 다르기를 기대", fresh.ConversationID)
	}

	next, err := AssignConversation(workDirPath, "proj-a")
	if err != nil {
		t.Fatalf("AssignConversation() 실패: %v", err)
	}
	if next.ConversationID != fresh.ConversationID {
		t.Errorf("다음 물음의 대화 ID = %q, 새로 시작한 %q 를 이어가기를 기대",
			next.ConversationID, fresh.ConversationID)
	}
	if next.IsNewConversation {
		t.Error("다음 물음의 IsNewConversation = true, 새로 시작한 대화를 이어가기를 기대")
	}
}

func TestStartNewConversationLeavesTheOtherLabelsAlone(t *testing.T) {
	// 한 세션을 새로 시작하는 것이 다른 세션의 대화를 끊을 이유가 없다.
	workDirPath := t.TempDir()
	mainConversation, err := AssignConversation(workDirPath, "main")
	if err != nil {
		t.Fatalf("준비 실패: %v", err)
	}
	if _, err := AssignConversation(workDirPath, "proj-a"); err != nil {
		t.Fatalf("준비 실패: %v", err)
	}

	if _, err := StartNewConversation(workDirPath, "proj-a"); err != nil {
		t.Fatalf("StartNewConversation() 실패: %v", err)
	}

	record := readConversationsFileForTest(t, workDirPath)
	if record.Conversations["main"] != mainConversation.ConversationID {
		t.Errorf("파일의 main = %q, 앞서 적은 %q 가 그대로 남아 있기를 기대",
			record.Conversations["main"], mainConversation.ConversationID)
	}
}

func TestStartNewConversationWorksWhenNothingWasRecordedYet(t *testing.T) {
	// 아직 아무 대화도 없는 라벨에 대해서도 답이 있어야 한다. 앱이 이 길을 고르는 자리는
	// 카드와 끝난 세션의 머리말이고, 그 사이에 기록이 사라지는 길이 있다.
	workDirPath := t.TempDir()

	fresh, err := StartNewConversation(workDirPath, "main")
	if err != nil {
		t.Fatalf("StartNewConversation() 실패: %v", err)
	}

	if !fresh.IsNewConversation || fresh.ConversationID == "" {
		t.Errorf("배정 = %+v, 새 대화 하나를 기대", fresh)
	}
	if readConversationsFileForTest(t, workDirPath).Conversations["main"] != fresh.ConversationID {
		t.Error("새로 시작한 대화가 기록에 남지 않았습니다")
	}
}
