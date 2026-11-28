package com.scantruth.ai.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.scantruth.ai.AppPreferences
import com.scantruth.ai.WeeklyDigest
import com.scantruth.ai.ui.components.AppCard
import com.scantruth.ai.ui.components.AppIcons
import com.scantruth.ai.ui.components.IconTile
import com.scantruth.ai.ui.state.AuthSession
import com.scantruth.ai.ui.state.ProfileAuthState
import com.scantruth.ai.ui.state.rememberProfileAuthState
import com.scantruth.ai.ui.theme.Brand
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val GoogleBlue = Color(0xFF4285F4)
private val AiPurple = Color(0xFF7347AD)

/**
 * Profile tab: the real Firebase Authentication session, followed by the privacy,
 * AI, notification and security sections.
 *
 * Signed out the screen only offers Google Sign-In; signed in it shows exactly what the
 * Firebase account reports. No placeholder user is ever created and no health data is
 * written from here.
 */
@Composable
fun ProfileScreen(
    onClearSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberProfileAuthState()
    var expandedSection by remember { mutableStateOf<String?>(null) }

    // Settings stored on this device; same keys and defaults as iOS and web.
    var allergenAlerts by remember {
        mutableStateOf(AppPreferences.bool(AppPreferences.CRITICAL_ALLERGEN_ALERTS, true))
    }
    var intoleranceAlerts by remember {
        mutableStateOf(AppPreferences.bool(AppPreferences.INTOLERANCE_ALERTS, true))
    }
    var weeklyDigest by remember {
        mutableStateOf(AppPreferences.bool(AppPreferences.WEEKLY_DIGEST, false))
    }
    var strictPenalties by remember {
        mutableStateOf(AppPreferences.bool(AppPreferences.STRICT_SAFETY_PENALTIES, true))
    }
    var notificationNote by remember { mutableStateOf<String?>(null) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var clearedMessage by remember { mutableStateOf<String?>(null) }

    val setDigest: (Boolean) -> Unit = { enabled ->
        weeklyDigest = enabled
        AppPreferences.set(AppPreferences.WEEKLY_DIGEST, enabled)
        if (enabled) WeeklyDigest.schedule(context) else WeeklyDigest.cancel(context)
        notificationNote = null
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && WeeklyDigest.notificationsAllowed(context)) {
            setDigest(true)
        } else {
            setDigest(false)
            notificationNote =
                "Allow notifications for ScanTruthAI in Settings to turn on the weekly digest."
        }
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Clear session health context?") },
            text = {
                Text(
                    "This removes the scan and report held on this device for this session. " +
                        "Your saved account data is not deleted."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onClearSession()
                    showClearConfirmation = false
                    clearedMessage = "Cleared from this device. Data saved in your account is " +
                        "not deleted."
                }) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) { Text("Cancel") }
            }
        )
    }

    DisposableEffect(state) {
        state.start()
        onDispose { state.stop() }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(icon = AppIcons.ShieldCheck, size = 42, deep = true)
                    Column(modifier = Modifier.padding(start = 12.dp)) {
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
                                text = "ACCOUNT & PRIVACY",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                letterSpacing = 1.1.sp
                            )
                        }
                    }
                }
                Text(
                    text = "Profile",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 14.dp)
                )
            }
        }

        item {
            when (val session = state.session) {
                AuthSession.Loading -> SessionLoadingCard()
                AuthSession.SignedOut -> SignedOutCard(
                    isSigningIn = state.isSigningIn,
                    onGoogleSignIn = { scope.launch { state.signIn(context) } }
                )

                is AuthSession.SignedIn -> SignedInCard(
                    session = session,
                    isSigningOut = state.isSigningOut,
                    onSignOut = { scope.launch { state.signOut() } }
                )
            }
        }

        state.errorMessage?.let { message ->
            item {
                AuthErrorCard(message = message, onDismiss = state::clearError)
            }
        }

        item {
            SectionGroup(
                title = "Privacy & Data",
                subtitle = "What leaves this device, and when",
                icon = AppIcons.ShieldCheck,
                expanded = expandedSection == "privacy",
                onToggle = {
                    expandedSection = if (expandedSection == "privacy") null else "privacy"
                }
            ) {
                PreferenceRow(
                    title = "HIPAA Safe Harbor & ABDM Redaction",
                    body = "Patient names, doctor references, medical record IDs, Aadhaar/SSN, " +
                        "phone numbers and dates of birth are automatically stripped on this " +
                        "device before any health analysis."
                )
                PreferenceRow(
                    title = "Clinical Data Preservation",
                    body = "Only anonymized clinical markers, diagnoses, lab ranges and allergies " +
                        "are kept to personalize product safety checks."
                )
                PreferenceRow(
                    title = "Local Storage Isolation",
                    body = "Your health context is stored under your own account. This device " +
                        "keeps only a temporary cache in the app's private storage."
                )
                OutlinedButton(
                    onClick = { showClearConfirmation = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Clear session health context", fontWeight = FontWeight.SemiBold)
                }
                clearedMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        item {
            SectionGroup(
                title = "AI Preferences",
                subtitle = "How the analysis engines are chosen",
                icon = AppIcons.Sparkle,
                iconTint = AiPurple,
                expanded = expandedSection == "ai",
                onToggle = { expandedSection = if (expandedSection == "ai") null else "ai" }
            ) {
                PreferenceRow(
                    title = "Cloud Engine: Gemini 3.5 Flash Lite",
                    body = "High-speed clinical reasoning that evaluates allergen conflicts, " +
                        "glycemic impact and nutrient interactions. Only masked text and " +
                        "ingredient names are sent."
                )
                PreferenceRow(
                    title = "On-Device Guard: Gemini Nano",
                    body = "A model that runs on this phone to mask private details and, when you " +
                        "choose Nano, to analyze ingredients with zero network transmission. " +
                        "Needs a device with AICore."
                )
                PreferenceRow(
                    title = "Analysis modes",
                    body = "Auto uses Gemini Nano when it is ready on this device, otherwise " +
                        "the cloud. Gemini Flash is cloud only. Nano never leaves your phone."
                )
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                ToggleRow(
                    title = "Strict Clinical Safety Penalties",
                    detail = "Enforce an automatic \"Avoid\" verdict for high-risk conflicts " +
                        "(e.g. whey protein with lactose intolerance).",
                    checked = strictPenalties,
                    onCheckedChange = {
                        strictPenalties = it
                        AppPreferences.set(AppPreferences.STRICT_SAFETY_PENALTIES, it)
                    }
                )
            }
        }

        item {
            SectionGroup(
                title = "Notifications",
                subtitle = "Ingredient alert settings",
                icon = AppIcons.Bell,
                expanded = expandedSection == "notifications",
                onToggle = {
                    expandedSection = if (expandedSection == "notifications") null else "notifications"
                }
            ) {
                Text(
                    text = "Customize the safety warnings you get when scanning food or medicine labels.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                ToggleRow(
                    title = "Critical Allergen Warnings",
                    detail = "Instant alerts for nuts, dairy, gluten and severe allergens.",
                    checked = allergenAlerts,
                    onCheckedChange = {
                        allergenAlerts = it
                        AppPreferences.set(AppPreferences.CRITICAL_ALLERGEN_ALERTS, it)
                    }
                )
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
                ToggleRow(
                    title = "Intolerance & Sensitivity Alerts",
                    detail = "Flag lactose, high sodium and glycemic sugar spikes.",
                    checked = intoleranceAlerts,
                    onCheckedChange = {
                        intoleranceAlerts = it
                        AppPreferences.set(AppPreferences.INTOLERANCE_ALERTS, it)
                    }
                )
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
                ToggleRow(
                    title = "Weekly Health Scan Digest",
                    detail = "A weekly reminder to review your scanned products against your health goals.",
                    checked = weeklyDigest,
                    onCheckedChange = { enabled ->
                        when {
                            !enabled -> setDigest(false)
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                !WeeklyDigest.notificationsAllowed(context) ->
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else -> setDigest(true)
                        }
                    }
                )
                notificationNote?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        item {
            SectionGroup(
                title = "Security",
                subtitle = "How your data and session are protected",
                icon = AppIcons.Lock,
                expanded = expandedSection == "security",
                onToggle = {
                    expandedSection = if (expandedSection == "security") null else "security"
                }
            ) {
                PreferenceRow(
                    title = "Firebase App Check",
                    body = "Requests are attested with Firebase App Check (Play Integrity, or a " +
                        "debug token in test builds) to block unauthorized API use."
                )
                PreferenceRow(
                    title = "Per-User Data Isolation",
                    body = "Firestore rules enforce strict isolation: request.auth.uid == userId."
                )
                PreferenceRow(
                    title = "Encryption in Transit & at Rest",
                    body = "TLS 1.3 transport and encrypted database storage."
                )
                PreferenceRow(
                    title = "Current Session",
                    body = (state.session as? AuthSession.SignedIn)?.let {
                        "Authenticated as ${it.email ?: "your Google account"}"
                    } ?: "Not signed in"
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = AppIcons.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "ScanTruthAI v1.0 · privacy-first by design",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun SessionLoadingCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Checking your account…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp)
            )
        }
    }
}

@Composable
private fun SignedOutCard(
    isSigningIn: Boolean,
    onGoogleSignIn: () -> Unit
) {
    AppCard {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(Brand.Primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = AppIcons.User,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (isSigningIn) "Checking sign-in status…" else "You are not signed in",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Brand.TextPrimary
                    )
                    Text(
                        text = "Sign in to save your health profile and scan history to your account, and see them on every device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Brand.TextSecondary,
                        lineHeight = 18.sp
                    )
                }
            }

            Button(
                onClick = onGoogleSignIn,
                enabled = !isSigningIn,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Brand.Primary,
                    contentColor = Color.White,
                    disabledContainerColor = Brand.Primary.copy(alpha = 0.45f),
                    disabledContentColor = Color.White.copy(alpha = 0.7f)
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
            ) {
                if (isSigningIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Text(
                        text = "Signing in...",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                } else {
                    Icon(
                        imageVector = AppIcons.Key,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Sign in with Google",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SignedInCard(
    session: AuthSession.SignedIn,
    isSigningOut: Boolean,
    onSignOut: () -> Unit
) {
    AppCard {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProfileAvatar(
                    photoUrl = session.photoUrl,
                    displayName = session.displayName,
                    email = session.email,
                    size = 64
                )

                Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(
                        text = session.displayName?.takeIf { it.isNotBlank() }
                            ?: "Signed in",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Brand.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    session.email?.let { email ->
                        Text(
                            text = email,
                            style = MaterialTheme.typography.bodySmall,
                            color = Brand.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Brand.Soft,
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(Brand.Primary, CircleShape)
                            )
                            Text(
                                text = "Signed in with Google",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Brand.Primary
                            )
                        }
                    }
                }
            }

            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 16.dp)
            )

            // Account ID, member-since date and sign-in method live behind "About", as on iOS and web.
            var aboutExpanded by remember { mutableStateOf(false) }
            val aboutRotation by animateFloatAsState(
                targetValue = if (aboutExpanded) 180f else 0f,
                label = "aboutChevron"
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { aboutExpanded = !aboutExpanded }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = AppIcons.InfoCircle,
                    contentDescription = null,
                    tint = Brand.Primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "About",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Brand.Primary,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .weight(1f)
                )
                Icon(
                    imageVector = AppIcons.ChevronDown,
                    contentDescription = if (aboutExpanded) "Collapse About" else "Expand About",
                    tint = Brand.Primary,
                    modifier = Modifier
                        .size(14.dp)
                        .rotate(aboutRotation)
                )
            }
            AnimatedVisibility(visible = aboutExpanded) {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    InfoLine("Account ID", session.uid)
                    InfoLine("Member since", formatJoinedDate(session.createdAtMillis) ?: "-")
                    InfoLine("Sign-in method", "Google")
                }
            }

            OutlinedButton(
                onClick = onSignOut,
                enabled = !isSigningOut,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Brand.Danger.copy(alpha = 0.25f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Brand.Danger
                )
            ) {
                if (isSigningOut) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Brand.Danger
                    )
                } else {
                    Icon(
                        imageVector = AppIcons.Logout,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = if (isSigningOut) "Signing out…" else "Sign out",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun AuthErrorCard(message: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = AppIcons.AlertTriangle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(18.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp)
            ) {
                Text(
                    text = "Authentication problem",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** Collapsible settings group: header row always visible, content on tap. */
@Composable
private fun SectionGroup(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color? = null,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "chevron"
    )

    AppCard {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconTile(icon = icon, size = 40, tint = iconTint)
                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Brand.TextPrimary
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Brand.TextSecondary,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
                Icon(
                    imageVector = AppIcons.ChevronDown,
                    contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                    tint = Brand.TextTertiary,
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(chevronRotation)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                ) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(bottom = 14.dp)
                    )
                    content()
                }
            }
        }
    }
}

@Composable
private fun PreferenceRow(title: String, body: String) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = Brand.Primary)
        )
    }
}

@Composable
private fun AvatarPlaceholder(size: Int) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = AppIcons.Person,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size((size * 0.46f).dp)
        )
    }
}

/**
 * The Google photo is drawn straight from the account's https URL; while it loads or
 * if it is missing, the initials underneath remain visible.
 */
@Composable
private fun ProfileAvatar(
    photoUrl: String?,
    displayName: String?,
    email: String?,
    size: Int
) {
    val initials = initialsFor(displayName, email)

    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        if (initials != null) {
            Text(
                text = initials,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold
            )
        } else {
            Icon(
                imageVector = AppIcons.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size((size / 2).dp)
            )
        }

        if (!photoUrl.isNullOrBlank()) {
            AsyncImage(
                model = photoUrl,
                contentDescription = "Google profile photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
            )
        }
    }
}

@Composable
private fun GoogleGlyph() {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "G",
            style = MaterialTheme.typography.labelLarge,
            color = GoogleBlue,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun initialsFor(displayName: String?, email: String?): String? {
    val source = displayName?.takeIf { it.isNotBlank() }
        ?: email?.substringBefore('@')?.takeIf { it.isNotBlank() }
        ?: return null
    val parts = source.trim().split(' ', '.', '_', '-').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    return parts.take(2).map { it.first().uppercaseChar() }.joinToString("")
}

private fun formatJoinedDate(millis: Long?): String? {
    if (millis == null || millis <= 0L) return null
    return DateTimeFormatter
        .ofPattern("d MMM yyyy")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(millis))
}
