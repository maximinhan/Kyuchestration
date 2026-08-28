import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    // Compose 컴파일러는 Kotlin 2.0 부터 Kotlin 과 같은 버전으로 함께 배포된다. 그래서 이 플러그인은
    // composeMultiplatform 이 아니라 kotlin 버전을 따라간다.
    alias(libs.plugins.kotlinComposeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

group = "com.kyuchestration.desktop"
version = "0.1.0"

kotlin {
    // 툴체인을 못 박아 두면 개발자 머신의 기본 JDK 가 무엇이든 같은 바이트코드가 나온다.
    jvmToolchain(21)
}

dependencies {
    // currentOs 는 빌드하는 머신의 스키코(Skia 바인딩)만 가져온다. 배포용 패키지는 각 OS 에서
    // 만들기 때문에 개발 빌드가 세 플랫폼의 네이티브 바이너리를 모두 받을 이유가 없다.
    implementation(compose.desktop.currentOs)
    implementation(libs.composeMaterial3)

    // kotlin("test") 는 적용된 Kotlin 플러그인의 버전을 그대로 따라간다. 버전 카탈로그에 한 줄 더
    // 적으면 Kotlin 을 올릴 때 두 곳을 맞춰야 하므로 여기서는 일부러 버전을 적지 않는다.
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        // Kotlin 최상위 함수는 파일 이름에 Kt 가 붙은 클래스로 컴파일된다. Main.kt 의 main() 이라
        // MainKt 다.
        mainClass = "com.kyuchestration.desktop.MainKt"

        nativeDistributions {
            // 세 포맷 모두 jpackage 가 만들고, jpackage 는 자기가 도는 OS 용 패키지만 만들 수 있다.
            // 여기에 적어 두는 것은 "어디로 배포할 것인가" 의 선언이고, 실제 생성은 각 OS 러너에서
            // 일어난다.
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)

            // 설치 패키지 이름은 파일 이름·설치 경로·Windows 레지스트리 키에 그대로 쓰인다.
            // 한글이 섞이면 그 경로들이 깨지므로, 창 제목("뀨케스트레이션")과 분리해 ASCII 로 둔다.
            packageName = "Kyuchestration"
            packageVersion = project.version.toString()
            description = "뀨케스트레이션 — 멀티레포 워크디렉토리 오케스트레이터"
            vendor = "maximinhan"

            macOS {
                bundleID = "com.kyuchestration.desktop"
            }
        }
    }
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
