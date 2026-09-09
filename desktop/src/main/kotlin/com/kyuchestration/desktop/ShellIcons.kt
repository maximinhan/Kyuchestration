package com.kyuchestration.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 셸이 쓰는 아이콘 다섯. 라이브러리에서 받아 오지 않고 여기서 그린다.
 *
 * `Icons.Default.*` 는 `material-icons-core` 아티팩트에 있고, 이 앱의 클래스패스에는 그것이
 * 없다(Compose Multiplatform 의 material3 는 끌고 오지 않는다). 아이콘 다섯 개를 쓰려고 의존을
 * 하나 더 들이는 것과, 열 몇 줄짜리 선 그림 다섯 개를 그리는 것 중 뒤를 골랐다 — 받아 오면
 * 쓰지 않을 수천 개가 함께 오고, 그것을 트리 셰이킹으로 걷어 내는 자리가 이 빌드에는 없다
 * (프로가드를 끈 이유는 desktop/build.gradle.kts 에 적혀 있다).
 *
 * 유니코드 기호(⚙ · ⟳)로 대신하지 않은 이유도 같은 자리다. 그 글자가 어떤 폰트로 그려질지는
 * 머신마다 다르고, 어떤 데스크톱에서는 이모지로 바뀌어 크기와 색이 우리 손을 떠난다.
 */
@Composable
internal fun WorkDirFolderIcon(tint: Color, size: Dp = ICON_SIZE) {
    Canvas(modifier = Modifier.size(size)) {
        val path = Path().apply {
            moveTo(x(0.08f), y(0.78f))
            lineTo(x(0.08f), y(0.22f))
            lineTo(x(0.42f), y(0.22f))
            lineTo(x(0.52f), y(0.36f))
            lineTo(x(0.92f), y(0.36f))
            lineTo(x(0.92f), y(0.78f))
            close()
        }
        drawPath(path, tint, style = outlineStroke())
    }
}

/** 받아 오는 일. 아래로 내려오는 화살표와 그것을 받는 바닥. */
@Composable
internal fun CloneDownloadIcon(tint: Color, size: Dp = ICON_SIZE) {
    Canvas(modifier = Modifier.size(size)) {
        drawLine(tint, Offset(x(0.5f), y(0.14f)), Offset(x(0.5f), y(0.60f)), outlineStrokeWidth(), StrokeCap.Round)
        val head = Path().apply {
            moveTo(x(0.28f), y(0.40f))
            lineTo(x(0.5f), y(0.62f))
            lineTo(x(0.72f), y(0.40f))
        }
        drawPath(head, tint, style = outlineStroke())
        drawLine(tint, Offset(x(0.16f), y(0.84f)), Offset(x(0.84f), y(0.84f)), outlineStrokeWidth(), StrokeCap.Round)
    }
}

/** 다시 묻는 일. 한 바퀴에 조금 못 미치는 원과 그 끝의 화살촉. */
@Composable
internal fun RefreshArcIcon(tint: Color, size: Dp = ICON_SIZE) {
    Canvas(modifier = Modifier.size(size)) {
        val inset = this.size.minDimension * 0.16f
        drawArc(
            color = tint,
            startAngle = -35f,
            sweepAngle = 290f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(this.size.width - inset * 2, this.size.height - inset * 2),
            style = outlineStroke(),
        )
        val head = Path().apply {
            moveTo(x(0.62f), y(0.10f))
            lineTo(x(0.86f), y(0.30f))
            lineTo(x(0.60f), y(0.44f))
        }
        drawPath(head, tint, style = outlineStroke())
    }
}

/** 앱이 남기는 기록. 줄이 그어진 종이 한 장. */
@Composable
internal fun DiagnosticLogIcon(tint: Color, size: Dp = ICON_SIZE) {
    Canvas(modifier = Modifier.size(size)) {
        val page = Path().apply {
            moveTo(x(0.18f), y(0.10f))
            lineTo(x(0.82f), y(0.10f))
            lineTo(x(0.82f), y(0.90f))
            lineTo(x(0.18f), y(0.90f))
            close()
        }
        drawPath(page, tint, style = outlineStroke())
        listOf(0.32f, 0.50f, 0.68f).forEach { line ->
            drawLine(tint, Offset(x(0.32f), y(line)), Offset(x(0.68f), y(line)), outlineStrokeWidth(), StrokeCap.Round)
        }
    }
}

/** 고를 수 있는 것들. 손잡이가 각기 다른 자리에 놓인 세 줄. */
@Composable
internal fun SettingsSlidersIcon(tint: Color, size: Dp = ICON_SIZE) {
    Canvas(modifier = Modifier.size(size)) {
        val rows = listOf(0.24f to 0.66f, 0.5f to 0.34f, 0.76f to 0.58f)
        rows.forEach { (row, knob) ->
            drawLine(tint, Offset(x(0.12f), y(row)), Offset(x(0.88f), y(row)), outlineStrokeWidth(), StrokeCap.Round)
            drawCircle(tint, radius = this.size.minDimension * 0.11f, center = Offset(x(knob), y(row)))
        }
    }
}

/**
 * 조율하는 자리. 가운데 하나와 그것이 부리는 셋.
 *
 * 세션 패널의 위계를 모양으로도 말한다 — 메인은 레포와 같은 것이 하나 더 있는 것이 아니라
 * 레포들을 부리는 자리다.
 */
@Composable
internal fun OrchestrationHubIcon(tint: Color, size: Dp = HUB_ICON_SIZE) {
    Canvas(modifier = Modifier.size(size)) {
        val center = Offset(x(0.5f), y(0.5f))
        val satellites = listOf(
            Offset(x(0.5f), y(0.12f)),
            Offset(x(0.15f), y(0.80f)),
            Offset(x(0.85f), y(0.80f)),
        )
        satellites.forEach { satellite ->
            drawLine(tint, center, satellite, outlineStrokeWidth(), StrokeCap.Round)
            drawCircle(tint, radius = this.size.minDimension * 0.10f, center = satellite)
        }
        drawCircle(tint, radius = this.size.minDimension * 0.17f, center = center)
    }
}

/** 그림의 좌표는 0~1 의 비율로 적는다. 아이콘 크기를 바꿔도 같은 그림이 나온다. */
private fun DrawScope.x(ratio: Float): Float = size.width * ratio

private fun DrawScope.y(ratio: Float): Float = size.height * ratio

private fun DrawScope.outlineStrokeWidth(): Float = size.minDimension * STROKE_WIDTH_RATIO

private fun DrawScope.outlineStroke(): Stroke = Stroke(
    width = outlineStrokeWidth(),
    cap = StrokeCap.Round,
    join = StrokeJoin.Round,
)

/** 선 굵기도 비율로 둔다. 20dp 에서 1.6dp — Material 의 아이콘 선 굵기와 같은 자세다. */
private const val STROKE_WIDTH_RATIO = 0.08f

private val ICON_SIZE = 20.dp

private val HUB_ICON_SIZE = 22.dp
