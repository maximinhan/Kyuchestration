package session

import "strings"

// sessionNamePrefix 는 이 도구가 만든 세션임을 나타내는 접두사다.
// List() 결과에서 사용자의 다른 tmux 세션과 구분하는 근거가 된다.
const sessionNamePrefix = "kyu-"

// mainSessionSuffix 는 워크디렉토리 최상위에서 뜨는 조율용 세션의 자리표시자다.
// 레포 이름이 들어갈 자리를 차지하므로 레포 세션과 같은 규칙 하나로 다뤄진다.
const mainSessionSuffix = "main"

// tmuxTargetSyntaxReplacer 는 tmux 타깃 지정 문법과 충돌하는 문자를 하이픈으로 바꾼다.
//
// tmux 는 `-t <target>` 에서 `:` 뒤를 window, `.` 뒤를 pane 으로 해석한다.
// 이 문자가 세션 이름에 있으면 tmux 가 생성 시점에 조용히 `_` 로 바꿔버리는데,
// 그러면 우리가 기억하는 이름과 실제 세션 이름이 달라져 has-session·kill-session 이
// "can't find pane" 으로 실패한다. 이름을 만드는 시점에 미리 치환해 어긋남 자체를 없앤다.
var tmuxTargetSyntaxReplacer = strings.NewReplacer(".", "-", ":", "-")

// WorkDirSessionPrefix 는 한 워크디렉토리의 세션 이름들이 공유하는 접두사다: kyu-<workdir>-.
//
// List() 결과에서 이 워크디렉토리의 세션만 골라내는 근거다(설계 문서 5.2). 스캔 결과로 거르는
// 방법과 달리, 디렉토리가 지워졌거나 이름이 바뀐 레포의 세션도 걸린다 — 전체 종료로 정리하려는
// 대상이 바로 그런 세션이다.
//
// 알려진 한계: 워크디렉토리 이름이 다른 워크디렉토리 이름의 접두사이면(featureX 와 featureX-hotfix)
// 이 접두사가 남의 세션까지 잡는다. 이름 규칙이 하이픈으로 구분하는데 이름 자체에도 하이픈이 들어갈
// 수 있어, 세션 이름만으로는 경계를 되짚을 수 없다. 규칙을 바꿔야 풀리는 문제라 여기 적어둔다.
func WorkDirSessionPrefix(workdirName string) string {
	return sessionNamePrefix + tmuxTargetSyntaxReplacer.Replace(workdirName) + "-"
}

// RepoSessionName 은 레포 세션의 이름을 만든다: kyu-<workdir>-<repo>.
//
// 워크디렉토리 이름을 포함하므로, 서로 다른 워크디렉토리에 같은 이름의 레포가
// 클론되어 있어도 세션이 충돌하지 않는다.
func RepoSessionName(workdirName, repoName string) string {
	return WorkDirSessionPrefix(workdirName) + tmuxTargetSyntaxReplacer.Replace(repoName)
}

// MainSessionName 은 워크디렉토리 메인 세션의 이름을 만든다: kyu-<workdir>-main.
func MainSessionName(workdirName string) string {
	return RepoSessionName(workdirName, mainSessionSuffix)
}
