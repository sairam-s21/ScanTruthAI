package com.scantruth.ai.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.scantruth.ai.HybridAIService.NanoAvailability
import com.scantruth.ai.model.AiMode
import com.scantruth.ai.ui.components.AppCard
import com.scantruth.ai.ui.components.AppIcons
import com.scantruth.ai.ui.components.CameraPreview
import com.scantruth.ai.ui.components.LoadingPanel
import com.scantruth.ai.ui.components.SoftBanner
import com.scantruth.ai.ui.components.documentDisplayName
import com.scantruth.ai.ui.components.rememberCameraPreviewState
import com.scantruth.ai.ui.state.IngredientAnalysisState
import com.scantruth.ai.ui.state.IngredientStep
import com.scantruth.ai.ui.theme.Brand
import java.io.File

/**
 * The Scan tab: capture an ingredient label, upload a photo, or type the list.
 *
 * OCR runs on this device and only produces an ingredient list; nothing reaches a model
 * until the user confirms the list, picks an engine and taps Analyze Ingredients.
 * Medical reports are handled from Home and My Health, never here.
 */
@Composable
fun ScanScreen(
    ingredientState: IngredientAnalysisState,
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

    BackHandler(enabled = isBusy) {
        ingredientState.cancel()
    }

    LaunchedEffect(Unit) {
        ingredientState.checkNano()
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
        if (uri != null) {
            readLabel(
                state = ingredientState,
                context = context,
                uri = uri,
                displayName = documentDisplayName(context, uri, "Ingredient label")
            )
            cameraError = null
        }
    }

    val onCapture: () -> Unit = {
        val capture = cameraState.imageCapture
        cameraError = null
        if (capture == null) {
            cameraError = "The camera is still starting. Try again in a moment."
        } else {
            captureToFile(
                context = context,
                imageCapture = capture,
                onSaved = { file ->
                    readLabel(
                        state = ingredientState,
                        context = context,
                        uri = Uri.fromFile(file),
                        displayName = file.name
                    )
                },
                onError = { message -> cameraError = message }
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
        ScanToolbar(onBack = onBack)

        CameraPanel(
            hasPermission = hasCameraPermission,
            isBusy = ingredientState.isReadingLabel,
            error = cameraError ?: cameraState.errorMessage,
            cameraContent = { previewModifier ->
                CameraPreview(state = cameraState, modifier = previewModifier)
            },
            onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) }
        )

        ScanControls(
            enabled = !isBusy,
            canCapture = cameraState.imageCapture != null,
            onCapture = onCapture,
            onUpload = {
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onType = { manualFocus.requestFocus() }
        )

        DetectedIngredientsCard(
            state = ingredientState,
            focusRequester = manualFocus
        )

        EngineSection(
            state = ingredientState,
            enabled = !isBusy
        )

        Button(
            onClick = {
                ingredientState.analyze()
            },
            enabled = ingredientState.ingredients.isNotEmpty() && !isBusy,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Brand.Primary,
                contentColor = Color.White,
                disabledContainerColor = Brand.Primary.copy(alpha = 0.45f),
                disabledContentColor = Color.White
            )
        ) {
            if (!ingredientState.isAnalyzing) {
                Icon(
                    imageVector = AppIcons.Sparkle,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp)
                )
            }
            Text(
                text = if (ingredientState.isAnalyzing) "Analyzing..." else "Analyze Ingredients",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        ingredientState.analysisError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        SoftBanner(
            icon = AppIcons.ShieldCheck,
            title = "Read on this device",
            body = "The label photo stays on your phone. Only the ingredient names and the " +
                "health context you approved are sent for analysis."
        )
    }

    val result = ingredientState.analysis
    if (result != null && ingredientState.step == IngredientStep.Ready) {
        SavedAnalysisSheet(
            record = result.toRecord(ingredientState.ingredients),
            fallbackNotice = result.fallbackNotice,
            onDismiss = { ingredientState.clearAnalysis() }
        )
    }
}

@Composable
private fun ScanToolbar(onBack: () -> Unit) {
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
                text = "Scan ingredients",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Point at the ingredient list on the pack",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun CameraPanel(
    hasPermission: Boolean,
    isBusy: Boolean,
    error: String?,
    cameraContent: @Composable (Modifier) -> Unit,
    onRequestPermission: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
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
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Text(
                    text = if (isBusy) "Hold still — reading the label…" else
                        "Keep the whole ingredients panel inside the frame",
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
        val bottom = size.height * 0.82f

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

        // Primary capture control: a deep-green shutter with white halo ring.
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
    focusRequester: FocusRequester
) {
    var input by remember { mutableStateOf("") }
    val ingredients = state.ingredients
    val addTyped = {
        state.addIngredient(input)
        input = ""
    }

    AppCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Detected Ingredients",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Surface(shape = RoundedCornerShape(50), color = Brand.Soft) {
                    Text(
                        text = "${ingredients.size}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Brand.Primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            if (ingredients.isEmpty()) {
                Text(
                    text = state.ocrMessage
                        ?: "Nothing detected yet. Capture a label, upload a photo, or type the ingredients below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ingredients.forEach { ingredient ->
                        com.scantruth.ai.ui.components.AppChip(
                            text = ingredient,
                            onRemove = { state.removeIngredient(ingredient) }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                label = { Text("Add an ingredient") },
                placeholder = { Text("e.g. sugar, palm oil, salt") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addTyped() }),
                shape = RoundedCornerShape(14.dp),
                textStyle = MaterialTheme.typography.bodyMedium
            )

            OutlinedButton(
                onClick = addTyped,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Brand.Primary.copy(alpha = 0.25f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Primary)
            ) {
                Icon(
                    imageVector = AppIcons.Plus,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "Add to list",
                    style = MaterialTheme.typography.labelLarge,
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
                        enabled = enabled,
                        onClick = { state.setMode(mode) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Text(
                text = when (state.aiMode) {
                    AiMode.AUTO -> "Gemini cloud model when it is reachable, otherwise Gemini " +
                        "Nano on this device. The result always names the engine used."
                    AiMode.FLASH -> "The Gemini cloud model. Only ingredient names and your " +
                        "approved profile are sent."
                    AiMode.NANO -> "Stays on this device using Gemini Nano. Never falls back to " +
                        "the cloud."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )

            NanoStatus(state = state, enabled = enabled)
        }
    }
}

@Composable
private fun EngineChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            Brand.SoftRaised
        },
        border = if (selected) null else BorderStroke(1.dp, Brand.Outline.copy(alpha = 0.5f)),
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 13.dp, horizontal = 6.dp)
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
                        if (status is NanoAvailability.Available) Color(0xFF33A666) else Color(0xFFF2A633)
                    )
            )
            Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
                Text(
                    text = "On-device model status",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = when {
                        state.isCheckingNano && status == null -> "Checking Gemini Nano…"
                        status is NanoAvailability.Available -> "Gemini Nano is ready on this device."
                        status is NanoAvailability.Downloadable ->
                            "Gemini Nano is supported but not downloaded yet. Download it to use " +
                                "on-device analysis."
                        status is NanoAvailability.Downloading ->
                            "Gemini Nano is downloading on this device. Check again when it finishes."
                        else -> "Gemini Nano is not available on this device, so on-device " +
                            "analysis cannot run here. Choose Auto or Gemini Flash to use the " +
                            "cloud model instead."
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
                        onClick = { state.checkNano() },
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

private fun readLabel(
    state: IngredientAnalysisState,
    context: Context,
    uri: Uri,
    displayName: String
) {
    state.readLabel(context = context, uri = uri, displayName = displayName)
}

/** Writes one still into the app cache, then hands the file back on the main thread. */
private fun captureToFile(
    context: Context,
    imageCapture: ImageCapture,
    onSaved: (File) -> Unit,
    onError: (String) -> Unit
) {
    val directory = File(context.cacheDir, "scan-captures").apply { mkdirs() }
    val file = File(directory, "scan-${System.currentTimeMillis()}.jpg")
    val options = ImageCapture.OutputFileOptions.Builder(file).build()

    imageCapture.takePicture(
        options,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                onSaved(file)
            }

            override fun onError(exception: ImageCaptureException) {
                onError("That photo could not be saved. Try again.")
            }
        }
    )
}
