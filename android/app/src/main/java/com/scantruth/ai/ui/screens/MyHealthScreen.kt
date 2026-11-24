package com.scantruth.ai.ui.screens

import android.net.Uri
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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.scantruth.ai.model.MedicalProfile
import com.scantruth.ai.model.ReportRecord
import com.scantruth.ai.ui.components.AppCard
import com.scantruth.ai.ui.components.AppChip
import com.scantruth.ai.ui.components.AppIcons
import com.scantruth.ai.ui.components.EmptyStateCard
import com.scantruth.ai.ui.components.ErrorPanel
import com.scantruth.ai.ui.components.IconTile
import com.scantruth.ai.ui.components.LoadingPanel
import com.scantruth.ai.ui.components.SectionHeader
import com.scantruth.ai.ui.components.SoftBanner
import com.scantruth.ai.ui.components.documentDisplayName
import com.scantruth.ai.ui.components.formatShortDate
import com.scantruth.ai.ui.state.MyHealthUiState
import com.scantruth.ai.ui.state.ProfileCategory
import com.scantruth.ai.ui.state.ReportAnalysisState
import com.scantruth.ai.ui.theme.Brand

/**
 * My Health: the profile every analysis is compared with, plus the documents the user
 * has added. A report is only read after an explicit "Start report analysis" tap and
 * always passes through the privacy review before the cloud step.
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
    var conditionDraft by remember { mutableStateOf<List<String>>(emptyList()) }
    var conditionInput by remember { mutableStateOf("") }

    var editingCategories by remember { mutableStateOf(setOf<ProfileCategory>()) }
    var editingExistingItem by remember { mutableStateOf<Pair<ProfileCategory, String>?>(null) }
    var editingExistingDraft by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        state.start(scope)
        state.load(scope)
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
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                Text(
                    text = "My Health",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Personal health context for tailored scan verdicts and saved reports.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        item {
            HealthContextBanner(
                itemCount = if (state.isSignedIn) state.profile.itemCount else 0,
                signedIn = state.isSignedIn
            )
        }

        if (!state.isSignedIn) {
            item { SignedOutCard() }
        }

        val itemMessage = state.itemMessage
        if (state.isValidatingItem || itemMessage != null) {
            item {
                ItemStatusBanner(
                    busy = state.isValidatingItem,
                    message = itemMessage ?: "Working…"
                )
            }
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
            item {
                SectionHeader(title = "AI Health Assistant")
            }

            item {
                AiHealthAssistantCard(
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

            item {
                SectionHeader(
                    title = "Diseases",
                    actionLabel = if (editingConditions) "Cancel" else "Edit",
                    onAction = {
                        if (editingConditions) {
                            editingConditions = false
                        } else {
                            conditionDraft = state.profile.conditions
                            conditionInput = ""
                            editingConditions = true
                        }
                    }
                )
            }

            item {
                DiseasesCard(
                    conditions = if (editingConditions) conditionDraft else state.profile.conditions,
                    editing = editingConditions,
                    input = conditionInput,
                    isSaving = state.isSavingProfile,
                    onInputChange = { conditionInput = it },
                    onAdd = {
                        val added = conditionInput.split(',', ';', '\n')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .filterNot { new -> conditionDraft.any { it.equals(new, ignoreCase = true) } }
                        state.validateItems(scope, ProfileCategory.CONDITIONS, added) { valid ->
                            conditionDraft = (conditionDraft + valid).distinctBy { it.lowercase() }
                            conditionInput = ""
                        }
                    },
                    onEdit = { condition ->
                        editingExistingItem = ProfileCategory.CONDITIONS to condition
                        editingExistingDraft = condition
                    },
                    onRemove = { value ->
                        conditionDraft = conditionDraft.filterNot {
                            it.equals(value, ignoreCase = true)
                        }
                    },
                    onStartEditing = {
                        conditionDraft = state.profile.conditions
                        conditionInput = ""
                        editingConditions = true
                    },
                    onUpdate = {
                        state.updateCategory(scope, ProfileCategory.CONDITIONS, conditionDraft)
                        editingConditions = false
                    }
                )
            }

            item { SectionHeader(title = "Health Context") }

            ProfileCategory.ACTIVE_CATEGORIES
                .filter { it != ProfileCategory.CONDITIONS }
                .forEach { category ->
                    item(key = category.name) {
                        CategoryCard(
                            category = category,
                            items = state.itemsFor(category),
                            isSaving = state.isSavingProfile,
                            isEditing = editingCategories.contains(category),
                            onToggleEdit = {
                                editingCategories = if (editingCategories.contains(category)) {
                                    editingCategories - category
                                } else {
                                    editingCategories + category
                                }
                            },
                            onAdd = {
                                dialogCategory = category
                                dialogDraft = ""
                            },
                            onEditItem = { item ->
                                editingExistingItem = category to item
                                editingExistingDraft = item
                            },
                            onRemove = { value -> state.removeItem(scope, category, value) }
                        )
                    }
                }

            item {
                SectionHeader(
                    title = "Medical Documents",
                    actionLabel = "History",
                    onAction = onOpenHistory
                )
            }

            item {
                Button(
                    onClick = {
                        documentLauncher.launch(arrayOf("application/pdf", "image/*"))
                    },
                    enabled = !reportState.isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = AppIcons.Plus,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Add a Medical Report",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

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
                                displayName = pendingName.ifBlank { "Medical report" }
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

            if (reportState.isBusy) {
                item { LoadingPanel(message = "Preparing your report on this device…") }
            }

            if (state.reports.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = AppIcons.Document,
                        title = "No documents yet",
                        body = "Add a lab result or discharge summary. Only a title, type and " +
                            "marker count are kept after analysis."
                    )
                }
            } else {
                items(count = state.reports.size, key = { index -> state.reports[index].id }) { index ->
                    ReportRow(record = state.reports[index])
                }
            }

            item { LocalOnlyNotice() }
        }
    }

    val category = dialogCategory
    if (category != null) {
        AlertDialog(
            onDismissRequest = { dialogCategory = null },
            title = { Text("Add to ${category.title.lowercase()}") },
            text = {
                Column {
                    Text(
                        text = "Add one item. It is stored in your own Firestore profile and " +
                            "used only to compare scans.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = dialogDraft,
                        onValueChange = { dialogDraft = it },
                        singleLine = true,
                        label = { Text(category.singular.replaceFirstChar { it.uppercase() }) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.padding(top = 12.dp)
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
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogCategory = null }) { Text("Cancel") }
            }
        )
    }

    val existingItem = editingExistingItem
    if (existingItem != null) {
        val (cat, oldVal) = existingItem
        AlertDialog(
            onDismissRequest = { editingExistingItem = null },
            title = { Text("Edit ${cat.singular}") },
            text = {
                OutlinedTextField(
                    value = editingExistingDraft,
                    onValueChange = { editingExistingDraft = it },
                    singleLine = cat != ProfileCategory.HEALTH_NOTES,
                    label = { Text("Value") },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newDraft = editingExistingDraft.trim()
                        if (newDraft.isNotEmpty() && !newDraft.equals(oldVal, ignoreCase = true)) {
                            if (editingConditions && cat == ProfileCategory.CONDITIONS) {
                                conditionDraft = conditionDraft.map {
                                    if (it.equals(oldVal, ignoreCase = true)) newDraft else it
                                }.distinctBy { it.lowercase() }
                            } else {
                                val current = state.itemsFor(cat)
                                val updated = current.map {
                                    if (it.equals(oldVal, ignoreCase = true)) newDraft else it
                                }.distinctBy { it.lowercase() }
                                state.updateCategory(scope, cat, updated)
                            }
                        }
                        editingExistingItem = null
                    },
                    enabled = editingExistingDraft.isNotBlank() && !state.isSavingProfile
                ) {
                    Text("Update")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingExistingItem = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ItemStatusBanner(busy: Boolean, message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Brand.Soft
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HealthContextBanner(itemCount: Int, signedIn: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(Brand.Deep, Brand.Primary),
                    start = androidx.compose.ui.geometry.Offset.Zero,
                    end = androidx.compose.ui.geometry.Offset(1100f, 700f)
                )
            )
    ) {
        Column(modifier = Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(Color.White.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = AppIcons.HeartPulse,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Text(
                    text = "Build Your Health Context",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .weight(1f)
                )
            }

            Text(
                text = "The more your profile knows, the more personal every ingredient " +
                    "verdict is. All data stays strictly in your own account.",
                style = MaterialTheme.typography.bodySmall,
                color = Brand.TextOnDeepMuted,
                modifier = Modifier.padding(top = 10.dp)
            )

            Surface(
                shape = RoundedCornerShape(100),
                color = Color.White.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                modifier = Modifier.padding(top = 14.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(Brand.Secondary, CircleShape)
                    )
                    Text(
                        text = when {
                            !signedIn -> "Sign in to start"
                            itemCount == 0 -> "No details added yet"
                            itemCount == 1 -> "1 detail active"
                            else -> "$itemCount details active"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

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
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Conditions, allergies, medications and reports live in your own " +
                        "account. Sign in from the Profile tab, then come back here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiseasesCard(
    conditions: List<String>,
    editing: Boolean,
    input: String,
    isSaving: Boolean,
    onInputChange: (String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onRemove: (String) -> Unit,
    onStartEditing: () -> Unit,
    onUpdate: () -> Unit
) {
    AppCard {
        Column(modifier = Modifier.padding(20.dp)) {
            if (conditions.isEmpty() && !editing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(icon = AppIcons.HeartPulse, size = 40)
                    Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(
                            text = "No diseases recorded",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Add actual illnesses such as Diabetes, Asthma, Tuberculosis or Hypertension.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                OutlinedButton(
                    onClick = onStartEditing,
                    modifier = Modifier.padding(top = 16.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.Plus,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "Add a disease",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    conditions.forEach { condition ->
                        AppChip(
                            text = condition,
                            onEdit = if (editing && !isSaving) ({ onEdit(condition) }) else null,
                            onRemove = if (editing && !isSaving) {
                                ({ onRemove(condition) })
                            } else {
                                null
                            }
                        )
                    }
                }

                if (editing) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = onInputChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        label = { Text("Add a disease") },
                        placeholder = { Text("e.g. Type 2 diabetes, Hypertension, Asthma") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )

                    OutlinedButton(
                        onClick = onAdd,
                        enabled = input.isNotBlank() && !isSaving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = AppIcons.Plus,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "Add to diseases",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }

                    Button(
                        onClick = onUpdate,
                        enabled = !isSaving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                    ) {
                        Text(
                            text = if (isSaving) "Updating…" else "Update Diseases",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else if (conditions.isNotEmpty()) {
                    Text(
                        text = "Used to compare every ingredient list and report you analyse.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryCard(
    category: ProfileCategory,
    items: List<String>,
    isSaving: Boolean,
    isEditing: Boolean,
    onToggleEdit: () -> Unit,
    onAdd: () -> Unit,
    onEditItem: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    AppCard {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconTile(
                    icon = when (category) {
                        ProfileCategory.ALLERGIES -> AppIcons.AlertTriangle
                        ProfileCategory.MEDICATIONS -> AppIcons.Pill
                        else -> AppIcons.Leaf
                    },
                    size = 38
                )
                Text(
                    text = category.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .weight(1f)
                )
                if (items.isNotEmpty()) {
                    TextButton(
                        onClick = onToggleEdit,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = if (isEditing) AppIcons.Check else AppIcons.Pencil,
                            contentDescription = null,
                            tint = Brand.Primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (isEditing) "Done" else "Edit",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Brand.Primary,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }

            if (items.isEmpty()) {
                Text(
                    text = "Nothing recorded yet. Add items so scans check for them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                FlowRow(
                    modifier = Modifier.padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items.forEach { item ->
                        AppChip(
                            text = item,
                            onEdit = if (isEditing && !isSaving) ({ onEditItem(item) }) else null,
                            onRemove = if (isEditing && !isSaving) ({ onRemove(item) }) else null
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onAdd,
                enabled = !isSaving,
                modifier = Modifier.padding(top = 14.dp),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = AppIcons.Plus,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Add ${category.singular}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }
    }
}

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
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(icon = AppIcons.Document, size = 42, deep = true)
                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(
                        text = "Ready to analyse · Tap to start",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = fileName.ifBlank { "Selected document" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            Text(
                text = "Nothing has been read yet. Start the analysis to read the document " +
                    "on this device, mask identifiers and review the text before anything " +
                    "is sent.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )

            Button(
                onClick = onStart,
                enabled = !isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
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
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
            ) {
                Text(
                    text = "Remove file",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ReportRow(record: ReportRecord) {
    AppCard {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconTile(icon = AppIcons.Document, size = 42)

            Column(
                modifier = Modifier
                    .padding(start = 14.dp)
                    .weight(1f)
            ) {
                Text(
                    text = record.reportTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
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
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }

            Surface(
                shape = RoundedCornerShape(50),
                color = Brand.Soft,
                border = BorderStroke(1.dp, Brand.Secondary.copy(alpha = 0.3f))
            ) {
                Text(
                    text = "Saved",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Brand.Primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun LocalOnlyNotice() {
    Column {
        SoftBanner(
            icon = AppIcons.ShieldCheck,
            title = "What is stored, and where",
            body = "Only your profile lists and report metadata (title, type, marker count) " +
                "are saved to your account. Original documents and redacted text are never " +
                "stored in Firestore."
        )
        Text(
            text = "You review the masked text before any cloud request, and high-risk " +
                "identifiers keep the approve button locked.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiHealthAssistantCard(
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
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(icon = AppIcons.Sparkle, size = 40, deep = true)
                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(
                        text = "AI Health Context Assistant",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Describe your diseases, allergies, medications, or dietary restrictions in plain words.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                label = { Text("Describe your health") },
                placeholder = { Text("e.g. I have diabetes, allergic to peanuts, and take vitamin D") },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(14.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                trailingIcon = {
                    if (input.isNotEmpty()) {
                        IconButton(onClick = onClearInput) {
                            Icon(
                                imageVector = AppIcons.Close,
                                contentDescription = "Clear input",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            )

            Text(
                text = "Tap to try an example:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)
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
                        shape = RoundedCornerShape(50),
                        color = Brand.SoftRaised,
                        border = BorderStroke(1.dp, Brand.Outline.copy(alpha = 0.6f)),
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
                            style = MaterialTheme.typography.labelSmall,
                            color = Brand.Primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Button(
                onClick = onAnalyze,
                enabled = input.isNotBlank() && !isAnalyzing,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
            ) {
                if (isAnalyzing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "Organizing with AI…",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                } else {
                    Icon(
                        imageVector = AppIcons.Sparkle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Organize with AI",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            if (clarificationMessage != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Brand.WarningSoft,
                    border = BorderStroke(1.dp, Brand.Warning.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = AppIcons.AlertTriangle,
                            contentDescription = null,
                            tint = Brand.Warning,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = clarificationMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = Brand.Warning,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }

            if (errorMessage != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Brand.DangerSoft,
                    border = BorderStroke(1.dp, Brand.Danger.copy(alpha = 0.3f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = AppIcons.AlertTriangle,
                            contentDescription = null,
                            tint = Brand.Danger,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = Brand.OnDangerSoft,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }

            if (successMessage != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Brand.Soft,
                    border = BorderStroke(1.dp, Brand.Secondary.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = AppIcons.Check,
                            contentDescription = null,
                            tint = Brand.Primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = successMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = Brand.Primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
