package com.scantruth.ai.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.scantruth.ai.ui.screens.HomeScreen
import com.scantruth.ai.ui.screens.IngredientResultScreen
import com.scantruth.ai.ui.screens.MyHealthScreen
import com.scantruth.ai.ui.screens.PrivacyReviewScreen
import com.scantruth.ai.ui.screens.ProfileScreen
import com.scantruth.ai.ui.screens.ReportResultScreen
import com.scantruth.ai.ui.screens.ScanHistoryScreen
import com.scantruth.ai.ui.screens.ScanScreen
import com.scantruth.ai.ui.state.HomeUiState
import com.scantruth.ai.ui.state.IngredientAnalysisState
import com.scantruth.ai.ui.state.MyHealthUiState
import com.scantruth.ai.ui.state.ReportAnalysisState

/**
 * The single navigation graph.
 *
 * The flow state holders are created once in [com.scantruth.ai.ui.ScanTruthAppShell] and
 * passed down, so a document started from Home, the Scan tab or My Health is reviewed and
 * saved by the same state — no data is re-serialised through navigation arguments.
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
                reportState = reportState,
                onScan = { navController.navigateToTopLevel(ScanTruthDestination.SCAN) },
                onOpenHealth = { navController.navigateToTopLevel(ScanTruthDestination.MY_HEALTH) },
                onOpenHistory = { navController.navigate(ScanTruthDestination.SCAN_HISTORY) },
                onOpenProfile = { navController.navigateToTopLevel(ScanTruthDestination.PROFILE) },
                onReviewReport = { navController.navigate(ScanTruthDestination.PRIVACY_REVIEW) }
            )
        }

        composable(ScanTruthDestination.SCAN) {
            ScanScreen(
                ingredientState = ingredientState,
                onOpenIngredientResult = {
                    navController.navigate(ScanTruthDestination.INGREDIENT_RESULT)
                },
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigateToTopLevel(ScanTruthDestination.HOME)
                    }
                }
            )
        }

        composable(ScanTruthDestination.MY_HEALTH) {
            MyHealthScreen(
                state = healthState,
                reportState = reportState,
                onOpenHistory = { navController.navigate(ScanTruthDestination.SCAN_HISTORY) },
                onReviewReport = { navController.navigate(ScanTruthDestination.PRIVACY_REVIEW) }
            )
        }

        composable(ScanTruthDestination.PROFILE) {
            ProfileScreen()
        }

        composable(ScanTruthDestination.SCAN_HISTORY) {
            ScanHistoryScreen(onBack = { navController.popBackStack() })
        }

        composable(ScanTruthDestination.PRIVACY_REVIEW) {
            PrivacyReviewScreen(
                state = reportState,
                onOpenResult = {
                    navController.navigate(ScanTruthDestination.REPORT_RESULT) {
                        popUpTo(ScanTruthDestination.PRIVACY_REVIEW) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(ScanTruthDestination.REPORT_RESULT) {
            val navScope = rememberCoroutineScope()
            ReportResultScreen(
                state = reportState,
                onDone = {
                    reportState.reset()
                    healthState.load(navScope)
                    navController.navigateToTopLevel(ScanTruthDestination.MY_HEALTH)
                },
                onBack = {
                    reportState.reset()
                    navController.popBackStack()
                }
            )
        }

        composable(ScanTruthDestination.INGREDIENT_RESULT) {
            IngredientResultScreen(
                state = ingredientState,
                onDone = {
                    ingredientState.reset()
                    navController.popBackStack()
                },
                onBack = {
                    ingredientState.clearAnalysis()
                    navController.popBackStack()
                }
            )
        }
    }
}
