package com.scantruth.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scantruth.ai.model.ScanRecord
import com.scantruth.ai.ui.components.AppCard
import com.scantruth.ai.ui.theme.Brand

/** Score colour from the shared rubric. */
internal fun scoreTone(score: Int?): Color = when {
    score == null -> Brand.Primary
    score >= 70 -> Brand.Primary
    score >= 40 -> Brand.Warning
    else -> Brand.Danger
}

/**
 * A saved analysis, opened from Recent Analysis or Scan history, laid out like the iOS
 * result view. Shows only what the saved document holds; nothing is re-generated.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedAnalysisSheet(record: ScanRecord, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tone = scoreTone(record.safetyScore)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDismiss) {
                    Text("Done", fontWeight = FontWeight.SemiBold, color = Brand.Primary)
                }
            }

            AppCard {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(76.dp)) {
                        CircularProgressIndicator(
                            progress = { 1f },
                            modifier = Modifier.size(76.dp),
                            color = tone.copy(alpha = 0.2f),
                            strokeWidth = 8.dp
                        )
                        CircularProgressIndicator(
                            progress = { (record.safetyScore ?: 0) / 100f },
                            modifier = Modifier.size(76.dp),
                            color = tone,
                            strokeWidth = 8.dp,
                            trackColor = Color.Transparent,
                            strokeCap = StrokeCap.Round
                        )
                        Text(
                            text = record.safetyScore?.toString() ?: "–",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column(modifier = Modifier.padding(start = 16.dp)) {
                        Text(
                            text = record.displayBadge ?: record.badgeLabel ?: "Saved analysis",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = tone
                        )
                        Text(
                            text = record.headline ?: "Analysis complete",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            record.aiSummary?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }

            if (record.healthFactors.isNotEmpty()) {
                SheetSection("HEALTH FACTORS") {
                    record.healthFactors.forEach { factor ->
                        AppCard {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = factor.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    factor.badge?.let { badge ->
                                        Surface(shape = RoundedCornerShape(50), color = Brand.Soft) {
                                            Text(
                                                text = badge,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                }
                                factor.description?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (record.ingredientBreakdown.isNotEmpty()) {
                SheetSection("INGREDIENT BREAKDOWN") {
                    record.ingredientBreakdown.forEach { item ->
                        AppCard {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    item.note?.let {
                                        Text(
                                            text = it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                item.status?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (record.intakeHeadline != null || record.intakeGuidance != null) {
                SheetSection("RECOMMENDED INTAKE") {
                    AppCard {
                        Column(modifier = Modifier.padding(14.dp)) {
                            record.intakeHeadline?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            record.intakeGuidance?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            if (record.aiSummary == null && record.healthFactors.isEmpty() && record.ingredientBreakdown.isEmpty()) {
                Text(
                    text = "Only the summary of this scan was saved, so the full breakdown is not available.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "Analyzed with ${record.modelUsed ?: "a saved model"}. AI-generated guidance is " +
                    "not medical advice. Check with a doctor or pharmacist before changing your diet " +
                    "or medication.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SheetSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
    }
}
