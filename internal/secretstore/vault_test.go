package secretstore

import (
	"slices"
	"strings"
	"testing"
)

func TestKeychainCommandsPassTheProfileNameButNeverTheToken(t *testing.T) {
	// macOS 는 이 머신에 없어 실제 실행을 검증할 수 없다. 대신 조립을 고정한다 — 토큰이 인자에
	// 실리면 같은 머신의 다른 사용자가 ps 로 읽을 수 있고, 그것은 플랫폼과 무관한 실수다.
	storeArguments := keychainStoreArguments(gitHubTokenNamespace, "개인")
	want := []string{"add-generic-password", "-U", "-s", "kyu", "-a", "개인", "-w"}
	if !slices.Equal(storeArguments, want) {
		t.Errorf("저장 인자 = %v, want %v", storeArguments, want)
	}

	// -w 뒤에 값이 오면 그 순간 토큰이 인자가 된다. 값 없이 두어야 security 가 표준 입력에서 읽는다.
	if storeArguments[len(storeArguments)-1] != "-w" {
		t.Errorf("저장 인자의 끝 = %q, 값 없는 -w 로 끝나기를 기대", storeArguments[len(storeArguments)-1])
	}

	lookupArguments := keychainLookupArguments(gitHubTokenNamespace, "개인")
	wantLookup := []string{"find-generic-password", "-s", "kyu", "-a", "개인", "-w"}
	if !slices.Equal(lookupArguments, wantLookup) {
		t.Errorf("조회 인자 = %v, want %v", lookupArguments, wantLookup)
	}

	deleteArguments := keychainDeleteArguments(gitHubTokenNamespace, "개인")
	wantDelete := []string{"delete-generic-password", "-s", "kyu", "-a", "개인"}
	if !slices.Equal(deleteArguments, wantDelete) {
		t.Errorf("삭제 인자 = %v, want %v", deleteArguments, wantDelete)
	}
}

func TestSecretToolCommandsIdentifyTheProfileByServiceAndAccount(t *testing.T) {
	// 조회·삭제가 저장할 때와 같은 속성을 써야 같은 항목을 가리킨다. 한 글자만 달라도 저장은
	// 성공하는데 조회는 빈손으로 돌아오고, 사용자는 토큰이 사라졌다고 여긴다.
	storeArguments := secretToolStoreArguments(gitHubTokenNamespace, "개인")
	want := []string{"store", "--label=kyu GitHub token", "service", "kyu", "account", "개인"}
	if !slices.Equal(storeArguments, want) {
		t.Errorf("저장 인자 = %v, want %v", storeArguments, want)
	}
	if slices.Contains(storeArguments, "--label") {
		t.Error("--label 을 값과 떼어 적었습니다 — secret-tool 은 --label=값 형태를 받는다")
	}

	lookupArguments := secretToolLookupArguments(gitHubTokenNamespace, "개인")
	wantLookup := []string{"lookup", "service", "kyu", "account", "개인"}
	if !slices.Equal(lookupArguments, wantLookup) {
		t.Errorf("조회 인자 = %v, want %v", lookupArguments, wantLookup)
	}

	clearArguments := secretToolClearArguments(gitHubTokenNamespace, "개인")
	wantClear := []string{"clear", "service", "kyu", "account", "개인"}
	if !slices.Equal(clearArguments, wantClear) {
		t.Errorf("삭제 인자 = %v, want %v", clearArguments, wantClear)
	}
}

func TestVaultFallsBackToTheConfigFileWhenNoPlatformStoreIsAvailable(t *testing.T) {
	// WSL 이 이 경우다. 키체인도 secret-service 도 없는 머신에서 저장 자체를 거절하면 kyu clone 을
	// 쓸 수 없고, 조용히 파일에 적으면 사용자는 토큰이 안전하게 보관된 줄 안다 — 그래서 폴백하되
	// 그 사실을 저장 위치 종류로 계속 드러낸다.
	t.Setenv("PATH", "")

	vault := detectSecretVault(t.TempDir(), gitHubTokenNamespace)
	if vault.kind() != StorageConfigFile {
		t.Errorf("저장 위치 = %q, 플랫폼 저장소가 없으면 %q 를 기대", vault.kind(), StorageConfigFile)
	}
}

func TestConfigFileStorageDescribesItselfAsPlaintext(t *testing.T) {
	// 사용자에게 보이는 문구다. "파일" 이라고만 하면 그것이 평문이라는 사실이 전달되지 않는다.
	description := StorageConfigFile.Description()
	if !strings.Contains(description, "평문") {
		t.Errorf("설명 = %q, 평문 저장임을 밝히기를 기대", description)
	}
}

func TestTheTwoNamespacesNeverPointAtTheSameStorageSlot(t *testing.T) {
	// 이름 공간이 갈라져 있다는 사실을 조립에서 고정한다. 저장소 왕복 시험(claude_token_store_test.go)
	// 은 파일 폴백에서만 도는데, 키체인과 secret-service 에서 겹치면 그 시험은 초록인 채로
	// 사용자의 GitHub 토큰과 claude 토큰이 서로를 덮는다.
	if gitHubTokenNamespace.keychainServiceName == claudeTokenNamespace.keychainServiceName {
		t.Errorf("두 이름 공간의 키체인 서비스 이름이 같습니다: %q", gitHubTokenNamespace.keychainServiceName)
	}
	if gitHubTokenNamespace.credentialsFileName == claudeTokenNamespace.credentialsFileName {
		t.Errorf("두 이름 공간의 폴백 파일 이름이 같습니다: %q", gitHubTokenNamespace.credentialsFileName)
	}
	if gitHubTokenNamespace.secretToolItemLabel == claudeTokenNamespace.secretToolItemLabel {
		t.Error("두 이름 공간의 표시 이름이 같습니다 — 사용자가 자기 관리자에서 둘을 가르지 못합니다")
	}

	// 계정 이름이 같아도 서비스가 다르면 같은 항목을 가리키지 않는다. 그 사실을 인자로 확인한다.
	gitHubArguments := secretToolLookupArguments(gitHubTokenNamespace, claudeTokenAccountName)
	claudeArguments := secretToolLookupArguments(claudeTokenNamespace, claudeTokenAccountName)
	if slices.Equal(gitHubArguments, claudeArguments) {
		t.Errorf("같은 계정 이름으로 두 이름 공간이 같은 항목을 가리킵니다: %v", gitHubArguments)
	}
}
