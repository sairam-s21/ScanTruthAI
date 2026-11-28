package com.scantruth.ai.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.scantruth.ai.HybridAIService.NanoAvailability
import com.scantruth.ai.domain.ExtractionConfidence
import com.scantruth.ai.model.AiMode
import com.scantruth.ai.ui.components.AppCard
import com.scantruth.ai.ui.components.AppChip
import com.scantruth.ai.ui.components.AppIcons
import com.scantruth.ai.ui.components.CameraPreview
import com.scantruth.ai.ui.components.ErrorPanel
import com.scantruth.ai.ui.components.LoadingPanel
import com.scantruth.ai.ui.components.PrivacyNoteCard
import com.scantruth.ai.ui.components.documentDisplayName
import com.scantruth.ai.ui.components.rememberCameraPreviewState
import com.scantruth.ai.ui.state.IngredientAnalysisState
import com.scantruth.ai.ui.state.IngredientStep
import com.scantruth.ai.ui.state.WorkStage
import com.scantruth.ai.ui.theme.Brand
import java.io.File

/**
 * The Scan tab: capture an ingredient label, upload a photo, or type the list.
 *
 * Clear separation between stages:
 *  1. Camera framing (guidance + capture controls visible before capture).
 *  2. OCR processing (camera instructions hidden; capturing -> reading -> extracting).
 *  3. Review (camera instructions hidden; editable ingredient chips, allergen notice, warnings, raw OCR toggle).
 *  4. AI analysis (model selection: Auto / Gemini Flash / On-device -> IngredientResultScreen).
 */
@Composable
fun ScanScreen(
    ingredientState: IngredientAnalysisState,
    onOpenIngredientResult: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cameraState = rememberCameraPreviewState()

    val manualFocus = remember { FocusRequester() }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val isBusy = ingredientState.isBusy

    var resultOpened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(ingredientState.step) {
        if (ingredientState.step != IngredientStep.Ready) {
            resultOpened = false
        } else if (!resultOpened) {
            resultOpened = true
            onOpenIngredientResult()
        }
    }

    BackHandler(enabled = isBusy) {
        ingredientState.cancel()
    }

    LaunchedEffect(Unit) {
        ingredientState.prefetchProfile()
    }

    LaunchedEffect(ingredientState.aiMode) {
        if (ingredientState.aiMode == AiMode.NANO) {
            ingredientState.checkNano()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            cameraError = "Camera access was denied. You can still upload a photo of the label."
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null && !ingredientState.isBusy) {
            cameraError = null
            ingredientState.readLabel(
                context = context,
                uri = uri,
                displayName = documentDisplayName(context, uri, "Ingredient label")
            )
        }
    }

    val onCapture: () -> Unit = {
        val capture = cameraState.imageCapture
        cameraError = null
        if (capture == null) {
            cameraError = "The camera is still starting. Try again in a moment."
        } else if (ingredientState.beginCapture()) {
            captureToFile(
                context = context,
                imageCapture = capture,
                onSaved = { file ->
                    ingredientState.readLabel(
                        context = context,
                        uri = Uri.fromFile(file),
                        displayName = file.name
                    )
                },
                onError = { message ->
                    cameraError = message
                    ingredientState.onCaptureFailed(message)
                }
            )
        }
    }

    val openGallery: () -> Unit = {
        if (!isBusy) {
            galleryLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ScanToolbar(
            isReviewing = !ingredientState.showCameraViewfinder && !ingredientState.isProcessingOcr &&
                ingredientState.ingredients.isNotEmpty(),
            onBack = onBack
        )

        when {
            ingredientState.isProcessingOcr -> {
                val working = ingredientState.step as? IngredientStep.Working
                OcrProgressCard(
                    stage = working?.stage ?: WorkStage.READING_OCR,
                    message = working?.message ?: "Reading ingredients…",
                    onCancel = { ingredientState.cancel() }
                )
            }

            ingredientState.showCameraViewfinder -> {
                CameraPanel(
                    hasPermission = hasCameraPermission,
                    isCapturing = ingredientState.isCapturing,
                    error = cameraError ?: cameraState.errorMessage,
                    cameraContent = { previewModifier ->
                        CameraPreview(state = cameraState, modifier = previewModifier)
                    },
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) }
                )

                ScanControls(
                    enabled = !isBusy,
                    canCapture = cameraState.imageCapture != null && !isBusy,
                    onCapture = onCapture,
                    onUpload = openGallery,
                    onType = { manualFocus.requestFocus() }
                )
            }

            else -> {
                LabelCapturedReviewCard(
                    state = ingredientState,
                    enabled = !isBusy,
                    onRescan = { ingredientState.rescanLabel() },
                    onUpload = openGallery
                )
            }
        }

        val aiWorking = (ingredientState.step as? IngredientStep.Working)
            ?.takeIf { ingredientState.isAnalyzingAi }
        if (aiWorking != null) {
            LoadingPanel(message = aiWorking.message)
            OutlinedButton(
                onClick = { ingredientState.cancel() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                Text("Cancel analysis", style = MaterialTheme.typography.labelLarge)
            }
        }

        (ingredientState.step as? IngredientStep.Failed)?.let { failed ->
            ErrorPanel(
                title = "That label could not be used",
                message = failed.message,
                actionLabel = "Scan again",
                onAction = {
                    cameraError = null
                    ingredientState.reset()
                }
            )
        }

        DetectedIngredientsCard(
            state = ingredientState,
            enabled = !isBusy,
            focusRequester = manualFocus
        )

        EngineSection(
            state = ingredientState,
            enabled = !isBusy
        )

        Button(
            onClick = { ingredientState.analyze() },
            enabled = ingredientState.canAnalyze,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Brand.Primary,
                contentColor = Color.White,
                disabledContainerColor = Brand.Primary.copy(alpha = 0.35f),
                disabledContentColor = Color.White.copy(alpha = 0.85f)
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
        ) {
            Icon(
                imageVector = AppIcons.Sparkle,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = if (ingredientState.isAnalyzingAi) "Analyzing…" else "Analyze Ingredients",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        when {
            ingredientState.isNanoSelectedAndUnavailable -> {
                val blockedReason = when (val status = ingredientState.nanoStatus) {
                    is NanoAvailability.Downloading ->
                        "Gemini Nano is still downloading. Wait for it to finish, or choose Auto or On-device."
                    is NanoAvailability.Downloadable ->
                        "Gemini Nano needs to be downloaded before running Nano mode, or choose Auto or On-device."
                    is NanoAvailability.Unsupported ->
                        "Gemini Nano is unavailable on this device, so Nano mode cannot run. Choose Auto or On-device instead."
                    else ->
                        "Checking Gemini Nano availability on this device before Nano mode can run."
                }
                Text(
                    text = blockedReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.Care
                )
            }

            !ingredientState.hasValidIngredients && !isBusy -> {
                Text(
                    text = "Scan a label or add at least one ingredient above to analyze.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.TextSecondary
                )
            }
        }

        PrivacyNoteCard(
            title = "Read on this device",
            body = "The label photo stays on your device. Only the ingredient names and the " +
                "health context you approved are sent for analysis."
        )
    }
}

@Composable
private fun ScanToolbar(isReviewing: Boolean, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 1.dp
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
            Text(
                text = if (isReviewing) "Review ingredients" else "Scan ingredients",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = if (isReviewing) {
                    "Verify the extracted list before analysis"
                } else {
                    "Point at the ingredient list on the pack"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun OcrProgressCard(
    stage: WorkStage,
    message: String,
    onCancel: () -> Unit
) {
    AppCard {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LoadingPanel(message = message)

            val stages = listOf(
                WorkStage.CAPTURING to "Capturing image",
                WorkStage.READING_OCR to "Reading ingredients",
                WorkStage.EXTRACTING to "Extracting ingredient list"
            )
            val currentIndex = stages.indexOfFirst { it.first == stage }.coerceAtLeast(0)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                stages.forEachIndexed { index, (_, label) ->
                    val completed = index < currentIndex
                    val active = index == currentIndex
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        completed -> Brand.Secondary
                                        active -> Brand.Primary
                                        else -> Brand.Outline
                                    }
                                )
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (active || completed) Brand.TextPrimary else Brand.TextTertiary
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text("Cancel scan", color = Brand.Danger, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun LabelCapturedReviewCard(
    state: IngredientAnalysisState,
    enabled: Boolean,
    onRescan: () -> Unit,
    onUpload: () -> Unit
) {
    var showRawOcr by rememberSaveable(state.rawOcrText) {
        mutableStateOf(state.extractionConfidence == ExtractionConfidence.LOW)
    }

    AppCard {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val pillBg: Color
                val dotColor: Color
                val pillTextColor: Color
                val pillLabel: String
                when (state.extractionConfidence) {
                    ExtractionConfidence.HIGH -> {
                        pillBg = Brand.Soft
                        dotColor = Brand.Secondary
                        pillTextColor = Brand.Primary
                        pillLabel = "Ready for review"
                    }
                    ExtractionConfidence.MEDIUM -> {
                        pillBg = Brand.Care.copy(alpha = 0.14f)
                        dotColor = Brand.Care
                        pillTextColor = Brand.Care
                        pillLabel = "Partial · verify list"
                    }
                    ExtractionConfidence.LOW -> {
                        pillBg = Brand.Avoid.copy(alpha = 0.12f)
                        dotColor = Brand.Avoid
                        pillTextColor = Brand.Avoid
                        pillLabel = "Low confidence · retake"
                    }
                }
                Surface(
                    shape = RoundedCornerShape(100),
                    color = pillBg
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(dotColor, CircleShape)
                        )
                        Text(
                            text = pillLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = pillTextColor
                        )
                    }
                }

                if (state.lastMetrics.ocrDurationMs > 0L) {
                    Text(
                        text = "OCR ${state.lastMetrics.ocrDurationMs}ms · Parse ${state.lastMetrics.parseDurationMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = Brand.TextTertiary
                    )
                }
            }

            if (state.sourceName.isNotBlank()) {
                Text(
                    text = state.sourceName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Brand.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (state.allergenDeclarations.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Brand.Care.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, Brand.Care.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Allergen declaration on label",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Brand.TextPrimary
                        )
                        Text(
                            text = state.allergenDeclarations.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = Brand.TextPrimary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            if (state.scanWarnings.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Brand.Care.copy(alpha = 0.1f),
                    border = BorderStroke(1.dp, Brand.Care.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        state.scanWarnings.forEach { warning ->
                            Text(
                                text = "• $warning",
                                style = MaterialTheme.typography.bodySmall,
                                color = Brand.TextPrimary
                            )
                        }
                    }
                }
            }

            if (state.rawOcrText.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showRawOcr = !showRawOcr }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (showRawOcr) "Hide extracted OCR text" else "Verify original OCR text",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Brand.Primary
                    )
                    Icon(
                        imageVector = AppIcons.ChevronDown,
                        contentDescription = null,
                        tint = Brand.Primary,
                        modifier = Modifier.size(14.dp)
                    )
                }

                AnimatedVisibility(visible = showRawOcr) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Brand.Background,
                        border = BorderStroke(1.dp, Brand.MintBorder)
                    ) {
                        Text(
                            text = state.rawOcrText,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = Brand.TextSecondary,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onRescan,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Brand.MintBorder)
                ) {
                    Icon(
                        imageVector = AppIcons.Camera,
                        contentDescription = null,
                        tint = Brand.Primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Scan again",
                        color = Brand.Primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
                OutlinedButton(
                    onClick = onUpload,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Brand.MintBorder)
                ) {
                    Icon(
                        imageVector = AppIcons.Gallery,
                        contentDescription = null,
                        tint = Brand.Primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Upload photo",
                        color = Brand.Primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraPanel(
    hasPermission: Boolean,
    isCapturing: Boolean,
    error: String?,
    cameraContent: @Composable (Modifier) -> Unit,
    onRequestPermission: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(290.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Brand.Deep)
    ) {
        if (hasPermission) {
            cameraContent(Modifier.fillMaxSize())
            ScanFrameOverlay(modifier = Modifier.fillMaxSize())

            Surface(
                shape = RoundedCornerShape(100),
                color = Color.Black.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp)
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
                        text = "Ingredient label",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(100),
                color = Color.Black.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(
                    text = if (isCapturing) {
                        "Capturing photo…"
                    } else {
                        "Keep the whole ingredients panel inside the frame"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            error?.let { message ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Brand.Danger.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(14.dp)
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = AppIcons.Camera,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Text(
                    text = "Camera access is off",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Text(
                    text = "Allow camera access to scan product labels instantly, or upload a photo from your gallery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.TextOnDeepMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp)
                )
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.padding(top = 18.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Brand.Deep
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                ) {
                    Text("Allow camera", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Viewfinder corners plus a scan line drawn over the live preview. */
@Composable
private fun ScanFrameOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val inset = 34.dp.toPx()
        val bracket = 34.dp.toPx()
        val stroke = 3.dp.toPx()
        val left = inset
        val top = size.height * 0.22f
        val right = size.width - inset
        val bottom = size.height * 0.80f

        val frame = Path().apply {
            moveTo(left, top + bracket); lineTo(left, top); lineTo(left + bracket, top)
            moveTo(right - bracket, top); lineTo(right, top); lineTo(right, top + bracket)
            moveTo(right, bottom - bracket); lineTo(right, bottom)
            lineTo(right - bracket, bottom)
            moveTo(left + bracket, bottom); lineTo(left, bottom); lineTo(left, bottom - bracket)
        }
        drawPath(
            path = frame,
            color = Color.White.copy(alpha = 0.94f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )

        drawRoundRect(
            color = Brand.Secondary.copy(alpha = 0.85f),
            topLeft = Offset(left + 8.dp.toPx(), (top + bottom) / 2),
            size = Size(right - left - 16.dp.toPx(), 2.dp.toPx()),
            cornerRadius = CornerRadius(1.dp.toPx())
        )
    }
}

@Composable
private fun ScanControls(
    enabled: Boolean,
    canCapture: Boolean,
    onCapture: () -> Unit,
    onUpload: () -> Unit,
    onType: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        CircleControl(
            icon = AppIcons.Gallery,
            label = "Upload",
            enabled = enabled,
            onClick = onUpload
        )

        Box(
            modifier = Modifier
                .size(78.dp)
                .clip(CircleShape)
                .background(if (enabled && canCapture) Brand.Primary else Brand.Outline),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = onCapture,
                enabled = enabled && canCapture,
                modifier = Modifier.size(78.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = AppIcons.Camera,
                        contentDescription = "Capture the label",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        CircleControl(
            icon = AppIcons.Pencil,
            label = "Type list",
            enabled = enabled,
            onClick = onType
        )
    }
}

@Composable
private fun CircleControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 2.dp
        ) {
            IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(52.dp)) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(21.dp)
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetectedIngredientsCard(
    state: IngredientAnalysisState,
    enabled: Boolean,
    focusRequester: FocusRequester
) {
    var input by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }
    val ingredients = state.ingredients

    AppCard {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Detected Ingredients",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Brand.Soft,
                    border = BorderStroke(1.dp, Brand.Secondary.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = "${ingredients.size}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Brand.Primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Text(
                text = when {
                    state.isProcessingOcr -> (state.step as? IngredientStep.Working)?.message
                        ?: "Reading ingredients on this device…"
                    ingredients.isEmpty() -> "No ingredients extracted yet. Capture a label, upload a " +
                        "photo, or add ingredients below."
                    else -> "Review the extracted list and remove or add items before AI analysis."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )

            if (ingredients.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ingredients.forEach { ingredient ->
                        AppChip(
                            text = ingredient,
                            onRemove = if (enabled) {
                                ({ state.removeIngredient(ingredient) })
                            } else {
                                null
                            }
                        )
                    }
                }
            }

            val submitInput = {
                val added = state.addIngredient(input)
                if (added == 0) {
                    inputError = "Add at least one new ingredient to the list."
                } else {
                    input = ""
                }
            }

            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    inputError = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .focusRequester(focusRequester),
                label = { Text("Add an ingredient") },
                placeholder = { Text("e.g. sugar, palm oil, salt") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (enabled) submitInput() }),
                shape = RoundedCornerShape(14.dp),
                textStyle = MaterialTheme.typography.bodyMedium
            )

            inputError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            OutlinedButton(
                onClick = submitInput,
                enabled = enabled,
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
                    text = "Add to list",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun EngineSection(
    state: IngredientAnalysisState,
    enabled: Boolean
) {
    val nanoAvailable = state.nanoStatus is NanoAvailability.Available

    AppCard {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "AI Model",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Choose where the analysis runs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp)
            )

            Row(
                modifier = Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AiMode.entries.forEach { mode ->
                    EngineChip(
                        label = mode.label,
                        selected = state.aiMode == mode,
                        unavailableHint = mode == AiMode.NANO && state.nanoStatus != null && !nanoAvailable,
                        enabled = enabled,
                        onClick = { state.setMode(mode) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            val engineDetail = when (state.aiMode) {
                AiMode.AUTO -> "Cloud-based Gemini Flash analysis. Only the confirmed ingredient names and approved health profile are sent."
                AiMode.NANO -> "Uses Gemini Nano via Firebase AI Logic on supported Android devices. Never falls back to the cloud."
                AiMode.ON_DEVICE -> "Independent offline analysis on this device. Works without internet, Gemini Nano, or Google AICore."
            }

            Text(
                text = engineDetail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )

            when (state.aiMode) {
                AiMode.AUTO -> ModelModeStatusCard(
                    dotColor = Brand.Secondary,
                    title = "Gemini Flash ready",
                    detail = "Runs in the cloud with Gemini Flash. Gemini Nano is not required."
                )

                AiMode.NANO -> NanoStatus(state = state, enabled = enabled)

                AiMode.ON_DEVICE -> ModelModeStatusCard(
                    dotColor = Brand.Secondary,
                    title = "Offline analyzer ready",
                    detail = "Uses OCR output, local ingredient database, allergy matching, and deterministic safety rules without internet or AICore."
                )
            }
        }
    }
}

@Composable
private fun ModelModeStatusCard(
    dotColor: Color,
    title: String,
    detail: String
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Brand.SoftRaised,
        border = BorderStroke(1.dp, Brand.Outline.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 5.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun EngineChip(
    label: String,
    selected: Boolean,
    unavailableHint: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = when {
            selected -> Brand.Primary
            unavailableHint -> Brand.Soft.copy(alpha = 0.45f)
            else -> Brand.Soft.copy(alpha = 0.85f)
        },
        border = if (selected) {
            null
        } else {
            BorderStroke(1.dp, if (unavailableHint) Brand.Care.copy(alpha = 0.35f) else Brand.MintBorder.copy(alpha = 0.6f))
        },
        contentColor = when {
            selected -> Color.White
            unavailableHint -> Brand.TextSecondary
            else -> Brand.TextPrimary
        }
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 4.dp)
        )
    }
}

@Composable
private fun NanoStatus(state: IngredientAnalysisState, enabled: Boolean) {
    val status = state.nanoStatus

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Brand.SoftRaised,
        border = BorderStroke(1.dp, Brand.Outline.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 5.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        when (status) {
                            is NanoAvailability.Available -> Brand.Secondary
                            is NanoAvailability.Unsupported,
                            is NanoAvailability.Downloadable -> Brand.Care
                            else -> Brand.Outline
                        }
                    )
            )
            Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
                Text(
                    text = "Gemini Nano status",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = when {
                        state.isCheckingNano && status == null -> "Checking Gemini Nano…"
                        status is NanoAvailability.Available -> "Gemini Nano is ready on this device."
                        status is NanoAvailability.Downloadable ->
                            "Gemini Nano can be downloaded. Needs Wi-Fi and about 1 GB."
                        status is NanoAvailability.Downloading -> "Gemini Nano is downloading…"
                        status is NanoAvailability.Unsupported -> status.reason
                        else -> "Checking whether Gemini Nano is supported on this device…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )

                when {
                    state.isDownloadingNano -> LoadingPanel(
                        message = "Downloading Gemini Nano…",
                        fraction = state.downloadFraction,
                        modifier = Modifier.padding(top = 10.dp)
                    )

                    status is NanoAvailability.Downloadable ||
                        status is NanoAvailability.Downloading -> OutlinedButton(
                        onClick = { state.downloadNano() },
                        enabled = enabled,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(top = 8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("Download Gemini Nano", style = MaterialTheme.typography.labelMedium)
                    }

                    else -> OutlinedButton(
                        onClick = { state.checkNano(forceRefresh = true) },
                        enabled = enabled && !state.isCheckingNano,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(top = 8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("Re-check availability", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/** Writes one still into the app cache, then hands the file back on the main thread. */
private fun captureToFile(
    context: Context,
    imageCapture: ImageCapture,
    onSaved: (File) -> Unit,
    onError: (String) -> Unit
) {
    val directory = File(context.cacheDir, "scan-captures")
    if (!directory.exists() && !directory.mkdirs()) {
        Log.e("ScanScreen", "Failed to create capture directory: ${directory.absolutePath}")
        onError("That photo could not be saved. Try again.")
        return
    }
    val file = File(directory, "scan-${System.currentTimeMillis()}.jpg")
    val options = ImageCapture.OutputFileOptions.Builder(file).build()

    try {
        imageCapture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (!file.exists() || file.length() <= 0L) {
                        Log.e("ScanScreen", "Camera capture completed with empty or missing file")
                        runCatching { file.delete() }
                        onError("That photo could not be saved. Try again.")
                        return
                    }
                    onSaved(file)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(
                        "ScanScreen",
                        "Camera capture failed (error=${exception.imageCaptureError}): ${exception.message}",
                        exception
                    )
                    runCatching { file.delete() }
                    onError("That photo could not be saved. Try again.")
                }
            }
        )
    } catch (e: Exception) {
        Log.e("ScanScreen", "Camera capture threw synchronously", e)
        runCatching { file.delete() }
        onError("That photo could not be saved. Try again.")
    }
}
