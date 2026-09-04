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
 * 아래 [FromDesignAppendix] 는 출처가 다르다. 이번 녹화로는 만들지 못한 상황(중단·거절)이라
 * 설계 문서 부록 A 에 적힌 것을 옮겼다.
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

    /** 서브에이전트가 떴다(3.10). 프롬프트 전문이 실려 오는데 여기서는 첫 줄만 남겼다. */
    const val TASK_STARTED: String =
        """{"type":"system","subtype":"task_started","task_id":"af9ca720398708b1c","tool_use_id":"toolu_01QUbBJ2Rh6wpXTtCkjoC95M","description":"Read README.txt first line","subagent_type":"general-purpose","is_backgrounded":false,"spawn_depth":1,"task_type":"local_agent","prompt":"작업 디렉터리(/tmp/fakerepo)의 README.txt 파일을 읽고, 그 파일의 첫 번째 줄 내용을 그대로 보고해줘.","uuid":"90c370d8-cafa-4641-ba2e-9b9fa969f970","session_id":"bdda591c-d334-4a29-9791-43506315ae17"}"""

    /** 서브에이전트의 진행(3.10). `task_id` 와 `tool_use_id` 가 함께 온다 — 이 두 값이 진행을 바깥 도구 카드에 잇는다. */
    const val TASK_PROGRESS: String =
        """{"type":"system","subtype":"task_progress","task_id":"af9ca720398708b1c","tool_use_id":"toolu_01QUbBJ2Rh6wpXTtCkjoC95M","description":"Reading README.txt","subagent_type":"general-purpose","usage":{"total_tokens":10027,"tool_uses":1,"duration_ms":3173},"last_tool_name":"Read","uuid":"3237dd79-6b17-4adb-9d00-bf26433c40d9","session_id":"bdda591c-d334-4a29-9791-43506315ae17"}"""

    /** 서브에이전트 안쪽의 도구 호출. `parent_tool_use_id` 가 채워져 있다(3.10) — 중첩의 열쇠다. */
    const val SUBAGENT_TOOL_USE: String =
        """{"type":"assistant","message":{"model":"claude-opus-5","id":"msg_011Cehx1GVs8feLs2y3hQZbW","type":"message","role":"assistant","content":[{"type":"tool_use","id":"toolu_015cgoqteHactDNH8Er6kf4J","name":"Read","input":{"file_path":"/tmp/fakerepo/README.txt"},"caller":{"type":"direct"}}],"stop_reason":null,"stop_sequence":null,"stop_details":null,"usage":{"input_tokens":2,"cache_creation_input_tokens":9988,"cache_read_input_tokens":0,"cache_creation":{"ephemeral_5m_input_tokens":9988,"ephemeral_1h_input_tokens":0},"output_tokens":1,"service_tier":"standard","inference_geo":"not_available"},"diagnostics":null,"context_management":null},"parent_tool_use_id":"toolu_01QUbBJ2Rh6wpXTtCkjoC95M","session_id":"bdda591c-d334-4a29-9791-43506315ae17","uuid":"e4ef25da-290d-4bbf-85a5-a9aa7121022d","timestamp":"2026-09-04T06:46:05.324Z","request_id":"req_011Cehx1Fnx5PfEdU1WpqeSw","subagent_type":"general-purpose","task_description":"Read README.txt first line"}"""

    /** 한도 사용률을 창 두 개로 준다(3.12). 턴마다 온다. */
    const val RATE_LIMIT: String =
        """{"type":"rate_limit_event","rate_limit_info":{"status":"allowed","resetsAt":1788513600,"rateLimitType":"five_hour","overageStatus":"rejected","overageDisabledReason":"org_level_disabled","isUsingOverage":false,"unifiedWindows":{"five_hour":{"utilization":0.75,"resetsAt":1788513600},"seven_day":{"utilization":0.07,"resetsAt":1789074000}}},"uuid":"7fa135b2-dd4b-447b-bea4-783240d66756","session_id":"8129eebb-0f2f-4067-af9c-d189154b6617"}"""

    /** 턴의 끝(3.1 · 3.12). 비용·토큰·컨텍스트 창·소요 시간·권한 거절 목록이 한 벌에 있다. */
    const val RESULT_SUCCESS: String =
        """{"duration_api_ms":7303,"stop_reason":"end_turn","session_id":"8129eebb-0f2f-4067-af9c-d189154b6617","total_cost_usd":0.0888635,"usage":{"input_tokens":4,"cache_creation_input_tokens":6792,"cache_read_input_tokens":26703,"output_tokens":263,"output_tokens_details":{"thinking_tokens":0},"server_tool_use":{"web_search_requests":0,"web_fetch_requests":0},"service_tier":"standard","cache_creation":{"ephemeral_1h_input_tokens":6792,"ephemeral_5m_input_tokens":0},"inference_geo":"not_available","iterations":[{"input_tokens":2,"output_tokens":145,"cache_read_input_tokens":16577,"cache_creation_input_tokens":341,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":341},"type":"message"}],"speed":"standard"},"modelUsage":{"claude-haiku-4-5-20251001":{"inputTokens":922,"outputTokens":15,"cacheReadInputTokens":0,"cacheCreationInputTokens":0,"webSearchRequests":0,"costUSD":0.000997,"contextWindow":200000,"maxOutputTokens":32000,"thinkingTokens":0,"canonicalModel":"claude-haiku-4-5","provider":"firstParty","costBasis":"list"},"claude-opus-5[1m]":{"inputTokens":4,"outputTokens":263,"cacheReadInputTokens":26703,"cacheCreationInputTokens":6792,"webSearchRequests":0,"costUSD":0.0878665,"contextWindow":1000000,"maxOutputTokens":64000,"thinkingTokens":0,"canonicalModel":"claude-opus-5","provider":"firstParty","costBasis":"list"}},"permission_denials":[],"terminal_reason":"completed","fast_mode_state":"off","fast_mode_disabled_reason":"sdk_opt_in_required","subagent_stats":{"spawned":0,"requested":{"background":0,"foreground":0,"unset":0},"started_in_background":0,"max_depth":0,"spawned_by_subagents":0,"completed":0,"failed":0,"killed":{"parent":0,"user":0,"system":0},"refused":{"depth_limit":0,"concurrency_limit":0,"budget":0},"by_type":{}},"is_error":false,"num_turns":2,"subtype":"success","api_error_status":null,"result":"첫 줄: `hello from fake repo`\n\n참고로 이 세션에서 claude.ai Gmail / Google Calendar / Google Drive MCP 서버는 인증이 필요한 상태이며, 비대화형 세션이라 여기서 OAuth를 진행할 수 없습니다. 해당 커넥터를 쓰시려면 claude.ai 커넥터 설정에서 인증해 주세요.","ttft_ms":2895,"type":"result","duration_ms":6459,"uuid":"f1c943db-2bfa-440e-a565-cfb51901c157","ttft_stream_ms":1111,"time_to_request_ms":59,"queued_turn_count":0}"""
}

/**
 * 설계 문서 부록 A 가 적어둔 줄.
 *
 * 이번 녹화로는 만들지 못한 상황이다 — 중단은 긴 답 도중에 제어 요청을 보내야 하고, 거절은
 * 승인 브리지(5 단계)가 있어야 한다. 부록 A 가 그때 온 것을 적어 두었으므로 그것을 옮긴다.
 *
 * **출처를 갈라 둔다.** 위의 것들은 이 PR 이 직접 받은 줄이고 여기 것들은 앞선 실측이 적어둔
 * 줄이다. 둘을 섞어 두면 나중에 어느 줄이 얼마나 믿을 만한지 가려지지 않는다.
 */
internal object FromDesignAppendix {

    /** 중단된 턴의 끝(A.9). 프로세스도 대화도 살아 있고, 이 턴만 끊긴 것이다. */
    const val RESULT_ABORTED: String =
        """{"type":"result","subtype":"error_during_execution","result":null,"terminal_reason":"aborted_streaming","stop_reason":null,"session_id":"84a72c08-1f4e-4a5e-9f9c-2b0d7c1e5a33","total_cost_usd":0.0125,"usage":{"input_tokens":3,"cache_creation_input_tokens":120,"cache_read_input_tokens":9000,"output_tokens":41},"num_turns":1,"permission_denials":[],"api_error_status":null}"""

    /** 중단 요청에 대한 답(A.9). 큐에 남은 것이 없었다. */
    const val CONTROL_RESPONSE: String =
        """{"type":"control_response","response":{"subtype":"success","request_id":"int_b8acf0f7","response":{"still_queued":[]}}}"""

    /**
     * 거절된 도구 호출의 결과(A.5).
     *
     * 부록이 적어둔 것은 `tool_result` **블록** 하나라, 그 블록이 실려 오는 `user` 이벤트에
     * 넣어 두었다 — 실측이 잰 도구 결과가 그 모양으로 왔다(A.3).
     */
    const val TOOL_RESULT_DENIED: String =
        """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","content":"사람이 거절했습니다 (프로브)","is_error":true,"tool_use_id":"toolu_01EK4pNYexJLbkuiiosevhXq"}]},"parent_tool_use_id":null,"session_id":"84a72c08-1f4e-4a5e-9f9c-2b0d7c1e5a33"}"""
}
