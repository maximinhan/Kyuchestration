package secretstore

// secretNamespace 는 한 종류의 비밀이 놓이는 자리의 이름들이다.
//
// 이 도구가 맡아 두는 비밀이 둘이 되면서 생긴 타입이다 — GitHub 토큰과 claude 자격 증명.
// 둘은 쓰임도 수명도 다르고, 한쪽을 지우는 일이 다른 쪽을 건드려서는 안 된다.
//
// **이름 공간을 프로필 이름이 아니라 저장소 이름에서 가른다.** 같은 키체인 서비스 안에서
// 계정 이름으로만 갈랐다면, "claude" 라는 이름의 GitHub 프로필을 만든 사용자가 자기 claude
// 토큰을 소리 없이 덮어쓴다. 서비스 이름부터 다르면 그 자리 자체가 생기지 않고, 사용자가
// 자기 비밀번호 관리자에서 둘을 갈라 보고 갈라 지울 수 있다.
type secretNamespace struct {
	// keychainServiceName 은 키체인·secret-service 항목의 service 속성이다.
	//
	// 사용자가 자기 키체인에서 이 항목을 찾아 직접 지울 수 있어야 하므로 도구 이름으로 시작한다.
	keychainServiceName string

	// secretToolItemLabel 은 secret-service 항목의 표시 이름이다.
	// 사용자의 비밀번호 관리자 화면에 그대로 보이는 문구다.
	secretToolItemLabel string

	// credentialsFileName 은 폴백 저장소가 이 이름 공간의 값을 적는 파일이다.
	credentialsFileName string
}

// gitHubTokenNamespace 는 kyu auth 가 다루는 GitHub 토큰의 자리다.
//
// 세 값이 모두 이 타입이 생기기 전의 상수 그대로다. 한 글자라도 바꾸면 이미 등록해 둔
// 사용자의 토큰을 이 도구가 다시 찾지 못한다 — 이름 공간을 나누는 일이 기존 사용자의
// 자격 증명을 잃는 일이 되어서는 안 된다.
var gitHubTokenNamespace = secretNamespace{
	keychainServiceName: "kyu",
	secretToolItemLabel: "kyu GitHub token",
	credentialsFileName: "credentials.json",
}

// claudeTokenNamespace 는 kyu claude-auth 가 다루는 claude 자격 증명의 자리다.
var claudeTokenNamespace = secretNamespace{
	keychainServiceName: "kyu-claude",
	secretToolItemLabel: "kyu Claude Code token",
	credentialsFileName: "claude-credentials.json",
}
