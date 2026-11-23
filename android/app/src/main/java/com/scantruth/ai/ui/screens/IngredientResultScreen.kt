package com.scantruth.ai.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.scantruth.ai.model.FactorDirection
import com.scantruth.ai.model.IngredientAnalysis
import com.scantruth.ai.model.SafetyBand
import com.scantruth.ai.model.VerdictLevel
import com.scantruth.ai.ui.components.AppChip
import com.scantruth.ai.ui.components.AppIcons
import com.scantruth.ai.ui.components.ErrorPanel
import com.scantruth.ai.ui.components.FallbackNotice
import com.scantruth.ai.ui.components.LoadingPanel
import com.scantruth.ai.ui.components.ScreenHeader
import com.scantruth.ai.ui.state.IngredientAnalysisState
import com.scantruth.ai.ui.state.IngredientStep

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

    LaunchedEffect(state.aiMode) {
        state.checkNano()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            ScreenHeader(
                title = "Analysis",
                subtitle = state.sourceName.ifBlank { "Scanned label" },
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
                    // Only a real cloud request is announced: an on-device or explicit
                    // Flash run carries no fallback metadata.
                    val fallback = analysis.fallbackNotice
                    if (analysis.usedFallback && fallback != null) {
                        item { FallbackNotice(text = fallback, title = "Cloud fallback") }
                    }
                    if (analysis.aiSummary.isNotBlank()) {
                        item { SummaryCard(analysis) }
                    }
                    if (analysis.healthFactors.isNotEmpty()) {
                        item { FactorsCard(analysis) }
                    }
                    if (analysis.ingredientBreakdown.isNotEmpty()) {
                        item { BreakdownCard(analysis) }
                    }
                    if (analysis.recommendedIntake.isNotBlank()) {
                        item { InfoCard("Recommended intake", analysis.recommendedIntake) }
                    }
                    if (analysis.uncertaintyNotes.isNotEmpty()) {
                        item { UncertaintyCard(analysis.uncertaintyNotes) }
                    }
                    state.saveNote?.let { note -> item { SaveStateCard(note) } }
                    item { MedicalDisclaimer() }
                    item {
                        Button(
                            onClick = onDone,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            contentPadding = PaddingValues(vertical = 14.dp)
                        ) {
                            Text("Done", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            IngredientStep.Detected -> item {
                ErrorPanel(
                    title = "Not analysed yet",
                    message = "Confirm the ingredient list on the Scan tab and tap " +
                        "Analyze Ingredients.",
                    actionLabel = "Back to Scan",
                    onAction = onBack
                )
            }

            is IngredientStep.Failed -> item {
                ErrorPanel(
                    title = "Analysis could not run",
                    message = step.message,
                    actionLabel = "Try another label",
                    onAction = {
                        state.reset()
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
private fun ScoreHeader(analysis: IngredientAnalysis) {
    val bandColor = when (analysis.band) {
        SafetyBand.GOOD -> MaterialTheme.colorScheme.primary
        SafetyBand.CARE -> Color(0xFFB7791F)
        SafetyBand.AVOID -> MaterialTheme.colorScheme.error
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 3.dp
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(bandColor.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = analysis.safetyScore.toString(),
                        style = MaterialTheme.typography.headlineLarge,
                        color = bandColor
                    )
                    Text(
                        text = "of 100",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = analysis.badgeLabel,
                style = MaterialTheme.typography.titleMedium,
                color = bandColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 14.dp)
            )
            Text(
                text = analysis.productName,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                text = analysis.productCategory,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                text = analysis.headline,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp)
            )

            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppChip(text = "Analysed by ${analysis.engine.label}")
            }
        }
    }
}

@Composable
private fun SummaryCard(analysis: IngredientAnalysis) {
    InfoCard(title = "Summary", body = analysis.aiSummary)
}

@Composable
private fun InfoCard(title: String, body: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun FactorsCard(analysis: IngredientAnalysis) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Health factors",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            analysis.healthFactors.forEach { factor ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = when (factor.direction) {
                            FactorDirection.POSITIVE -> AppIcons.ShieldCheck
                            FactorDirection.CAUTION -> AppIcons.AlertTriangle
                            else -> AppIcons.HeartPulse
                        },
                        contentDescription = null,
                        tint = when (factor.direction) {
                            FactorDirection.POSITIVE -> MaterialTheme.colorScheme.primary
                            FactorDirection.CAUTION -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(17.dp).padding(top = 2.dp)
                    )
                    Column(modifier = Modifier.padding(start = 10.dp)) {
                        Text(
                            text = factor.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (factor.detail.isNotBlank()) {
                            Text(
                                text = factor.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BreakdownCard(analysis: IngredientAnalysis) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Ingredient breakdown",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            analysis.ingredientBreakdown.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        item.note?.let { note ->
                            Text(
                                text = note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = verdictLabel(item.verdict),
                        style = MaterialTheme.typography.labelMedium,
                        color = when (item.verdict) {
                            VerdictLevel.SAFE -> MaterialTheme.colorScheme.primary
                            VerdictLevel.CAUTION -> Color(0xFFB7791F)
                            VerdictLevel.AVOID -> MaterialTheme.colorScheme.error
                            VerdictLevel.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

private fun verdictLabel(verdict: VerdictLevel): String = when (verdict) {
    VerdictLevel.SAFE -> "Safe"
    VerdictLevel.CAUTION -> "Caution"
    VerdictLevel.AVOID -> "Avoid"
    VerdictLevel.UNKNOWN -> "Unknown"
}

@Composable
private fun UncertaintyCard(notes: List<String>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Uncertainty",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            notes.forEach { note ->
                Text(
                    text = "• $note",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun MedicalDisclaimer() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = AppIcons.AlertTriangle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(17.dp)
            )
            Text(
                text = "This is general ingredient information compared with the profile you " +
                    "approved, not medical advice. It does not diagnose or treat any condition. " +
                    "Ask a clinician or pharmacist about your own situation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
    }
}
