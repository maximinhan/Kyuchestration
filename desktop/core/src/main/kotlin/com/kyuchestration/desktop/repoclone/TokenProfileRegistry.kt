package com.kyuchestration.desktop.repoclone


/**
 * 이 머신에 등록된 GitHub 토큰 프로필을 보고, 새 토큰을 등록하는 자리.
 *
 * 레포를 묻는 포트(GitHubRepositoryCatalog)와 갈라 둔다. 이 포트만 비밀을 건네받는다 —
 * 토큰이 앱을 지나는 통로가 어디인지 물었을 때 답이 파일 하나로 끝나야, 그 통로에 로그 한 줄이
 * 끼어드는 일을 막을 수 있다.
 *
 * 토큰을 꺼내 오는 길은 없다. 앱은 토큰을 저장하지도 읽지도 않고, 이름을 kyu 에게 건네
 * "그 프로필로 붙어라" 라고만 말한다 — 저장과 인출은 엔진의 secretstore 가 하는 일이다.
 */
interface TokenProfileRegistry {

    /**
     * 등록된 프로필 전부. 하나도 없으면 빈 목록이다.
     *
     * @throws CloneStepFailure 프로필을 물어보지 못했을 때.
     */
    fun listProfiles(): List<TokenProfile>

    /**
     * 토큰을 확인하고 [profileName] 이름으로 저장한다.
     *
     * 거절당하면 아무것도 저장되지 않는다(auth_add.go). 그 보장이 있어야 화면이 실패한 등록을
     * "다시 붙여넣으세요" 로 되돌릴 수 있다 — 반쯤 저장된 프로필이 남으면 다음 실행에서 같은
     * 실패가 되풀이된다.
     *
     * @throws CloneStepFailure 토큰이 거절당했거나 등록을 마치지 못했을 때.
     */
    fun registerProfile(profileName: String, token: String): RegisteredTokenProfile

    /**
     * [profileName] 프로필과 그 토큰을 지운다.
     *
     * 돌려주는 것이 없다. 지운 뒤의 목록을 여기서 만들어 주지 않는 이유는, 앱이 지운 이름만
     * 빼서 목록을 지어내면 그 목록이 이 머신의 사실이 아니라 앱의 짐작이 되기 때문이다 —
     * 다른 터미널에서 그 사이에 프로필을 하나 더 지웠으면 앱은 없는 것을 계속 보여준다.
     * 지운 뒤의 목록은 부르는 쪽이 [listProfiles] 로 다시 묻는다.
     *
     * @throws CloneStepFailure 그런 이름이 없거나 지우지 못했을 때.
     */
    fun removeProfile(profileName: String)
}
