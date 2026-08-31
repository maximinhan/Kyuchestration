package workdir

import (
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// 이 파일은 워크디렉토리의 대화 기록이다 — .coord/conversations.json (설계 문서 5.5.2).
//
// plan.md 와 방향이 반대인 파일이다. 계획은 사람이 쓰고 도구가 읽지만, 이것은 도구가 쓰고
// 도구가 읽는다. 같은 디렉토리에 방향이 다른 두 파일이 사는 것이라, kyu init 이 만드는
// 템플릿에는 넣지 않는다 — 필요할 때 생긴다.
//
// 무엇을 적는가: 세션 라벨 하나에 claude 대화 ID 하나. 앱이 세션을 잃는 자리(앱 종료·크래시)를
// 메우는 것이 이 기록이다. 다음 실행이 같은 ID 로 --resume 하면 대화가 이어진다.
//
// 파일 형식 (schemaVersion 1):
//
//	{
//	  "schemaVersion": 1,
//	  "conversations": {
//	    "main": "d1b9d634-a927-4abf-ad80-5941671b08a8",
//	    "proj-a": "211f6974-88a8-4453-9248-a02b0d6febae"
//	  }
//	}
//
// 키가 세션 이름(kyu-<workdir>-<repo>)이 아니라 라벨(main 또는 레포 이름)인 이유는,
// 파일이 이미 그 워크디렉토리 안에 있어 워크디렉토리 이름이 키에 또 드는 것이 중복이어서다.

// conversationsFileName 은 대화 기록 파일의 이름이다. 자리는 계획 파일과 같은 .coord 다.
const conversationsFileName = "conversations.json"

// conversationsFileSchemaVersion 은 이 파일 형식의 판이다.
//
// list --json 의 판 규약과 같다 — 필드를 더하는 변경으로는 올리지 않고, 필드를 빼거나 이름·의미를
// 바꿀 때만 올린다. 다만 읽는 쪽이 도구 자신이라 모르는 판을 만났을 때의 처지가 다르다.
// 아래 readConversations 가 그것을 적는다.
const conversationsFileSchemaVersion = 1

// CoordDirectoryPermission 은 조율 디렉토리(.coord)의 권한이다.
//
// 사람이 그 안의 파일을 자기 에디터로 열고 비밀은 담기지 않으므로 읽기를 열어둔다.
// 이 디렉토리를 만드는 자리가 둘이다 — kyu init 과 이 파일의 기록. 두 곳이 각자 권한을 정하면
// 어느 문으로 들어왔는지에 따라 다른 권한의 디렉토리가 생긴다.
const CoordDirectoryPermission = 0o755

// conversationsFilePermission 은 대화 기록 파일의 권한이다. 사람이 열어볼 수 있어야 하고
// (설계 문서 5.5.2) 대화 ID 는 비밀이 아니다.
const conversationsFilePermission = 0o644

// ConversationAssignment 는 한 세션 라벨에 배정된 대화 하나다.
//
// 경고를 함께 들고 있는 이유는 Plan 과 같다(plan.go 의 Plan 주석). 기록을 읽지 못해 버린 것은
// 실패가 아니지만 — 버려도 새 대화를 열면 되므로 — 조용히 넘길 일도 아니다.
// 앞선 대화를 잃은 것은 사용자가 알아야 하는 사실이다.
type ConversationAssignment struct {
	// ConversationID 는 claude 에게 --session-id 또는 --resume 으로 넘길 값이다.
	ConversationID string

	// IsNewConversation 은 이번 호출이 이 대화를 처음 만들었는지다.
	//
	// 부르는 쪽이 --session-id(새 대화)와 --resume(이어가기) 중 어느 플래그를 쓸지가 이 값으로 갈린다
	// (설계 문서 5.5.3).
	IsNewConversation bool

	// Warnings 는 기록을 버린 이유를 사용자에게 보일 문장으로 담은 것이다.
	// 고칠 자리를 찾을 수 있도록 기록 파일 경로를 문장 안에 포함한다.
	Warnings []string
}

// AssignConversation 은 이 라벨이 쓸 대화를 정한다. 기록이 있으면 그것을, 없으면 새로 만들어 적는다.
//
// 조회처럼 보이는 이름에 부작용이 있으므로 짚어둔다 — 묻는 것이 곧 기록하는 것이다
// (설계 문서 5.5.3). 만드는 것과 적는 것을 갈라둘 수 없다. 만들어 놓고 적지 않으면 다음 물음이
// 다른 ID 를 만들고, 그러면 이어갈 대화가 매번 새로 생겨 이 기능 전체가 성립하지 않는다.
//
// 적었는데 그 대화가 실제로 열리지 않는 경우(앱의 PTY 열기 실패 등)에는 전사가 없는 채로
// 기록만 남는다. 다음 실행이 --resume 을 시도했다 실패하고, 그것을 앱의 폴백이 받는다
// (설계 문서 5.5.4). 해가 없으므로 여기서 되돌리지 않는다.
func AssignConversation(workDirPath, label string) (ConversationAssignment, error) {
	recordedConversations, warnings, err := readConversations(workDirPath)
	if err != nil {
		return ConversationAssignment{}, err
	}

	if recordedID, recorded := recordedConversations[label]; recorded {
		if conversationCanBeResumed(recordedID) {
			return ConversationAssignment{ConversationID: recordedID, Warnings: warnings}, nil
		}

		// 빈 값은 대화를 가리키지 못한다. 그대로 넘기면 claude 가 받는 인자가 --resume "" 가 되고,
		// 그 실패는 앱 안에서 곧바로 닫히는 터미널로만 보인다.
		warnings = append(warnings, fmt.Sprintf(
			"대화 기록의 %s 항목이 비어 있어 새 대화를 엽니다: %s", label, conversationsFilePath(workDirPath)))
	}

	return recordNewConversation(workDirPath, label, recordedConversations, warnings)
}

// StartNewConversation 은 이 라벨에 적혀 있던 대화를 버리고 새 대화를 배정한다 — 화면의 "새 대화로 시작".
//
// 이어가기가 기본인 자리에 이 길이 따로 필요한 이유가 둘이다. 대화가 길어져 새로 시작하고 싶은
// 것은 아주 흔한 요구이고(설계 문서 5.5.5 가가 대화 ID 를 유도하는 안을 기각한 이유가 그것이다),
// 적혀 있는 대화를 더는 이어갈 수 없을 때 — 전사가 사라졌을 때 — 사용자가 빠져나오는 길이
// 이것뿐이다(설계 문서 5.5.4).
//
// 버리는 것은 이 라벨 하나다. 한 세션을 새로 시작하는 것이 같은 워크디렉토리의 다른 세션이
// 이어가던 대화를 끊을 이유가 없다.
func StartNewConversation(workDirPath, label string) (ConversationAssignment, error) {
	recordedConversations, warnings, err := readConversations(workDirPath)
	if err != nil {
		return ConversationAssignment{}, err
	}
	return recordNewConversation(workDirPath, label, recordedConversations, warnings)
}

// recordNewConversation 은 새 대화를 만들어 이 라벨의 자리에 적고, 그 배정을 돌려준다.
//
// 두 입구가 이 자리에서 만난다 — 기록이 없어 새로 만드는 길과, 있는 기록을 버리고 새로 만드는 길.
// 만드는 것과 적는 것을 갈라둘 수 없다는 규율이 그 둘에 똑같이 걸리므로(설계 문서 5.5.3)
// 한 함수에 둔다.
func recordNewConversation(
	workDirPath, label string,
	recordedConversations map[string]string,
	warnings []string,
) (ConversationAssignment, error) {
	newID := newConversationID()
	recordedConversations[label] = newID
	if err := writeConversations(workDirPath, recordedConversations); err != nil {
		return ConversationAssignment{}, err
	}
	return ConversationAssignment{ConversationID: newID, IsNewConversation: true, Warnings: warnings}, nil
}

// LabelsWithRecordedConversation 은 이 워크디렉토리에서 이어갈 대화가 적혀 있는 라벨을 모은다.
//
// AssignConversation 과 같은 파일을 읽지만 아무것도 적지 않는다 — 이것은 진짜 조회다. 목록은
// 워크디렉토리를 열어둔 동안 3 초마다 도는 물음이라(설계 문서 부록 A), 여기에 부작용이 있으면
// 화면을 켜둔 것만으로 아무도 연 적 없는 대화가 만들어진다.
//
// 경고를 돌려주지 않는다. 기록을 버렸다는 말은 AssignConversation 이 카드를 눌러 실제로 대화를
// 이어가려는 그 순간에 한다(session_command.go 의 conversationFlagsForLabel). 3 초마다 같은 말을
// 되풀이할 자리가 아니고, 읽지 못한 기록으로는 어떤 대화도 이어갈 수 없으므로 "이어갈 대화 없음"
// 은 그 자체로 참인 답이다.
func LabelsWithRecordedConversation(workDirPath string) (map[string]bool, error) {
	recordedConversations, _, err := readConversations(workDirPath)
	if err != nil {
		return nil, err
	}

	labels := make(map[string]bool, len(recordedConversations))
	for label, recordedID := range recordedConversations {
		if conversationCanBeResumed(recordedID) {
			labels[label] = true
		}
	}
	return labels, nil
}

// conversationCanBeResumed 는 적혀 있는 값이 실제로 대화를 가리키는지다.
//
// 두 함수가 이 판단을 나눠 갖는다. 갈라지면 카드가 "이어갈 대화 있음" 이라고 말한 자리에서
// --session-id 가 나가고, 사용자는 이어진다고 믿은 대화를 잃는다.
func conversationCanBeResumed(recordedID string) bool {
	return strings.TrimSpace(recordedID) != ""
}

// conversationsFilePath 는 워크디렉토리의 대화 기록 파일 경로를 만든다: <workdir>/.coord/conversations.json.
//
// PlanFilePath 와 같은 이유로 경로 규약을 함수 하나에 모은다 — 읽는 쪽과 쓰는 쪽이 각자 조립하면
// 한쪽만 고쳤을 때 도구가 자기가 적은 기록을 읽지 못하게 되고, 그 어긋남은 "기록 없음" 이라는
// 정상 결과로 보여서 드러나지 않는다.
func conversationsFilePath(workDirPath string) string {
	return filepath.Join(workDirPath, coordDirectoryName, conversationsFileName)
}

// conversationsFile 은 기록 파일의 직렬화 모양이다.
type conversationsFile struct {
	SchemaVersion int `json:"schemaVersion"`

	// Conversations 는 세션 라벨(main 또는 레포 이름)에서 대화 ID 로 가는 표다.
	Conversations map[string]string `json:"conversations"`
}

// readConversations 는 적혀 있는 대화 표를 읽는다. 읽지 못한 기록은 버리고 그 이유를 경고로 남긴다.
//
// 버리는 쪽을 택한 근거는 계획 파일과 같다(설계 문서 6.1) — 파싱 실패가 도구 전체를 막지 않는다.
// 여기서 실패로 끝내면 사용자는 자기가 쓴 적도 없는 파일을 손으로 지우기 전까지 앱에서 어떤
// 세션도 열지 못한다. 잃는 것은 이어갈 대화뿐이고, 그것은 새 대화 하나로 대신할 수 있다.
//
// 버리는 자세가 계획 파일과 다른 자리도 하나 있다. 계획은 사람이 쓴 글이라 도구가 고쳐 쓰지
// 않지만, 이 파일은 도구가 쓰고 도구가 읽는 것이라(설계 문서 5.5.2) 다음 쓰기가 새 문서로 덮는다.
func readConversations(workDirPath string) (map[string]string, []string, error) {
	recordFilePath := conversationsFilePath(workDirPath)

	recordFileContent, err := os.ReadFile(recordFilePath)
	if errors.Is(err, os.ErrNotExist) {
		// 이 워크디렉토리에서 아직 세션을 연 적이 없다. 흔한 정상 상태다.
		return map[string]string{}, nil, nil
	}
	if err != nil {
		// 파일이 거기 있는데 읽지 못한 것(권한 등)은 기록 없음과 다른 사실이다. 없는 기록으로
		// 뭉뚱그리면 이어갈 수 있었던 대화를 새 대화로 덮어쓰고, 사용자는 그것이 왜 사라졌는지 모른다.
		return nil, nil, fmt.Errorf("대화 기록 파일 읽기 실패 (%s): %w", recordFilePath, err)
	}

	var record conversationsFile
	if err := json.Unmarshal(recordFileContent, &record); err != nil {
		return map[string]string{}, []string{fmt.Sprintf(
			"대화 기록을 읽지 못해 버립니다 (%s): %v — 이어가던 대화 대신 새 대화가 열립니다", recordFilePath, err)}, nil
	}

	// 모르는 판의 값을 아는 판인 척 읽지 않는다. 판이 오르는 것은 필드를 빼거나 뜻을 바꿀 때이므로
	// (list_json.go 의 판 규약) 모르는 판의 대화 ID 는 이 판의 대화 ID 와 같은 것이라는 보장이 없다.
	if record.SchemaVersion != conversationsFileSchemaVersion {
		return map[string]string{}, []string{fmt.Sprintf(
			"대화 기록이 모르는 판이라 버립니다 (%s): schemaVersion %d — 이 kyu 가 아는 판은 %d 입니다",
			recordFilePath, record.SchemaVersion, conversationsFileSchemaVersion)}, nil
	}

	if record.Conversations == nil {
		return map[string]string{}, nil, nil
	}
	return record.Conversations, nil, nil
}

// writeConversations 는 대화 표를 기록 파일에 적는다. .coord 가 아직 없으면 만든다.
//
// 임시 파일에 다 적고 제자리로 옮긴다. 파일에 바로 쓰면 도중에 프로세스가 죽었을 때 잘린 문서가
// 남고, 다음 실행은 그것을 깨진 기록으로 읽어 워크디렉토리의 대화를 전부 버린다 — rename 은
// 같은 파일 시스템 안에서 원자적이라 그 창이 없다.
//
// 두 실행이 겹치는 창은 남는다. 둘이 같은 표를 읽고 각자 자기 라벨을 더해 적으면 나중 것이
// 이긴다. 그때 잃는 것은 진 쪽 라벨의 기록 하나이고, 그 라벨은 다음 물음에서 새 대화를 받는다 —
// 5.5.3 이 "기록했는데 앱이 실행하지 않은 경우" 에 대해 적은 것과 같은 정도의 해다.
// 잠금을 두는 것은 그 해에 비해 큰 장치이므로 두지 않는다.
func writeConversations(workDirPath string, conversations map[string]string) error {
	recordFilePath := conversationsFilePath(workDirPath)

	coordDirectoryPath := filepath.Dir(recordFilePath)
	if err := os.MkdirAll(coordDirectoryPath, CoordDirectoryPermission); err != nil {
		return fmt.Errorf("조율 디렉토리 생성 실패 (%s): %w", coordDirectoryPath, err)
	}

	// 들여쓰기를 준다. 사람이 열어볼 수 있어야 하는 파일이고(설계 문서 5.5.2), 한 줄로 뭉치면
	// 어느 라벨의 대화인지 눈으로 찾지 못한다.
	document, err := json.MarshalIndent(conversationsFile{
		SchemaVersion: conversationsFileSchemaVersion,
		Conversations: conversations,
	}, "", "  ")
	if err != nil {
		return fmt.Errorf("대화 기록 조립 실패: %w", err)
	}
	document = append(document, '\n')

	// 임시 파일을 같은 디렉토리에 만든다. 다른 파일 시스템에 두면 rename 이 링크가 아니라 복사가
	// 되어 원자성을 잃는다.
	temporaryFile, err := os.CreateTemp(coordDirectoryPath, conversationsFileName+".*")
	if err != nil {
		return fmt.Errorf("대화 기록 임시 파일 생성 실패 (%s): %w", coordDirectoryPath, err)
	}
	temporaryFilePath := temporaryFile.Name()

	if err := writeAndCloseConversationsTemporaryFile(temporaryFile, document); err != nil {
		os.Remove(temporaryFilePath)
		return err
	}

	if err := os.Rename(temporaryFilePath, recordFilePath); err != nil {
		os.Remove(temporaryFilePath)
		return fmt.Errorf("대화 기록 파일 교체 실패 (%s): %w", recordFilePath, err)
	}
	return nil
}

// writeAndCloseConversationsTemporaryFile 은 임시 파일에 문서를 적고 닫는다.
//
// CreateTemp 이 만드는 파일은 0o600 이라 권한을 다시 준다. 그대로 두면 제자리로 옮긴 뒤에도
// 소유자만 읽을 수 있어, 계획 파일과 나란히 놓인 이 파일만 다른 규칙을 갖게 된다.
//
// 닫기를 defer 로 미루지 않는다. 버퍼가 디스크로 내려가지 못한 실패는 Close 에서만 드러나는데,
// 그 에러를 버리면 내용이 잘린 기록이 제자리로 옮겨진다.
func writeAndCloseConversationsTemporaryFile(temporaryFile *os.File, document []byte) error {
	if err := temporaryFile.Chmod(conversationsFilePermission); err != nil {
		temporaryFile.Close()
		return fmt.Errorf("대화 기록 임시 파일 권한 설정 실패 (%s): %w", temporaryFile.Name(), err)
	}
	if _, err := temporaryFile.Write(document); err != nil {
		temporaryFile.Close()
		return fmt.Errorf("대화 기록 쓰기 실패 (%s): %w", temporaryFile.Name(), err)
	}
	if err := temporaryFile.Close(); err != nil {
		return fmt.Errorf("대화 기록 임시 파일 닫기 실패 (%s): %w", temporaryFile.Name(), err)
	}
	return nil
}

// newConversationID 는 새 대화의 ID 를 만든다 — RFC 4122 버전 4 UUID.
//
// claude 가 --session-id 에 "must be a valid UUID" 를 요구한다(설계 문서 5.5.1). ID 를 알아내는
// 것이 아니라 정해주는 것이라, claude 가 자기 ID 를 어디에 어떻게 내놓는지 알 필요가 없다.
//
// 라이브러리를 들이지 않는다. 릴리스는 새 머신에서 바이너리 하나 복사로 끝나야 하고(설계 문서 8.1),
// 필요한 것은 무작위 16 바이트와 버전·변형 비트를 정해주는 두 줄이 전부다.
//
// crypto/rand.Read 에는 실패 경로가 없다 — Go 1.24 부터 이 함수는 실패하지 않고, 시스템의
// 무작위 원천이 죽으면 프로그램이 그 자리에서 멈춘다. 그래서 여기에 에러 반환이 없다.
func newConversationID() string {
	var randomBytes [16]byte
	rand.Read(randomBytes[:])

	randomBytes[6] = (randomBytes[6] & 0x0f) | 0x40 // 버전 4 (무작위)
	randomBytes[8] = (randomBytes[8] & 0x3f) | 0x80 // RFC 4122 변형

	return fmt.Sprintf("%x-%x-%x-%x-%x",
		randomBytes[0:4], randomBytes[4:6], randomBytes[6:8], randomBytes[8:10], randomBytes[10:16])
}
