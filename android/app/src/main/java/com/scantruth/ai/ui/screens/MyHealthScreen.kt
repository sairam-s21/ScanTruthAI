package com.scantruth.ai.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scantruth.ai.model.ReportRecord
import com.scantruth.ai.ui.components.AppCard
import com.scantruth.ai.ui.components.AppChip
import com.scantruth.ai.ui.components.AppIcons
import com.scantruth.ai.ui.components.ErrorPanel
import com.scantruth.ai.ui.components.IconTile
import com.scantruth.ai.ui.components.LoadingPanel
import com.scantruth.ai.ui.components.PrivacyNoteCard
import com.scantruth.ai.ui.components.SectionHeader
import com.scantruth.ai.ui.components.documentDisplayName
import com.scantruth.ai.ui.components.formatShortDate
import com.scantruth.ai.ui.state.MyHealthUiState
import com.scantruth.ai.ui.state.ProfileCategory
import com.scantruth.ai.ui.state.ReportAnalysisState
import com.scantruth.ai.ui.theme.Brand

/**
 * My Health screen matching the web application's visual styling, terminology,
 * card hierarchy and user interaction flow.
 */
@Composable
fun MyHealthScreen(
    state: MyHealthUiState,
    reportState: ReportAnalysisState,
    onOpenHistory: () -> Unit,
    onReviewReport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var dialogCategory by remember { mutableStateOf<ProfileCategory?>(null) }
    var dialogDraft by remember { mutableStateOf("") }

    // A picked report waits here until the user explicitly starts the analysis.
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingName by remember { mutableStateOf("") }

    var editingConditions by remember { mutableStateOf(false) }
    var editingCategories by remember { mutableStateOf(setOf<ProfileCategory>()) }

    LaunchedEffect(Unit) {
        state.start(scope)
        state.load(scope)
    }

    DisposableEffect(state) {
        onDispose {
            state.clearSuccessMessage()
        }
    }

    val documentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingUri = uri
            pendingName = documentDisplayName(context, uri, "Medical report")
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Screen header
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "My Health",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.4).sp
                    ),
                    color = Brand.TextPrimary
                )
                Text(
                    text = "Personal health context for tailored scan verdicts and saved reports.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.TextSecondary
                )
            }
        }

        // Status banner when busy or after an action
        val itemMessage = state.itemMessage
        if (state.isValidatingItem || itemMessage != null) {
            item {
                ItemStatusBanner(
                    busy = state.isValidatingItem,
                    message = itemMessage ?: "Working…"
                )
            }
        }

        // Hero card: Build Your Health Context
        item {
            HealthContextBanner(
                itemCount = if (state.isSignedIn) state.profile.itemCount else 0,
                signedIn = state.isSignedIn
            )
        }

        if (!state.isSignedIn) {
            item { SignedOutCard() }
        }

        if (state.isSignedIn && state.isLoading) {
            item { LoadingPanel(message = "Loading your health profile…") }
        }

        state.error?.let { message ->
            item {
                ErrorPanel(
                    title = "Could not load your profile",
                    message = message,
                    actionLabel = "Try again",
                    onAction = { state.load(scope) }
                )
            }
        }

        if (state.isSignedIn) {
            val conditions = state.profile.conditions

            // Known Conditions section (matches Web Known Conditions)
            item {
                SectionHeader(
                    title = "Known Conditions",
                    actionLabel = if (conditions.isNotEmpty()) (if (editingConditions) "Done" else "Edit") else null,
                    onAction = { editingConditions = !editingConditions }
                )
            }

            item {
                KnownConditionsCard(
                    conditions = conditions,
                    editing = editingConditions,
                    isSaving = state.isSavingProfile,
                    onAdd = {
                        dialogCategory = ProfileCategory.CONDITIONS
                        dialogDraft = ""
                    },
                    onRemove = { condition ->
                        state.removeItem(scope, ProfileCategory.CONDITIONS, condition)
                    }
                )
            }

            // Health Context section: Allergies, Medications, Dietary restrictions
            item {
                SectionHeader(title = "Health Context")
            }

            val contextCategories = listOf(
                ProfileCategory.ALLERGIES to AppIcons.AlertTriangle,
                ProfileCategory.MEDICATIONS to AppIcons.Pill,
                ProfileCategory.DIETARY_RESTRICTIONS to AppIcons.Leaf
            )

            contextCategories.forEach { (category, icon) ->
                item(key = category.name) {
                    val isEditing = editingCategories.contains(category)
                    CategoryCard(
                        category = category,
                        icon = icon,
                        items = state.itemsFor(category),
                        isSaving = state.isSavingProfile,
                        isEditing = isEditing,
                        onToggleEdit = {
                            editingCategories = if (isEditing) {
                                editingCategories - category
                            } else {
                                editingCategories + category
                            }
                        },
                        onAdd = {
                            dialogCategory = category
                            dialogDraft = ""
                        },
                        onRemove = { item -> state.removeItem(scope, category, item) }
                    )
                }
            }

            // Health notes card (matches Web Health notes assistant)
            item {
                HealthNotesCard(
                    input = state.aiAssistantInput,
                    isAnalyzing = state.isAiAssistantAnalyzing,
                    clarificationMessage = state.aiAssistantClarification,
                    errorMessage = state.aiAssistantError,
                    successMessage = state.aiAssistantSuccessMessage,
                    onInputChange = state::updateAiAssistantInput,
                    onAnalyze = { state.analyzeHealthStatement(scope) },
                    onClearInput = state::clearAiAssistant
                )
            }

            // Medical Documents section
            item {
                SectionHeader(
                    title = "Medical Documents",
                    actionLabel = if (state.reports.isNotEmpty()) "History" else null,
                    onAction = onOpenHistory
                )
            }

            // Uploaded documents list
            items(count = state.reports.size, key = { index -> state.reports[index].id }) { index ->
                ReportRow(record = state.reports[index])
            }

            // Pending document card if selected
            val pickedUri = pendingUri
            if (pickedUri != null) {
                item {
                    PendingReportCard(
                        fileName = pendingName,
                        isBusy = reportState.isBusy,
                        onStart = {
                            reportState.start(
                                context = context,
                                uri = pickedUri,
                                displayName = pendingName
                            )
                            pendingUri = null
                            pendingName = ""
                            onReviewReport()
                        },
                        onRemove = {
                            pendingUri = null
                            pendingName = ""
                        }
                    )
                }
            }

            // Add Medical Report Card
            item {
                MedicalReportUploadCard(
                    isBusy = reportState.isBusy,
                    hasReports = state.reports.isNotEmpty(),
                    onAddReport = {
                        documentLauncher.launch(arrayOf("application/pdf", "image/*"))
                    }
                )
            }
        }

        // Privacy note card matching web
        item {
            PrivacyNoteCard(
                title = "Your privacy is protected",
                body = "Gemini Nano checks and masks private details on your device before anything is sent to the cloud, and only after you approve the redacted text."
            )
        }
    }

    // Add manual item dialog
    val category = dialogCategory
    if (category != null) {
        AlertDialog(
            onDismissRequest = { dialogCategory = null },
            title = {
                Text(
                    text = category.addTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Brand.TextPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Add one item. It is stored in your own account profile and used to compare scans.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Brand.TextSecondary
                    )
                    OutlinedTextField(
                        value = dialogDraft,
                        onValueChange = { dialogDraft = it },
                        singleLine = true,
                        placeholder = { Text(category.placeholder, color = Brand.TextTertiary) },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Brand.Primary,
                            unfocusedBorderColor = Brand.Divider,
                            cursorColor = Brand.Primary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.addItem(scope, category, dialogDraft)
                        dialogCategory = null
                    },
                    enabled = dialogDraft.isNotBlank() && !state.isSavingProfile && !state.isValidatingItem
                ) {
                    Text(
                        text = "Add",
                        fontWeight = FontWeight.Bold,
                        color = Brand.Primary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogCategory = null }) {
                    Text("Cancel", color = Brand.TextSecondary)
                }
            }
        )
    }
}

/** Status banner matching web .status-banner */
@Composable
private fun ItemStatusBanner(busy: Boolean, message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Brand.Soft.copy(alpha = 0.85f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(16.dp),
                    strokeWidth = 2.dp,
                    color = Brand.Primary
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = Brand.TextSecondary
            )
        }
    }
}

/** Hero banner matching web .hero-card.column */
@Composable
private fun HealthContextBanner(itemCount: Int, signedIn: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(Brand.Deep, Brand.Primary),
                    start = androidx.compose.ui.geometry.Offset.Zero,
                    end = androidx.compose.ui.geometry.Offset(1100f, 700f)
                )
            )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = AppIcons.HeartFile,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Text(
                    text = "Build Your Health Context",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = Color.White
                )
            }

            Text(
                text = "The more your profile knows, the more personal every ingredient verdict is. All data stays strictly in your own account.",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = Color.White.copy(alpha = 0.85f)
            )

            Surface(
                shape = RoundedCornerShape(100),
                color = Color.White.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(Brand.MintBorder, CircleShape)
                    )
                    Text(
                        text = when {
                            !signedIn -> "Sign in to start"
                            itemCount == 0 -> "No details added yet"
                            itemCount == 1 -> "1 detail added"
                            else -> "$itemCount details added"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = Color.White
                    )
                }
            }
        }
    }
}

/** Signed out notice card */
@Composable
private fun SignedOutCard() {
    AppCard {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconTile(icon = AppIcons.Lock, size = 40)
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    text = "Sign in to use My Health",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Brand.TextPrimary
                )
                Text(
                    text = "Conditions, allergies, medications and reports live in your own account. Sign in from the Profile tab, then come back here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.TextSecondary,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}

/** Known Conditions card matching web Known Conditions section */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KnownConditionsCard(
    conditions: List<String>,
    editing: Boolean,
    isSaving: Boolean,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit
) {
    AppCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconTile(icon = AppIcons.Heart, size = 44)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (conditions.isNotEmpty()) "Recorded conditions" else "No known conditions recorded",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = Brand.TextPrimary
                    )
                    Text(
                        text = if (conditions.isNotEmpty()) {
                            "These personalize every scan analysis."
                        } else {
                            "Add conditions to make every scan analysis personalized and relevant."
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 13.sp,
                            color = Brand.TextSecondary
                        )
                    )
                }
            }

            if (conditions.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    conditions.forEach { condition ->
                        AppChip(
                            text = condition,
                            onRemove = if (editing && !isSaving) ({ onRemove(condition) }) else null
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onAdd,
                enabled = !isSaving,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Brand.Primary.copy(alpha = 0.25f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Primary),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp)
            ) {
                Icon(
                    imageVector = AppIcons.Plus,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "Add a condition",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }
    }
}

/** Health Context card for Allergies, Medications, and Dietary restrictions */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryCard(
    category: ProfileCategory,
    icon: ImageVector,
    items: List<String>,
    isSaving: Boolean,
    isEditing: Boolean,
    onToggleEdit: () -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit
) {
    AppCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                IconTile(icon = icon, size = 40)
                Text(
                    text = category.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = Brand.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (items.isNotEmpty()) {
                    TextButton(
                        onClick = onToggleEdit,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isEditing) "Done" else "Edit",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = Brand.Primary
                        )
                        Icon(
                            imageVector = if (isEditing) AppIcons.Check else AppIcons.Pencil,
                            contentDescription = null,
                            tint = Brand.Primary,
                            modifier = Modifier
                                .padding(start = 3.dp)
                                .size(12.dp)
                        )
                    }
                }
            }

            if (items.isEmpty()) {
                Text(
                    text = "Nothing recorded yet. Add items so scans check for them.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 13.sp,
                        color = Brand.TextSecondary
                    )
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items.forEach { item ->
                        AppChip(
                            text = item,
                            onRemove = if (isEditing && !isSaving) ({ onRemove(item) }) else null
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onAdd,
                enabled = !isSaving,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Brand.Primary.copy(alpha = 0.25f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Primary),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp)
            ) {
                Icon(
                    imageVector = AppIcons.Plus,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = category.addTitle,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }
    }
}

/**
 * Health notes card matching Web "Health notes" section and user requirement:
 * Button label: "Add Notes"
 * Placeholder: "Add a note"
 * Heading: "Health notes"
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HealthNotesCard(
    input: String,
    isAnalyzing: Boolean,
    clarificationMessage: String?,
    errorMessage: String?,
    successMessage: String?,
    onInputChange: (String) -> Unit,
    onAnalyze: () -> Unit,
    onClearInput: () -> Unit
) {
    AppCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Health notes",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = Brand.TextPrimary
            )
            Text(
                text = "Anything else that should be considered, e.g. a recent diagnosis or a symptom that is not in the lists above.",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    color = Brand.TextSecondary,
                    lineHeight = 16.sp
                )
            )

            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = "Add a note",
                        color = Brand.TextTertiary,
                        fontSize = 14.sp
                    )
                },
                minLines = 3,
                maxLines = 5,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Brand.Primary,
                    unfocusedBorderColor = Brand.Divider,
                    cursorColor = Brand.Primary,
                    focusedTextColor = Brand.TextPrimary,
                    unfocusedTextColor = Brand.TextPrimary
                ),
                trailingIcon = {
                    if (input.isNotEmpty()) {
                        IconButton(onClick = onClearInput) {
                            Icon(
                                imageVector = AppIcons.Close,
                                contentDescription = "Clear input",
                                tint = Brand.TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            )

            // Example suggestions to make typing notes effortless
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Tap to try an example:",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Brand.TextSecondary
                    )
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val examples = listOf(
                        "I have diabetes",
                        "Allergic to peanuts",
                        "I take vitamin D",
                        "Lactose intolerant",
                        "Recovered from tuberculosis"
                    )
                    examples.forEach { example ->
                        Surface(
                            shape = RoundedCornerShape(100),
                            color = Brand.Soft,
                            onClick = {
                                val nextText = if (input.isBlank()) {
                                    example
                                } else {
                                    "$input. $example"
                                }
                                onInputChange(nextText)
                            }
                        ) {
                            Text(
                                text = example,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = Brand.Primary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            // Primary action button: Exact Web wording "Add Notes"
            Button(
                onClick = onAnalyze,
                enabled = input.isNotBlank() && !isAnalyzing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Brand.Primary,
                    contentColor = Color.White
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
            ) {
                if (isAnalyzing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "Adding notes…",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                } else {
                    Icon(
                        imageVector = AppIcons.Sparkle,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                    Text(
                        text = "Add Notes",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            // Fine print matching web
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Icon(
                    imageVector = AppIcons.Lock,
                    contentDescription = null,
                    tint = Brand.Primary,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(12.dp)
                )
                Text(
                    text = "Your note is masked on this device and shown to you for review before anything is sent to the cloud.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        color = Brand.TextSecondary,
                        lineHeight = 15.sp
                    )
                )
            }

            // Clarification banner if needed
            if (clarificationMessage != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Brand.WarningSoft,
                    border = BorderStroke(1.dp, Brand.Warning.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = AppIcons.AlertTriangle,
                            contentDescription = null,
                            tint = Brand.Warning,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = clarificationMessage,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Brand.Warning,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                }
            }

            // Error banner if any
            if (errorMessage != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Brand.DangerSoft,
                    border = BorderStroke(1.dp, Brand.Danger.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = AppIcons.AlertTriangle,
                            contentDescription = null,
                            tint = Brand.Danger,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Brand.OnDangerSoft
                            )
                        )
                    }
                }
            }

            // Success banner (auto-dismisses after 4.5 seconds)
            if (successMessage != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Brand.Soft,
                    border = BorderStroke(1.dp, Brand.Secondary.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = AppIcons.CheckCircle,
                            contentDescription = null,
                            tint = Brand.Primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = successMessage,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Brand.Primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            }
        }
    }
}

/** Medical document upload card matching web */
@Composable
private fun MedicalReportUploadCard(
    isBusy: Boolean,
    hasReports: Boolean,
    onAddReport: () -> Unit
) {
    AppCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!hasReports) {
                Text(
                    text = "No medical reports uploaded.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 13.sp,
                        color = Brand.TextSecondary
                    )
                )
            }
            OutlinedButton(
                onClick = onAddReport,
                enabled = !isBusy,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Brand.Primary.copy(alpha = 0.25f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Primary),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp)
            ) {
                Icon(
                    imageVector = AppIcons.FileUp,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Add a medical report",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
            Text(
                text = "PDF or photo. Private details are masked on this device first.",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    color = Brand.TextSecondary
                )
            )
        }
    }
}

/** Pending Report Card waiting for explicit analysis */
@Composable
private fun PendingReportCard(
    fileName: String,
    isBusy: Boolean,
    onStart: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        onClick = onStart,
        enabled = !isBusy,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Brand.Card,
        border = BorderStroke(1.5.dp, Brand.Primary),
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(icon = AppIcons.Document, size = 42, deep = true)
                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(
                        text = "Ready to analyse · Tap to start",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Brand.Primary
                    )
                    Text(
                        text = fileName.ifBlank { "Selected document" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Brand.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            Text(
                text = "Nothing has been read yet. Start the analysis to read the document on this device, mask identifiers and review the text before anything is sent.",
                style = MaterialTheme.typography.bodySmall,
                color = Brand.TextSecondary,
                modifier = Modifier.padding(top = 10.dp)
            )

            Button(
                onClick = onStart,
                enabled = !isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Brand.Primary,
                    contentColor = Color.White
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
            ) {
                Icon(
                    imageVector = AppIcons.Sparkle,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Analyse Report",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            TextButton(
                onClick = onRemove,
                enabled = !isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
            ) {
                Text(
                    text = "Remove file",
                    style = MaterialTheme.typography.labelMedium,
                    color = Brand.TextSecondary
                )
            }
        }
    }
}

/** Report Row matching web */
@Composable
private fun ReportRow(record: ReportRecord) {
    AppCard {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconTile(icon = AppIcons.Document, size = 40)

            Column(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .weight(1f)
            ) {
                Text(
                    text = record.reportTitle,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = Brand.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(
                        record.reportType.takeIf { it.isNotBlank() },
                        "${record.markersCount} markers",
                        "${record.identifiersMasked} masked",
                        formatShortDate(record.savedAtMillis).takeIf { it.isNotBlank() }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        color = Brand.TextSecondary
                    ),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Surface(
                shape = RoundedCornerShape(100),
                color = Brand.Soft
            ) {
                Text(
                    text = "Saved",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = Brand.Primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}
