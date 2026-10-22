// 화면 모듈(core-ui-split-design.md 6.15). Compose 창과 거울. 코어를 의존하지 않는다 — 화면이 코어의 클래스를 볼 수 있으면 같은 JVM
// 지름길이 생긴다(원칙 20). 화면은 코어를 자식 프로세스로 띄우고 그 stdio 의 틀 줄로만 말한다.
plugins {
    alias(libs.plugins.kotlinJvm)
    // Compose 컴파일러는 Kotlin 2.0 부터 Kotlin 과 같은 버전으로 함께 배포된다. 그래서 이 플러그인은
    // composeMultiplatform 이 아니라 kotlin 버전을 따라간다.
    alias(libs.plugins.kotlinComposeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":diagnostics"))

    // currentOs 는 빌드하는 머신의 스키코(Skia 바인딩)만 가져온다. 배포용 패키지는 각 OS 에서
    // 만들기 때문에 개발 빌드가 세 플랫폼의 네이티브 바이너리를 모두 받을 이유가 없다.
    implementation(compose.desktop.currentOs)
    implementation(libs.composeMaterial3)
    // 카드가 도구 인자 · 엔진의 답 문서를 JSON 으로 읽는다. protocol 이 끌고 오지만 이 모듈이 직접 쓰므로 적는다 — 끌려온 것에
    // 기대면 그쪽 의존이 바뀔 때 이유도 모르고 깨진다.
    implementation(libs.kotlinxSerializationJson)

    // 거울이 틀을 읽는 코루틴과 StateFlow 를 직접 쓴다. 같은 이유로 적는다.
    implementation(libs.kotlinxCoroutinesCore)

    // 모델의 답을 그리는 마크다운 렌더러(설계 5.8). 셋을 함께 넣는다 — core 는 Material 을 모르고,
    // -m3 가 테마를 붙이고, -code 가 코드 펜스에 색을 넣는다. 하이라이터는 -code 가 끌고 오지만
    // 우리가 다크 여부를 직접 넘기므로 쓰는 것을 그대로 적는다.
    implementation(libs.markdownRenderer)
    implementation(libs.markdownRendererM3)
    implementation(libs.markdownRendererCode)
    implementation(libs.highlights)

    // 거울의 시험은 가상 시간 위에서 틀을 하나씩 흘려 본다.
    testImplementation(libs.kotlinxCoroutinesTest)

    // 화면의 시험 몇이 코어를 쓴다 — 녹화한 claude 줄을 코어의 어댑터로 접어 카드를 그려 보고, 진짜 홀더와 거울을 메모리 줄로 잇고,
    // 코어를 진짜 자식 프로세스로 띄운다. 시험에만 있는 의존이라 화면의 코드는 여전히 코어를 볼 수 없다.
    testImplementation(project(":core"))
    testImplementation(testFixtures(project(":core")))
}
