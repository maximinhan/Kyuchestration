package com.kyuchestration.desktop.kyu

import java.nio.file.Path

/**
 * 부른 내용을 적어 두고 미리 정한 답을 돌려주는 실행기.
 *
 * 어댑터마다 자기 파일 안에 같은 것을 두고 있었다. 실행기를 쓰는 어댑터가 다섯이 되면서
 * (관찰 · 초기화 · 토큰 프로필 · 레포 조회 · 클론) 같은 클래스를 다섯 번 적게 됐고, 그때부터는
 * "무엇을 적어 두는가" 가 파일마다 조금씩 갈라진다 — 실제로 stdin 을 적어 두는 것은 이 자리에
 * 하나뿐인 지금 한 번에 더하면 되는 일이다.
 *
 * 모의 라이브러리를 쓰지 않는다. 어댑터가 시험하는 것은 "어떤 인자로 불렀는가" 와 "그 답을 어떻게
 * 읽었는가" 뿐이고, 그 둘은 목록 세 개와 람다 하나면 눈으로 읽힌다.
 */
internal class RecordingKyuCommandRunner(
    private val respondTo: (List<String>) -> KyuCommandResult,
) : KyuCommandRunner {

    val receivedArguments = mutableListOf<List<String>>()
    val receivedWorkingDirectories = mutableListOf<Path?>()

    /** 자식 프로세스의 stdin 으로 흘러간 것. 토큰이 인자가 아니라 이 통로로 가는지를 여기서 본다. */
    val receivedStandardInputs = mutableListOf<String?>()

    override fun run(arguments: List<String>, workingDirectory: Path?, standardInput: String?): KyuCommandResult {
        receivedArguments += arguments
        receivedWorkingDirectories += workingDirectory
        receivedStandardInputs += standardInput
        return respondTo(arguments)
    }
}

/** 종료 코드 0 으로 이 문서를 낸 실행. */
internal fun succeedingKyuCommandResult(standardOutput: String) =
    KyuCommandResult(exitCode = 0, standardOutput = standardOutput, standardError = "")
