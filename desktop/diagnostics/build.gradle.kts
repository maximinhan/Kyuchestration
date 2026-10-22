// 진단 기록(core-ui-split-design.md 6.10). 화면과 코어가 각자 자기 파일에 적지만, 남길 수 있는 사건의 닫힌 갈래와 파일을 다루는
// 규칙은 하나다 — 둘로 베껴 두면 "기록에 비밀이 들어가지 않는가" 를 두 파일에서 따로 답해야 한다.
//
// 기록 파일의 자리가 맥인지에 따라 갈려서 그 판정(runningOnMac)도 여기 있다. 코어와 화면이 같은 판정을 쓴다.
plugins {
    alias(libs.plugins.kotlinJvm)
    // 기록하는 가짜(RecordingDiagnosticLog)를 코어와 화면의 시험이 함께 쓴다.
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
