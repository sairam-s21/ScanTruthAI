//
//  ScanView.swift
//  ScanTruthAI
//

import SwiftUI
import PhotosUI

enum AIModelOption: String, CaseIterable, Identifiable {
    case auto, flash, nano

    var id: String { rawValue }

    /// Mode string understood by `HybridAIService.analyzeIngredients`.
    var mode: String { rawValue }

    var title: String {
        switch self {
        case .auto: return "Auto"
        case .flash: return "Gemini Flash"
        case .nano: return "On-device"
        }
    }

    var detail: String {
        switch self {
        case .auto:
            return "Gemini cloud model when it is reachable, otherwise Apple's on-device model. The result always names the engine used."
        case .flash:
            return "The Gemini cloud model. Only ingredient names and your approved profile are sent."
        case .nano:
            return "Stays on this device using Apple Foundation Models. Never falls back to the cloud."
        }
    }
}

/// Identifiable wrapper so the result can drive `.sheet(item:)`.
struct AnalysisOutcome: Identifiable {
    let id = UUID()
    let result: AnalysisResult
    let modelUsedLabel: String
    let fallbackNotice: String?
}

struct ScanView: View {
    @State private var cameraService = CameraService()
    @State private var selectedModel: AIModelOption = .auto
    @State private var detectedIngredients: [String] = []
    @State private var manualIngredientText: String = ""
    @State private var selectedPhotoItem: PhotosPickerItem? = nil
    @State private var isProcessingOcr: Bool = false
    @State private var ocrStatusMessage: String? = nil
    @State private var isAnalyzing: Bool = false
    @State private var analysisError: String? = nil
    @State private var analysisOutcome: AnalysisOutcome? = nil
    @State private var onDeviceAvailable: Bool = HybridAIService.shared.isOnDeviceModelAvailable
    @FocusState private var isIngredientFieldFocused: Bool

    var onBack: (() -> Void)? = nil

    var body: some View {
        NavigationStack {
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 18) {
                    header

                    viewfinderCard

                    captureControls

                    detectedIngredientsCard

                    aiModelCard

                    analyzeSection

                    PrivacyNoteCard(
                        title: "Read on this device",
                        text: "The label photo stays on your phone. Only the ingredient names and the health context you approved are sent for analysis."
                    )
                }
                .padding(.horizontal, 20)
                .padding(.top, 16)
                .padding(.bottom, 110) // Padding for custom bottom tab bar
            }
            .background(Theme.background.ignoresSafeArea())
            .scrollDismissesKeyboard(.interactively)
            .onAppear {
                cameraService.checkPermissionsAndSetup()
                onDeviceAvailable = HybridAIService.shared.isOnDeviceModelAvailable
            }
            .onDisappear {
                cameraService.stopSession()
            }
            .onChange(of: selectedPhotoItem) { _, newItem in
                guard let newItem else { return }
                Task {
                    await handlePhotoPickerSelection(item: newItem)
                }
            }
            .sheet(item: $analysisOutcome) { outcome in
                AnalysisResultView(
                    result: outcome.result,
                    modelUsedLabel: outcome.modelUsedLabel,
                    fallbackNotice: outcome.fallbackNotice,
                    onDismiss: { analysisOutcome = nil }
                )
            }
        }
    }

    // MARK: - Header

    private var header: some View {
        HStack(spacing: 14) {
            Button(action: {
                onBack?()
            }) {
                Image(systemName: "arrow.left")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Theme.textPrimary)
                    .frame(width: 44, height: 44)
                    .background(Color.white)
                    .clipShape(Circle())
                    .overlay(
                        Circle().stroke(Color.black.opacity(0.06), lineWidth: 1)
                    )
            }
            .accessibilityLabel("Back")

            VStack(alignment: .leading, spacing: 3) {
                Text("Scan ingredients")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(Theme.textPrimary)

                Text("Point at the ingredient list on the pack")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textSecondary)
            }

            Spacer()
        }
    }

    // MARK: - Viewfinder

    private var viewfinderCard: some View {
        ZStack {
            if cameraService.isRunning && cameraService.isCameraAuthorized {
                CameraPreviewView(session: cameraService.session)
            } else {
                // No camera (Simulator, or access not granted): sample label illustration.
                Color(red: 0.94, green: 0.93, blue: 0.88)

                VStack(spacing: 8) {
                    Text("NUTRITION FACTS")
                        .font(.system(size: 13, weight: .heavy))
                        .foregroundStyle(Color.black.opacity(0.85))
                        .tracking(0.5)

                    Rectangle()
                        .fill(Color.black.opacity(0.75))
                        .frame(width: 140, height: 4)

                    ForEach(0..<3) { _ in
                        Rectangle()
                            .fill(Color.black.opacity(0.4))
                            .frame(width: 120, height: 2.5)
                    }
                }
                .padding(18)
                .background(Color.white.opacity(0.95))
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .shadow(color: Color.black.opacity(0.06), radius: 6, x: 0, y: 3)
                .rotationEffect(.degrees(-3))
            }

            // Scan line and corner brackets
            Rectangle()
                .fill(Color(red: 0.35, green: 0.82, blue: 0.65).opacity(0.7))
                .frame(height: 2)
                .padding(.horizontal, 28)

            ViewfinderCorners(cornerLength: 28, cornerRadius: 10)
                .stroke(Color.white.opacity(0.9), lineWidth: 3)
                .padding(EdgeInsets(top: 50, leading: 30, bottom: 56, trailing: 30))

            if isProcessingOcr {
                ProgressView()
                    .tint(Theme.primary)
                    .scaleEffect(1.3)
                    .padding(16)
                    .background(Color.white.opacity(0.85))
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }
        }
        .frame(height: 250)
        .overlay(alignment: .topLeading) {
            HStack(spacing: 6) {
                Circle()
                    .fill(Color(red: 0.35, green: 0.82, blue: 0.65))
                    .frame(width: 6, height: 6)
                Text("Ingredient label")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(Color.white)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(Color.black.opacity(0.55))
            .clipShape(Capsule())
            .padding(14)
        }
        .overlay(alignment: .bottom) {
            Text("Keep the whole ingredients panel inside the frame")
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(Color.white)
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(Color.black.opacity(0.55))
                .clipShape(Capsule())
                .padding(.bottom, 14)
        }
        .clipShape(RoundedRectangle(cornerRadius: 26))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Camera viewfinder. Keep the whole ingredients panel inside the frame.")
    }

    // MARK: - Upload / shutter / Type list

    private var captureControls: some View {
        HStack(alignment: .center) {
            PhotosPicker(selection: $selectedPhotoItem, matching: .images) {
                circleControl(systemImage: "photo", label: "Upload")
            }

            Spacer()

            Button(action: {
                Task { await captureAndProcess() }
            }) {
                Image(systemName: isProcessingOcr ? "sparkles" : "camera")
                    .font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(Color.white)
                    .frame(width: 76, height: 76)
                    .background(Theme.primary)
                    .clipShape(Circle())
                    .overlay(
                        Circle().stroke(Theme.mintBorder, lineWidth: 5).padding(-6)
                    )
                    .shadow(color: Theme.primary.opacity(0.25), radius: 8, x: 0, y: 4)
            }
            .disabled(isProcessingOcr)
            .accessibilityLabel("Capture photo of the ingredient list")

            Spacer()

            Button(action: {
                isIngredientFieldFocused = true
            }) {
                circleControl(systemImage: "pencil", label: "Type list")
            }
        }
        .padding(.horizontal, 24)
        .padding(.top, 4)
    }

    private func circleControl(systemImage: String, label: String) -> some View {
        VStack(spacing: 6) {
            Image(systemName: systemImage)
                .font(.system(size: 18, weight: .medium))
                .foregroundStyle(Theme.primary)
                .frame(width: 52, height: 52)
                .background(Color.white)
                .clipShape(Circle())
                .overlay(
                    Circle().stroke(Color.black.opacity(0.08), lineWidth: 1)
                )

            Text(label)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(Theme.textPrimary)
        }
    }

    // MARK: - Detected ingredients

    private var detectedIngredientsCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("Detected Ingredients")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(Theme.textPrimary)

                Spacer()

                Text("\(detectedIngredients.count)")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(Theme.primary)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(Theme.accentMint)
                    .clipShape(Capsule())
            }

            if detectedIngredients.isEmpty {
                Text(ocrStatusMessage ?? "Nothing detected yet. Capture a label, upload a photo, or type the ingredients below.")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                FlowLayout(spacing: 8) {
                    ForEach(detectedIngredients, id: \.self) { ingredient in
                        HStack(spacing: 6) {
                            Text(ingredient)
                                .font(.system(size: 13, weight: .medium))
                                .foregroundStyle(Theme.textPrimary)

                            Button(action: {
                                detectedIngredients.removeAll { $0 == ingredient }
                            }) {
                                Image(systemName: "xmark")
                                    .font(.system(size: 10, weight: .bold))
                                    .foregroundStyle(Theme.textSecondary)
                            }
                            .accessibilityLabel("Remove \(ingredient)")
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(Theme.accentMint)
                        .clipShape(Capsule())
                    }
                }
            }

            VStack(alignment: .leading, spacing: 4) {
                Text("Add an ingredient")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(Theme.primary)

                TextField("e.g. sugar, palm oil, salt", text: $manualIngredientText)
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.textPrimary)
                    .focused($isIngredientFieldFocused)
                    .submitLabel(.done)
                    .onSubmit(addTypedIngredients)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(Color.white)
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .stroke(isIngredientFieldFocused ? Theme.primary : Color.black.opacity(0.12),
                            lineWidth: isIngredientFieldFocused ? 1.5 : 1)
            )

            OutlinedActionButton(title: "Add to list", systemImage: "plus", fullWidth: true) {
                addTypedIngredients()
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .primaryCard(cornerRadius: 20)
    }

    /// Adds what was typed; several ingredients can be separated by commas.
    private func addTypedIngredients() {
        let parts = manualIngredientText
            .components(separatedBy: CharacterSet(charactersIn: ",\n"))
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }

        for part in parts where !detectedIngredients.contains(part) {
            detectedIngredients.append(part)
        }
        manualIngredientText = ""
    }

    // MARK: - AI model

    private var aiModelCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text("AI Model")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(Theme.textPrimary)

                Text("Choose where the analysis runs.")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textSecondary)
            }

            HStack(spacing: 8) {
                ForEach(AIModelOption.allCases) { option in
                    let isSelected = selectedModel == option

                    Button(action: {
                        selectedModel = option
                    }) {
                        Text(option.title)
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(isSelected ? Color.white : Theme.textPrimary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(isSelected ? Theme.primary : Theme.accentMint.opacity(0.8))
                            .clipShape(RoundedRectangle(cornerRadius: 14))
                    }
                    .accessibilityAddTraits(isSelected ? .isSelected : [])
                }
            }

            Text(selectedModel.detail)
                .font(.system(size: 12))
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)

            onDeviceStatusBox
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .primaryCard(cornerRadius: 20)
    }

    private var onDeviceStatusBox: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Circle()
                    .fill(onDeviceAvailable ? Color(red: 0.2, green: 0.65, blue: 0.4) : Theme.amberWarning)
                    .frame(width: 7, height: 7)

                Text("On-device model status")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Theme.textPrimary)
            }

            Text(onDeviceAvailable
                 ? "Apple Foundation Models are ready on this device."
                 : "Apple Foundation Models are not available on this device, so on-device analysis cannot run here. Choose Auto or Gemini Flash to use the cloud model instead.")
                .font(.system(size: 12))
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)

            OutlinedActionButton(title: "Re-check availability") {
                onDeviceAvailable = HybridAIService.shared.isOnDeviceModelAvailable
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.accentMint.opacity(0.6))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    // MARK: - Analyze

    private var analyzeSection: some View {
        VStack(spacing: 8) {
            FilledActionButton(
                title: isAnalyzing ? "Analyzing..." : "Analyze Ingredients",
                systemImage: isAnalyzing ? nil : "sparkles",
                isEnabled: !isAnalyzing && !detectedIngredients.isEmpty
            ) {
                Task { await analyzeIngredients() }
            }

            if let analysisError {
                Text(analysisError)
                    .font(.system(size: 12))
                    .foregroundStyle(Color.red.opacity(0.85))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    // MARK: - Stage 4 Analysis
    private func analyzeIngredients() async {
        isAnalyzing = true
        analysisError = nil
        defer { isAnalyzing = false }

        // Profile is optional: without sign-in / Firebase the analysis runs with an empty profile.
        let profileJson = await FirestoreService.shared.medicalProfileJson()

        do {
            let output = try await HybridAIService.shared.analyzeIngredients(
                ingredients: detectedIngredients,
                medicalProfileJson: profileJson,
                mode: selectedModel.mode
            )
            let result = try AnalysisResult.decode(from: output.json)
            analysisOutcome = AnalysisOutcome(
                result: result,
                modelUsedLabel: output.modelUsedLabel,
                fallbackNotice: output.fallbackNotice
            )
            ScanHistoryStore.shared.record(ScanRecord(
                result: result,
                ingredients: detectedIngredients,
                modelUsedLabel: output.modelUsedLabel,
                scannedAt: Date(),
                ownerUID: AuthService.shared.uid
            ))
            await saveScanHistory(result: result, modelUsedLabel: output.modelUsedLabel)
        } catch {
            analysisError = "Analysis failed: \(error.localizedDescription)"
        }
    }

    /// Best-effort: history is only saved when Firebase is configured and a user is signed in.
    private func saveScanHistory(result: AnalysisResult, modelUsedLabel: String) async {
        guard FirestoreService.canSync,
              let data = try? JSONEncoder().encode(result),
              var payload = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return }

        // Keys must match the shared schema (docx section 3D / SPEC.md) so Web and Android read them.
        payload["rawIngredients"] = detectedIngredients
        payload["modelUsed"] = modelUsedLabel
        payload["safetyScore"] = result.clampedScore ?? 0
        payload["badgeLabel"] = result.displayBadge
        do {
            try await FirestoreService.shared.saveScanHistory(scanPayload: payload)
        } catch {
            // Do not fail silently: the scan would be missing from History and from the other apps.
            analysisError = "Analysis finished, but saving it to your account failed: \(error.localizedDescription)"
        }
    }

    // MARK: - OCR Processing Actions
    private func captureAndProcess() async {
        isProcessingOcr = true
        ocrStatusMessage = "Analyzing label on-device..."

        defer {
            isProcessingOcr = false
        }

        // 1. If running on physical device with active camera, capture photo
        if let image = await cameraService.capturePhoto() {
            await runOcr(on: image)
        } else {
            #if targetEnvironment(simulator)
            // Demo ingredients only on the simulator; never invent data on a real device.
            simulateScanExtraction()
            #else
            ocrStatusMessage = "Camera unavailable. Allow camera access in Settings or upload a photo instead."
            #endif
        }
    }

    private func handlePhotoPickerSelection(item: PhotosPickerItem) async {
        isProcessingOcr = true
        ocrStatusMessage = "Reading image..."

        defer {
            isProcessingOcr = false
            // Lets the same photo be picked again later.
            selectedPhotoItem = nil
        }

        if let data = try? await item.loadTransferable(type: Data.self),
           let image = UIImage(data: data) {
            await runOcr(on: image)
        } else {
            ocrStatusMessage = "Could not read that photo. Try another one."
        }
    }

    private func runOcr(on image: UIImage) async {
        do {
            let extractedText = try await OcrService.extractText(from: image)
            let ingredients = OcrService.parseIngredients(from: extractedText)

            if ingredients.isEmpty {
                // Do not guess by turning every line of the label into an "ingredient".
                ocrStatusMessage = "Could not find an ingredients list in that photo. Keep the word \"Ingredients\" in the frame, or type them below."
            } else {
                self.detectedIngredients = ingredients
            }
        } catch {
            ocrStatusMessage = "Error scanning image: \(error.localizedDescription)"
        }
    }

    private func simulateScanExtraction() {
        // Fallback demo sample for Simulator testing
        let demoIngredients = ["Whole grain oats", "Honey", "Almonds", "Sea salt", "Natural vanilla extract"]
        for item in demoIngredients {
            if !detectedIngredients.contains(item) {
                detectedIngredients.append(item)
            }
        }
    }
}

// MARK: - Viewfinder Corner Shape (four rounded L-shaped corners)
struct ViewfinderCorners: Shape {
    var cornerLength: CGFloat = 26
    var cornerRadius: CGFloat = 8

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let l = cornerLength
        let r = cornerRadius

        // Top-left
        path.move(to: CGPoint(x: rect.minX, y: rect.minY + l))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + r))
        path.addQuadCurve(
            to: CGPoint(x: rect.minX + r, y: rect.minY),
            control: CGPoint(x: rect.minX, y: rect.minY)
        )
        path.addLine(to: CGPoint(x: rect.minX + l, y: rect.minY))

        // Top-right
        path.move(to: CGPoint(x: rect.maxX - l, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX - r, y: rect.minY))
        path.addQuadCurve(
            to: CGPoint(x: rect.maxX, y: rect.minY + r),
            control: CGPoint(x: rect.maxX, y: rect.minY)
        )
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY + l))

        // Bottom-right
        path.move(to: CGPoint(x: rect.maxX, y: rect.maxY - l))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - r))
        path.addQuadCurve(
            to: CGPoint(x: rect.maxX - r, y: rect.maxY),
            control: CGPoint(x: rect.maxX, y: rect.maxY)
        )
        path.addLine(to: CGPoint(x: rect.maxX - l, y: rect.maxY))

        // Bottom-left
        path.move(to: CGPoint(x: rect.minX + l, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX + r, y: rect.maxY))
        path.addQuadCurve(
            to: CGPoint(x: rect.minX, y: rect.maxY - r),
            control: CGPoint(x: rect.minX, y: rect.maxY)
        )
        path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY - l))

        return path
    }
}

// MARK: - Simple Flow Layout for Chips
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 0
        var height: CGFloat = 0
        var currentX: CGFloat = 0
        var currentY: CGFloat = 0
        var maxRowHeight: CGFloat = 0

        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if currentX + size.width > width, currentX > 0 {
                currentX = 0
                currentY += maxRowHeight + spacing
                maxRowHeight = 0
            }
            currentX += size.width + spacing
            maxRowHeight = max(maxRowHeight, size.height)
            height = max(height, currentY + maxRowHeight)
        }
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var currentX: CGFloat = bounds.minX
        var currentY: CGFloat = bounds.minY
        var maxRowHeight: CGFloat = 0

        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if currentX + size.width > bounds.maxX, currentX > bounds.minX {
                currentX = bounds.minX
                currentY += maxRowHeight + spacing
                maxRowHeight = 0
            }
            view.place(at: CGPoint(x: currentX, y: currentY), proposal: .unspecified)
            currentX += size.width + spacing
            maxRowHeight = max(maxRowHeight, size.height)
        }
    }
}

#Preview {
    ScanView()
}
