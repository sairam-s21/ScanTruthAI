package com.scantruth.ai.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scantruth.ai.model.AlertSeverity
import com.scantruth.ai.ui.components.AppCard
import com.scantruth.ai.ui.components.AppIcons
import com.scantruth.ai.ui.components.IconTile
import com.scantruth.ai.ui.components.SectionHeader
import com.scantruth.ai.ui.components.SoftBanner
import com.scantruth.ai.ui.components.documentDisplayName
import com.scantruth.ai.ui.state.MyHealthUiState
import com.scantruth.ai.ui.state.ProfileCategory
import com.scantruth.ai.ui.state.ReportAnalysisState
import com.scantruth.ai.ui.state.ReportStep
import com.scantruth.ai.ui.theme.Brand

/** My Health, laid out like the website: conditions, context cards, notes and documents. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MyHealthScreen(
    state: MyHealthUiState,
    reportState: ReportAnalysisState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var addCategory by remember { mutableStateOf<ProfileCategory?>(null) }

    val documentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Generic label on purpose: file names often contain the patient's name.
            val name = documentDisplayName(context, uri, "Medical report")
            val label = if (name.lowercase().endsWith(".pdf")) "Medical report (PDF)" else "Medical report (image)"
            reportState.start(context, uri, label)
        }
    }
    val pickReport = { documentLauncher.launch(arrayOf("application/pdf", "image/*")) }

    LaunchedEffect(Unit) { state.start(scope); state.load(scope) }
    DisposableEffect(Unit) { onDispose { state.stop() } }
    LaunchedEffect(state.reportPickerRequested) {
        if (state.reportPickerRequested) {
            state.onReportPickerOpened()
            pickReport()
        }
    }

    val step = reportState.step
    val reportStatus = when (step) {
        is ReportStep.Working -> step.message
        ReportStep.Extracting, ReportStep.ExtractionReady -> "Extracting health markers..."
        is ReportStep.Failed -> step.message
        ReportStep.Saved -> reportState.savedMessage
        else -> null
    }
    val processing = state.isProcessing || reportState.isBusy
    val status = reportStatus ?: state.status

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("My Health", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    "Personal health context for tailored scan verdicts and saved reports.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (processing || status != null) {
            item { StatusBanner(text = status ?: "Working...", spinning = processing) }
        }

        item { HealthHeroCard(detailCount = state.detailCount) }

        item {
            val category = ProfileCategory.CONDITIONS
            val items = state.itemsFor(category)
            val editing = state.isEditing(category)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (items.isEmpty()) {
                    SectionHeader(title = category.title)
                } else {
                    SectionHeader(
                        title = category.title,
                        actionLabel = if (editing) "Done" else "Edit",
                        actionIcon = if (editing) AppIcons.Check else AppIcons.Pencil,
                        onAction = { state.toggleEditing(category) }
                    )
                }
                AppCard {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            IconTile(icon = AppIcons.HeartPulse)
                            Column(modifier = Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    if (items.isEmpty()) "No known conditions recorded" else "Recorded conditions",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    if (items.isEmpty()) {
                                        "Add conditions to make every scan analysis personalized and relevant."
                                    } else {
                                        "These personalize every scan analysis."
                                    },
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (items.isNotEmpty()) {
                            ChipList(items, editing) { state.removeItem(scope, category, it) }
                        }
                        SmallOutlinedButton(category.addTitle, AppIcons.Plus) { addCategory = category }
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(title = "Health Context")
                ProfileCategory.CONTEXT_CARDS.forEach { category ->
                    val items = state.itemsFor(category)
                    val editing = state.isEditing(category)
                    AppCard {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconTile(icon = categoryIcon(category), size = 40)
                                Text(
                                    category.title,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(start = 12.dp).weight(1f)
                                )
                                if (items.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { state.toggleEditing(category) }
                                            .padding(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            if (editing) "Done" else "Edit",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Brand.Primary
                                        )
                                        Icon(
                                            imageVector = if (editing) AppIcons.Check else AppIcons.Pencil,
                                            contentDescription = null,
                                            tint = Brand.Primary,
                                            modifier = Modifier.padding(start = 4.dp).size(12.dp)
                                        )
                                    }
                                }
                            }
                            if (items.isEmpty()) {
                                Text(
                                    "Nothing recorded yet. Add items so scans check for them.",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                ChipList(items, editing) { state.removeItem(scope, category, it) }
                            }
                            SmallOutlinedButton(category.addTitle, AppIcons.Plus) { addCategory = category }
                        }
                    }
                }
            }
        }

        item {
            AppCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Health notes", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Anything else that should be considered, e.g. a recent diagnosis or a symptom that is not in the lists above.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = state.noteDraft,
                        onValueChange = state::updateNoteDraft,
                        placeholder = { Text("Add a note") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                        shape = RoundedCornerShape(12.dp),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        minLines = 3
                    )
                    FilledActionButton(
                        text = "Save note",
                        enabled = !processing && state.noteDraft.isNotBlank(),
                        onClick = {
                            reportState.startNote(state.noteDraft)
                            state.updateNoteDraft("")
                        }
                    )
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(AppIcons.Lock, contentDescription = null, tint = Brand.Primary, modifier = Modifier.padding(top = 1.dp).size(12.dp))
                        Text(
                            "Your note is masked on this device and shown to you for review before anything is sent to the cloud.",
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(title = "Medical Documents")
                state.reportTitles.forEach { title ->
                    AppCard {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconTile(icon = AppIcons.Document, size = 40)
                            Text(
                                title,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                }
                AppCard {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.reportTitles.isEmpty()) {
                            Text("No medical reports uploaded.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        SmallOutlinedButton("Add a medical report", AppIcons.Document, enabled = !processing, onClick = pickReport)
                        Text(
                            "PDF or photo. Private details are masked on this device first.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            SoftBanner(
                icon = AppIcons.ShieldCheck,
                title = "Your privacy is protected",
                body = "Gemini Nano checks and masks private details on your device before anything is sent to the cloud, and only after you approve the redacted text."
            )
        }
    }

    addCategory?.let { category ->
        AddItemDialog(
            category = category,
            onDismiss = { addCategory = null },
            onAdd = { text ->
                addCategory = null
                state.addItem(scope, category, text)
            }
        )
    }

    if (reportState.step == ReportStep.Review) {
        PrivacyReviewSheet(state = reportState, onCancel = { reportState.reject() }, onApprove = { reportState.approve() })
    }
}

private fun categoryIcon(category: ProfileCategory): ImageVector = when (category) {
    ProfileCategory.ALLERGIES -> AppIcons.AlertTriangle
    ProfileCategory.MEDICATIONS -> AppIcons.Pill
    ProfileCategory.DIETARY_RESTRICTIONS -> AppIcons.Leaf
    else -> AppIcons.HeartPulse
}

@Composable
private fun StatusBanner(text: String, spinning: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Brand.Soft.copy(alpha = 0.7f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (spinning) {
            CircularProgressIndicator(color = Brand.Primary, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        }
        Text(
            text,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = if (spinning) 10.dp else 0.dp)
        )
    }
}

@Composable
private fun HealthHeroCard(detailCount: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Brand.Primary, Brand.Deep)))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(AppIcons.HeartPulse, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Text(
                "Build Your Health Context",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
        Text(
            "The more your profile knows, the more personal every ingredient verdict is. All data stays strictly in your own account.",
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = Color.White.copy(alpha = 0.85f)
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.12f))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(6.dp).background(Brand.Outline, CircleShape))
            Text(
                when (detailCount) {
                    0 -> "No details added yet"
                    1 -> "1 detail added"
                    else -> "$detailCount details added"
                },
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }
}

/** Mint chips; in edit mode they turn pale red with a remove button, as on iOS and the web. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipList(items: List<String>, editing: Boolean, onDelete: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (editing) Color(0x12FF3B30) else Brand.Soft)
                    .padding(start = 12.dp, end = if (editing) 8.dp else 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(item, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                if (editing) {
                    Icon(
                        imageVector = AppIcons.RemoveCircle,
                        contentDescription = "Remove $item",
                        tint = Color(0xCCFF3B30),
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .clickable { onDelete(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallOutlinedButton(
    text: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Brand.Primary.copy(alpha = 0.25f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Brand.Primary),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun AddItemDialog(category: ProfileCategory, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text(category.addTitle, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(category.placeholder) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAdd(text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", fontSize = 16.sp, color = Brand.Primary) }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(text) }) {
                Text("Add", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Brand.Primary)
            }
        }
    )
}

/**
 * Shows exactly what will be sent after on-device masking. Nothing leaves the device until
 * the user taps "Approve & Extract Profile".
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PrivacyReviewSheet(state: ReportAnalysisState, onCancel: () -> Unit, onApprove: () -> Unit) {
    val identifiers = state.identifiers
    val header = if (state.usedFallback) {
        state.fallbackNotice ?: "Gemini Nano is unavailable, so a rule-based filter masked the text on your device."
    } else {
        "Gemini Nano masked personal identifiers locally. Only the text below is sent for analysis."
    }
    val blocking = state.alerts.filter { it.severity == AlertSeverity.HIGH }
    val warnings = state.alerts.filter { it.severity != AlertSeverity.HIGH }

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterStart)) {
                    Text("Cancel", fontSize = 16.sp, color = Brand.Primary)
                }
                Text(
                    "Privacy Review",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brand.Soft)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.ShieldCheck, contentDescription = null, tint = Brand.Primary, modifier = Modifier.size(22.dp))
                        Text(
                            "On-Device Privacy Gate",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                    Text(header, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (identifiers.isEmpty()) {
                    WarningLine("No private details were detected. Read the text below carefully before approving.")
                }
                (blocking + warnings).forEach { alert ->
                    WarningLine("${alert.title}. ${alert.detail}", danger = alert.severity == AlertSeverity.HIGH)
                }

                if (identifiers.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CapsLabel("MASKED DETAILS (${identifiers.size})")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            identifiers.forEach { id ->
                                Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.05f)) {
                                    Text(
                                        "${id.category.label}: ${id.token}",
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CapsLabel("TEXT THAT WILL BE SENT")
                    Text(
                        state.redactedText,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .padding(12.dp)
                    )
                }

                FilledActionButton(
                    text = "Approve & Extract Profile",
                    icon = AppIcons.Check,
                    enabled = state.canApprove,
                    onClick = onApprove
                )
            }
        }
    }
}

@Composable
private fun CapsLabel(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WarningLine(text: String, danger: Boolean = false) {
    val tone = if (danger) Color(0xFFD9363E) else Color(0xFFFF9500)
    Row(verticalAlignment = Alignment.Top) {
        Icon(AppIcons.AlertTriangle, contentDescription = null, tint = tone, modifier = Modifier.padding(top = 1.dp).size(14.dp))
        Text(text, fontSize = 12.sp, color = tone, modifier = Modifier.padding(start = 6.dp))
    }
}
