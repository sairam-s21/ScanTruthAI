package com.scantruth.ai.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.scantruth.ai.ui.components.formatTimestamp
import com.scantruth.ai.ui.theme.Brand
import kotlinx.coroutines.launch

/**
 * Every scan saved to the signed-in account, including scans made in the iOS and web apps.
 * A bottom sheet, as on the website.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanHistorySheet(onDismiss: () -> Unit, onChanged: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<ScanRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var openRecord by remember { mutableStateOf<ScanRecord?>(null) }
    val signedIn = FirestoreRepository.isSignedIn()

    LaunchedEffect(Unit) {
        if (!signedIn) {
            isLoading = false
            return@LaunchedEffect
        }
        when (val result = FirestoreRepository.loadRecentScans(limit = 50)) {
            is StoreResult.Success -> records = result.value
            is StoreResult.Failure -> error = result.message
        }
        isLoading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Text(
                    text = "Scan history",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center)
                )
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Text("Done", fontWeight = FontWeight.SemiBold, color = Brand.Primary)
                }
            }

            when {
                !signedIn -> HistoryMessage(
                    "Sign in from the Profile tab to see scans saved to your account, including ones from the iOS and web apps."
                )
                isLoading -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Brand.Primary)
                }
                records.isEmpty() && error != null -> HistoryMessage(error!!)
                records.isEmpty() -> HistoryMessage(
                    "No saved scans yet. Scans you make in any ScanTruthAI app will show up here."
                )
                else -> LazyColumn(
                    modifier = Modifier.heightIn(max = 2000.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp
                    )
                ) {
                    error?.let { message ->
                        item {
                            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    items(count = records.size, key = { records[it].id }) { index ->
                        val record = records[index]
                        HistoryRow(
                            record = record,
                            onOpen = { openRecord = record },
                            onDelete = {
                                scope.launch {
                                    when (val result = FirestoreRepository.deleteScan(record.id)) {
                                        is StoreResult.Success -> {
                                            records = records.filterNot { it.id == record.id }
                                            onChanged()
                                        }
                                        is StoreResult.Failure -> error = "Could not delete that scan: ${result.message}"
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    openRecord?.let { record ->
        SavedAnalysisSheet(record = record, onDismiss = { openRecord = null })
    }
}

@Composable
private fun HistoryMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp)
    )
}

@Composable
private fun HistoryRow(record: ScanRecord, onOpen: () -> Unit, onDelete: () -> Unit) {
    val tone = scoreTone(record.safetyScore)
    val detail = listOfNotNull(
        formatTimestamp(record.scannedAtMillis).takeIf { it.isNotBlank() },
        record.ingredientCount?.takeIf { it > 0 }?.let { "$it ingredients" }
    ).joinToString(" · ")

    AppCard {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f).clickable(onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
                    CircularProgressIndicator(
                        progress = { 1f },
                        modifier = Modifier.size(48.dp),
                        color = tone.copy(alpha = 0.2f),
                        strokeWidth = 5.dp
                    )
                    CircularProgressIndicator(
                        progress = { (record.safetyScore ?: 0) / 100f },
                        modifier = Modifier.size(48.dp),
                        color = tone,
                        strokeWidth = 5.dp,
                        trackColor = Color.Transparent,
                        strokeCap = StrokeCap.Round
                    )
                    Text(record.safetyScore?.toString() ?: "–", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(
                        text = record.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    record.displayBadge?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = tone)
                    }
                    if (detail.isNotBlank()) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Icon(
                    imageVector = AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = AppIcons.Trash,
                    contentDescription = "Delete ${record.title}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
