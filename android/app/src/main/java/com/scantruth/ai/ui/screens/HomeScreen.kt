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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scantruth.ai.model.ScanKind
import com.scantruth.ai.model.ScanRecord
import com.scantruth.ai.ui.components.AppCard
import com.scantruth.ai.ui.components.AppChip
import com.scantruth.ai.ui.components.AppIcons
import com.scantruth.ai.ui.components.EmptyStateCard
import com.scantruth.ai.ui.components.ErrorPanel
import com.scantruth.ai.ui.components.IconTile
import com.scantruth.ai.ui.components.LoadingPanel
import com.scantruth.ai.ui.components.ScannerIllustration
import com.scantruth.ai.ui.components.SectionHeader
import com.scantruth.ai.ui.components.SoftBanner
import com.scantruth.ai.ui.components.documentDisplayName
import com.scantruth.ai.ui.components.formatShortDate
import com.scantruth.ai.ui.components.greetingForHour
import com.scantruth.ai.ui.navigation.ScanFrameIcon
import com.scantruth.ai.ui.state.HomeUiState
import com.scantruth.ai.ui.state.ReportAnalysisState
import com.scantruth.ai.ui.theme.Brand
import java.util.Calendar

/**
 * Home dashboard. Everything here is read from FirebaseAuth and Firestore for the user
 * who is actually signed in; a signed-out visitor sees an honest sign-in prompt instead
 * of demo data.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    reportState: ReportAnalysisState,
    onScan: () -> Unit,
    onOpenHealth: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenProfile: () -> Unit,
    onReviewReport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showNotifications by remember { mutableStateOf(false) }
    var openRecord by remember { mutableStateOf<ScanRecord?>(null) }

    LaunchedEffect(Unit) {
        state.start(scope)
        state.refresh(scope)
    }

    DisposableEffect(Unit) {
        onDispose { state.stop() }
    }

    // Adding a report from Home goes through the same privacy review as every other
    // entry point, and never touches the ingredient flow.
    val reportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            reportState.start(
                context = context,
                uri = uri,
                displayName = documentDisplayName(context, uri, "Medical report")
            )
            onReviewReport()
        }
    }

    val greeting = greetingForHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
    val firstName = state.displayName?.trim()?.substringBefore(' ')

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            AppHeader(onNotifications = { showNotifications = true })
        }

        item {
            GreetingBlock(
                greeting = greeting,
                name = firstName,
                signedIn = state.isSignedIn
            )
        }

        item { ScanHeroCard(onScan = onScan) }

        if (!state.isSignedIn) {
            item { SignInPromptCard(onOpenProfile = onOpenProfile) }
        }

        item {
            SectionHeader(
                title = "Medical Context",
                actionLabel = "View All",
                onAction = onOpenHealth
            )
        }

        item {
            if (state.isLoading && state.isSignedIn) {
                LoadingPanel(message = "Loading your medical context…")
            } else {
                MedicalContextCard(
                    state = state,
                    reportBusy = reportState.isBusy,
                    onOpenHealth = onOpenHealth,
                    onAddReport = {
                        reportLauncher.launch(arrayOf("application/pdf", "image/*"))
                    }
                )
            }
        }

        state.error?.let { message ->
            item {
                ErrorPanel(
                    title = "Could not refresh your dashboard",
                    message = message,
                    actionLabel = "Try again",
                    onAction = { state.refresh(scope) }
                )
            }
        }

        item {
            SectionHeader(
                title = "Recent Analysis",
                actionLabel = "View All",
                onAction = onOpenHistory
            )
        }

        when {
            state.isLoading && state.isSignedIn -> item {
                LoadingPanel(message = "Loading your recent analysis…")
            }

            state.recentScans.isEmpty() -> item {
                EmptyStateCard(
                    icon = AppIcons.Document,
                    title = "",
                    body = if (state.isSignedIn) {
                        "No saved analyses yet"
                    } else {
                        "Sign in from the Profile tab to see your saved scans"
                    },
                    actionLabel = "Scan a product",
                    onAction = onScan
                )
            }

            else -> items(
                count = minOf(state.recentScans.size, 4),
                key = { index -> state.recentScans[index].id }
            ) { index ->
                val record = state.recentScans[index]
                RecentAnalysisRow(record = record, onClick = { openRecord = record })
            }
        }

        item {
            SoftBanner(
                icon = AppIcons.ShieldCheck,
                title = "Your privacy is protected",
                body = "Names, dates of birth, phone numbers and IDs are masked on this " +
                    "device. Reports reach the cloud only after you approve the redacted text."
            )
        }
    }

    openRecord?.let { record ->
        SavedAnalysisSheet(record = record, onDismiss = { openRecord = null })
    }

    if (showNotifications) {
        AlertDialog(
            onDismissRequest = { showNotifications = false },
            icon = { IconTile(icon = AppIcons.Bell, size = 44) },
            title = { Text("Notifications") },
            text = {
                Text(
                    "You're all caught up. Analysis results and privacy notices appear " +
                        "inside the app, on this screen and in the review steps.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = { showNotifications = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun AppHeader(onNotifications: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(icon = AppIcons.ShieldCheck, size = 42, deep = true)
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(
                text = "ScanTruthAI",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.padding(top = 1.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .background(Brand.Secondary, CircleShape)
                )
                Text(
                    text = "ON-DEVICE PRIVACY VERIFIED",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.1.sp
                )
            }
        }

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 1.dp
        ) {
            IconButton(onClick = onNotifications, modifier = Modifier.size(42.dp)) {
                Icon(
                    imageVector = AppIcons.Bell,
                    contentDescription = "Notifications",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}

@Composable
private fun GreetingBlock(greeting: String, name: String?, signedIn: Boolean) {
    Column {
        Text(
            text = if (signedIn && !name.isNullOrBlank()) "$greeting, $name" else greeting,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Make a safer choice.",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = "Scan ingredients or review medical records with guaranteed on-device privacy.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun ScanHeroCard(onScan: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(Brand.Deep, Brand.Primary),
                    start = androidx.compose.ui.geometry.Offset.Zero,
                    end = androidx.compose.ui.geometry.Offset(1000f, 800f)
                )
            )
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 22.dp, bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Surface(
                    shape = RoundedCornerShape(100),
                    color = Color.White.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(Brand.Secondary, CircleShape)
                        )
                        Text(
                            text = "SCAN & KNOW",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            letterSpacing = 1.2.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = "Scan Ingredients",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Text(
                    text = "Check what is really inside before it goes in your basket.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.TextOnDeepMuted,
                    modifier = Modifier.padding(top = 6.dp)
                )

                Button(
                    onClick = onScan,
                    modifier = Modifier
                        .padding(top = 18.dp)
                        .height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Brand.Deep
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 18.dp)
                ) {
                    Icon(
                        imageVector = ScanFrameIcon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Start scanning",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            ScannerIllustration(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .width(112.dp)
                    .height(152.dp)
            )
        }
    }
}

@Composable
private fun SignInPromptCard(onOpenProfile: () -> Unit) {
    AppCard {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconTile(icon = AppIcons.Person, size = 40)
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    text = "Browsing as a guest",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Sign in to keep your health context and history across devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Brand.Soft,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenProfile)
            ) {
                Text(
                    text = "Sign in",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MedicalContextCard(
    state: HomeUiState,
    reportBusy: Boolean,
    onOpenHealth: () -> Unit,
    onAddReport: () -> Unit
) {
    val profile = state.profile

    AppCard {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(icon = AppIcons.HeartFile, size = 42)
                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(
                        text = if (profile.itemCount == 0) {
                            "No medical context yet"
                        } else {
                            "Your health context"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (profile.itemCount == 0) {
                            "Add conditions and allergies so every scan is personal."
                        } else {
                            "${profile.itemCount} " +
                                (if (profile.itemCount == 1) "detail" else "details") +
                                " personalize every scan."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            val chips = (profile.conditions + profile.allergies + profile.medications).take(6)
            if (chips.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    chips.forEach { AppChip(text = it) }
                }
            }

            Button(
                onClick = onAddReport,
                enabled = !reportBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp)
                    .height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
            ) {
                Icon(
                    imageVector = AppIcons.Plus,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Analyse Medical Report",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            TextButton(
                onClick = onOpenHealth,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Build your health context",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = AppIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentAnalysisRow(record: ScanRecord, onClick: () -> Unit) {
    AppCard(modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val isMedicine = record.subtitle.equals("Medicine", ignoreCase = true)
            IconTile(
                icon = when {
                    record.kind == ScanKind.MEDICAL_REPORT -> AppIcons.Document
                    isMedicine -> AppIcons.Pill
                    else -> AppIcons.Leaf
                },
                size = 42
            )

            Column(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .weight(1f)
            ) {
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Brand.TextPrimary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(
                        record.subtitle.takeIf { it.isNotBlank() },
                        formatShortDate(record.scannedAtMillis).takeIf { it.isNotBlank() }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.TextSecondary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            val score = record.safetyScore
            if (score != null) {
                ScoreBadge(score = score)
            }
        }
    }
}

/** Compact score pill matching web .score-pill */
@Composable
private fun ScoreBadge(score: Int) {
    val tone = when {
        score >= 70 -> Brand.Good
        score >= 40 -> Brand.Care
        else -> Brand.Avoid
    }

    Surface(
        shape = RoundedCornerShape(100),
        color = tone.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, tone.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(tone, CircleShape)
            )
            Text(
                text = "$score",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = tone
            )
            Text(
                text = "/100",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal
                ),
                color = Brand.TextSecondary
            )
        }
    }
}
