package com.scantruth.ai.ui.screens

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.scantruth.ai.FirestoreRepository
import com.scantruth.ai.FirestoreRepository.StoreResult
import com.scantruth.ai.model.ScanKind
import com.scantruth.ai.model.ScanRecord
import com.scantruth.ai.ui.components.AppChip
import com.scantruth.ai.ui.components.AppIcons
import com.scantruth.ai.ui.components.ErrorPanel
import com.scantruth.ai.ui.components.LoadingPanel
import com.scantruth.ai.ui.components.ScreenHeader
import com.scantruth.ai.ui.components.formatTimestamp

/**
 * Full scan history. Reads the signed-in user's own records; a signed-out visitor is
 * told plainly that nothing was stored rather than being shown an empty fake list.
 */
@Composable
fun ScanHistoryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var records by remember { mutableStateOf<List<ScanRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var signedOut by remember { mutableStateOf(!FirestoreRepository.isSignedIn()) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        isLoading = true
        error = null
        signedOut = !FirestoreRepository.isSignedIn()

        when (val result = FirestoreRepository.loadRecentScans(limit = 50)) {
            is StoreResult.Success -> records = result.value
            is StoreResult.Failure -> {
                records = emptyList()
                if (!result.notSignedIn) error = result.message
            }
        }

        isLoading = false
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ScreenHeader(
                title = "Scan history",
                subtitle = when {
                    signedOut -> "Not signed in"
                    records.isEmpty() -> "No saved scans"
                    records.size == 1 -> "1 saved scan"
                    else -> "${records.size} saved scans"
                },
                onBack = onBack
            )
        }

        if (isLoading) {
            item { LoadingPanel(message = "Loading your scan history…") }
        }

        error?.let { message ->
            item {
                ErrorPanel(
                    title = "Could not load history",
                    message = message,
                    actionLabel = "Try again",
                    onAction = { reloadKey += 1 }
                )
            }
        }

        if (!isLoading && signedOut) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = "Scans run while signed out are never stored. Sign in from " +
                            "the Profile tab and your next scans appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        if (!isLoading && !signedOut && records.isEmpty() && error == null) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Text(
                        text = "Nothing here yet. A label analysis or a saved report will " +
                            "show up in this list.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        items(count = records.size, key = { index -> records[index].id }) { index ->
            HistoryRow(record = records[index])
        }
    }
}

@Composable
private fun HistoryRow(record: ScanRecord) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (record.kind == ScanKind.MEDICAL_REPORT) {
                            AppIcons.Document
                        } else {
                            AppIcons.Pill
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Column(
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .weight(1f)
                ) {
                    Text(
                        text = record.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (record.kind == ScanKind.MEDICAL_REPORT) {
                            "Medical report"
                        } else {
                            "Ingredient label"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                val score = record.safetyScore
                val badge = record.badgeLabel
                if (score != null && badge != null) {
                    AppChip(text = "$badge · $score")
                }
            }

            if (record.subtitle.isNotBlank()) {
                Text(
                    text = record.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }

            val when_ = formatTimestamp(record.scannedAtMillis)
            if (when_.isNotBlank()) {
                Text(
                    text = when_,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
