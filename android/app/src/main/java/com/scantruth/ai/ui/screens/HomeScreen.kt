package com.scantruth.ai.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scantruth.ai.model.ScanRecord
import com.scantruth.ai.ui.components.AppCard
import com.scantruth.ai.ui.components.AppChip
import com.scantruth.ai.ui.components.AppIcons
import com.scantruth.ai.ui.components.IconTile
import com.scantruth.ai.ui.components.ScannerIllustration
import com.scantruth.ai.ui.components.SectionHeader
import com.scantruth.ai.ui.components.SoftBanner
import com.scantruth.ai.ui.components.formatShortDate
import com.scantruth.ai.ui.components.greetingForHour
import com.scantruth.ai.ui.navigation.ScanFrameIcon
import com.scantruth.ai.ui.state.HomeUiState
import com.scantruth.ai.ui.theme.Brand
import java.util.Calendar

/** Home dashboard, laid out like the website and the iOS app. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    /** Signed out, the session-only profile from My Health is shown instead. */
    profileOverride: com.scantruth.ai.model.MedicalProfile? = null,
    onScan: () -> Unit,
    onOpenHealth: () -> Unit,
    onAnalyseReport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var openRecord by remember { mutableStateOf<ScanRecord?>(null) }
    var showHistory by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        state.start(scope)
        state.refresh(scope)
    }
    DisposableEffect(Unit) { onDispose { state.stop() } }

    val greeting = greetingForHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
    val firstName = state.displayName?.trim()?.substringBefore(' ')
    val profile = profileOverride ?: state.profile
    val detailCount = profile.conditions.size + profile.allergies.size +
        profile.medications.size + profile.dietaryRestrictions.size

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        item { BrandHeader(subtitle = "ON-DEVICE PRIVACY VERIFIED", showBell = true) }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (state.isSignedIn && !firstName.isNullOrBlank()) "$greeting, $firstName" else greeting,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Brand.Primary
                )
                Text(
                    text = "Make a safer choice.",
                    fontSize = 32.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Scan ingredients or review medical records with guaranteed on-device privacy.",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item { ScanHeroCard(onScan = onScan) }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(title = "Medical Context", actionLabel = "View All", onAction = onOpenHealth)
                AppCard {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            IconTile(icon = AppIcons.HeartPulse, size = 44)
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text(
                                    text = if (detailCount == 0) "No medical context yet" else "Your health context",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (detailCount == 0) {
                                        "Add conditions and allergies so every scan is personal."
                                    } else {
                                        "$detailCount ${if (detailCount == 1) "detail" else "details"} personalize every scan."
                                    },
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                        val chips = (profile.conditions + profile.allergies + profile.medications).take(6)
                        if (chips.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) { chips.forEach { AppChip(text = it) } }
                        }
                        FilledActionButton(text = "Analyse Medical Report", icon = AppIcons.Plus, onClick = onAnalyseReport)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onOpenHealth),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Build your health context", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Brand.Primary)
                            Icon(
                                imageVector = AppIcons.ChevronRight,
                                contentDescription = null,
                                tint = Brand.Primary,
                                modifier = Modifier.padding(start = 4.dp).size(12.dp)
                            )
                        }
                    }
                }
            }
        }

        item { SectionHeader(title = "Recent Analysis", actionLabel = "View All", onAction = { showHistory = true }) }

        if (state.recentScans.isEmpty()) {
            item {
                DashedCard {
                    Icon(
                        imageVector = AppIcons.Document,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp)
                    )
                    Text(
                        text = if (state.isSignedIn) "No saved analyses yet" else "Sign in from the Profile tab to see your saved scans",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Scan a product",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Brand.Primary,
                        modifier = Modifier.clickable(onClick = onScan)
                    )
                }
            }
        } else {
            items(count = minOf(state.recentScans.size, 4), key = { state.recentScans[it].id }) { index ->
                val record = state.recentScans[index]
                RecentRow(record = record, onClick = { openRecord = record })
            }
        }

        item {
            SoftBanner(
                icon = AppIcons.ShieldCheck,
                title = "Your privacy is protected",
                body = "Names, dates of birth, phone numbers and IDs are masked on this device. " +
                    "Reports reach the cloud only after you approve the redacted text."
            )
        }
    }

    openRecord?.let { record -> SavedAnalysisSheet(record = record, onDismiss = { openRecord = null }) }
    if (showHistory) {
        ScanHistorySheet(onDismiss = { showHistory = false }, onChanged = { state.refresh(scope) })
    }
}

/** Shield tile, app name and small-caps subtitle; the bell is display-only, as on iOS and the web. */
@Composable
internal fun BrandHeader(subtitle: String, showBell: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon = AppIcons.ShieldCheck, size = 44, deep = true)
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text("ScanTruthAI", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                Box(Modifier.size(5.dp).background(Brand.Primary, CircleShape))
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 5.dp)
                )
            }
        }
        if (showBell) {
            Surface(
                shape = CircleShape,
                color = Color.White,
                border = BorderStroke(1.dp, Color.Black.copy(alpha = 0.06f)),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = AppIcons.Bell,
                        contentDescription = "Notifications",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun FilledActionButton(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Brand.Primary,
            contentColor = Color.White,
            disabledContainerColor = Brand.Primary.copy(alpha = 0.45f),
            disabledContentColor = Color.White
        )
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(17.dp))
        }
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = if (icon != null) 8.dp else 0.dp)
        )
    }
}

@Composable
internal fun DashedCard(content: @Composable () -> Unit) {
    val border = Brand.Outline
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = border,
                    cornerRadius = CornerRadius(18.dp.toPx()),
                    style = Stroke(
                        width = 1.2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
                    )
                )
            }
            .padding(horizontal = 16.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) { content() }
}

@Composable
private fun ScanHeroCard(onScan: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(Brand.Primary, Brand.Deep)))
    ) {
        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.14f)) {
                    Text(
                        text = "SCAN & KNOW",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
                Text("Scan Ingredients", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    "Check what is really inside before it goes in your basket.",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.85f)
                )
                Button(
                    onClick = onScan,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Brand.Primary),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Icon(imageVector = ScanFrameIcon, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Start scanning", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
                }
            }
            ScannerIllustration(modifier = Modifier.padding(start = 12.dp).width(92.dp).height(120.dp))
        }
    }
}

@Composable
private fun RecentRow(record: ScanRecord, onClick: () -> Unit) {
    val isMedicine = record.subtitle.equals("medicine", ignoreCase = true)
    val category = record.subtitle.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercaseChar() } ?: "Scan"
    val date = formatShortDate(record.scannedAtMillis)
    AppCard(modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon = if (isMedicine) AppIcons.Pill else AppIcons.Leaf, size = 44)
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    text = record.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (date.isNotBlank()) "$category · $date" else category,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            ScorePill(score = record.safetyScore)
        }
    }
}

/** "● 95 /100" pill tinted by the shared score rubric. */
@Composable
internal fun ScorePill(score: Int?) {
    val tone = scoreTone(score)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(tone.copy(alpha = 0.12f))
            .border(1.dp, tone.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).background(tone, CircleShape))
        Text(
            text = score?.toString() ?: "–",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 5.dp)
        )
        Text("/100", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 3.dp))
    }
}
