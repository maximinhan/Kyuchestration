package com.kyuchestration.desktop

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCode
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes

/**
 * 모델이 완성한 답을 마크다운으로 그린다(설계 5.8).
 *
 * **이 렌더러 하나가 여러 화면을 덮는다.** 모델의 답만이 아니라 `/context` 같은 로컬 명령의 답도
 * 마크다운 표로 온다(3.13) — 그 답들을 따로 그릴 자리를 만들 필요가 없다는 뜻이다.
 *
 * @param modifier 폭만 채운다. 이 컴포저블의 기본값이 `fillMaxSize` 라 그대로 두면 전사 목록
 *   안에서 항목 하나가 화면 전체를 차지한다.
 */
@Composable
internal fun AssistantMarkdown(text: String, modifier: Modifier = Modifier) {
    Markdown(
        content = text,
        typography = chatMarkdownTypography(),
        components = highlightedCodeComponents(),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * 대화에 맞춘 마크다운 타이포.
 *
 * **기본값을 그대로 쓰지 않는다.** 라이브러리의 기본은 `#` 을 `displayLarge` 로 그리는데, 그것은
 * 문서 한 편의 제목에 맞춘 크기다 — 답 한 줄에 `#` 하나가 들어오면 화면의 모든 것이 그 아래로
 * 밀린다. 대화의 위계는 본문이 기준이고, 제목은 그보다 한두 단계 클 뿐이다.
 */
@Composable
private fun chatMarkdownTypography(): MarkdownTypography = markdownTypography(
    h1 = MaterialTheme.typography.titleLarge,
    h2 = MaterialTheme.typography.titleMedium,
    h3 = MaterialTheme.typography.titleSmall,
    h4 = MaterialTheme.typography.titleSmall,
    h5 = MaterialTheme.typography.titleSmall,
    h6 = MaterialTheme.typography.titleSmall,
    text = MaterialTheme.typography.bodyMedium,
    paragraph = MaterialTheme.typography.bodyMedium,
    ordered = MaterialTheme.typography.bodyMedium,
    bullet = MaterialTheme.typography.bodyMedium,
    list = MaterialTheme.typography.bodyMedium,
    table = MaterialTheme.typography.bodyMedium,
    code = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
)

/**
 * 코드 펜스에 색을 넣는 구성.
 *
 * 하이라이터의 테마를 우리가 정해서 넘긴다. 라이브러리의 기본은 `isSystemInDarkTheme()` 인데,
 * 이 앱의 다크는 사용자가 창에서 고르는 것이라(ThemePreference) 시스템의 답과 다를 수 있다 —
 * 그대로 두면 어두운 화면에 밝은 테마의 색이 얹힌다. 지금 그려지고 있는 바탕에서 판단한다.
 */
@Composable
private fun highlightedCodeComponents(): MarkdownComponents {
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < DARK_SURFACE_LUMINANCE
    val highlightsBuilder = remember(darkTheme) {
        Highlights.Builder().theme(SyntaxThemes.default(darkMode = darkTheme))
    }

    return markdownComponents(
        codeFence = { model ->
            MarkdownCodeFence(model.content, model.node, model.typography.code) { code, language, style ->
                MarkdownHighlightedCode(
                    code = code,
                    language = codeFenceLanguageName(language),
                    style = style,
                    highlightsBuilder = highlightsBuilder,
                )
            }
        },
        codeBlock = { model ->
            MarkdownCodeBlock(model.content, model.node, model.typography.code) { code, language, style ->
                MarkdownHighlightedCode(
                    code = code,
                    language = codeFenceLanguageName(language),
                    style = style,
                    highlightsBuilder = highlightsBuilder,
                )
            }
        },
    )
}

/**
 * 펜스에 적힌 언어를 하이라이터가 아는 이름으로 옮긴다. 모르는 것은 null — 색 없이 그린다.
 *
 * **라이브러리의 알려진 결함을 메우는 한 함수다**(설계 3.15). `SyntaxLanguage.getByName` 이
 * 열거형 이름과의 완전일치만 보므로 `bash` · `js` · `py` 같이 사람이 실제로 적는 이름이 전부
 * 매칭에 실패한다. 두 번째 사용처를 기다릴 추상화가 아니라, 이 결함이 있는 동안 필요한 매핑이다.
 *
 * 없는 언어를 가까운 것으로 대신 칠하지 않는다. `json` 을 JAVASCRIPT 로 칠하면 키워드가 아닌
 * 것에 키워드 색이 붙어, 색이 없는 것보다 읽기 어려워진다.
 */
internal fun codeFenceLanguageName(fenceLanguage: String?): String? {
    val fenceName = fenceLanguage?.trim()?.lowercase().orEmpty()
    if (fenceName.isEmpty()) {
        return null
    }

    val highlighterName = COMMON_FENCE_ALIASES[fenceName] ?: fenceName
    return SyntaxLanguage.getByName(highlighterName)?.name
}

/**
 * 사람이 적는 이름 → 하이라이터의 이름.
 *
 * 지원 언어 17 개에 실제로 닿는 별칭만 적는다. `json` · `yaml` · `sql` · `diff` 는 하이라이터에
 * 아예 없어서 여기에 적을 이름이 없다.
 */
private val COMMON_FENCE_ALIASES = mapOf(
    "sh" to "shell",
    "bash" to "shell",
    "zsh" to "shell",
    "shell-session" to "shell",
    "console" to "shell",
    "js" to "javascript",
    "jsx" to "javascript",
    "mjs" to "javascript",
    "node" to "javascript",
    "ts" to "typescript",
    "tsx" to "typescript",
    "py" to "python",
    "python3" to "python",
    "kt" to "kotlin",
    "kts" to "kotlin",
    "rs" to "rust",
    "golang" to "go",
    "c++" to "cpp",
    "cxx" to "cpp",
    "cc" to "cpp",
    "objc" to "c",
    "cs" to "csharp",
    "c#" to "csharp",
    "rb" to "ruby",
)

/** 이 아래면 어두운 바탕으로 본다. Material 3 에는 라이트·다크를 묻는 표준 자리가 없다. */
private const val DARK_SURFACE_LUMINANCE = 0.5f
