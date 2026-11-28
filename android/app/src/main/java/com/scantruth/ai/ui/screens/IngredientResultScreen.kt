package com.scantruth.ai.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scantruth.ai.model.FactorDirection
import com.scantruth.ai.model.HealthFactor
import com.scantruth.ai.model.IngredientAnalysis
import com.scantruth.ai.model.IngredientVerdict
import com.scantruth.ai.model.SafetyBand
import com.scantruth.ai.model.VerdictLevel
import com.scantruth.ai.ui.components.AppCard
import com.scantruth.ai.ui.components.AppIcons
import com.scantruth.ai.ui.components.ErrorPanel
import com.scantruth.ai.ui.components.FallbackNotice
import com.scantruth.ai.ui.components.LoadingPanel
import com.scantruth.ai.ui.components.ScreenHeader
import com.scantruth.ai.ui.state.IngredientAnalysisState
import com.scantruth.ai.ui.state.IngredientStep
import com.scantruth.ai.ui.theme.Brand

/** The dedicated ingredient analysis result screen. */
@Composable
fun IngredientResultScreen(
    state: IngredientAnalysisState,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onBack()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        val currentAnalysis = state.analysis
        item {
            ScreenHeader(
                title = currentAnalysis?.productName?.takeIf { it.isNotBlank() } ?: "Analysis",
                subtitle = currentAnalysis?.productCategory?.takeIf { it.isNotBlank() }
                    ?: state.sourceName.ifBlank { "Scanned label" },
                onBack = onBack
            )
        }

        when (val step = state.step) {
            is IngredientStep.Working -> item { LoadingPanel(message = step.message) }

            IngredientStep.Ready -> {
                val analysis = state.analysis
                if (analysis == null) {
                    item {
                        ErrorPanel(
                            title = "Nothing to show",
                            message = "The analysis result is no longer available.",
                            actionLabel = "Back",
                            onAction = onBack
                        )
                    }
                } else {
                    item { ScoreHeader(analysis) }

                    val fallback = analysis.fallbackNotice
                    if (analysis.usedFallback && fallback != null) {
                        item { FallbackNotice(text = fallback, title = "Cloud fallback") }
                    }

                    if (analysis.aiSummary.isNotBlank()) {
                        item {
                            Text(
                                text = analysis.aiSummary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Brand.TextPrimary,
                                lineHeight = 20.sp
                            )
                        }
                    }

                    if (analysis.healthFactors.isNotEmpty()) {
                        item { CapsSectionHeader("HEALTH FACTORS") }
                        analysis.healthFactors.forEach { factor ->
                            item { HealthFactorCard(factor) }
                        }
                    }

                    if (analysis.ingredientBreakdown.isNotEmpty()) {
                        item { CapsSectionHeader("INGREDIENT BREAKDOWN") }
                        analysis.ingredientBreakdown.forEach { verdict ->
                            item { IngredientBreakdownCard(verdict) }
                        }
                    }

                    if (analysis.recommendedIntake.isNotBlank()) {
                        item { CapsSectionHeader("RECOMMENDED INTAKE") }
                        item { InfoCard(body = analysis.recommendedIntake) }
                    }

                    if (analysis.uncertaintyNotes.isNotEmpty()) {
                        item { UncertaintyCard(analysis.uncertaintyNotes) }
                    }

                    state.saveNote?.let { note -> item { SaveStateCard(note) } }

                    if (state.lastMetrics.aiInferenceDurationMs > 0L) {
                        item {
                            Text(
                                text = "Analyzed with ${analysis.engine.label} · " +
                                    "AI ${state.lastMetrics.aiInferenceDurationMs}ms" +
                                    if (state.lastMetrics.ocrDurationMs > 0L) {
                                        " · OCR ${state.lastMetrics.ocrDurationMs}ms"
                                    } else {
                                        ""
                                    },
                                style = MaterialTheme.typography.labelSmall,
                                color = Brand.TextTertiary
                            )
                        }
                    }

                    item { MedicalDisclaimer() }
                    item {
                        Button(
                            onClick = onDone,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Brand.Primary,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(vertical = 14.dp)
                        ) {
                            Text("Done", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            IngredientStep.Detected -> item {
                ErrorPanel(
                    title = "Not analysed yet",
                    message = "Review the ingredient list on the Scan tab and tap Analyze Ingredients.",
                    actionLabel = "Back to Scan",
                    onAction = onBack
                )
            }

            is IngredientStep.Failed -> item {
                ErrorPanel(
                    title = "Analysis could not run",
                    message = step.message,
                    actionLabel = "Back to Scan",
                    onAction = {
                        state.clearAnalysis()
                        onBack()
                    }
                )
            }

            IngredientStep.Idle -> item {
                ErrorPanel(
                    title = "No label scanned",
                    message = "Scan an ingredient label to see an analysis here.",
                    actionLabel = "Back",
                    onAction = onBack
                )
            }
        }
    }
}

@Composable
private fun CapsSectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = Brand.TextSecondary,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun ScoreHeader(analysis: IngredientAnalysis) {
    val bandColor = when (analysis.band) {
        SafetyBand.GOOD -> Brand.Good
        SafetyBand.CARE -> Brand.Care
        SafetyBand.AVOID -> Brand.Avoid
    }
    val rubricLabel = when {
        analysis.safetyScore >= 70 -> "Good match for you"
        analysis.safetyScore >= 40 -> "Use with care"
        else -> "Avoid"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Brand.Card,
        border = BorderStroke(1.dp, Color(0x0F000000)),
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier.size(76.dp),
                contentAlignment = Alignment.Center
            ) {
                val sweep = (analysis.safetyScore.coerceIn(0, 100) / 100f) * 360f
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 8.dp.toPx()
                    drawArc(
                        color = bandColor.copy(alpha = 0.2f),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = bandColor,
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }
                Text(
                    text = analysis.safetyScore.toString(),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Brand.TextPrimary
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = rubricLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = bandColor
                )
                Text(
                    text = analysis.headline,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Brand.TextPrimary,
                    lineHeight = 21.sp
                )
                Surface(
                    shape = RoundedCornerShape(100),
                    color = Brand.Soft,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(
                        text = analysis.engine.label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Brand.Primary,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HealthFactorCard(factor: HealthFactor) {
    val (badgeText, badgeBg, badgeFg) = when (factor.direction) {
        FactorDirection.POSITIVE -> Triple("Helpful", Brand.Soft, Brand.Primary)
        FactorDirection.CAUTION -> Triple("Caution", Brand.Care.copy(alpha = 0.14f), Brand.Care)
        else -> Triple("Info", Brand.SoftRaised, Brand.TextSecondary)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Brand.Card,
        border = BorderStroke(1.dp, Color(0x0F000000))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = factor.label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Brand.TextPrimary,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                Surface(
                    shape = RoundedCornerShape(100),
                    color = badgeBg
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = badgeFg,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            if (factor.detail.isNotBlank()) {
                Text(
                    text = factor.detail,
                    fontSize = 13.sp,
                    color = Brand.TextSecondary,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun IngredientBreakdownCard(item: IngredientVerdict) {
    val (label, statusColor, statusBg) = when (item.verdict) {
        VerdictLevel.SAFE -> Triple("SAFE", Brand.Good, Brand.Soft)
        VerdictLevel.AVOID -> Triple("AVOID", Brand.Avoid, Brand.Avoid.copy(alpha = 0.12f))
        VerdictLevel.CAUTION -> Triple("CAUTION", Brand.Care, Brand.Care.copy(alpha = 0.14f))
        VerdictLevel.UNKNOWN -> Triple("UNKNOWN", Brand.TextSecondary, Brand.SoftRaised)
    }

    val explanation = item.note?.takeIf { it.isNotBlank() } ?: when (item.verdict) {
        VerdictLevel.SAFE -> "No identified conflict with the available health profile."
        VerdictLevel.AVOID -> "Conflicts with a known allergy or dietary restriction in your profile."
        VerdictLevel.CAUTION -> "Relevant concern or uncertainty requires attention."
        VerdictLevel.UNKNOWN -> "Insufficient information to make a reliable assessment."
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Brand.Card,
        border = BorderStroke(
            width = 1.dp,
            color = if (item.verdict == VerdictLevel.AVOID) Brand.Avoid.copy(alpha = 0.2f) else Color(0x0F000000)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = item.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Brand.TextPrimary,
                    modifier = Modifier.weight(1f).padding(end = 10.dp)
                )
                Surface(
                    shape = RoundedCornerShape(100),
                    color = statusBg
                ) {
                    Text(
                        text = label,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
            }

            item.conflict?.takeIf { it.isNotBlank() }?.let { conflict ->
                Text(
                    text = "Profile conflict: $conflict",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor
                )
            }

            Text(
                text = explanation,
                fontSize = 12.sp,
                color = Brand.TextSecondary,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun InfoCard(body: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Brand.Card,
        border = BorderStroke(1.dp, Color(0x0F000000))
    ) {
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = Brand.TextPrimary,
            modifier = Modifier.padding(14.dp)
        )
    }
}

@Composable
private fun UncertaintyCard(notes: List<String>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Brand.SoftRaised,
        border = BorderStroke(1.dp, Brand.MintBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Uncertainty notes",
                style = MaterialTheme.typography.titleSmall,
                color = Brand.TextPrimary
            )
            notes.forEach { note ->
                Text(
                    text = "• $note",
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.TextSecondary,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun SaveStateCard(note: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Brand.Soft
    ) {
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            color = Brand.Primary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun MedicalDisclaimer() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Brand.Soft.copy(alpha = 0.8f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = AppIcons.AlertTriangle,
                contentDescription = null,
                tint = Brand.Primary,
                modifier = Modifier.size(16.dp).padding(top = 2.dp)
            )
            Text(
                text = "This is general ingredient information compared with the profile you " +
                    "approved, not medical advice. It does not diagnose or treat any condition. " +
                    "Ask a clinician or pharmacist about your own situation.",
                style = MaterialTheme.typography.bodySmall,
                color = Brand.TextSecondary,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
    }
}
