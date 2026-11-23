//
//  ProfileView.swift
//  ScanTruthAI
//

import SwiftUI

struct ProfileView: View {
    private let auth = AuthService.shared

    // Settings from the web app's Profile cards. Stored on this device.
    @AppStorage(AppPreferences.criticalAllergenAlerts) private var allergenAlerts: Bool = true
    @AppStorage(AppPreferences.intoleranceAlerts) private var intoleranceAlerts: Bool = true
    @AppStorage(AppPreferences.weeklyDigest) private var weeklyDigest: Bool = false
    @AppStorage(AppPreferences.strictSafetyPenalties) private var strictPenalties: Bool = true

    @State private var aboutExpanded: Bool = false
    @State private var privacyExpanded: Bool = false
    @State private var aiExpanded: Bool = false
    @State private var alertsExpanded: Bool = false
    @State private var securityExpanded: Bool = false

    @State private var showClearConfirmation: Bool = false
    @State private var clearedMessage: String? = nil
    @State private var notificationNote: String? = nil

    var body: some View {
        NavigationStack {
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 18) {
                    BrandHeader(subtitle: "ACCOUNT & PRIVACY")

                    Text("Profile")
                        .font(.system(size: 30, weight: .bold))
                        .foregroundStyle(Theme.textPrimary)

                    if auth.isSignedIn {
                        signedInCard
                    } else {
                        signedOutCard
                    }

                    if let message = auth.errorMessage {
                        Text(message)
                            .font(.system(size: 12))
                            .foregroundStyle(Color.red.opacity(0.85))
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    privacyCard

                    aiPreferencesCard

                    alertSettingsCard

                    securityCard
                }
                .padding(.horizontal, 20)
                .padding(.top, 16)
                .padding(.bottom, 110) // Padding for custom bottom tab bar
            }
            .background(Theme.background.ignoresSafeArea())
            .confirmationDialog(
                "Clear session health context?",
                isPresented: $showClearConfirmation,
                titleVisibility: .visible
            ) {
                Button("Clear", role: .destructive) {
                    HealthProfileStore.shared.reset()
                    ScanHistoryStore.shared.reset()
                    clearedMessage = "Cleared from this device. Data saved in your account is not deleted and loads again when you reopen the app."
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This removes the health details and scans held on this device for this session. Your saved account data is not deleted.")
            }
        }
    }

    // MARK: - Account (signed in)

    private var signedInCard: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 14) {
                avatar

                VStack(alignment: .leading, spacing: 4) {
                    Text(auth.displayName ?? "Signed in")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundStyle(Theme.textPrimary)
                        .lineLimit(1)

                    Text(auth.email ?? "")
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textSecondary)
                        .lineLimit(1)

                    HStack(spacing: 5) {
                        Circle()
                            .fill(Theme.primary)
                            .frame(width: 6, height: 6)
                        Text("Signed in with \(auth.providerLabel ?? "your account")")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(Theme.primary)
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(Theme.accentMint)
                    .clipShape(Capsule())
                    .padding(.top, 2)
                }

                Spacer(minLength: 0)
            }

            Divider()

            aboutSection

            OutlinedActionButton(
                title: "Sign out",
                systemImage: "rectangle.portrait.and.arrow.right",
                tint: Color.red.opacity(0.85),
                fullWidth: true,
                isLarge: true
            ) {
                auth.signOut()
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .primaryCard(cornerRadius: 22)
    }

    /// Account ID, member-since date and sign-in method live behind "About" instead of on the main card.
    private var aboutSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Button(action: {
                withAnimation(.easeInOut(duration: 0.2)) {
                    aboutExpanded.toggle()
                }
            }) {
                HStack(spacing: 8) {
                    Image(systemName: "info.circle")
                        .font(.system(size: 14))

                    Text("About")
                        .font(.system(size: 14, weight: .semibold))

                    Spacer()

                    Image(systemName: "chevron.down")
                        .font(.system(size: 12, weight: .semibold))
                        .rotationEffect(.degrees(aboutExpanded ? 180 : 0))
                }
                .foregroundStyle(Theme.primary)
            }
            .accessibilityLabel("About. \(aboutExpanded ? "Expanded" : "Collapsed")")

            if aboutExpanded {
                VStack(alignment: .leading, spacing: 12) {
                    infoRow(label: "Account ID", value: auth.uid ?? "-", monospaced: true)
                    infoRow(
                        label: "Member since",
                        value: auth.memberSince.map { $0.formatted(.dateTime.day().month(.abbreviated).year()) } ?? "-"
                    )
                    infoRow(label: "Sign-in method", value: auth.providerLabel ?? "-")
                }
            }
        }
    }

    private var avatar: some View {
        AsyncImage(url: auth.photoURL) { image in
            image
                .resizable()
                .scaledToFill()
        } placeholder: {
            Image(systemName: "person.fill")
                .font(.system(size: 26))
                .foregroundStyle(Color.white)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Theme.primary)
        }
        .frame(width: 64, height: 64)
        .clipShape(Circle())
        .accessibilityHidden(true)
    }

    private func infoRow(label: String, value: String, monospaced: Bool = false) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(label)
                .font(.system(size: 12))
                .foregroundStyle(Theme.textSecondary)
                .frame(width: 96, alignment: .leading)

            Text(value)
                .font(.system(size: 12, weight: .medium, design: monospaced ? .monospaced : .default))
                .foregroundStyle(Theme.primary)
                .lineLimit(1)
                .truncationMode(.middle)
                .textSelection(.enabled)
        }
    }

    // MARK: - Account (signed out)

    private var signedOutCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 14) {
                Image(systemName: "person.fill")
                    .font(.system(size: 26))
                    .foregroundStyle(Color.white)
                    .frame(width: 64, height: 64)
                    .background(Theme.primary)
                    .clipShape(Circle())

                VStack(alignment: .leading, spacing: 4) {
                    Text("You are not signed in")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(Theme.textPrimary)

                    Text("Sign in to save your health profile and scan history to your account, and see them on every device.")
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            FilledActionButton(
                title: auth.isWorking ? "Signing in..." : "Sign in with Google",
                systemImage: auth.isWorking ? nil : "person.badge.key.fill",
                isEnabled: !auth.isWorking
            ) {
                Task { await auth.signInWithGoogle() }
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .primaryCard(cornerRadius: 22)
    }

    // MARK: - Privacy & Data (web: "On-device Privacy Shield")

    private var privacyCard: some View {
        expandableCard(
            title: "Privacy & Data",
            subtitle: "What leaves this device, and when",
            systemImage: "checkmark.shield",
            tint: Theme.primary,
            background: Theme.accentMint,
            isExpanded: $privacyExpanded
        ) {
            VStack(alignment: .leading, spacing: 14) {
                infoRows([
                    (
                        "HIPAA Safe Harbor & ABDM Redaction",
                        "Patient names, doctor references, medical record IDs, Aadhaar/SSN, phone numbers and dates of birth are automatically stripped on this device before any health analysis."
                    ),
                    (
                        "Clinical Data Preservation",
                        "Only anonymized clinical markers, diagnoses, lab ranges and allergies are kept to personalize product safety checks."
                    ),
                    (
                        "Local Storage Isolation",
                        "Your health context is stored under your own account. This device keeps only a temporary cache in the app's private storage."
                    )
                ])

                OutlinedActionButton(
                    title: "Clear session health context",
                    tint: Color.red.opacity(0.85),
                    fullWidth: true
                ) {
                    showClearConfirmation = true
                }

                if let clearedMessage {
                    Text(clearedMessage)
                        .font(.system(size: 11))
                        .foregroundStyle(Theme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    // MARK: - AI Preferences (web: "AI Preferences & Models")

    private var aiPreferencesCard: some View {
        expandableCard(
            title: "AI Preferences",
            subtitle: "How the analysis engines are chosen",
            systemImage: "sparkles",
            tint: Theme.aiPurple,
            background: Theme.aiPurpleBg,
            isExpanded: $aiExpanded
        ) {
            VStack(alignment: .leading, spacing: 14) {
                infoRows([
                    (
                        "Cloud Engine: Gemini 3.5 Flash Lite",
                        "High-speed clinical reasoning that evaluates allergen conflicts, glycemic impact and nutrient interactions. Only masked text and ingredient names are sent."
                    ),
                    (
                        "On-Device Guard: Apple Foundation Models",
                        "A model that runs on this iPhone to mask private details and, when you choose On-device, to analyze ingredients with zero network transmission. Needs Apple Intelligence."
                    ),
                    (
                        "Analysis modes",
                        "Auto uses the cloud when it is reachable and falls back to on-device. Gemini Flash is cloud only. On-device never leaves your phone."
                    )
                ])

                Divider()

                toggleRow(
                    title: "Strict Clinical Safety Penalties",
                    detail: "Enforce an automatic \"Avoid\" verdict for high-risk conflicts (e.g. whey protein with lactose intolerance).",
                    isOn: $strictPenalties
                )
            }
        }
    }

    // MARK: - Notifications (web: "Ingredient Alert Settings")

    private var alertSettingsCard: some View {
        expandableCard(
            title: "Notifications",
            subtitle: "Ingredient alert settings",
            systemImage: "bell",
            tint: Theme.primary,
            background: Theme.accentMint,
            isExpanded: $alertsExpanded
        ) {
            VStack(alignment: .leading, spacing: 14) {
                Text("Customize the safety warnings you get when scanning food or medicine labels.")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)

                toggleRow(
                    title: "Critical Allergen Warnings",
                    detail: "Instant alerts for nuts, dairy, gluten and severe allergens.",
                    isOn: $allergenAlerts
                )

                Divider()

                toggleRow(
                    title: "Intolerance & Sensitivity Alerts",
                    detail: "Flag lactose, high sodium and glycemic sugar spikes.",
                    isOn: $intoleranceAlerts
                )

                Divider()

                toggleRow(
                    title: "Weekly Health Scan Digest",
                    detail: "A weekly reminder to review your scanned products against your health goals.",
                    isOn: $weeklyDigest
                )

                if let notificationNote {
                    Text(notificationNote)
                        .font(.system(size: 11))
                        .foregroundStyle(Color.red.opacity(0.85))
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .onChange(of: weeklyDigest) { _, newValue in
            Task {
                let allowed = await DigestNotifier.setEnabled(newValue)
                if allowed {
                    notificationNote = nil
                } else {
                    weeklyDigest = false
                    notificationNote = "Allow notifications for ScanTruthAI in Settings to turn on the weekly digest."
                }
            }
        }
    }

    // MARK: - Security (web: "Security Architecture")

    private var securityCard: some View {
        expandableCard(
            title: "Security",
            subtitle: "How your data and session are protected",
            systemImage: "lock",
            tint: Theme.primary,
            background: Theme.accentMint,
            isExpanded: $securityExpanded
        ) {
            infoRows([
                (
                    "Firebase App Check",
                    "Requests are attested with Firebase App Check (a debug token in test builds) to block unauthorized API use."
                ),
                (
                    "Per-User Data Isolation",
                    "Firestore rules enforce strict isolation: request.auth.uid == userId."
                ),
                (
                    "Encryption in Transit & at Rest",
                    "TLS 1.3 transport and encrypted database storage."
                ),
                (
                    "Current Session",
                    auth.email.map { "Authenticated as \($0)" } ?? "Not signed in"
                )
            ])
        }
    }

    // MARK: - Shared pieces

    /// A list of titled paragraphs separated by dividers, like the rows in the web app's dialogs.
    private func infoRows(_ rows: [(String, String)]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                if index > 0 {
                    Divider()
                        .padding(.vertical, 10)
                }

                VStack(alignment: .leading, spacing: 4) {
                    Text(row.0)
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(Theme.textPrimary)

                    Text(row.1)
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    private func toggleRow(title: String, detail: String, isOn: Binding<Bool>) -> some View {
        Toggle(isOn: isOn) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(Theme.textPrimary)

                Text(detail)
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .tint(Theme.primary)
    }

    private func expandableCard<Content: View>(
        title: String,
        subtitle: String,
        systemImage: String,
        tint: Color,
        background: Color,
        isExpanded: Binding<Bool>,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Button(action: {
                withAnimation(.easeInOut(duration: 0.2)) {
                    isExpanded.wrappedValue.toggle()
                }
            }) {
                HStack(spacing: 12) {
                    IconTile(systemImage: systemImage, tint: tint, background: background, size: 40)

                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(Theme.textPrimary)

                        Text(subtitle)
                            .font(.system(size: 12))
                            .foregroundStyle(Theme.textSecondary)
                    }

                    Spacer()

                    Image(systemName: "chevron.down")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(Theme.textMuted)
                        .rotationEffect(.degrees(isExpanded.wrappedValue ? 180 : 0))
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(title). \(isExpanded.wrappedValue ? "Expanded" : "Collapsed")")

            if isExpanded.wrappedValue {
                Divider()
                content()
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .primaryCard(cornerRadius: 20)
    }
}

#Preview {
    ProfileView()
}
