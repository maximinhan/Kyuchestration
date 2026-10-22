// 세션을 소유하는 헤드리스 코어(core-ui-split-design.md 6.15). 상태 홀더 · claude 프로세스 · 앱 소켓 · kyu 호출이 여기 있다.
//
// Compose 를 의존하지 않는다. 클래스패스의 대부분이 화면 것이고(설계 3.6), 코어 코드가 Compose 타입을 쓰면 여기서 컴파일이
// 깨진다 — 의존 방향을 시험이 아니라 빌드가 막는다.
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    // 코어의 시험 도구(기록하는 진단 기록 · 명령을 모두 한 번씩 부르는 자리 · 시험용 kyu 바이너리)를 화면의 시험도 쓴다.
    // 시험 소스를 서로 끌어다 쓰면 한 모듈의 시험이 다른 모듈의 시험 클래스 전부를 싣는다 — 내놓을 것만 test fixtures 로 둔다.
    `java-test-fixtures`
}

dependencies {
    api(project(":protocol"))
    api(project(":diagnostics"))
    implementation(libs.kotlinxSerializationJson)
    implementation(libs.kotlinxCoroutinesCore)

    // 폴링을 실제로 3 초 기다리며 시험하면 테스트가 그만큼 느려진다. 가상 시간으로 돌린다.
    testImplementation(libs.kotlinxCoroutinesTest)

    // 시험용 kyu 가 없을 때 시험을 건너뛰게 하는 자리(Assumptions.abort)가 JUnit 5 를 직접 쓴다. 시험 쪽의 kotlin("test") 는
    // useJUnitPlatform 을 보고 같은 junit5 변형을 고르므로 판이 갈리지 않는다.
    testFixturesImplementation(kotlin("test-junit5"))
    testFixturesImplementation(libs.kotlinxCoroutinesTest)
    testFixturesApi(testFixtures(project(":diagnostics")))
}
