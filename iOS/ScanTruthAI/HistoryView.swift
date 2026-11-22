//
//  HistoryView.swift
//  ScanTruthAI
//

import SwiftUI

/// Every scan saved to the signed-in account, including scans made in the Web and Android apps.
struct HistoryView: View {
    @Environment(\.dismiss) private var dismiss

    private let store = ScanHistoryStore.shared
    private let auth = AuthService.shared

    @State private var selectedRecord: ScanRecord? = nil

    var body: some View {
        NavigationStack {
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Theme.background.ignoresSafeArea())
                .navigationTitle("Scan history")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done") { dismiss() }
                            .foregroundStyle(Theme.primary)
                    }
                }
                .task {
                    await store.loadHistory()
                }
                .sheet(item: $selectedRecord) { record in
                    AnalysisResultView(
                        result: record.result,
                        modelUsedLabel: record.modelUsedLabel,
                        onDismiss: { selectedRecord = nil }
                    )
                }
        }
    }

    @ViewBuilder
    private var content: some View {
        if !auth.isSignedIn {
            message(
                icon: "person.crop.circle.badge.exclamationmark",
                text: "Sign in from the Profile tab to see scans saved to your account, including ones from the web and Android apps."
            )
        } else if store.history.isEmpty && store.isLoadingHistory {
            ProgressView()
        } else if store.history.isEmpty, let error = store.historyError {
            message(icon: "exclamationmark.triangle", text: error, retry: true)
        } else if store.history.isEmpty {
            message(
                icon: "doc.text",
                text: "No saved scans yet. Scans you make in any ScanTruthAI app will show up here."
            )
        } else {
            list
        }
    }

    private var list: some View {
        ScrollView {
            LazyVStack(spacing: 10) {
                if let error = store.historyError {
                    Text(error)
                        .font(.system(size: 12))
                        .foregroundStyle(Color.red.opacity(0.85))
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                ForEach(store.history) { record in
                    row(record)
                }

                if store.skippedCount > 0 {
                    Text("\(store.skippedCount) saved scan(s) could not be read.")
                        .font(.system(size: 11))
                        .foregroundStyle(Theme.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
        .refreshable {
            await store.loadHistory()
        }
    }

    private func row(_ record: ScanRecord) -> some View {
        let result = record.result

        return Button(action: {
            selectedRecord = record
        }) {
            HStack(spacing: 14) {
                ZStack {
                    Circle()
                        .stroke(result.scoreColor.opacity(0.2), lineWidth: 5)
                    Circle()
                        .trim(from: 0, to: CGFloat(result.clampedScore ?? 0) / 100)
                        .stroke(result.scoreColor, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                    Text(result.clampedScore.map(String.init) ?? "–")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(Theme.textPrimary)
                }
                .frame(width: 48, height: 48)

                VStack(alignment: .leading, spacing: 3) {
                    Text(result.productName ?? "Scanned product")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Theme.textPrimary)
                        .lineLimit(1)

                    Text(result.displayBadge)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(result.scoreColor)

                    Text(detailLine(for: record))
                        .font(.system(size: 11))
                        .foregroundStyle(Theme.textSecondary)
                        .lineLimit(1)
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Theme.textMuted)
            }
            .padding(14)
            .primaryCard(cornerRadius: 16)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(result.productName ?? "Product"), score \(result.clampedScore.map(String.init) ?? "unknown"), \(result.displayBadge)")
    }

    private func detailLine(for record: ScanRecord) -> String {
        let date = record.scannedAt.formatted(date: .abbreviated, time: .shortened)
        guard !record.ingredients.isEmpty else { return date }
        return "\(date) · \(record.ingredients.count) ingredients"
    }

    private func message(icon: String, text: String, retry: Bool = false) -> some View {
        VStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 28))
                .foregroundStyle(Theme.textSecondary.opacity(0.8))

            Text(text)
                .font(.system(size: 13))
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)

            if retry {
                Button("Try again") {
                    Task { await store.loadHistory() }
                }
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Theme.primary)
            }
        }
        .padding(32)
    }
}

#Preview {
    HistoryView()
}
