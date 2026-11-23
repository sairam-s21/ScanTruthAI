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
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(18.dp)
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(top = 3.dp)
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
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val corner = CornerRadius(26.dp.toPx())

        drawRoundRect(
            color = Color.White.copy(alpha = 0.08f),
            size = Size(w, h),
            cornerRadius = corner
        )

        val inset = 20.dp.toPx()
        val bracket = 26.dp.toPx()
        val stroke = 3.dp.toPx()
        val frame = Brand.Soft.copy(alpha = 0.85f)
        val left = inset
        val top = inset
        val right = w - inset
        val bottom = h - inset

        val path = Path().apply {
            // top-left
            moveTo(left, top + bracket); lineTo(left, top); lineTo(left + bracket, top)
            // top-right
            moveTo(right - bracket, top); lineTo(right, top); lineTo(right, top + bracket)
            // bottom-right
            moveTo(right, bottom - bracket); lineTo(right, bottom); lineTo(right - bracket, bottom)
            // bottom-left
            moveTo(left + bracket, bottom); lineTo(left, bottom); lineTo(left, bottom - bracket)
        }
        drawPath(
            path = path,
            color = frame,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )

        // Ingredient lines being read.
        val lineX = left + 20.dp.toPx()
        val widths = listOf(0.52f, 0.68f, 0.44f, 0.60f)
        widths.forEachIndexed { index, fraction ->
            drawRoundRect(
                color = Color.White.copy(alpha = 0.55f),
                topLeft = Offset(lineX, top + 44.dp.toPx() + index * 22.dp.toPx()),
                size = Size((right - left - 40.dp.toPx()) * fraction, 5.dp.toPx()),
                cornerRadius = CornerRadius(3.dp.toPx())
            )
        }

        // Scan line.
        drawRoundRect(
            color = Brand.Soft,
            topLeft = Offset(left + 12.dp.toPx(), top + 150.dp.toPx()),
            size = Size(right - left - 24.dp.toPx(), 2.dp.toPx()),
            cornerRadius = CornerRadius(1.dp.toPx())
        )

        // Verification badge.
        val badgeCenter = Offset(right - 26.dp.toPx(), bottom - 26.dp.toPx())
        drawCircle(color = Brand.Secondary, radius = 18.dp.toPx(), center = badgeCenter)
        val check = Path().apply {
            moveTo(badgeCenter.x - 7.dp.toPx(), badgeCenter.y)
            lineTo(badgeCenter.x - 2.dp.toPx(), badgeCenter.y + 5.dp.toPx())
            lineTo(badgeCenter.x + 8.dp.toPx(), badgeCenter.y - 5.dp.toPx())
        }
        drawPath(
            path = check,
            color = Color.White,
            style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}
