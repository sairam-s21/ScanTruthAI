package com.scantruth.ai.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scantruth.ai.FirestoreRepository
import com.scantruth.ai.FirestoreRepository.StoreResult
import com.scantruth.ai.model.ScanRecord
import com.scantruth.ai.ui.components.AppCard
import com.scantruth.ai.ui.components.AppIcons
import com.scantruth.ai.ui.components.ErrorPanel
import com.scantruth.ai.ui.components.LoadingPanel
import com.scantruth.ai.ui.components.ScreenHeader
import com.scantruth.ai.ui.components.formatTimestamp
import com.scantruth.ai.ui.theme.Brand
import kotlinx.coroutines.launch

/**
 * Scan history screen matching Web HistorySheet design and terminology.
 */
@Composable
fun ScanHistoryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<ScanRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var signedOut by remember { mutableStateOf(!FirestoreRepository.isSignedIn()) }
    var reloadKey by remember { mutableStateOf(0) }
    var openRecord by remember { mutableStateOf<ScanRecord?>(null) }

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
        verticalArrangement = Arrangement.spacedBy(12.dp)
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
                AppCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = AppIcons.Person,
                            contentDescription = null,
                            tint = Brand.TextSecondary,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = "Sign in from the Profile tab to see scans saved to your account, including ones from the iOS and Android apps.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Brand.TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        if (!isLoading && !signedOut && records.isEmpty() && error == null) {
            item {
                AppCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = AppIcons.Document,
                            contentDescription = null,
                            tint = Brand.TextSecondary,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = "No saved scans yet. Scans you make in any ScanTruthAI app will show up here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Brand.TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        items(count = records.size, key = { index -> records[index].id }) { index ->
            val record = records[index]
            HistoryRow(
                record = record,
                onClick = { openRecord = record },
                onDelete = {
                    scope.launch {
                        FirestoreRepository.deleteScanRecord(record.id)
                        reloadKey += 1
                    }
                }
            )
        }
    }

    openRecord?.let { record ->
        SavedAnalysisSheet(record = record, onDismiss = { openRecord = null })
    }
}

/** History row matching web .history-row */
@Composable
private fun HistoryRow(
    record: ScanRecord,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val score = record.safetyScore
    val tone = when {
        score == null -> Brand.Primary
        score >= 70 -> Brand.Good
        score >= 40 -> Brand.Care
        else -> Brand.Avoid
    }

    val detail = listOfNotNull(
        formatTimestamp(record.scannedAtMillis).takeIf { it.isNotBlank() },
        record.ingredientCount?.takeIf { it > 0 }?.let { "$it ingredients" }
    ).joinToString(" · ")

    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Clickable area to open the analysis sheet
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onClick),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ScoreRing matching web
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
                    CircularProgressIndicator(
                        progress = { 1f },
                        modifier = Modifier.size(48.dp),
                        color = tone.copy(alpha = 0.2f),
                        strokeWidth = 5.dp
                    )
                    CircularProgressIndicator(
                        progress = { (score ?: 0) / 100f },
                        modifier = Modifier.size(48.dp),
                        color = tone,
                        strokeWidth = 5.dp,
                        trackColor = Color.Transparent,
                        strokeCap = StrokeCap.Round
                    )
                    Text(
                        text = score?.toString() ?: "–",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Brand.TextPrimary
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = record.title.ifBlank { "Scanned product" },
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = Brand.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = record.displayBadge ?: record.badgeLabel ?: "Scan",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = tone
                    )
                    if (detail.isNotBlank()) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.sp
                            ),
                            color = Brand.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Icon(
                    imageVector = AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = Brand.TextTertiary,
                    modifier = Modifier.size(15.dp)
                )
            }

            // Trash button matching web .icon-only
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(36.dp)
            ) {
                Icon(
                    imageVector = AppIcons.Trash,
                    contentDescription = "Delete scan",
                    tint = Brand.TextTertiary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
