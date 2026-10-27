package dev.velum.app.ui.recording

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import dev.velum.app.ui.theme.VelumInk
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Animated bars used while a live mic/demo session is active. */
@Composable
fun LiveWaveform(
    active: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 48,
) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (isActive) {
            tick++
            delay(48)
        }
    }
    val ink = VelumInk
    Canvas(modifier.fillMaxWidth().height(72.dp)) {
        val gap = 3.dp.toPx()
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        for (i in 0 until barCount) {
            val phase = (tick + i * 3) / 8f
            val amp = (0.25f + 0.75f * ((sin(phase.toDouble()) + 1) / 2f).toFloat())
                .coerceIn(0.12f, 1f)
            val h = size.height * amp
            val x = i * (barWidth + gap)
            val y = (size.height - h) / 2f
            drawRoundRect(
                color = ink.copy(alpha = if (active) 0.9f else 0.25f),
                topLeft = Offset(x, y),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

@Composable
fun rememberLiveElapsedSeconds(running: Boolean, initialSeconds: Long = 0L): Long {
    var elapsed by remember { mutableLongStateOf(initialSeconds) }
    LaunchedEffect(running, initialSeconds) {
        elapsed = initialSeconds
        if (!running) return@LaunchedEffect
        while (isActive) {
            delay(1_000)
            elapsed += 1
        }
    }
    return elapsed
}

fun formatDurationClock(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
