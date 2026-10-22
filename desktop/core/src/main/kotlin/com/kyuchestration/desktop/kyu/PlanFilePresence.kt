package com.kyuchestration.desktop.kyu

import java.nio.file.Path
import kotlin.io.path.isRegularFile

/**
 * 이 워크디렉토리에 계획 파일(.coord/plan.md)이 있는지 본다.
 *
 * 앱이 워크디렉토리의 파일을 직접 들여다보는 유일한 자리다. `kyu list --json` 계약에는 아직
 * 이 사실이 실려 오지 않는다 — 계획이 없는 것은 흔한 정상 상태라 경고로도 나오지 않는다
 * (plan.go 의 LoadPlan). 계약에 필드가 생기면 어댑터가 그 값을 읽도록 옮기고 이 파일은 지운다.
 *
 * 파일 하나가 있는지 보는 것은 조율 계층의 복제가 아니다. 스캔·상태 추론·계획 파싱은 여전히
 * 엔진의 몫이고, 여기서 아는 것은 "계획을 두는 자리가 어디인가" 하나뿐이다. 판단을 이 함수에
 * 모아 두는 것이 그 선을 지키는 방법이다 — 화면 여기저기서 경로를 조립하기 시작하면 그때부터는
 * 앱이 워크디렉토리의 구조를 아는 것이 된다.
 */
internal fun planFileExistsIn(workDirPath: Path): Boolean =
    workDirPath.resolve(COORD_DIRECTORY_NAME).resolve(PLAN_FILE_NAME).isRegularFile()

private const val COORD_DIRECTORY_NAME = ".coord"
private const val PLAN_FILE_NAME = "plan.md"
