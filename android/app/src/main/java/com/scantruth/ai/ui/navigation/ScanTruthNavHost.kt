package com.scantruth.ai.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.scantruth.ai.ui.screens.HomeScreen
import com.scantruth.ai.ui.screens.MyHealthScreen
import com.scantruth.ai.ui.screens.ProfileScreen
import com.scantruth.ai.ui.screens.ScanScreen
import com.scantruth.ai.ui.state.HomeUiState
import com.scantruth.ai.ui.state.IngredientAnalysisState
import com.scantruth.ai.ui.state.MyHealthUiState
import com.scantruth.ai.ui.state.ReportAnalysisState

/**
 * The single navigation graph: the four tabs. The flow state holders are created once in
 * [com.scantruth.ai.ui.ScanTruthAppShell], so work in progress survives tab switches.
 */
@Composable
fun ScanTruthNavHost(
    navController: NavHostController,
    homeState: HomeUiState,
    healthState: MyHealthUiState,
    reportState: ReportAnalysisState,
    ingredientState: IngredientAnalysisState,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = ScanTruthDestination.HOME,
        modifier = modifier
    ) {
        composable(ScanTruthDestination.HOME) {
            HomeScreen(
                state = homeState,
                profileOverride = if (homeState.isSignedIn) null else healthState.profile,
                onScan = { navController.navigateToTopLevel(ScanTruthDestination.SCAN) },
                onOpenHealth = { navController.navigateToTopLevel(ScanTruthDestination.MY_HEALTH) },
                onAnalyseReport = {
                    healthState.requestReportPicker()
                    navController.navigateToTopLevel(ScanTruthDestination.MY_HEALTH)
                }
            )
        }

        composable(ScanTruthDestination.SCAN) {
            ScanScreen(
                ingredientState = ingredientState,
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigateToTopLevel(ScanTruthDestination.HOME)
                    }
                }
            )
        }

        composable(ScanTruthDestination.MY_HEALTH) {
            MyHealthScreen(state = healthState, reportState = reportState)
        }

        composable(ScanTruthDestination.PROFILE) {
            ProfileScreen(
                onClearSession = {
                    ingredientState.reset()
                    reportState.clearSensitive()
                }
            )
        }
    }
}
