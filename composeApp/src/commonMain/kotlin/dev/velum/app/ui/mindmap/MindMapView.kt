package dev.velum.app.ui.mindmap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.velum.app.i18n.LocalStrings
import dev.velum.app.ui.theme.VelumInk
import dev.velum.app.ui.theme.VelumInkSecondary

/**
 * Phone-readable indented tree with connector lines (no external libs).
 */
@Composable
fun MindMapView(
    root: MindMapNode,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            root.label,
            color = VelumInk,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
        )
        Spacer(Modifier.height(12.dp))
        root.children.forEachIndexed { index, child ->
            MindMapBranch(
                node = child,
                depth = 0,
                isLast = index == root.children.lastIndex,
            )
        }
        if (root.children.isEmpty()) {
            Text(
                strings.ux.mindMapEmpty,
                color = VelumInkSecondary,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun MindMapBranch(
    node: MindMapNode,
    depth: Int,
    isLast: Boolean,
) {
    val indent = 12.dp + (depth * 18).dp
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(start = indent)
                .width(16.dp)
                .height(22.dp),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val midY = size.height * 0.55f
                val stroke = Color(0xFF8E8E93)
                drawLine(
                    color = stroke,
                    start = Offset(0f, 0f),
                    end = Offset(0f, if (isLast) midY else size.height),
                    strokeWidth = 2f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = stroke,
                    start = Offset(0f, midY),
                    end = Offset(size.width, midY),
                    strokeWidth = 2f,
                    cap = StrokeCap.Round,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                node.label,
                color = VelumInk,
                fontWeight = if (depth == 0) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = if (depth == 0) 15.sp else 14.sp,
                lineHeight = 20.sp,
            )
            node.children.forEachIndexed { i, grand ->
                MindMapBranch(
                    node = grand,
                    depth = depth + 1,
                    isLast = i == node.children.lastIndex,
                )
            }
        }
    }
}
