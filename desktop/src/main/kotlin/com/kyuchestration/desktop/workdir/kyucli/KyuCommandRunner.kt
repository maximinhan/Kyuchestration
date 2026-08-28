package com.kyuchestration.desktop.workdir.kyucli

import com.kyuchestration.desktop.workdir.WorkDirObservationFailure

/**
 * kyu 를 한 번 실행하고 끝날 때까지 기다리는 자리.
 *
 * 어댑터 안쪽의 이음매다. 이 인터페이스가 있어야 "출력을 어떻게 읽고 실패를 어떻게 옮기는가" 를
 * 실제 프로세스 없이 시험할 수 있다.
 */
interface KyuCommandRunner {

    /**
     * @param arguments kyu 뒤에 붙는 인자. 실행 파일 이름은 여기 넣지 않는다.
     * @throws WorkDirObservationFailure.KyuExecutableNotFound 부를 kyu 가 없을 때.
     */
    fun run(arguments: List<String>): KyuCommandResult
}

/**
 * 실행이 끝난 kyu 가 남긴 것.
 *
 * 두 스트림을 갈라서 들고 있는다. kyu 는 JSON 문서만 stdout 으로 내고 사람에게 하는 말은
 * stderr 로 내기로 계약했으므로(list_json.go), 여기서 섞으면 그 구분이 사라진다.
 */
data class KyuCommandResult(
    val exitCode: Int,
    val standardOutput: String,
    val standardError: String,
)
