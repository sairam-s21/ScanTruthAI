package com.scantruth.ai.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.scantruth.ai.model.HealthMarker
import com.scantruth.ai.model.MarkerStatus
import com.scantruth.ai.model.MedicalExtraction
import com.scantruth.ai.ui.components.AppChip
import com.scantruth.ai.ui.components.AppIcons
import com.scantruth.ai.ui.components.ErrorPanel
import com.scantruth.ai.ui.components.LoadingPanel
import com.scantruth.ai.ui.components.ScreenHeader
import com.scantruth.ai.ui.components.StatRow
import com.scantruth.ai.ui.state.ReportAnalysisState
import com.scantruth.ai.ui.state.ReportStep

/**
 * Step 9-10 of the medical pipeline: the validated structured extraction, and the
 * explicit save into the user's Firestore profile.
 */
@Composable
fun ReportResultScreen(
    state: ReportAnalysisState,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onBack()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            ScreenHeader(
                title = "Extracted health data",
                subtitle = state.sourceName.ifBlank { "Medical report" },
                onBack = onBack
            )
        }

        when (val step = state.step) {
            is ReportStep.Working -> item { LoadingPanel(message = step.message) }

            is ReportStep.ExtractionReady -> {
                val extraction = state.extraction
                if (extraction == null) {
                    item {
                        ErrorPanel(
                            title = "Nothing to show",
                            message = "The extraction result is no longer available.",
                            actionLabel = "Back",
                            onAction = onBack
                        )
                    }
                } else {
                    item { ExtractionSummary(extraction) }
                    item { ExtractionLists(extraction) }
                    if (extraction.markers.isNotEmpty()) {
                        item { MarkersCard(extraction.markers) }
                    }
                    item {
                        Button(
                            onClick = { state.save() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            contentPadding = PaddingValues(vertical = 14.dp)
                        ) {
                            Text(
                                text = "Save to My Health",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                    item { DisclaimerCard() }
                }
            }

            ReportStep.Saved -> item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = state.savedMessage ?: "Saved.",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Your profile and report history now include this extraction.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        Button(
                            onClick = onDone,
                            modifier = Modifier.padding(top = 16.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text("Go to My Health", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            is ReportStep.Failed -> item {
                ErrorPanel(
                    title = "Extraction failed",
                    message = step.message,
                    actionLabel = "Back",
                    onAction = onBack
                )
            }

            else -> item {
                ErrorPanel(
                    title = "No extraction yet",
                    message = "Approve a redacted report first, then its extraction appears here.",
                    actionLabel = "Back",
                    onAction = onBack
                )
            }
        }
    }
}

@Composable
private fun ExtractionSummary(extraction: MedicalExtraction) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = extraction.reportTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                AppChip(text = extraction.engine.label)
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
            StatRow("Report type", extraction.reportType)
            StatRow("Markers found", extraction.markers.size.toString())
            StatRow(
                "Health items",
                (extraction.conditions.size + extraction.allergies.size +
                    extraction.medications.size + extraction.dietaryRestrictions.size).toString()
            )
        }
    }
}

@Composable
private fun ExtractionLists(extraction: MedicalExtraction) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ExtractionSection("Conditions", extraction.conditions)
        ExtractionSection("Allergies", extraction.allergies)
        ExtractionSection("Medications", extraction.medications)
        ExtractionSection("Dietary restrictions", extraction.dietaryRestrictions)
        ExtractionSection("Health notes", extraction.healthNotes)
    }
}

@Composable
private fun ExtractionSection(title: String, items: List<String>) {
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
            if (items.isEmpty()) {
                Text(
                    text = "None found in this report.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            } else {
                items.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp).size(5.dp)
                        ) {}
                        Text(
                            text = item,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkersCard(markers: List<HealthMarker>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Reported values",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            markers.forEach { marker ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = marker.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = statusLabel(marker.status),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor(marker.status)
                        )
                    }
                    Text(
                        text = listOfNotNull(marker.value, marker.unit).joinToString(" "),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun statusColor(status: MarkerStatus) = when (status) {
    MarkerStatus.HIGH, MarkerStatus.LOW -> MaterialTheme.colorScheme.error
    MarkerStatus.NORMAL -> MaterialTheme.colorScheme.primary
    MarkerStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun statusLabel(status: MarkerStatus): String = when (status) {
    // The model reports a status, not a reference range, so these say exactly that
    // instead of claiming a range the report may never have contained.
    MarkerStatus.HIGH -> "Reported above range"
    MarkerStatus.LOW -> "Reported below range"
    MarkerStatus.NORMAL -> "Reported as normal"
    MarkerStatus.UNKNOWN -> "No range stated"
}

@Composable
private fun DisclaimerCard() {
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
                text = "These values were read from your report by an AI model. Check them " +
                    "against the original document. ScanTruthAI does not diagnose or treat " +
                    "any condition.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
    }
}
