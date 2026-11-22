//
//  MyHealthView.swift
//  ScanTruthAI
//

import SwiftUI
import UniformTypeIdentifiers

struct MyHealthView: View {
    @State private var healthNoteText: String = ""
    @State private var showFileImporter: Bool = false
    @State private var pendingReview: PendingReview? = nil
    @State private var isProcessing: Bool = false
    @State private var statusMessage: String? = nil

    // "+ Add …" dialog
    @State private var addCategory: HealthCategory? = nil
    @State private var newItemText: String = ""

    // Lists currently in edit mode (each section has its own Edit / Done button)
    @State private var editing: Set<HealthCategory> = []

    // Shared, so the profile survives tab switches (ContentView rebuilds tab views).
    private let store = HealthProfileStore.shared

    /// True when Home's "Analyse Medical Report" sent the user here: open the report picker once.
    @Binding var openReportPicker: Bool

    var body: some View {
        NavigationStack {
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 20) {
                    titleBlock

                    if isProcessing || statusMessage != nil {
                        statusBanner
                    }

                    healthProfileHeroCard

                    knownConditionsSection

                    healthContextSection

                    healthNotesCard

                    medicalDocumentsSection

                    PrivacyNoteCard(
                        title: "Your privacy is protected",
                        text: "Apple Foundation Models check and mask private details on your device before anything is sent to the cloud, and only after you approve the redacted text."
                    )
                }
                .padding(.horizontal, 20)
                .padding(.top, 16)
                .padding(.bottom, 110) // Padding for custom bottom tab bar
            }
            .background(Theme.background.ignoresSafeArea())
            .fileImporter(
                isPresented: $showFileImporter,
                allowedContentTypes: [.pdf, .image],
                allowsMultipleSelection: false
            ) { result in
                switch result {
                case .success(let urls):
                    if let url = urls.first {
                        Task { await handleImportedFile(url) }
                    }
                case .failure(let error):
                    statusMessage = "Could not open the file: \(error.localizedDescription)"
                }
            }
            .sheet(item: $pendingReview) { review in
                PrivacyReviewSheet(
                    gate: review.gate,
                    onApprove: {
                        pendingReview = nil
                        Task { await extractAndSave(review) }
                    },
                    onCancel: {
                        pendingReview = nil
                    }
                )
            }
            .alert(
                addCategory?.addTitle ?? "",
                isPresented: Binding(
                    get: { addCategory != nil },
                    set: { if !$0 { addCategory = nil } }
                )
            ) {
                TextField(addCategory?.placeholder ?? "", text: $newItemText)
                Button("Add") {
                    let category = addCategory
                    let text = newItemText
                    newItemText = ""
                    if let category {
                        Task { await addManualItem(text, to: category) }
                    }
                }
                Button("Cancel", role: .cancel) {
                    newItemText = ""
                }
            }
            .task {
                await store.loadFromCloud()
            }
            .task {
                guard openReportPicker else { return }
                openReportPicker = false
                // Let the tab finish appearing before presenting the picker.
                try? await Task.sleep(for: .milliseconds(350))
                showFileImporter = true
            }
        }
    }

    // MARK: - Title

    private var titleBlock: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("My Health")
                .font(.system(size: 30, weight: .bold))
                .foregroundStyle(Theme.textPrimary)

            Text("Personal health context for tailored scan verdicts and saved reports.")
                .font(.system(size: 13))
                .foregroundStyle(Theme.textSecondary)
        }
    }

    // MARK: - Status Banner

    private var statusBanner: some View {
        HStack(spacing: 10) {
            if isProcessing {
                ProgressView()
            }
            Text(statusMessage ?? "Working...")
                .font(.system(size: 12))
                .foregroundStyle(Theme.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(12)
        .background(Theme.accentMint.opacity(0.7))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - Hero

    private var healthProfileHeroCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                Image(systemName: "heart.text.square")
                    .font(.system(size: 22))
                    .foregroundStyle(Color.white)
                    .frame(width: 48, height: 48)
                    .background(Color.white.opacity(0.14))
                    .clipShape(RoundedRectangle(cornerRadius: 14))

                Text("Build Your Health Context")
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(Color.white)
            }

            Text("The more your profile knows, the more personal every ingredient verdict is. All data stays strictly in your own account.")
                .font(.system(size: 13))
                .foregroundStyle(Color.white.opacity(0.85))
                .fixedSize(horizontal: false, vertical: true)

            HStack(spacing: 6) {
                Circle()
                    .fill(Theme.mintBorder)
                    .frame(width: 6, height: 6)

                Text(heroPillText)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Color.white)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(Color.white.opacity(0.12))
            .clipShape(Capsule())
            .overlay(Capsule().stroke(Color.white.opacity(0.25), lineWidth: 1))
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            LinearGradient(
                colors: [Theme.primary, Theme.primaryDark],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        )
        .clipShape(RoundedRectangle(cornerRadius: 24))
        .shadow(color: Theme.primary.opacity(0.15), radius: 8, x: 0, y: 4)
    }

    private var heroPillText: String {
        switch store.detailCount {
        case 0: return "No details added yet"
        case 1: return "1 detail added"
        default: return "\(store.detailCount) details added"
        }
    }

    // MARK: - Known Conditions

    private var knownConditionsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(
                title: "Known Conditions",
                actionTitle: store.conditions.isEmpty ? nil : (editing.contains(.condition) ? "Done" : "Edit"),
                action: { toggleEditing(.condition) },
                actionIcon: editing.contains(.condition) ? "checkmark" : "pencil"
            )

            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .top, spacing: 12) {
                    IconTile(systemImage: "heart")

                    VStack(alignment: .leading, spacing: 4) {
                        Text(store.conditions.isEmpty ? "No known conditions recorded" : "Recorded conditions")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(Theme.textPrimary)

                        Text(store.conditions.isEmpty
                             ? "Add conditions to make every scan analysis personalized and relevant."
                             : "These personalize every scan analysis.")
                            .font(.system(size: 13))
                            .foregroundStyle(Theme.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }

                if !store.conditions.isEmpty {
                    EditableChipList(items: store.conditions, isEditing: editing.contains(.condition)) { item in
                        Task { await removeItem(item, from: .condition) }
                    }
                }

                OutlinedActionButton(title: HealthCategory.condition.addTitle, systemImage: "plus") {
                    addCategory = .condition
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .primaryCard(cornerRadius: 18)
        }
    }

    // MARK: - Health Context (allergies, medications, dietary restrictions)

    private var healthContextSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: "Health Context")

            contextCard(title: "Allergies", systemImage: "exclamationmark.triangle", category: .allergy)
            contextCard(title: "Medications", systemImage: "pills", category: .medication)
            contextCard(title: "Dietary restrictions", systemImage: "leaf", category: .dietary)
        }
    }

    private func contextCard(title: String, systemImage: String, category: HealthCategory) -> some View {
        let items = store.items(for: category)
        let isEditing = editing.contains(category)

        return VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                IconTile(systemImage: systemImage, size: 40)

                Text(title)
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(Theme.textPrimary)

                Spacer()

                if !items.isEmpty {
                    Button(action: {
                        toggleEditing(category)
                    }) {
                        HStack(spacing: 4) {
                            Text(isEditing ? "Done" : "Edit")
                            Image(systemName: isEditing ? "checkmark" : "pencil")
                                .font(.system(size: 10, weight: .bold))
                        }
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Theme.primary)
                    }
                    .accessibilityLabel("\(isEditing ? "Finish editing" : "Edit") \(title)")
                }
            }

            if items.isEmpty {
                Text("Nothing recorded yet. Add items so scans check for them.")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textSecondary)
            } else {
                EditableChipList(items: items, isEditing: isEditing) { item in
                    Task { await removeItem(item, from: category) }
                }
            }

            OutlinedActionButton(title: category.addTitle, systemImage: "plus") {
                addCategory = category
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .primaryCard(cornerRadius: 18)
    }

    // MARK: - Health notes (typed note -> mask on device -> review -> extract)

    private var healthNotesCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Health notes")
                .font(.system(size: 18, weight: .bold))
                .foregroundStyle(Theme.textPrimary)

            Text("Anything else that should be considered, e.g. a recent diagnosis or a symptom that is not in the lists above.")
                .font(.system(size: 12))
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)

            ZStack(alignment: .topLeading) {
                if healthNoteText.isEmpty {
                    Text("Add a note")
                        .font(.system(size: 14))
                        .foregroundStyle(Theme.textMuted)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                }

                TextEditor(text: $healthNoteText)
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.textPrimary)
                    .scrollContentBackground(.hidden)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
                    .frame(minHeight: 84)
            }
            .background(Color.white)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(Color.black.opacity(0.08), lineWidth: 1)
            )

            FilledActionButton(
                title: "Save note",
                isEnabled: !isProcessing && !healthNoteText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ) {
                Task { await submitTypedNote() }
            }

            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "lock")
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.primary)
                    .padding(.top, 2)

                Text("Your note is masked on this device and shown to you for review before anything is sent to the cloud.")
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .primaryCard(cornerRadius: 18)
    }

    // MARK: - Medical documents

    private var medicalDocumentsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: "Medical Documents")

            ForEach(Array(store.reports.enumerated()), id: \.offset) { _, title in
                HStack(spacing: 12) {
                    IconTile(systemImage: "doc.text", size: 40)

                    Text(title)
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Theme.textPrimary)

                    Spacer()
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .primaryCard(cornerRadius: 16)
            }

            VStack(alignment: .leading, spacing: 12) {
                if store.reports.isEmpty {
                    Text("No medical reports uploaded.")
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textSecondary)
                }

                OutlinedActionButton(title: "Add a medical report", systemImage: "arrow.up.doc") {
                    showFileImporter = true
                }

                Text("PDF or photo. Private details are masked on this device first.")
                    .font(.system(size: 11))
                    .foregroundStyle(Theme.textSecondary)
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .primaryCard(cornerRadius: 18)
        }
    }

    // MARK: - Edit / delete

    private func toggleEditing(_ category: HealthCategory) {
        if editing.contains(category) {
            editing.remove(category)
        } else {
            editing.insert(category)
        }
    }

    /// Removes one item from this device and, when signed in, from the account.
    private func removeItem(_ item: String, from category: HealthCategory) async {
        store.remove(item, from: category)

        // Nothing left to edit in this list.
        if store.items(for: category).isEmpty {
            editing.remove(category)
        }

        guard FirestoreService.canSync else {
            statusMessage = "Removed on this device only. Sign in to keep your account in sync."
            return
        }

        do {
            try await FirestoreService.shared.removeProfileItem(field: category.firestoreField, item: item)
            statusMessage = "Removed \"\(item)\"."
        } catch {
            statusMessage = "Removed on this device, but removing it from your account failed: \(error.localizedDescription)"
        }
    }

    // MARK: - Manual add

    /// Saves one item typed into the "+ Add …" dialog, to this device and, when signed in, to the account.
    private func addManualItem(_ text: String, to category: HealthCategory) async {
        let item = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !item.isEmpty else { return }

        guard HealthCategory.passesBasicCheck(item) else {
            statusMessage = "\"\(item)\" doesn't look like a \(category.singularName). Check the spelling and try again."
            return
        }

        // Only real entries reach the health context; junk would skew every scan verdict.
        isProcessing = true
        statusMessage = "Checking \"\(item)\"..."
        defer { isProcessing = false }

        let validation: HealthItemValidation
        do {
            validation = try await HybridAIService.shared.validateHealthItem(item, category: category)
        } catch {
            statusMessage = "Couldn't verify \"\(item)\" right now, so it wasn't added. Try again."
            return
        }

        guard validation.isValid else {
            statusMessage = "\"\(item)\" doesn't look like a \(category.singularName). Check the spelling and try again."
            return
        }

        let normalized = validation.normalizedName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let finalItem = HealthCategory.passesBasicCheck(normalized) ? normalized : item

        store.add(finalItem, to: category)

        guard FirestoreService.canSync else {
            statusMessage = "Added on this device only. Sign in to save it to your account."
            return
        }

        do {
            try await FirestoreService.shared.mergeExtractedHealthMarkers(
                newConditions: category == .condition ? [finalItem] : [],
                newAllergies: category == .allergy ? [finalItem] : [],
                newMedications: category == .medication ? [finalItem] : [],
                newDietaryRestrictions: category == .dietary ? [finalItem] : []
            )
            statusMessage = "Saved to your account."
        } catch {
            statusMessage = "Added on this device, but saving failed: \(error.localizedDescription)"
        }
    }

    // MARK: - Pipeline (OCR -> on-device privacy gate -> review -> cloud extraction -> save)

    /// Stage 1: read text from the picked PDF or image on-device, then mask it.
    private func handleImportedFile(_ url: URL) async {
        isProcessing = true
        statusMessage = "Reading document on-device..."
        defer { isProcessing = false }

        let isPdf = UTType(filenameExtension: url.pathExtension)?.conforms(to: .pdf) ?? false
        let text: String

        if isPdf {
            text = await Task.detached { OcrService.extractText(fromPdfUrl: url) }.value
        } else {
            let accessed = url.startAccessingSecurityScopedResource()
            defer { if accessed { url.stopAccessingSecurityScopedResource() } }

            guard let data = try? Data(contentsOf: url), let image = UIImage(data: data) else {
                statusMessage = "Could not read that image."
                return
            }
            do {
                text = try await OcrService.extractText(from: image)
            } catch {
                statusMessage = "Could not read text from the image: \(error.localizedDescription)"
                return
            }
        }

        // Generic label on purpose: file names often contain the patient's name.
        await beginReview(
            rawText: text,
            sourceLabel: isPdf ? "Medical report (PDF)" : "Medical report (image)",
            isTypedNote: false
        )
    }

    private func submitTypedNote() async {
        let trimmed = healthNoteText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        isProcessing = true
        defer { isProcessing = false }

        await beginReview(rawText: trimmed, sourceLabel: "Typed health note", isTypedNote: true)
    }

    /// Stage 2: mask private details on-device, then let the user review exactly what will be sent.
    private func beginReview(rawText: String, sourceLabel: String, isTypedNote: Bool) async {
        guard !rawText.isEmpty else {
            statusMessage = "No readable text was found. Scanned PDFs without a text layer are not supported yet; try a photo instead."
            return
        }

        statusMessage = "Masking private details on-device..."
        let json = await HybridAIService.shared.runOnDevicePrivacyGate(rawText: rawText)

        guard let gate = try? JSONDecoder().decode(PrivacyGateResult.self, from: Data(json.utf8)) else {
            statusMessage = "Privacy masking failed, so nothing was sent."
            return
        }

        statusMessage = nil
        if isTypedNote { healthNoteText = "" }
        pendingReview = PendingReview(gate: gate, sourceLabel: sourceLabel, isTypedNote: isTypedNote)
    }

    /// Stage 3: send only the approved, masked text to the cloud model, then merge and save.
    private func extractAndSave(_ review: PendingReview) async {
        isProcessing = true
        statusMessage = "Extracting health markers..."
        defer { isProcessing = false }

        let profile: ExtractedProfile
        do {
            let json = try await HybridAIService.shared.extractMedicalContext(
                sanitizedText: review.gate.redactedText,
                sourceLabel: review.sourceLabel
            )
            profile = try JSONDecoder().decode(ExtractedProfile.self, from: Data(json.utf8))
        } catch {
            statusMessage = "Extraction failed: \(error.localizedDescription)"
            return
        }

        store.merge(profile)
        if !review.isTypedNote {
            store.reports.append(profile.reportTitle ?? review.sourceLabel)
        }

        guard FirestoreService.canSync else {
            statusMessage = "Profile updated on this device only. Sign in to save it to your account."
            return
        }

        do {
            if review.isTypedNote {
                try await FirestoreService.shared.saveTypedHealthNote(
                    userNote: review.gate.redactedText,
                    extractedSummary: (profile.conditions ?? []).joined(separator: ", ")
                )
            } else {
                // Empty profileFields: only adds the report document; arrays are merged below.
                try await FirestoreService.shared.saveMedicalReportAndProfile(
                    reportTitle: profile.reportTitle ?? review.sourceLabel,
                    markersCount: profile.healthMarkers?.count ?? 0,
                    redactedText: review.gate.redactedText,
                    profileFields: [:]
                )
            }
            try await FirestoreService.shared.mergeExtractedHealthMarkers(
                newConditions: profile.conditions ?? [],
                newAllergies: profile.allergies ?? [],
                newMedications: profile.medications ?? [],
                newDietaryRestrictions: profile.dietaryRestrictions ?? [],
                newHealthMarkers: (profile.healthMarkers ?? []).map { $0.dictionary },
                reportTitle: review.isTypedNote ? nil : (profile.reportTitle ?? review.sourceLabel)
            )
            statusMessage = "Profile updated and saved."
        } catch {
            statusMessage = "Profile updated on this device, but saving failed: \(error.localizedDescription)"
        }
    }
}

// MARK: - Privacy Review Sheet
struct PrivacyReviewSheet: View {
    let gate: PrivacyGateResult
    var onApprove: () -> Void
    var onCancel: () -> Void

    private var itemCount: Int { gate.detectedItems?.count ?? 0 }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    // Header Card
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Image(systemName: "lock.shield.fill")
                                .font(.system(size: 22))
                                .foregroundStyle(Theme.primary)
                            Text("On-Device Privacy Gate")
                                .font(.system(size: 18, weight: .bold))
                                .foregroundStyle(Theme.textPrimary)
                        }

                        Text(headerMessage)
                            .font(.system(size: 13))
                            .foregroundStyle(Theme.textSecondary)
                    }
                    .padding(16)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Theme.accentMint)
                    .clipShape(RoundedRectangle(cornerRadius: 16))

                    if itemCount == 0 {
                        Label("No private details were detected. Read the text below carefully before approving.", systemImage: "exclamationmark.triangle")
                            .font(.system(size: 12))
                            .foregroundStyle(Color.orange)
                    }

                    if let items = gate.detectedItems, !items.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("MASKED DETAILS (\(items.count))")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundStyle(Theme.textSecondary)
                                .tracking(1.0)

                            FlowLayout(spacing: 8) {
                                ForEach(items, id: \.self) { item in
                                    Text("\(item.label ?? "Private detail"): \(item.maskedPreview ?? "••••")")
                                        .font(.system(size: 11, weight: .medium))
                                        .foregroundStyle(Theme.textPrimary)
                                        .padding(.horizontal, 10)
                                        .padding(.vertical, 6)
                                        .background(Color.black.opacity(0.05))
                                        .clipShape(Capsule())
                                }
                            }
                        }
                    }

                    // Exactly what will leave the device
                    VStack(alignment: .leading, spacing: 8) {
                        Text("TEXT THAT WILL BE SENT")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(Theme.textSecondary)
                            .tracking(1.0)

                        Text(gate.redactedText)
                            .font(.system(size: 12, design: .monospaced))
                            .foregroundStyle(Theme.textPrimary)
                            .textSelection(.enabled)
                            .padding(14)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color.black.opacity(0.03))
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                    }

                    FilledActionButton(title: "Approve & Extract Profile", systemImage: "checkmark.circle.fill") {
                        onApprove()
                    }
                    .padding(.top, 10)
                }
                .padding(20)
            }
            .navigationTitle("Privacy Review")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        onCancel()
                    }
                    .foregroundStyle(Theme.primary)
                }
            }
            .background(Theme.background.ignoresSafeArea())
        }
    }

    private var headerMessage: String {
        if gate.usedFallback == true {
            return gate.fallbackNotice
                ?? "Apple Foundation Models are unavailable, so a rule-based filter masked the text on your device."
        }
        return "Apple Foundation Models masked personal identifiers locally. Only the text below is sent for analysis."
    }
}

#Preview {
    // Sample data so the Edit buttons and chips are visible in the canvas.
    HealthProfileStore.shared.merge(ExtractedProfile(
        conditions: ["Hypertension", "Pre-diabetic"],
        allergies: ["Peanuts", "Lactose"],
        medications: ["Metformin 500 mg"],
        dietaryRestrictions: ["Low sodium", "Low added sugar"]
    ))
    return MyHealthView(openReportPicker: .constant(false))
}
