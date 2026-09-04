package com.kyuchestration.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 코드 펜스의 언어 이름을 하이라이터가 아는 이름으로 옮긴다(설계 3.15 의 함정 둘째).
 *
 * 하이라이터(`dev.snipme:highlights`)는 자기 열거형 이름과 **완전히 같은** 문자열만 알아본다
 * (`SyntaxLanguage.getByName`). 그래서 `bash` · `js` · `py` 처럼 사람이 실제로 적는 이름이 전부
 * 매칭에 실패한다 — 이 매핑이 없으면 코드 블록 대부분에 색이 붙지 않는다.
 */
class CodeFenceLanguageNameTest {

    @Test
    fun `흔한 별칭을 하이라이터의 이름으로 옮긴다`() {
        assertEquals("SHELL", codeFenceLanguageName("bash"))
        assertEquals("SHELL", codeFenceLanguageName("sh"))
        assertEquals("SHELL", codeFenceLanguageName("zsh"))
        assertEquals("JAVASCRIPT", codeFenceLanguageName("js"))
        assertEquals("TYPESCRIPT", codeFenceLanguageName("ts"))
        assertEquals("PYTHON", codeFenceLanguageName("py"))
        assertEquals("KOTLIN", codeFenceLanguageName("kt"))
        assertEquals("RUST", codeFenceLanguageName("rs"))
        assertEquals("GO", codeFenceLanguageName("golang"))
        assertEquals("CPP", codeFenceLanguageName("c++"))
        assertEquals("CSHARP", codeFenceLanguageName("cs"))
    }

    @Test
    fun `하이라이터가 이미 아는 이름은 그대로 둔다`() {
        assertEquals("KOTLIN", codeFenceLanguageName("kotlin"))
        assertEquals("JAVA", codeFenceLanguageName("Java"))
    }

    @Test
    fun `모르는 언어는 색 없이 그린다`() {
        // 지원 언어가 17 개뿐이라 json · yaml · diff 는 아예 없다. 억지로 다른 언어로 칠하면
        // 키워드가 아닌 것이 키워드 색으로 보인다 — 색이 없는 편이 낫다.
        assertNull(codeFenceLanguageName("json"))
        assertNull(codeFenceLanguageName("yaml"))
        assertNull(codeFenceLanguageName("diff"))
    }

    @Test
    fun `언어를 적지 않은 펜스도 색 없이 그린다`() {
        assertNull(codeFenceLanguageName(null))
        assertNull(codeFenceLanguageName("   "))
    }
}
