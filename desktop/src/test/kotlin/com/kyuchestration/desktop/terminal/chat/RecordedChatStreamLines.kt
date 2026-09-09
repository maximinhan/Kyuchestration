package com.kyuchestration.desktop.terminal.chat

/**
 * `claude` 가 실제로 낸 출력 줄을 그대로 적어둔 것.
 *
 * **2026-09-04, `claude` 2.1.259, WSL2(Linux).** 격리된 스크래치의 가짜 레포에서
 * `-p --input-format stream-json --output-format stream-json --verbose --include-partial-messages
 * --replay-user-messages` 로 두 번 돌려 받은 것이다. 하나는 파일을 읽히고, 다른 하나는
 * 서브에이전트를 띄웠다. chat-ui-design.md 3 절과 부록 A 가 같은 방법으로 잰 것들이다.
 *
 * **손으로 지어낸 줄이 아니다.** 어댑터를 지어낸 입력으로 시험하면 그 시험이 확인하는 것은
 * 우리가 상상한 `claude` 이고, 진짜와 어긋나는 자리는 앱을 띄운 사람이 처음 발견한다.
 * 여기 적힌 것은 실제로 온 것이라, 필드 이름 하나가 틀리면 이 자리에서 먼저 깨진다.
 *
 * 손댄 것은 셋이다. 긴 절대경로를 `/tmp/fakerepo` 로 줄였고, 이 머신의 홈 경로를 `/home/me` 로
 * 바꿨고, 수십 개짜리 목록(`slash_commands`·`tools`)과 아주 긴 문자열은 앞부분만 남겼다.
 * **키는 하나도 손대지 않았다** — 어댑터가 읽는 것이 그 이름들이다.
 *
 * **4 단계가 여기에 더한 것**(2026-09-04, 같은 판): 도구별 결과의 실제 모양(Bash·Edit·Write·거절)과
 * 중단 왕복이다. 3 단계까지는 중단과 거절을 설계 문서 부록 A 에서 옮겨 왔는데, 카드를 그리려면
 * 곁가지의 키 이름이 맞아야 해서 직접 받았다. **옮겨 온 줄은 이제 하나도 없다.**
 *
 * **6 단계가 여기에 더한 것**(2026-09-08, `claude` 2.1.259, 같은 방법): 서브에이전트 한 벌
 * (`Agent` 호출 → `task_started` → 안쪽 대화 → `task_progress` → `task_updated` →
 * `task_notification` → 바깥 결과)과 권한에 막힌 턴의 `result` 다. 이 줄들은 **엔진이 실제로
 * 조립하는 챗 모드 플래그 그대로**(`--forward-subagent-text` 포함) 돌려 받은 것이라, 앱이 받을
 * 것과 같다. 서브에이전트 줄들은 한 실행에서 통째로 온 것이라 `tool_use_id` 가 서로 맞는다 —
 * 손으로 짝을 지어 준 줄이 하나도 없다.
 */
internal object RecordedChatStreamLines {

    /** `system/init` — 턴마다 다시 온다(3.1). `slash_commands`·`tools`·`agents` 는 실제로는 수십 개라 앞의 몇 개만 남겼다. */
    const val SESSION_INIT: String =
        """{"type":"system","subtype":"init","cwd":"/tmp/fakerepo","session_id":"8129eebb-0f2f-4067-af9c-d189154b6617","tools":["Task","Bash","CronCreate"],"mcp_servers":[],"model":"claude-opus-5[1m]","permissionMode":"acceptEdits","slash_commands":["deep-research","design-sync","dataviz"],"terminal_slash_commands":["doctor","color"],"apiKeySource":"none","claude_code_version":"2.1.259","output_style":"default","agents":["claude","Explore"],"skills":["deep-research"],"plugins":[],"capabilities":["interrupt_receipt_v1","interrupt_cancel_queued_v1","msg_lifecycle_v1"],"analytics_disabled":false,"product_feedback_disabled":false,"uuid":"7554f9a2-e36f-402f-8555-5478fcf487f1","memory_paths":{"auto":"/home/me/.claude/projects/-tmp-fakerepo/memory/"},"messaging_socket_path":"/run/user/1000/cc-socks/1234.sock","fast_mode_state":"off","fast_mode_disabled_reason":"sdk_opt_in_required"}"""

    /** `--replay-user-messages` 가 되돌려준 사용자 메시지(3.14). `isReplay` 가 붙어 온다. */
    const val USER_MESSAGE_REPLAYED: String =
        """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"README.txt 를 Read 도구로 읽고 첫 줄을 그대로 답해."}]},"session_id":"8129eebb-0f2f-4067-af9c-d189154b6617","parent_tool_use_id":null,"uuid":"9d946d6f-32bb-4367-b44d-da4982f6e815","timestamp":"2026-09-04T06:45:44.564Z","isReplay":true}"""

    /** 텍스트 블록이 든 `assistant`. 같은 턴에서 위의 도구 호출과 따로 왔다 — 블록 단위라는 뜻이다(3.2). */
    const val ASSISTANT_TEXT: String =
        """{"type":"assistant","message":{"model":"claude-opus-5","id":"msg_011CehwzCGqjvFkxV1SzAHh3","type":"message","role":"assistant","content":[{"type":"text","text":"첫 줄: `hello from fake repo`\n\n참고로 이 세션에서 claude.ai Gmail / Google Calendar / Google Drive MCP 서버는 인증이 필요한 상태이며, 비대화형 세션이라 여기서 OAuth를 진행할 수 없습니다. 해당 커넥터를 쓰시려면 claude.ai 커넥터 설정에서 인증해 주세요."}],"stop_reason":null,"stop_sequence":null,"stop_details":null,"usage":{"input_tokens":2,"cache_creation_input_tokens":341,"cache_read_input_tokens":16577,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":341},"output_tokens":2,"service_tier":"standard","inference_geo":"not_available"},"diagnostics":null,"context_management":null},"parent_tool_use_id":null,"session_id":"8129eebb-0f2f-4067-af9c-d189154b6617","uuid":"c16b4449-e2e4-4f41-8498-7aa92cd461dd","timestamp":"2026-09-04T06:45:50.968Z","request_id":"req_011CehwzBG4qVjA98AhaExdz"}"""

    /** 도구 호출 블록이 든 `assistant`(3.4). 인자가 다 채워진 완성본이다. */
    const val ASSISTANT_TOOL_USE: String =
        """{"type":"assistant","message":{"model":"claude-opus-5","id":"msg_011CehwyysiRVXFzc7ky5LM3","type":"message","role":"assistant","content":[{"type":"tool_use","id":"toolu_01J5SUwNqw8vdmZEYXkCZQqM","name":"Read","input":{"file_path":"/tmp/fakerepo/README.txt"},"caller":{"type":"direct"}}],"stop_reason":null,"stop_sequence":null,"stop_details":null,"usage":{"input_tokens":2,"cache_creation_input_tokens":6451,"cache_read_input_tokens":10126,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":6451},"output_tokens":14,"service_tier":"standard","inference_geo":"not_available"},"diagnostics":null,"context_management":null},"parent_tool_use_id":null,"session_id":"8129eebb-0f2f-4067-af9c-d189154b6617","uuid":"d402d2a6-b220-4d3a-b982-baaf0431d7a1","timestamp":"2026-09-04T06:45:47.437Z","request_id":"req_011Cehwyy2s7jNTt3kspf3hL"}"""

    /** 사고 블록이 든 `assistant`. **온 `thinking` 이 빈 문자열이다** — 서명만 실려 온 채로 왔다. 갈래는 이 줄로 확인되지만 사고 본문이 어떻게 실려 오는지는 아직 재지 못했다. */
    const val ASSISTANT_THINKING: String =
        """{"type":"assistant","message":{"model":"claude-opus-5","id":"msg_011CehwzaqtwSJDFaBaRYVQw","type":"message","role":"assistant","content":[{"type":"thinking","thinking":"","signature":"CAIS8AUKpgEIERgCKkBLQjNK…"}],"stop_reason":null,"stop_sequence":null,"stop_details":null,"usage":{"input_tokens":2,"cache_creation_input_tokens":3496,"cache_read_input_tokens":13298,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":3496},"output_tokens":2,"service_tier":"standard","inference_geo":"not_available"},"diagnostics":null,"context_management":null},"parent_tool_use_id":null,"session_id":"bdda591c-d334-4a29-9791-43506315ae17","uuid":"1775a887-bf06-494a-8823-4e070fb9cbe8","timestamp":"2026-09-04T06:45:57.457Z","request_id":"req_011CehwzZtqxNr44brrZ7NHT"}"""

    /** 도구 결과(3.4). `content` 는 모델이 읽는 줄 번호 붙은 텍스트고, `tool_use_result` 곁가지가 그 도구가 실제로 한 일을 담는다. */
    const val TOOL_RESULT: String =
        """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_01J5SUwNqw8vdmZEYXkCZQqM","type":"tool_result","content":"1\thello from fake repo\n2\t"}]},"parent_tool_use_id":null,"session_id":"8129eebb-0f2f-4067-af9c-d189154b6617","uuid":"42ec7320-06a7-45da-b407-39c6ceaff0c1","timestamp":"2026-09-04T06:45:47.489Z","tool_use_result":{"type":"text","file":{"filePath":"/tmp/fakerepo/README.txt","content":"hello from fake repo\n","numLines":2,"startLine":1,"totalLines":2}}}"""

    /** 글자 조각(3.3). 같은 내용의 완성본이 `assistant` 로 다시 온다. */
    const val STREAM_TEXT_DELTA: String =
        """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"첫"}},"session_id":"8129eebb-0f2f-4067-af9c-d189154b6617","parent_tool_use_id":null,"uuid":"b8162e5f-79ff-4424-ba3a-8233844993fa"}"""

    /** 도구 인자 조각(3.3). **잘린 JSON 이라 그 자체로 파싱되지 않는다** — 어댑터가 이어 붙이려 들면 안 되는 자리다. */
    const val STREAM_INPUT_JSON_DELTA: String =
        """{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"file_path\": \"/tmp/fakerepo"}},"session_id":"8129eebb-0f2f-4067-af9c-d189154b6617","parent_tool_use_id":null,"uuid":"4adc03c0-bdd4-4df0-9e94-f098c81c607c"}"""

    /** 도구 블록의 시작(3.3). 이름은 있고 `input` 은 비어 있다 — 이름이 인자보다 먼저 온다. */
    const val STREAM_TOOL_BLOCK_STARTED: String =
        """{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_01J5SUwNqw8vdmZEYXkCZQqM","name":"Read","input":{},"caller":{"type":"direct"}}},"session_id":"8129eebb-0f2f-4067-af9c-d189154b6617","parent_tool_use_id":null,"uuid":"ec402c15-dca7-4df9-b9f9-59ad91a54a2e"}"""

    /** API 요청을 보내는 중(3.2). 실측으로 아는데 아직 그릴 화면이 없는 갈래다. */
    const val SYSTEM_STATUS: String =
        """{"type":"system","subtype":"status","status":"requesting","session_id":"8129eebb-0f2f-4067-af9c-d189154b6617","uuid":"679b60cb-acf9-4543-9f92-e893a9323c6a"}"""

    /**
     * 메인이 서브에이전트를 띄우는 도구 호출.
     *
     * **도구 이름이 `Agent` 다.** 설계 문서가 `Task` 라고 적어둔 자리인데, 이 판이 실제로 부른
     * 이름은 이것이다(2026-09-08). 카드가 이름으로 갈래를 정하는 자리라 지어낸 이름을 쓰면
     * 서브에이전트 카드가 한 번도 서지 않는다.
     */
    const val AGENT_TOOL_USE: String =
        """{"type":"assistant","message":{"model":"claude-opus-5","id":"msg_011Ceq7fHrMdcTdaiP6Vi9SQ","type":"message","role":"assistant","content":[{"type":"tool_use","id":"toolu_0144EDWk6by45Zj2YrwXLNBX","name":"Agent","input":{"description":"Read README.txt first line","subagent_type":"general-purpose","run_in_background":false,"prompt":"/tmp/fakerepo/README.txt 파일을 읽고, 그 파일의 첫 번째 줄을 그대로(수정 없이) 보고해라. 다른 작업은 하지 말고, 첫 줄 내용만 반환해라. 파일이 없으면 없다고 보고해라."},"caller":{"type":"direct"}}],"stop_reason":null,"stop_sequence":null,"stop_details":null,"usage":{"input_tokens":2,"cache_creation_input_tokens":0,"cache_read_input_tokens":16723,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":0},"output_tokens":2,"service_tier":"standard","inference_geo":"not_available"},"diagnostics":null,"context_management":null},"parent_tool_use_id":null,"session_id":"e4dba7e4-2718-4a07-a524-907722eff1a8","uuid":"acf3c087-d815-4ae5-8c4b-7317455ab169","timestamp":"2026-09-08T01:36:54.793Z","request_id":"req_011Ceq7fH2zqYuBQyvar1oBZ"}"""

    /** 서브에이전트가 떴다(3.10). `tool_use_id` 가 바깥 도구 카드를 가리킨다. 프롬프트 전문이 실려 온다. */
    const val TASK_STARTED: String =
        """{"type":"system","subtype":"task_started","task_id":"aadb20bff6e5d47e9","tool_use_id":"toolu_0144EDWk6by45Zj2YrwXLNBX","description":"Read README.txt first line","subagent_type":"general-purpose","is_backgrounded":false,"spawn_depth":1,"task_type":"local_agent","prompt":"/tmp/fakerepo/README.txt 파일을 읽고, 그 파일의 첫 번째 줄을 그대로(수정 없이) 보고해라. 다른 작업은 하지 말고, 첫 줄 내용만 반환해라. 파일이 없으면 없다고 보고해라.","uuid":"0abd9b37-9150-47cf-abbb-7aa535a30664","session_id":"e4dba7e4-2718-4a07-a524-907722eff1a8"}"""

    /** 서브에이전트가 받은 프롬프트가 안쪽 사용자 메시지로 되돌아온다. `parent_tool_use_id` 가 채워져 있다. */
    const val SUBAGENT_PROMPT_ECHOED: String =
        """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"/tmp/fakerepo/README.txt 파일을 읽고, 그 파일의 첫 번째 줄을 그대로(수정 없이) 보고해라. 다른 작업은 하지 말고, 첫 줄 내용만 반환해라. 파일이 없으면 없다고 보고해라."}]},"parent_tool_use_id":"toolu_0144EDWk6by45Zj2YrwXLNBX","session_id":"e4dba7e4-2718-4a07-a524-907722eff1a8","uuid":"21df9aad-7357-4cc6-9154-06ddc2f18f9e","timestamp":"2026-09-08T01:36:57.583Z","subagent_type":"general-purpose","task_description":"Read README.txt first line"}"""

    /** 서브에이전트가 한 말. **`--forward-subagent-text` 를 켜야 온다**(6 단계 실측). */
    const val SUBAGENT_TEXT: String =
        """{"type":"assistant","message":{"model":"claude-opus-5","id":"msg_011Ceq7fw2FinLuofpYdJve1","type":"message","role":"assistant","content":[{"type":"text","text":"I'll read the file."}],"stop_reason":null,"stop_sequence":null,"stop_details":null,"usage":{"input_tokens":2,"cache_creation_input_tokens":2767,"cache_read_input_tokens":6925,"cache_creation":{"ephemeral_5m_input_tokens":2767,"ephemeral_1h_input_tokens":0},"output_tokens":1,"service_tier":"standard","inference_geo":"not_available"},"diagnostics":null,"context_management":null},"parent_tool_use_id":"toolu_0144EDWk6by45Zj2YrwXLNBX","session_id":"e4dba7e4-2718-4a07-a524-907722eff1a8","uuid":"5c91b28e-da6a-4990-bdcb-40efcab7003a","timestamp":"2026-09-08T01:36:59.305Z","request_id":"req_011Ceq7fvM5AHrgXXWt552C2","subagent_type":"general-purpose","task_description":"Read README.txt first line"}"""

    /** 서브에이전트의 진행(3.10). `description` 과 `last_tool_name` 이 "지금 무엇을 하는 중인가" 를 준다. */
    const val TASK_PROGRESS: String =
        """{"type":"system","subtype":"task_progress","task_id":"aadb20bff6e5d47e9","tool_use_id":"toolu_0144EDWk6by45Zj2YrwXLNBX","description":"Reading README.txt","subagent_type":"general-purpose","usage":{"total_tokens":9700,"tool_uses":1,"duration_ms":1728},"last_tool_name":"Read","uuid":"0b163015-bcff-450d-a51d-c4fc7a0208cb","session_id":"e4dba7e4-2718-4a07-a524-907722eff1a8"}"""

    /** 서브에이전트 안쪽의 도구 호출. `parent_tool_use_id` 가 채워져 있다(3.10) — 중첩의 열쇠다. */
    const val SUBAGENT_TOOL_USE: String =
        """{"type":"assistant","message":{"model":"claude-opus-5","id":"msg_011Ceq7fw2FinLuofpYdJve1","type":"message","role":"assistant","content":[{"type":"tool_use","id":"toolu_01Qu3cuw7ubHfTYKdCU4a2hs","name":"Read","input":{"file_path":"/tmp/fakerepo/README.txt"},"caller":{"type":"direct"}}],"stop_reason":null,"stop_sequence":null,"stop_details":null,"usage":{"input_tokens":2,"cache_creation_input_tokens":2767,"cache_read_input_tokens":6925,"cache_creation":{"ephemeral_5m_input_tokens":2767,"ephemeral_1h_input_tokens":0},"output_tokens":1,"service_tier":"standard","inference_geo":"not_available"},"diagnostics":null,"context_management":null},"parent_tool_use_id":"toolu_0144EDWk6by45Zj2YrwXLNBX","session_id":"e4dba7e4-2718-4a07-a524-907722eff1a8","uuid":"e97ef80d-1c69-417a-a341-b68d61aae0da","timestamp":"2026-09-08T01:36:59.309Z","request_id":"req_011Ceq7fvM5AHrgXXWt552C2","subagent_type":"general-purpose","task_description":"Read README.txt first line"}"""

    /** 그 안쪽 도구의 결과. **`tool_use_result` 곁가지가 없다** — 안쪽 카드는 모델이 읽은 텍스트로만 그린다. */
    const val SUBAGENT_TOOL_RESULT: String =
        """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_01Qu3cuw7ubHfTYKdCU4a2hs","type":"tool_result","content":"1\thello from fake repo\n2\t"}]},"parent_tool_use_id":"toolu_0144EDWk6by45Zj2YrwXLNBX","session_id":"e4dba7e4-2718-4a07-a524-907722eff1a8","uuid":"a1506ea1-8cca-4e9e-ae13-ba358d4fa370","timestamp":"2026-09-08T01:36:59.332Z","subagent_type":"general-purpose","task_description":"Read README.txt first line"}"""

    /**
     * 서브에이전트의 상태가 바뀌었다.
     *
     * **`tool_use_id` 가 없고 상태가 `patch` 안에 있다** — 설계 문서가 `{"status":"completed"}` 로
     * 적어둔 것과 다른 모양이다(2026-09-08). 이 줄이 말하는 것을 카드는 바깥 도구 결과와
     * `task_notification` 에서 이미 받으므로, 어댑터는 이 줄을 조용히 지나보낸다.
     */
    const val TASK_UPDATED: String =
        """{"type":"system","subtype":"task_updated","task_id":"aadb20bff6e5d47e9","patch":{"status":"completed","end_time":1788831422590},"uuid":"65770718-4fb6-4233-9084-1e91f26c48fd","session_id":"e4dba7e4-2718-4a07-a524-907722eff1a8"}"""

    /** 서브에이전트가 끝났다. `output_file` 이 그 실행의 원출력 자리다. */
    const val TASK_NOTIFICATION: String =
        """{"type":"system","subtype":"task_notification","task_id":"aadb20bff6e5d47e9","tool_use_id":"toolu_0144EDWk6by45Zj2YrwXLNBX","status":"completed","output_file":"/tmp/claude-1000/-tmp-fakerepo/e4dba7e4-2718-4a07-a524-907722eff1a8/tasks/aadb20bff6e5d47e9.output","summary":"파일: /tmp/fakerepo/README.txt\n\n첫 번째 줄:\n\n```\nhello from fake repo\n```\n\n(파일은 존재하며, 총 1줄 + 끝 개행으로 구성되어 있습니다.)","usage":{"total_tokens":10849,"tool_uses":1,"duration_ms":5008},"uuid":"b6cd7ded-47ea-46cf-9208-9711769047d5","session_id":"e4dba7e4-2718-4a07-a524-907722eff1a8"}"""

    /**
     * 바깥 `Agent` 호출의 결과. `content` 가 **텍스트 블록 둘**이고, 곁가지에 `status`·`agentType` 이 있다.
     */
    const val AGENT_RESULT: String =
        """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_0144EDWk6by45Zj2YrwXLNBX","type":"tool_result","content":[{"type":"text","text":"파일: /tmp/fakerepo/README.txt\n\n첫 번째 줄:\n\n```\nhello from fake repo\n```\n\n(파일은 존재하며, 총 1줄 + 끝 개행으로 구성되어 있습니다.)"},{"type":"text","text":"agentId: aadb20bff6e5d47e9 (use SendMessage with to: 'aadb20bff6e5d47e9', summary: '<5-10 word recap>' to continue this agent)\n<usage>subagent_tokens: 10852\ntool_uses: 1\nduration_ms: 5010</usage>"}]}]},"parent_tool_use_id":null,"session_id":"e4dba7e4-2718-4a07-a524-907722eff1a8","uuid":"417d7433-29cd-4592-91e9-866266b09d72","timestamp":"2026-09-08T01:37:05.597Z","tool_use_result":{"status":"completed","prompt":"/tmp/fakerepo/README.txt 파일을 읽고, 그 파일의 첫 번째 줄을 그대로(수정 없이) 보고해라. 다른 작업은 하지 말고, 첫 줄 내용만 반환해라. 파일이 없으면 없다고 보고해라.","agentId":"aadb20bff6e5d47e9","agentType":"general-purpose","harnessNoteCount":0,"harnessTailCount":0,"harnessSectionHash":"491e67377b018209","content":[{"type":"text","text":"파일: /tmp/fakerepo/README.txt\n\n첫 번째 줄:\n\n```\nhello from fake repo\n```\n\n(파일은 존재하며, 총 1줄 + 끝 개행으로 구성되어 있습니다.)"}],"resolvedModel":"claude-opus-5[1m]","totalDurationMs":5010,"totalTokens":10852,"totalToolUseCount":1,"usage":{"input_tokens":2,"cache_creation_input_tokens":988,"cache_read_input_tokens":9692,"output_tokens":170,"output_tokens_details":{"thinking_tokens":81},"server_tool_use":{"web_search_requests":0,"web_fetch_requests":0},"service_tier":"standard","cache_creation":{"ephemeral_1h_input_tokens":0,"ephemeral_5m_input_tokens":988},"inference_geo":"not_available","iterations":[{"input_tokens":2,"output_tokens":170,"cache_read_input_tokens":9692,"cache_creation_input_tokens":988,"cache_creation":{"ephemeral_5m_input_tokens":988,"ephemeral_1h_input_tokens":0},"type":"message"}],"speed":"standard"},"toolStats":{"readCount":1,"searchCount":0,"bashCount":0,"editFileCount":0,"linesAdded":0,"linesRemoved":0,"otherToolCount":0}}}"""

    /**
     * 권한에 막힌 호출이 실린 턴의 끝(3.6).
     *
     * `--permission-mode dontAsk` 로 Write 를 시켜 받았다(2026-09-08). **항목의 모양이 이 줄로
     * 처음 확정됐다** — `tool_name`·`tool_use_id`·`tool_input` 셋이다.
     */
    const val RESULT_WITH_PERMISSION_DENIALS: String =
        """{"duration_api_ms":12073,"stop_reason":"end_turn","session_id":"999d5dd5-324b-4414-b5ab-56a27f779deb","total_cost_usd":0.10143950000000002,"usage":{"input_tokens":4,"cache_creation_input_tokens":7078,"cache_read_input_tokens":26829,"output_tokens":646,"output_tokens_details":{"thinking_tokens":231},"server_tool_use":{"web_search_requests":0,"web_fetch_requests":0},"service_tier":"standard","cache_creation":{"ephemeral_1h_input_tokens":7078,"ephemeral_5m_input_tokens":0},"inference_geo":"not_available","iterations":[{"input_tokens":2,"output_tokens":462,"cache_read_input_tokens":16703,"cache_creation_input_tokens":501,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":501},"type":"message"}],"speed":"standard"},"modelUsage":{"claude-haiku-4-5-20251001":{"inputTokens":955,"outputTokens":24,"cacheReadInputTokens":0,"cacheCreationInputTokens":0,"webSearchRequests":0,"costUSD":0.001075,"contextWindow":200000,"maxOutputTokens":32000,"thinkingTokens":0,"canonicalModel":"claude-haiku-4-5","provider":"firstParty","costBasis":"list"},"claude-opus-5[1m]":{"inputTokens":4,"outputTokens":646,"cacheReadInputTokens":26829,"cacheCreationInputTokens":7078,"webSearchRequests":0,"costUSD":0.10036450000000001,"contextWindow":1000000,"maxOutputTokens":64000,"thinkingTokens":231,"canonicalModel":"claude-opus-5","provider":"firstParty","costBasis":"list"}},"permission_denials":[{"tool_name":"Write","tool_use_id":"toolu_01J3E2oezHZVWiyWBHWPGdxZ","tool_input":{"file_path":"/tmp/fakerepo/denied.txt","content":"DENIED-PROBE\n"}}],"terminal_reason":"completed","fast_mode_state":"off","fast_mode_disabled_reason":"sdk_opt_in_required","subagent_stats":{"spawned":0,"requested":{"background":0,"foreground":0,"unset":0},"started_in_background":0,"max_depth":0,"spawned_by_subagents":0,"completed":0,"failed":0,"killed":{"parent":0,"user":0,"system":0},"refused":{"depth_limit":0,"concurrency_limit":0,"budget":0},"by_type":{}},"is_error":false,"num_turns":3,"subtype":"success","api_error_status":null,"result":"`date` ran fine:\n\n```\n2026. 09. 08. (화) 10:37:30 KST\n```\n\nBut the file was **not** created. The `Write` tool call was denied — the session is in \"don't ask\" mode, so write permission is blocked and can't be prompted for here.\n\nI'm not going to route around it with a shell redirect (`echo ... > denied.txt`), since the denial is on file-writing itself, not on that particular tool's syntax.\n\nTo get `denied.txt` created, you'd need to either allow `Write` (e.g. via `/permissions` in an interactive session, or a permission entry in `.claude/settings.json`), or tell me explicitly that using Bash to write the file is acceptable to you.","ttft_ms":2997,"type":"result","duration_ms":12102,"uuid":"595bf1b2-fd6c-405a-8e86-de26a87c789a","ttft_stream_ms":1910,"time_to_request_ms":85,"queued_turn_count":0}"""

    /** 한도 사용률을 창 두 개로 준다(3.12). 턴마다 온다. */
    const val RATE_LIMIT: String =
        """{"type":"rate_limit_event","rate_limit_info":{"status":"allowed","resetsAt":1788513600,"rateLimitType":"five_hour","overageStatus":"rejected","overageDisabledReason":"org_level_disabled","isUsingOverage":false,"unifiedWindows":{"five_hour":{"utilization":0.75,"resetsAt":1788513600},"seven_day":{"utilization":0.07,"resetsAt":1789074000}}},"uuid":"7fa135b2-dd4b-447b-bea4-783240d66756","session_id":"8129eebb-0f2f-4067-af9c-d189154b6617"}"""

    /** 턴의 끝(3.1 · 3.12). 비용·토큰·컨텍스트 창·소요 시간·권한 거절 목록이 한 벌에 있다. */
    const val RESULT_SUCCESS: String =
        """{"duration_api_ms":7303,"stop_reason":"end_turn","session_id":"8129eebb-0f2f-4067-af9c-d189154b6617","total_cost_usd":0.0888635,"usage":{"input_tokens":4,"cache_creation_input_tokens":6792,"cache_read_input_tokens":26703,"output_tokens":263,"output_tokens_details":{"thinking_tokens":0},"server_tool_use":{"web_search_requests":0,"web_fetch_requests":0},"service_tier":"standard","cache_creation":{"ephemeral_1h_input_tokens":6792,"ephemeral_5m_input_tokens":0},"inference_geo":"not_available","iterations":[{"input_tokens":2,"output_tokens":145,"cache_read_input_tokens":16577,"cache_creation_input_tokens":341,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":341},"type":"message"}],"speed":"standard"},"modelUsage":{"claude-haiku-4-5-20251001":{"inputTokens":922,"outputTokens":15,"cacheReadInputTokens":0,"cacheCreationInputTokens":0,"webSearchRequests":0,"costUSD":0.000997,"contextWindow":200000,"maxOutputTokens":32000,"thinkingTokens":0,"canonicalModel":"claude-haiku-4-5","provider":"firstParty","costBasis":"list"},"claude-opus-5[1m]":{"inputTokens":4,"outputTokens":263,"cacheReadInputTokens":26703,"cacheCreationInputTokens":6792,"webSearchRequests":0,"costUSD":0.0878665,"contextWindow":1000000,"maxOutputTokens":64000,"thinkingTokens":0,"canonicalModel":"claude-opus-5","provider":"firstParty","costBasis":"list"}},"permission_denials":[],"terminal_reason":"completed","fast_mode_state":"off","fast_mode_disabled_reason":"sdk_opt_in_required","subagent_stats":{"spawned":0,"requested":{"background":0,"foreground":0,"unset":0},"started_in_background":0,"max_depth":0,"spawned_by_subagents":0,"completed":0,"failed":0,"killed":{"parent":0,"user":0,"system":0},"refused":{"depth_limit":0,"concurrency_limit":0,"budget":0},"by_type":{}},"is_error":false,"num_turns":2,"subtype":"success","api_error_status":null,"result":"첫 줄: `hello from fake repo`\n\n참고로 이 세션에서 claude.ai Gmail / Google Calendar / Google Drive MCP 서버는 인증이 필요한 상태이며, 비대화형 세션이라 여기서 OAuth를 진행할 수 없습니다. 해당 커넥터를 쓰시려면 claude.ai 커넥터 설정에서 인증해 주세요.","ttft_ms":2895,"type":"result","duration_ms":6459,"uuid":"f1c943db-2bfa-440e-a565-cfb51901c157","ttft_stream_ms":1111,"time_to_request_ms":59,"queued_turn_count":0}"""

    /** Bash 호출. `command` 와 `description` 두 인자가 온다. */
    const val BASH_TOOL_USE: String =
        """{"type":"assistant","message":{"model":"claude-opus-5","id":"msg_011CeiEMEnqtA4GZ8ij9Lhx8","type":"message","role":"assistant","content":[{"type":"tool_use","id":"toolu_018mbb1frTJYxPMVbRmmjibx","name":"Bash","input":{"command":"echo probe-bash","description":"Echo probe-bash"},"caller":{"type":"direct"}}],"stop_reason":null,"stop_sequence":null,"stop_details":null,"usage":{"input_tokens":2,"cache_creation_input_tokens":171,"cache_read_input_tokens":16998,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":171},"output_tokens":17,"service_tier":"standard","inference_geo":"not_available"},"diagnostics":null,"context_management":null},"parent_tool_use_id":null,"session_id":"24bb485d-b648-40e7-ac1b-c4177e9f8e30","uuid":"a2f3774e-a8ae-4ba8-b9d5-2d9865b6821e","timestamp":"2026-09-04T10:20:23.990Z","request_id":"req_011CeiEME2TQiXCPbCP7KR2h"}"""

    /** Bash 결과. 곁가지에 `stdout`·`stderr` 가 있고 **종료 코드는 없다** — 카드가 성공·실패로만 그리는 근거다. */
    const val BASH_RESULT: String =
        """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_018mbb1frTJYxPMVbRmmjibx","type":"tool_result","content":"probe-bash","is_error":false}]},"parent_tool_use_id":null,"session_id":"24bb485d-b648-40e7-ac1b-c4177e9f8e30","uuid":"82120604-6e07-46e6-b517-76bd5c526590","timestamp":"2026-09-04T10:20:24.964Z","tool_use_result":{"stdout":"probe-bash","stderr":"","interrupted":false,"isImage":false,"noOutputExpected":false}}"""

    /** 관문에 걸린 Bash 호출. 이 스크래치는 신뢰된 워크스페이스가 아니라 허용 규칙이 무시됐다. */
    const val BASH_TOOL_USE_REFUSED: String =
        """{"type":"assistant","message":{"model":"claude-opus-5","id":"msg_011CeiEMSczSUuLB9FXDXpkQ","type":"message","role":"assistant","content":[{"type":"tool_use","id":"toolu_01CkVYHpUpAxcjbgrPSzkmFR","name":"Bash","input":{"command":"sh -c \"exit 3\"","description":"Run a command exiting with code 3"},"caller":{"type":"direct"}}],"stop_reason":null,"stop_sequence":null,"stop_details":null,"usage":{"input_tokens":2,"cache_creation_input_tokens":115,"cache_read_input_tokens":17169,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":115},"output_tokens":16,"service_tier":"standard","inference_geo":"not_available"},"diagnostics":null,"context_management":null},"parent_tool_use_id":null,"session_id":"24bb485d-b648-40e7-ac1b-c4177e9f8e30","uuid":"6c3fa003-c5d5-4f1c-b100-d80a6d0d8850","timestamp":"2026-09-04T10:20:26.533Z","request_id":"req_011CeiEMRuah7NMF4KyUEpyB"}"""

    /** 거절된 도구 결과. **곁가지가 객체가 아니라 문자열이다** — `"Error: …"`. 카드는 그 모양에서 읽을 것이 없으므로 인자와 이 문구로만 그린다. */
    const val BASH_RESULT_REFUSED: String =
        """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"This command requires approval","is_error":true,"tool_use_id":"toolu_01CkVYHpUpAxcjbgrPSzkmFR"}]},"parent_tool_use_id":null,"session_id":"24bb485d-b648-40e7-ac1b-c4177e9f8e30","uuid":"2a48d4d3-9abf-41a9-8367-cd8501fb4768","timestamp":"2026-09-04T10:20:26.544Z","tool_use_result":"Error: This command requires approval","tool_result_meta":[{"id":"toolu_01CkVYHpUpAxcjbgrPSzkmFR","non_execution_kind":"user-rejected"}]}"""

    /** Edit 호출. `old_string`·`new_string` 이 인자로 온다 — diff 를 우리가 계산할 재료이기도 하지만, 아래 결과에 이미 계산된 패치가 있다. */
    const val EDIT_TOOL_USE: String =
        """{"type":"assistant","message":{"model":"claude-opus-5","id":"msg_011CeiEMh694uuyhs7LZMUv2","type":"message","role":"assistant","content":[{"type":"tool_use","id":"toolu_01LqFPtroHzET7qqM4fmieMs","name":"Edit","input":{"replace_all":false,"file_path":"/tmp/fakerepo/notes.md","old_string":"둘째 줄","new_string":"둘째 줄 고침"},"caller":{"type":"direct"}}],"stop_reason":null,"stop_sequence":null,"stop_details":null,"usage":{"input_tokens":2,"cache_creation_input_tokens":179,"cache_read_input_tokens":17412,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":179},"output_tokens":16,"service_tier":"standard","inference_geo":"not_available"},"diagnostics":null,"context_management":null},"parent_tool_use_id":null,"session_id":"24bb485d-b648-40e7-ac1b-c4177e9f8e30","uuid":"40b17f0b-7565-40ad-9457-a7aa158800c6","timestamp":"2026-09-04T10:20:30.673Z","request_id":"req_011CeiEMgXQZQ2KPkFzCJxBi"}"""

    /** Edit 결과. **`structuredPatch` 가 여기 있다** — `claude` 가 이미 계산한 패치라 앱이 diff 를 다시 짜지 않는다. */
    const val EDIT_RESULT: String =
        """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_01LqFPtroHzET7qqM4fmieMs","type":"tool_result","content":"The file /tmp/fakerepo/notes.md has been updated successfully. (file state is current in your context — no need to Read it back)"}]},"parent_tool_use_id":null,"session_id":"24bb485d-b648-40e7-ac1b-c4177e9f8e30","uuid":"d48bdf14-dee7-4b3d-bb23-3efe7036f8f6","timestamp":"2026-09-04T10:20:30.703Z","tool_use_result":{"filePath":"/tmp/fakerepo/notes.md","oldString":"둘째 줄","newString":"둘째 줄 고침","originalFile":"첫째 줄\n둘째 줄\n셋째 줄\n","structuredPatch":[{"oldStart":1,"oldLines":3,"newStart":1,"newLines":3,"lines":[" 첫째 줄","-둘째 줄","+둘째 줄 고침"," 셋째 줄"]}],"userModified":false,"replaceAll":false}}"""

    /** 없던 파일에 쓰는 Write 호출. */
    const val WRITE_TOOL_USE_CREATING: String =
        """{"type":"assistant","message":{"model":"claude-opus-5","id":"msg_011CeiEMrvygMvj45FLNBSLu","type":"message","role":"assistant","content":[{"type":"tool_use","id":"toolu_01HDtdQHJtwmjm1GKVHDj5WP","name":"Write","input":{"file_path":"/tmp/fakerepo/written.txt","content":"written by probe\n"},"caller":{"type":"direct"}}],"stop_reason":null,"stop_sequence":null,"stop_details":null,"usage":{"input_tokens":2,"cache_creation_input_tokens":335,"cache_read_input_tokens":17591,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":335},"output_tokens":17,"service_tier":"standard","inference_geo":"not_available"},"diagnostics":null,"context_management":null},"parent_tool_use_id":null,"session_id":"24bb485d-b648-40e7-ac1b-c4177e9f8e30","uuid":"6d833e79-d546-4f67-bd6d-459f601118b0","timestamp":"2026-09-04T10:20:32.809Z","request_id":"req_011CeiEMrM18MxanQyyN9rgq"}"""

    /** 새로 만든 파일의 Write 결과. `type: "create"` 이고 **`structuredPatch` 가 비어 있다** — 더해진 줄은 `content` 전체다. */
    const val WRITE_RESULT_CREATED: String =
        """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_01HDtdQHJtwmjm1GKVHDj5WP","type":"tool_result","content":"File created successfully at: /tmp/fakerepo/written.txt (file state is current in your context — no need to Read it back)"}]},"parent_tool_use_id":null,"session_id":"24bb485d-b648-40e7-ac1b-c4177e9f8e30","uuid":"d9ccc7fb-7d63-4f2e-a85d-3f0b6fa63b6b","timestamp":"2026-09-04T10:20:32.829Z","tool_use_result":{"type":"create","filePath":"/tmp/fakerepo/written.txt","content":"written by probe\n","structuredPatch":[],"originalFile":null,"userModified":false}}"""

    /** 이미 있는 파일을 덮어쓰는 Write 호출. */
    const val WRITE_TOOL_USE_OVERWRITING: String =
        """{"type":"assistant","message":{"model":"claude-opus-5","id":"msg_011CeiEd9czUtHDy4octyi2L","type":"message","role":"assistant","content":[{"type":"tool_use","id":"toolu_01EJ6JyD7tCwfFcLfVpY94Yt","name":"Write","input":{"file_path":"/tmp/fakerepo/overwrite.txt","content":"첫째 줄\n바뀐 둘째 줄\n셋째 줄\n넷째 줄\n"},"caller":{"type":"direct"}}],"stop_reason":null,"stop_sequence":null,"stop_details":null,"usage":{"input_tokens":2,"cache_creation_input_tokens":182,"cache_read_input_tokens":16822,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":182},"output_tokens":2,"service_tier":"standard","inference_geo":"not_available"},"diagnostics":null,"context_management":null},"parent_tool_use_id":null,"session_id":"dfe67b0d-66b2-44a9-83c6-647b70bc87a1","uuid":"6e6935a5-7ce1-4704-88be-384addedc0c2","timestamp":"2026-09-04T10:24:00.679Z","request_id":"req_011CeiEd8vZyjH1NKVrwzvef"}"""

    /** 덮어쓴 Write 결과. `type: "update"` 이고 패치가 실려 온다 — Edit 과 같은 모양이다. */
    const val WRITE_RESULT_UPDATED: String =
        """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_01EJ6JyD7tCwfFcLfVpY94Yt","type":"tool_result","content":"The file /tmp/fakerepo/overwrite.txt has been updated successfully. (file state is current in your context — no need to Read it back)"}]},"parent_tool_use_id":null,"session_id":"dfe67b0d-66b2-44a9-83c6-647b70bc87a1","uuid":"48ab449c-272a-497a-bafb-26c141198591","timestamp":"2026-09-04T10:24:00.704Z","tool_use_result":{"type":"update","filePath":"/tmp/fakerepo/overwrite.txt","content":"첫째 줄\n바뀐 둘째 줄\n셋째 줄\n넷째 줄\n","structuredPatch":[{"oldStart":1,"oldLines":3,"newStart":1,"newLines":4,"lines":[" 첫째 줄","-둘째 줄","+바뀐 둘째 줄"," 셋째 줄","+넷째 줄"]}],"originalFile":"첫째 줄\n둘째 줄\n셋째 줄\n","userModified":false}}"""

    /** 이미 있는 파일에 **같은 내용**을 다시 쓰는 Write 호출. */
    const val WRITE_TOOL_USE_SAME: String =
        """{"type":"assistant","message":{"model":"claude-opus-5","id":"msg_011CeiJZ7PQQJD8ZYymFD3Ff","type":"message","role":"assistant","content":[{"type":"tool_use","id":"toolu_01NyAVJ6YGFCwD6pqAzezsj4","name":"Write","input":{"file_path":"/tmp/fakerepo/same.txt","content":"첫째 줄\n둘째 줄\n"},"caller":{"type":"direct"}}],"stop_reason":null,"stop_sequence":null,"stop_details":null,"usage":{"input_tokens":2,"cache_creation_input_tokens":613,"cache_read_input_tokens":16835,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":613},"output_tokens":16,"service_tier":"standard","inference_geo":"not_available"},"diagnostics":null,"context_management":null},"parent_tool_use_id":null,"session_id":"3d78f6d7-4510-46b1-a25f-4f6f31240cfc","uuid":"62cb4365-1200-4552-b34b-87f0e0189c29","timestamp":"2026-09-04T11:15:32.935Z","request_id":"req_011CeiJZ6WZiZftxmSiXtiEd"}"""

    /** 그 결과. `type: "update"` 인데 **패치가 비어 있다** — 빈 패치를 "새 파일" 로 읽으면 안 되는 근거다. */
    const val WRITE_RESULT_SAME: String =
        """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_01NyAVJ6YGFCwD6pqAzezsj4","type":"tool_result","content":"The file /tmp/fakerepo/same.txt has been updated successfully. (file state is current in your context — no need to Read it back)"}]},"parent_tool_use_id":null,"session_id":"3d78f6d7-4510-46b1-a25f-4f6f31240cfc","uuid":"b1bb6cd8-6808-4fd9-b677-525802f69c86","timestamp":"2026-09-04T11:15:32.958Z","tool_use_result":{"type":"update","filePath":"/tmp/fakerepo/same.txt","content":"첫째 줄\n둘째 줄\n","structuredPatch":[],"originalFile":"첫째 줄\n둘째 줄\n","userModified":false}}"""

    /** 빈 내용으로 새 파일을 만드는 Write 호출. */
    const val WRITE_TOOL_USE_EMPTY: String =
        """{"type":"assistant","message":{"model":"claude-opus-5","id":"msg_011CeiJZ7PQQJD8ZYymFD3Ff","type":"message","role":"assistant","content":[{"type":"tool_use","id":"toolu_015yxv8kMoX3v2sbpcev5wqK","name":"Write","input":{"file_path":"/tmp/fakerepo/empty.txt","content":""},"caller":{"type":"direct"}}],"stop_reason":null,"stop_sequence":null,"stop_details":null,"usage":{"input_tokens":2,"cache_creation_input_tokens":613,"cache_read_input_tokens":16835,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":613},"output_tokens":16,"service_tier":"standard","inference_geo":"not_available"},"diagnostics":null,"context_management":null},"parent_tool_use_id":null,"session_id":"3d78f6d7-4510-46b1-a25f-4f6f31240cfc","uuid":"08be01e4-6e0f-4667-8ba6-172a5c82f232","timestamp":"2026-09-04T11:15:33.512Z","request_id":"req_011CeiJZ6WZiZftxmSiXtiEd"}"""

    /** 그 결과. `type: "create"` 이고 `content` 가 빈 문자열이다 — 더해진 줄이 0 이라야 한다. */
    const val WRITE_RESULT_EMPTY: String =
        """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_015yxv8kMoX3v2sbpcev5wqK","type":"tool_result","content":"File created successfully at: /tmp/fakerepo/empty.txt (file state is current in your context — no need to Read it back)"}]},"parent_tool_use_id":null,"session_id":"3d78f6d7-4510-46b1-a25f-4f6f31240cfc","uuid":"b3518f04-477a-4488-bbd9-be698cbeb3b7","timestamp":"2026-09-04T11:15:33.543Z","tool_use_result":{"type":"create","filePath":"/tmp/fakerepo/empty.txt","content":"","structuredPatch":[],"originalFile":null,"userModified":false}}"""

    /** 중단 안내(3.8). **사용자 메시지와 같은 모양으로 온다** — 다른 것은 `isReplay` 가 없다는 것뿐이다. */
    const val INTERRUPTION_ECHOED: String =
        """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]},"parent_tool_use_id":null,"session_id":"f79590cd-0133-40c4-ac5b-784ed69690d1","uuid":"b5c0c63f-0e28-4112-8c1b-c55684e91d9c","timestamp":"2026-09-04T10:23:46.973Z"}"""

    /** 중단 요청에 대한 답(3.8). `still_queued` 는 큐에 말이 들어 있을 때도 비어 있었다 — 큐 깊이를 이 필드로 아는 길은 없다. */
    const val CONTROL_RESPONSE: String =
        """{"type":"control_response","response":{"subtype":"success","request_id":"int_probe01","response":{"still_queued":[]}}}"""

    /** 끊긴 턴의 끝. `terminal_reason: "aborted_streaming"` 이고 `result` 키가 아예 없다. **`duration_ms` 는 실려 온다** — 설계 부록 A.9 가 잰 판에는 없던 것이다. */
    const val RESULT_ABORTED: String =
        """{"duration_api_ms":1013,"stop_reason":null,"session_id":"f79590cd-0133-40c4-ac5b-784ed69690d1","total_cost_usd":0.001037,"usage":{"output_tokens_details":{"thinking_tokens":0},"input_tokens":0,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":0,"server_tool_use":{"web_search_requests":0,"web_fetch_requests":0},"service_tier":"standard","cache_creation":{"ephemeral_1h_input_tokens":0,"ephemeral_5m_input_tokens":0},"inference_geo":"","iterations":[],"speed":"standard"},"modelUsage":{"claude-haiku-4-5-20251001":{"inputTokens":927,"outputTokens":22,"cacheReadInputTokens":0,"cacheCreationInputTokens":0,"webSearchRequests":0,"costUSD":0.001037,"contextWindow":200000,"maxOutputTokens":32000,"thinkingTokens":0,"canonicalModel":"claude-haiku-4-5","provider":"firstParty","costBasis":"list"}},"permission_denials":[],"terminal_reason":"aborted_streaming","fast_mode_state":"off","fast_mode_disabled_reason":"sdk_opt_in_required","subagent_stats":{"spawned":0,"requested":{"background":0,"foreground":0,"unset":0},"started_in_background":0,"max_depth":0,"spawned_by_subagents":0,"completed":0,"failed":0,"killed":{"parent":0,"user":0,"system":0},"refused":{"depth_limit":0,"concurrency_limit":0,"budget":0},"by_type":{}},"is_error":true,"num_turns":2,"subtype":"error_during_execution","errors":["[ede_diagnostic] result_type=user last_content_type=n/a stop_reason=null"],"type":"result","duration_ms":4732,"uuid":"cf2397c3-6323-4c97-9ab3-33132c6bcf51","queued_turn_count":0}"""
}
