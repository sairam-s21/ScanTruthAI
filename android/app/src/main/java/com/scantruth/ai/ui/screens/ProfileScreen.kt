package com.scantruth.ai.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
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

/**
 * Profile tab: the real Firebase Authentication session, followed by the privacy,
 * AI, notification and security sections.
 *
 * Signed out the screen only offers Google Sign-In; signed in it shows exactly what the
 * Firebase account reports. No placeholder user is ever created and no health data is
 * written from here.
 */
@Composable
fun ProfileScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberProfileAuthState()
    var expandedSection by remember { mutableStateOf<String?>(null) }

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
                icon = AppIcons.Lock,
                expanded = expandedSection == "privacy",
                onToggle = {
                    expandedSection = if (expandedSection == "privacy") null else "privacy"
                }
            ) {
                PrivacyBullet("Names, dates, IDs and contact details are masked on this device.")
                PrivacyBullet("A report reaches the cloud only after you approve the redacted text.")
                PrivacyBullet("Original documents and redacted text are never stored in Firestore.")
                PrivacyBullet("Signing out clears the cached account data on this device.")
            }
        }

        item {
            SectionGroup(
                title = "AI Preferences",
                subtitle = "How the analysis engines are chosen",
                icon = AppIcons.Sparkle,
                expanded = expandedSection == "ai",
                onToggle = { expandedSection = if (expandedSection == "ai") null else "ai" }
            ) {
                PreferenceRow(
                    title = "Auto",
                    body = "Gemini Nano when it is ready on this device, otherwise Gemini Flash."
                )
                PreferenceRow(
                    title = "Gemini Flash",
                    body = "The cloud model. Only ingredient names and your approved profile are sent."
                )
                PreferenceRow(
                    title = "Nano",
                    body = "Stays on this device. Never falls back to the cloud."
                )
                Text(
                    text = "Chosen per scan on the Scan screen; the result always names the engine used.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }

        item {
            SectionGroup(
                title = "Notifications",
                subtitle = "Where results and reminders appear",
                icon = AppIcons.Bell,
                expanded = expandedSection == "notifications",
                onToggle = {
                    expandedSection = if (expandedSection == "notifications") null else "notifications"
                }
            ) {
                PreferenceRow(
                    title = "In-app only",
                    body = "ScanTruthAI does not send push notifications. Results appear in " +
                        "Recent Analysis on Home, and privacy prompts appear in the review steps."
                )
                PreferenceRow(
                    title = "Nothing leaves silently",
                    body = "No analysis result is shared with anyone else, and no email or " +
                        "message is sent on your behalf."
                )
            }
        }

        item {
            SectionGroup(
                title = "Security",
                subtitle = "Account protection in this build",
                icon = AppIcons.ShieldCheck,
                expanded = expandedSection == "security",
                onToggle = {
                    expandedSection = if (expandedSection == "security") null else "security"
                }
            ) {
                PreferenceRow(
                    title = "Google sign-in",
                    body = "Handled by Firebase Authentication. ScanTruthAI never sees your " +
                        "Google password."
                )
                PreferenceRow(
                    title = "App Check",
                    body = "Requests to Firebase are attested for this app installation."
                )
                PreferenceRow(
                    title = "Local-first processing",
                    body = "OCR and masking run on this device before any network call."
                )
                state.session.let { session ->
                    if (session is AuthSession.SignedIn) {
                        Text(
                            text = "Signed in as ${session.email ?: "your Google account"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(Brand.Deep, Brand.Primary),
                    start = androidx.compose.ui.geometry.Offset.Zero,
                    end = androidx.compose.ui.geometry.Offset(900f, 700f)
                )
            )
    ) {
        Column(modifier = Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarPlaceholder(size = 64)
                Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(
                        text = "You are browsing as a guest",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Sign in to keep your health context and scan history attached to your own account.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Brand.TextOnDeepMuted,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Button(
                onClick = onGoogleSignIn,
                enabled = !isSigningIn,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp)
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Brand.Deep
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
            ) {
                if (isSigningIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Brand.Primary
                    )
                    Text(
                        text = "Signing you in…",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                } else {
                    GoogleGlyph()
                    Text(
                        text = "Continue with Google",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
            }

            Text(
                text = "You stay signed out until you pick an account.",
                style = MaterialTheme.typography.bodySmall,
                color = Brand.TextOnDeepMuted,
                modifier = Modifier.padding(top = 10.dp)
            )
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
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileAvatar(
                    photoUrl = session.photoUrl,
                    displayName = session.displayName,
                    email = session.email,
                    size = 64
                )

                Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(
                        text = session.displayName?.takeIf { it.isNotBlank() }
                            ?: "Google account",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    session.email?.let { email ->
                        Text(
                            text = email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Brand.Soft,
                        border = BorderStroke(1.dp, Brand.Secondary.copy(alpha = 0.3f)),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(
                            text = "Signed in with Google",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Brand.Primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 16.dp)
            )

            InfoLine("Account ID", session.uid)
            formatJoinedDate(session.createdAtMillis)?.let { joined ->
                InfoLine("Member since", joined)
            }
            InfoLine("Sign-in method", "Google")

            OutlinedButton(
                onClick = onSignOut,
                enabled = !isSigningOut,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                if (isSigningOut) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
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
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "chevron"
    )
    val container by animateColorAsState(
        targetValue = if (expanded) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            MaterialTheme.colorScheme.surface
        },
        label = "sectionContainer"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = container,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconTile(icon = icon, size = 40)
                Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Icon(
                    imageVector = AppIcons.ChevronRight,
                    contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
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
                        modifier = Modifier.padding(bottom = 12.dp)
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
private fun PrivacyBullet(text: String) {
    Row(
        modifier = Modifier.padding(bottom = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(5.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 10.dp)
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
