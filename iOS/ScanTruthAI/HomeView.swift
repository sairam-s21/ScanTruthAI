//
//  HomeView.swift
//  ScanTruthAI
//

import SwiftUI

struct HomeView: View {
    var onOpenScanner: () -> Void
    var onCompleteProfile: () -> Void
    /// "Analyse Medical Report": go to My Health and open the report picker straight away.
    var onAnalyseReport: () -> Void = {}

    private let history = ScanHistoryStore.shared
    private let auth = AuthService.shared
    private let profile = HealthProfileStore.shared

    @State private var selectedRecord: ScanRecord? = nil
    @State private var showHistory: Bool = false

    var body: some View {
        NavigationStack {
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 22) {
                    BrandHeader(subtitle: "ON-DEVICE PRIVACY VERIFIED") {
                        bellButton
                    }

                    greetingSection

                    scanHeroCard

                    medicalContextSection

                    recentAnalysisSection

                    PrivacyNoteCard(
                        title: "Your privacy is protected",
                        text: "Names, dates of birth, phone numbers and IDs are masked on this device. Reports reach the cloud only after you approve the redacted text."
                    )
                }
                .padding(.horizontal, 20)
                .padding(.top, 16)
                .padding(.bottom, 110) // Padding for custom bottom tab bar
            }
            .background(Theme.background.ignoresSafeArea())
            .sheet(item: $selectedRecord) { record in
                AnalysisResultView(
                    result: record.result,
                    modelUsedLabel: record.modelUsedLabel,
                    onDismiss: { selectedRecord = nil }
                )
            }
            .sheet(isPresented: $showHistory) {
                HistoryView()
            }
            .task {
                // Signed-in users see their saved scans and health context after a relaunch.
                await history.refreshFromCloud()
                await profile.loadFromCloud()
            }
        }
    }

    // MARK: - Header

    private var bellButton: some View {
        Image(systemName: "bell")
            .font(.system(size: 17))
            .foregroundStyle(Theme.textPrimary)
            .frame(width: 44, height: 44)
            .background(Color.white)
            .clipShape(Circle())
            .overlay(
                Circle().stroke(Color.black.opacity(0.06), lineWidth: 1)
            )
            .accessibilityLabel("Notifications")
    }

    // MARK: - Greeting

    private var greetingText: String {
        let hour = Calendar.current.component(.hour, from: Date())
        let part = hour < 12 ? "Good morning" : (hour < 17 ? "Good afternoon" : "Good evening")

        if let first = auth.displayName?.split(separator: " ").first {
            return "\(part), \(first)"
        }
        return part
    }

    private var greetingSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(greetingText)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Theme.primary)

            Text("Make a safer choice.")
                .font(.system(size: 32, weight: .bold))
                .foregroundStyle(Theme.textPrimary)

            Text("Scan ingredients or review medical records with guaranteed on-device privacy.")
                .font(.system(size: 15))
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: - Hero card

    private var scanHeroCard: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 12) {
                Text("SCAN & KNOW")
                    .font(.system(size: 10, weight: .bold))
                    .tracking(1.0)
                    .foregroundStyle(Color.white)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(Color.white.opacity(0.14))
                    .clipShape(Capsule())

                Text("Scan Ingredients")
                    .font(.system(size: 24, weight: .bold))
                    .foregroundStyle(Color.white)

                Text("Check what is really inside before it goes in your basket.")
                    .font(.system(size: 13))
                    .foregroundStyle(Color.white.opacity(0.85))
                    .fixedSize(horizontal: false, vertical: true)

                Button(action: onOpenScanner) {
                    HStack(spacing: 8) {
                        Image(systemName: "viewfinder")
                            .font(.system(size: 14, weight: .semibold))
                        Text("Start scanning")
                            .font(.system(size: 14, weight: .semibold))
                    }
                    .foregroundStyle(Theme.primary)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .background(Color.white)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                }
                .padding(.top, 2)
            }

            Spacer(minLength: 4)

            heroIllustration
        }
        .padding(20)
        .background(
            LinearGradient(
                colors: [Theme.primary, Theme.primaryDark],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        )
        .clipShape(RoundedRectangle(cornerRadius: 26))
        .shadow(color: Theme.primary.opacity(0.18), radius: 12, x: 0, y: 6)
    }

    /// A scan frame with a few "text" lines and a check mark.
    private var heroIllustration: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 16)
                .fill(Color.white.opacity(0.07))

            ViewfinderCorners(cornerLength: 18, cornerRadius: 6)
                .stroke(Color.white.opacity(0.6), lineWidth: 2)
                .padding(8)

            VStack(alignment: .leading, spacing: 8) {
                Capsule().fill(Color.white.opacity(0.35)).frame(width: 34, height: 4)
                Capsule().fill(Color.white.opacity(0.25)).frame(width: 46, height: 4)
                Capsule().fill(Color.white.opacity(0.25)).frame(width: 28, height: 4)
            }
            .offset(x: -4, y: -8)

            Image(systemName: "checkmark")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(Theme.primary)
                .frame(width: 28, height: 28)
                .background(Theme.mintBorder)
                .clipShape(Circle())
                .offset(x: 22, y: 30)
        }
        .frame(width: 92, height: 120)
        .accessibilityHidden(true)
    }

    // MARK: - Medical context

    private var medicalContextSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: "Medical Context", actionTitle: "View All", action: onCompleteProfile)

            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .top, spacing: 12) {
                    IconTile(systemImage: "heart.text.square")

                    VStack(alignment: .leading, spacing: 4) {
                        Text(profile.detailCount == 0 ? "No medical context yet" : "Your health context")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(Theme.textPrimary)

                        Text(profile.detailCount == 0
                             ? "Add conditions and allergies so every scan is personal."
                             : "\(profile.detailCount) \(profile.detailCount == 1 ? "detail" : "details") personalize every scan.")
                            .font(.system(size: 13))
                            .foregroundStyle(Theme.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }

                if profile.detailCount > 0 {
                    ChipList(items: Array((profile.conditions + profile.allergies + profile.medications).prefix(6)))
                }

                FilledActionButton(title: "Analyse Medical Report", systemImage: "plus", action: onAnalyseReport)

                Button(action: onCompleteProfile) {
                    HStack(spacing: 4) {
                        Text("Build your health context")
                        Image(systemName: "chevron.right")
                            .font(.system(size: 10, weight: .bold))
                    }
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Theme.primary)
                    .frame(maxWidth: .infinity)
                }
            }
            .padding(16)
            .primaryCard(cornerRadius: 20)
        }
    }

    // MARK: - Recent analysis

    /// The account's newest scans; falls back to the scan made in this session when nothing is loaded yet.
    private var recentRecords: [ScanRecord] {
        if !history.history.isEmpty {
            return Array(history.history.prefix(4))
        }
        return history.latest.map { [$0] } ?? []
    }

    private var recentAnalysisSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: "Recent Analysis", actionTitle: "View All") {
                showHistory = true
            }

            if recentRecords.isEmpty {
                emptyRecentState
            } else {
                ForEach(recentRecords) { record in
                    recentRow(record)
                }
            }
        }
    }

    private func recentRow(_ record: ScanRecord) -> some View {
        let result = record.result
        let isMedicine = result.productCategory?.lowercased() == "medicine"
        let category = result.productCategory?.capitalized ?? "Scan"
        let date = record.scannedAt.formatted(.dateTime.day().month(.abbreviated))

        return Button(action: {
            selectedRecord = record
        }) {
            HStack(spacing: 12) {
                IconTile(systemImage: isMedicine ? "pills" : "leaf")

                VStack(alignment: .leading, spacing: 3) {
                    Text(result.productName ?? "Scanned product")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Theme.textPrimary)
                        .lineLimit(1)

                    Text("\(category) · \(date)")
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.textSecondary)
                }

                Spacer(minLength: 8)

                ScorePill(result: result)
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .primaryCard(cornerRadius: 18)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(result.productName ?? "Product"), \(category), score \(result.clampedScore.map(String.init) ?? "unknown")")
    }

    private var emptyRecentState: some View {
        VStack(spacing: 10) {
            Image(systemName: "doc.text")
                .font(.system(size: 26))
                .foregroundStyle(Theme.textSecondary.opacity(0.8))

            Text(auth.isSignedIn ? "No saved analyses yet" : "Sign in from the Profile tab to see your saved scans")
                .font(.system(size: 13))
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)

            Button("Scan a product", action: onOpenScanner)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Theme.primary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 28)
        .padding(.horizontal, 16)
        .dashedCard(cornerRadius: 18, color: Theme.mintBorder)
    }
}

#Preview {
    HomeView(onOpenScanner: {}, onCompleteProfile: {})
}
