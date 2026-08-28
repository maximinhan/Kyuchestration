plugins {
    alias(libs.plugins.kotlinJvm)
}

group = "com.kyuchestration.desktop"
version = "0.1.0"

kotlin {
    // 툴체인을 못 박아 두면 개발자 머신의 기본 JDK 가 무엇이든 같은 바이트코드가 나온다.
    jvmToolchain(21)
}

dependencies {
    // kotlin("test") 는 적용된 Kotlin 플러그인의 버전을 그대로 따라간다. 버전 카탈로그에 한 줄 더
    // 적으면 Kotlin 을 올릴 때 두 곳을 맞춰야 하므로 여기서는 일부러 버전을 적지 않는다.
    testImplementation(kotlin("test"))
}

tasks.jar {
    manifest {
        // DesktopBuildVersion 이 화면에 올릴 버전을 여기서 읽는다. 이 한 줄이 빠지면 정식으로
        // 패키징한 앱도 영영 "개발 빌드" 로 뜬다.
        attributes("Implementation-Version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}
