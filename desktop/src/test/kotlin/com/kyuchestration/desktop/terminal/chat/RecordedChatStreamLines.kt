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

    /** Bash 호출. `command` 와 `description` 두 인자가 온다. */
    const val BASH_TOOL_USE: String =
        """{"type":"assistant","message":{"model":"claude-opus-5","id":"msg_011CeiEMEnqtA4GZ8ij9Lhx8","type":"message","role":"assistant","content":[{"type":"tool_use","id":"toolu_018mbb1frTJYxPMVbRmmjibx","name":"Bash","input":{"command":"echo probe-bash","description":"Echo probe-bash"},"caller":{"type":"direct"}}],"stop_reason":null,"stop_sequence":null,"stop_details":null,"usage":{"input_tokens":2,"cache_creation_input_tokens":171,"cache_read_input_tokens":16998,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":171},"output_tokens":17,"service_tier":"standard","inference_geo":"not_available"},"diagnostics":null,"context_management":null},"parent_tool_use_id":null,"session_id":"24bb485d-b648-40e7-ac1b-c4177e9f8e30","uuid":"a2f3774e-a8ae-4ba8-b9d5-2d9865b6821e","timestamp":"2026-09-04T10:20:23.990Z","request_id":"req_011CeiEME2TQiXCPbCP7KR2h"}"""

    /** Bash 결과. 곁가지에 `stdout`·`stderr` 가 있고 **종료 코드는 없다** — 카드가 성공·실패로만 그리는 근거다. */
    const val BASH_RESULT: String =
        """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_018mbb1frTJYxPMVbRmmjibx","type":"tool_result","content":"probe-bash","is_error":false}]},"parent_tool_use_id":null,"session_id":"24bb485d-b648-40e7-ac1b-c4177e9f8e30","uuid":"82120604-6e07-46e6-b517-76bd5c526590","timestamp":"2026-09-04T10:20:24.964Z","tool_use_result":{"stdout":"probe-bash","stderr":"","interrupted":false,"isImage":false,"noOutputExpected":false}}"""

    /** 관문에 걸린 Bash 호출. 이 스크래치는 신뢰된 워크스페이스가 아니라 허용 규칙이 무시됐다. */
    const val BASH_TOOL_USE_REFUSED: String =
        """{"type":"assistant","message":{"model":"claude-opus-5","id":"msg_011CeiEMSczSUuLB9FXDXpkQ","type":"message","role":"assistant","content":[{"type":"tool_use","id":"toolu_01CkVYHpUpAxcjbgrPSzkmFR","name":"Bash","input":{"command":"sh -c \"exit 3\"","description":"Run a command exiting with code 3"},"caller":{"type":"direct"}}],"stop_reason":null,"stop_sequence":null,"stop_details":null,"usage":{"input_tokens":2,"cache_creation_input_tokens":115,"cache_read_input_tokens":17169,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":115},"output_tokens":16,"service_tier":"standard","inference_geo":"not_available"},"diagnostics":null,"context_management":null},"parent_tool_use_id":null,"session_id":"24bb485d-b648-40e7-ac1b-c4177e9f8e30","uuid":"6c3fa003-c5d5-4f1c-b100-d80a6d0d8850","timestamp":"2026-09-04T10:20:26.533Z","request_id":"req_011CeiEMRuah7NMF4KyUEpyB"}"""

    /** 거절된 도구 결과. **곁가지가 객체가 아니라 문자열이다** — `"Error: …"`. 카드가 이 모양을 만나면 일반 카드로 떨어진다. */
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
