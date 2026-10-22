// 화면과 코어 사이를 건너는 값의 모듈(core-ui-split-design.md 6.3 · 6.15). 코어도 화면도 이 클래스를 그대로 쓴다 — 코어 안의
// 모델과 선 위의 모델을 따로 두고 사이에 옮겨 적는 층을 두지 않는다.
plugins {
    alias(libs.plugins.kotlinJvm)
    // 경계를 건너는 값에 직렬화기를 붙여 준다. 리플렉션 없이 컴파일 시점에 만들어지므로, 필드 이름을 잘못 적으면 실행이 아니라
    // 빌드에서 걸린다.
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    // 틀 · 모델의 타입이 이 라이브러리의 타입(JsonObject)을 품는다. 이 모듈을 쓰는 쪽도 그 타입을 보므로 api 다.
    api(libs.kotlinxSerializationJson)

    // 경계 인터페이스(ScreenFacingCore)가 상태를 StateFlow 로 내놓는다. 화면의 거울과 코어의 구현이 같은 모양을 지키게 하는
    // 자리라 이 모듈에 있고, 그래서 StateFlow 도 이 모듈의 공개 표면이다.
    api(libs.kotlinxCoroutinesCore)
}
