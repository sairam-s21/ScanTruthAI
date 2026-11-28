package com.scantruth.ai.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.scantruth.ai.FirestoreRepository
import com.scantruth.ai.model.MedicalProfile
import com.scantruth.ai.ui.components.AppIcons
import com.scantruth.ai.ui.navigation.ScanFrameIcon
import com.scantruth.ai.ui.navigation.ScanTruthDestination
import com.scantruth.ai.ui.navigation.ScanTruthNavHost
import com.scantruth.ai.ui.navigation.navigateToTopLevel
import com.scantruth.ai.ui.state.HomeUiState
import com.scantruth.ai.ui.state.IngredientAnalysisState
import com.scantruth.ai.ui.state.MyHealthUiState
import com.scantruth.ai.ui.state.ReportAnalysisState

/**
 * App shell: owns the four flow state holders and the bottom bar.
 *
 * The state holders live here, above the nav host, so an in-flight scan survives tab
 * switches and the review/result screens belong to the same flow the user started.
 * The bottom bar is hidden on pushed screens, where a back arrow leads the way.
 */
@Composable
fun ScanTruthAppShell() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val homeState = remember { HomeUiState() }
    val healthState = remember { MyHealthUiState() }
    val reportState = remember { ReportAnalysisState() }
    val ingredientState = remember { IngredientAnalysisState() }

    // Global observation of auth state and analysis lifecycle for data consistency.
    val shellScope = rememberCoroutineScope()
    val lastUid = remember { arrayOfNulls<String>(1) }
    DisposableEffect(Unit) {
        homeState.start(shellScope)
        healthState.start(shellScope)

        reportState.onReportSaved = {
            homeState.refresh(shellScope)
            healthState.load(shellScope)
        }
        ingredientState.onScanSaved = {
            homeState.refresh(shellScope)
        }
        reportState.onGuestExtraction = { extraction, title ->
            healthState.applyGuestExtraction(extraction, title)
        }
        // Signed in, scans use the stored profile; signed out, the session-only one.
        ingredientState.profileSource = {
            if (FirestoreRepository.isSignedIn()) {
                (FirestoreRepository.loadMedicalProfile() as? FirestoreRepository.StoreResult.Success)?.value
                    ?: MedicalProfile()
            } else {
                healthState.profile
            }
        }

        val auth = Firebase.auth
        lastUid[0] = auth.currentUser?.uid
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val uid = firebaseAuth.currentUser?.uid
            if (lastUid[0] != null && uid == null) {
                reportState.clearSensitive()
            }
            lastUid[0] = uid
        }
        auth.addAuthStateListener(listener)

        onDispose {
            auth.removeAuthStateListener(listener)
            homeState.stop()
            healthState.stop()
            reportState.onReportSaved = null
            reportState.onGuestExtraction = null
            ingredientState.onScanSaved = null
        }
    }

    val showBottomBar = ScanTruthDestination.isTopLevel(currentRoute)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                ScanTruthBottomBar(
                    currentRoute = currentRoute,
                    onSelect = { route -> navController.navigateToTopLevel(route) }
                )
            }
        }
    ) { innerPadding ->
        ScanTruthNavHost(
            navController = navController,
            homeState = homeState,
            healthState = healthState,
            reportState = reportState,
            ingredientState = ingredientState,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

/**
 * Four-tab navigation with a deep-green pill behind the selected tab, so the active
 * destination is unmistakable on every screen.
 */
@Composable
private fun ScanTruthBottomBar(
    currentRoute: String?,
    onSelect: (String) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 14.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
            ) {
                ScanTruthDestination.tabs.forEach { route ->
                    val selected = currentRoute == route
                    NavigationPill(
                        label = ScanTruthDestination.label(route),
                        icon = tabIcon(route),
                        selected = selected,
                        onClick = { onSelect(route) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun NavigationPill(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "navContainer"
    )
    val content by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            Color(0xFF99ADA3)
        },
        label = "navContent"
    )
    val verticalPadding = 9.dp

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(container)
            .clickable(enabled = !selected, onClick = onClick)
            .padding(vertical = verticalPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = content,
            modifier = Modifier.size(21.dp)
        )
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = content,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

private fun tabIcon(route: String): ImageVector = when (route) {
    ScanTruthDestination.HOME -> AppIcons.Leaf
    ScanTruthDestination.SCAN -> ScanFrameIcon
    ScanTruthDestination.MY_HEALTH -> AppIcons.HeartPulse
    else -> AppIcons.Person
}
