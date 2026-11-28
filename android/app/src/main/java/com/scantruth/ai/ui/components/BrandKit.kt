package com.scantruth.ai.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.scantruth.ai.ui.theme.Brand

/**
 * Shared building blocks for the forest-green identity: icon tiles, soft banners,
 * statistic tiles and the decorative scanner illustration.
 */

/** Square icon tile with a soft green (or deep green) background. */
@Composable
fun IconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Int = 42,
    deep: Boolean = false,
    tint: Color? = null
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .background(
                color = if (deep) Brand.Deep else Brand.Soft,
                shape = RoundedCornerShape((size / 3.2f).dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint ?: if (deep) Color.White else Brand.Primary,
            modifier = Modifier.size((size * 0.48f).dp)
        )
    }
}

/** Quiet informational banner on a soft green surface. */
@Composable
fun SoftBanner(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Brand.Soft.copy(alpha = 0.8f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Brand.Primary,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(18.dp)
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/** One metric inside a summary card: value on top, label underneath. */
@Composable
fun StatTile(
    value: String,
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Brand.SoftRaised,
        border = BorderStroke(1.dp, Brand.Outline.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconTile(icon = icon, size = 34)
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** White card used across the dashboard, with the shared radius and hairline border. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 1.dp
    ) {
        Column(content = content)
    }
}

/** Dashed-style empty state used until real content exists. */
@Composable
fun EmptyStateCard(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            IconTile(icon = icon, size = 44)
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 5.dp)
            )
            if (actionLabel != null && onAction != null) {
                Button(
                    onClick = onAction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(actionLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * Decorative scanner illustration for the Home hero card: a viewfinder, a few
 * ingredient lines and a verification tick. Drawn locally so it scales cleanly and
 * needs no asset.
 */
@Composable
fun ScannerIllustration(modifier: Modifier = Modifier) {
    // Same drawing as the website's hero illustration: a 92x120 tile with soft corner
    // brackets, three ingredient lines and a mint check badge.
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = Color.White.copy(alpha = 0.07f),
            size = Size(w, h),
            cornerRadius = CornerRadius(16.dp.toPx())
        )

        val inset = 8.dp.toPx()
        val arm = 18.dp.toPx()
        val left = inset
        val top = inset
        val right = w - inset
        val bottom = h - inset
        val corners = Path().apply {
            moveTo(left, top + arm); lineTo(left, top); lineTo(left + arm, top)
            moveTo(right - arm, top); lineTo(right, top); lineTo(right, top + arm)
            moveTo(right, bottom - arm); lineTo(right, bottom); lineTo(right - arm, bottom)
            moveTo(left + arm, bottom); lineTo(left, bottom); lineTo(left, bottom - arm)
        }
        drawPath(corners, color = Color.White.copy(alpha = 0.6f), style = Stroke(width = 2.dp.toPx()))

        val lineHeight = 4.dp.toPx()
        val gap = 8.dp.toPx()
        val widths = listOf(34.dp.toPx(), 46.dp.toPx(), 28.dp.toPx())
        val blockHeight = widths.size * lineHeight + (widths.size - 1) * gap
        var y = (h - blockHeight) / 2f
        widths.forEachIndexed { index, lineWidth ->
            drawRoundRect(
                color = Color.White.copy(alpha = if (index == 0) 0.35f else 0.28f),
                topLeft = Offset(26.dp.toPx(), y),
                size = Size(lineWidth, lineHeight),
                cornerRadius = CornerRadius(2.dp.toPx())
            )
            y += lineHeight + gap
        }

        val radius = 14.dp.toPx()
        val center = Offset(w - 10.dp.toPx() - radius, h - 14.dp.toPx() - radius)
        drawCircle(color = Brand.Outline, radius = radius, center = center)
        val check = Path().apply {
            moveTo(center.x - 5.dp.toPx(), center.y)
            lineTo(center.x - 1.5.dp.toPx(), center.y + 3.5.dp.toPx())
            lineTo(center.x + 5.dp.toPx(), center.y - 3.5.dp.toPx())
        }
        drawPath(check, color = Brand.Primary, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round))
    }
}
