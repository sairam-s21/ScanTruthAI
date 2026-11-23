package com.scantruth.ai.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.scantruth.ai.model.AlertSeverity
import com.scantruth.ai.model.PrivacyAlert
import com.scantruth.ai.ui.components.AppChip
import com.scantruth.ai.ui.components.AppIcons
import com.scantruth.ai.ui.components.ErrorPanel
import com.scantruth.ai.ui.components.FallbackNotice
import com.scantruth.ai.ui.components.LoadingPanel
import com.scantruth.ai.ui.components.ScreenHeader
import com.scantruth.ai.ui.state.ReportAnalysisState
import com.scantruth.ai.ui.state.ReportStep

/**
 * Step 6-8 of the medical pipeline: the user sees exactly what will be sent, what is
 * still uncertain, and what was masked. Only their approval lets the redacted text reach
 * the cloud model, and approval is refused while a high-risk identifier is still visible.
 */
@Composable
fun PrivacyReviewScreen(
    state: ReportAnalysisState,
    onOpenResult: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var resultOpened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.step) {
        if (state.step != ReportStep.ExtractionReady) {
            resultOpened = false
        } else if (!resultOpened) {
            resultOpened = true
            onOpenResult()
        }
    }

    val isProcessing = state.step is ReportStep.Working || state.step is ReportStep.Extracting

    BackHandler {
        if (isProcessing || state.step is ReportStep.Review) {
            state.reject()
        }
        onBack()
    }

    DisposableEffect(Unit) {
        onDispose {
            if (state.step is ReportStep.Working || state.step is ReportStep.Extracting) {
                state.reject()
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            ScreenHeader(
                title = "Privacy review",
                subtitle = state.sourceName.ifBlank { "Medical report" },
                onBack = {
                    if (isProcessing || state.step is ReportStep.Review) {
                        state.reject()
                    }
                    onBack()
                }
            )
        }

        when (val step = state.step) {
            is ReportStep.Working -> {
                item { LoadingPanel(message = step.message) }
                item {
                    OutlinedButton(
                        onClick = {
                            state.reject()
                            onBack()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        contentPadding = PaddingValues(vertical = 13.dp)
                    ) {
                        Text(
                            text = "Cancel and discard this report",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }

            is ReportStep.Extracting -> {
                item {
                    LoadingPanel(message = "Extracting medical details from the redacted text…")
                }
                item {
                    OutlinedButton(
                        onClick = {
                            state.reject()
                            onBack()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        contentPadding = PaddingValues(vertical = 13.dp)
                    ) {
                        Text(
                            text = "Cancel and discard this report",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }

            ReportStep.Review -> {
                item { MaskingSummary(state = state) }
                state.fallbackNotice?.let { notice ->
                    item { FallbackNotice(text = notice) }
                }
                item { ReviewAlertsCard(state = state) }
                item { DetectedIdentifiersCard(state = state) }
                item { TextComparisonCard(state = state) }
                item { AddRedactionCard(state = state) }
                item { ApprovalSection(state = state, onApprove = { state.approve() }) }
                item {
                    OutlinedButton(
                        onClick = {
                            state.reject()
                            onBack()
                        },
                        enabled = !state.isBusy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        contentPadding = PaddingValues(vertical = 13.dp)
                    ) {
                        Text(
                            text = "Reject this report",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                item { CloudNotice() }
            }

            is ReportStep.Failed -> item {
                ErrorPanel(
                    title = if (step.cloudBlocked) {
                        "Cloud processing stopped"
                    } else {
                        "That report could not be prepared"
                    },
                    message = step.message,
                    actionLabel = "Choose another file",
                    onAction = {
                        state.reset()
                        onBack()
                    }
                )
            }

            else -> item {
                ErrorPanel(
                    title = "Nothing to review",
                    message = "Pick a medical report from My Health or scan one from the Scan tab.",
                    actionLabel = "Back",
                    onAction = onBack
                )
            }
        }
    }
}

@Composable
private fun MaskingSummary(state: ReportAnalysisState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AppIcons.ShieldCheck,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = "Masked on this device",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = buildString {
                        append("${state.pagesRead} page")
                        if (state.pagesRead != 1) append("s")
                        append(" read · ${state.replacements} identifier")
                        if (state.replacements != 1) append("s")
                        append(" replaced")
                        if (state.truncated) append(" · first ${state.pagesRead} pages used")
                        if (state.unreadablePages > 0) {
                            append(" · ${state.unreadablePages} page")
                            if (state.unreadablePages != 1) append("s")
                            append(" unreadable")
                        }
                        if (state.nanoAssistUsed) append(" · Gemini Nano assist applied")
                        if (state.manualRedactions > 0) {
                            append(" · ${state.manualRedactions} added by you")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ReviewAlertsCard(state: ReportAnalysisState) {
    val highRisk = state.alerts.count { it.severity == AlertSeverity.HIGH }
    val hasAlerts = state.alerts.isNotEmpty()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (highRisk > 0) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = if (highRisk > 0) 0.dp else 2.dp
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = when {
                    highRisk > 0 -> "Approval blocked"
                    hasAlerts -> "Check these before approving"
                    else -> "Nothing suspicious left"
                },
                style = MaterialTheme.typography.titleMedium,
                color = if (highRisk > 0) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )

            Text(
                text = when {
                    highRisk > 0 ->
                        "$highRisk identifier" + (if (highRisk == 1) " is" else "s are") +
                            " still unmasked. Add the missed text below, or reject the " +
                            "report. Nothing is sent while this is unresolved."

                    hasAlerts ->
                        "These are review hints, not blockers: check them against the paper " +
                            "report and hide anything that identifies you."

                    else ->
                        "No unmasked identifier pattern was found, and the numbers in this " +
                            "document look consistent."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (highRisk > 0) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 6.dp)
            )

            state.alerts.forEach { alert ->
                AlertRow(alert)
            }
        }
    }
}

@Composable
private fun AlertRow(alert: PrivacyAlert) {
    val blocking = alert.severity == AlertSeverity.HIGH
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = AppIcons.AlertTriangle,
            contentDescription = null,
            tint = if (blocking) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp)
        )
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Text(
                text = buildString {
                    append(alert.title)
                    alert.lineNumber?.let { append(" · line $it") }
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (blocking) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            Text(
                text = alert.detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (blocking) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun DetectedIdentifiersCard(state: ReportAnalysisState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "Detected personal information",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (state.identifiers.isEmpty()) {
                Text(
                    text = "No personal identifiers were found in this document.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            } else {
                Text(
                    text = "Only the replaced placeholders below are kept. The original " +
                        "values were discarded on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )

                state.identifiers.forEach { identifier ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppChip(text = identifier.token)
                        Text(
                            text = identifier.category.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .padding(start = 10.dp)
                                .weight(1f)
                        )
                        Text(
                            text = if (identifier.occurrences == 1) {
                                "1 match"
                            } else {
                                "${identifier.occurrences} matches"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Side-by-side review: the redacted payload the model will receive, and the original
 * document layout with every masked value drawn as bullets. The protected copy is built
 * from token lengths on-device, so it never holds a readable identifier.
 */
@Composable
private fun TextComparisonCard(state: ReportAnalysisState) {
    var showOriginal by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "Compare with the original",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (showOriginal) {
                    "Original layout, kept on this device. Masked values appear as bullets, " +
                        "so the document can be checked without exposing them."
                } else {
                    "This is the exact text the cloud model will receive."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CompareTab(
                    label = "Redacted",
                    selected = !showOriginal,
                    onClick = { showOriginal = false }
                )
                CompareTab(
                    label = "Original (protected)",
                    selected = showOriginal,
                    onClick = { showOriginal = true }
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .heightIn(max = 320.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = if (showOriginal) {
                        state.protectedOriginalText.ifBlank { state.redactedText }
                    } else {
                        state.redactedText
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp)
                )
            }
        }
    }
}

@Composable
private fun CompareTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun AddRedactionCard(state: ReportAnalysisState) {
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "Missed something?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Type or paste a name, ID, phone number or address that should be " +
                    "hidden. It is masked here on this device and the text above is rebuilt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        error = null
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Text to hide") },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedButton(
                    onClick = {
                        val cleaned = value.trim()
                        if (cleaned.length < 2) {
                            error = "Enter at least 2 characters."
                        } else {
                            state.addRedaction(cleaned)
                            value = ""
                        }
                    },
                    enabled = !state.isBusy,
                    modifier = Modifier.padding(start = 10.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Hide", style = MaterialTheme.typography.labelLarge)
                }
            }

            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun ApprovalSection(state: ReportAnalysisState, onApprove: () -> Unit) {
    Column {
        if (state.approveBlocked) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = AppIcons.AlertTriangle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Approval is unavailable until the high-risk identifiers above " +
                            "are masked. Add them below, or reject the report.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
            }
        }

        Button(
            onClick = onApprove,
            enabled = state.canApprove,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            contentPadding = PaddingValues(vertical = 14.dp)
        ) {
            Text(
                text = "Approve redacted text and analyse",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun CloudNotice() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text = "Only the redacted text is sent to Gemini cloud for structured " +
                "extraction. The original document never leaves this device, and nothing is " +
                "sent until you tap the button.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        )
    }
}
