package com.scantruth.ai.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.scantruth.ai.ui.theme.Brand

/** Back arrow + screen title, used by every pushed (non-tab) screen. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Column(modifier = Modifier.padding(start = 14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            subtitle?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/** Centred loading card for the working states of the report/ingredient flows. */
@Composable
fun LoadingPanel(
    message: String,
    modifier: Modifier = Modifier,
    fraction: Float? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 14.dp)
            )
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}

/** Error card with an optional retry action. Never hides the reason. */
@Composable
fun ErrorPanel(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = AppIcons.AlertTriangle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .padding(top = 6.dp)
                )
            }

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 12.dp)
            )

            if (actionLabel != null && onAction != null) {
                Button(
                    onClick = onAction,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onErrorContainer,
                        contentColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text(text = actionLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** Small labelled chip used for identifiers, conditions and profile items. */
@Composable
fun AppChip(
    text: String,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    val chipContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(
                start = 12.dp,
                end = if (onRemove != null || onEdit != null) 6.dp else 12.dp,
                top = 7.dp,
                bottom = 7.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = text, style = MaterialTheme.typography.labelMedium)
            if (onEdit != null) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    border = BorderStroke(0.8.dp, Brand.Outline.copy(alpha = 0.5f)),
                    onClick = onEdit,
                    modifier = Modifier.padding(start = 2.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.Pencil,
                        contentDescription = "Edit $text",
                        tint = Brand.Primary,
                        modifier = Modifier
                            .padding(4.dp)
                            .size(11.dp)
                    )
                }
            }
            if (onRemove != null) {
                Surface(
                    shape = CircleShape,
                    color = Brand.DangerSoft,
                    border = BorderStroke(0.8.dp, Brand.Danger.copy(alpha = 0.35f)),
                    onClick = onRemove,
                    modifier = Modifier.padding(start = 2.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.Close,
                        contentDescription = "Remove $text",
                        tint = Brand.Danger,
                        modifier = Modifier
                            .padding(4.dp)
                            .size(11.dp)
                    )
                }
            }
        }
    }

    if (onClick != null) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(50),
            color = containerColor,
            contentColor = contentColor,
            onClick = onClick,
            content = chipContent
        )
    } else {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(50),
            color = containerColor,
            contentColor = contentColor,
            content = chipContent
        )
    }
}

/** A labelled statistic row used on summary cards. */
@Composable
fun StatRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(140.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End
        )
    }
}
