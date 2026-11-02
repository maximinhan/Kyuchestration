package com.kyuchestration.desktop

import com.mikepenz.markdown.model.State
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * 전사가 들고 있는 마크다운 파싱 결과(ChatMarkdown 의 AssistantMarkdown).
 *
 * 목록은 화면 밖으로 나간 항목을 버렸다가 돌아오면 다시 그린다. 그때마다 화면 스레드에서 다시 파싱하면 지난 답 수백 개를
 * 오르내리는 스크롤이 끊긴다 — 같은 글은 한 번만 파싱한다.
 */
class ParsedMarkdownCacheTest {

    @Test
    fun `같은 글은 한 번 파싱한 결과를 다시 쓴다`() {
        val cache = ParsedMarkdownCache()

        val first = cache.parsedOf("**보라색코끼리** 를 기억하겠습니다.")
        val again = cache.parsedOf("**보라색코끼리** 를 기억하겠습니다.")

        assertIs<State.Success>(first)
        assertSame(first, again)
    }

    @Test
    fun `다른 글은 따로 파싱한다`() {
        val cache = ParsedMarkdownCache()

        assertNotSame(cache.parsedOf("앞선 답 1"), cache.parsedOf("앞선 답 2"))
    }
}
