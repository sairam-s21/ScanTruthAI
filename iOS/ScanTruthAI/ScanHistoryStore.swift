//
//  ScanHistoryStore.swift
//  ScanTruthAI
//

import Foundation
import FirebaseFirestore

/// One analysed product, shown on Home and in the history list.
struct ScanRecord: Identifiable {
    /// Firestore document id for saved scans, so list rows stay stable across reloads.
    let id: String
    let result: AnalysisResult
    let ingredients: [String]
    let modelUsedLabel: String
    let scannedAt: Date
    /// Firebase uid of the account this scan belongs to; nil if it was made while signed out.
    let ownerUID: String?

    init(
        id: String = UUID().uuidString,
        result: AnalysisResult,
        ingredients: [String],
        modelUsedLabel: String,
        scannedAt: Date,
        ownerUID: String?
    ) {
        self.id = id
        self.result = result
        self.ingredients = ingredients
        self.modelUsedLabel = modelUsedLabel
        self.scannedAt = scannedAt
        self.ownerUID = ownerUID
    }

    /// Builds a record from a `users/{uid}/scanHistory/{scanId}` document (schema: SPEC.md section 2D).
    /// Returns nil for documents that cannot be read, so one odd document never breaks the whole list.
    static func from(firestore data: [String: Any], docID: String? = nil, ownerUID: String) -> ScanRecord? {
        let analysisKeys = [
            "productName", "productCategory", "safetyScore", "badgeLabel", "headline",
            "aiSummary", "healthFactors", "ingredientBreakdown", "recommendedIntake"
        ]
        // Drop Timestamps and other non-JSON values before decoding.
        let plain = data.filter { analysisKeys.contains($0.key) }

        guard JSONSerialization.isValidJSONObject(plain),
              let json = try? JSONSerialization.data(withJSONObject: plain),
              let result = try? JSONDecoder().decode(AnalysisResult.self, from: json) else { return nil }

        return ScanRecord(
            id: docID ?? UUID().uuidString,
            result: result,
            ingredients: data["rawIngredients"] as? [String] ?? [],
            modelUsedLabel: data["modelUsed"] as? String ?? "Saved analysis",
            scannedAt: (data["scannedAt"] as? Timestamp)?.dateValue() ?? Date(),
            ownerUID: ownerUID
        )
    }
}

/// Holds the signed-in account's scans. When signed in, everything here belongs to that account
/// and is loaded from its Firestore data, including scans saved by the Web and Android apps.
@Observable
@MainActor
final class ScanHistoryStore {
    static let shared = ScanHistoryStore()

    /// Most recent scan, shown on Home.
    private(set) var latest: ScanRecord?
    /// Full history, newest first.
    private(set) var history: [ScanRecord] = []
    private(set) var isLoadingHistory = false
    private(set) var historyError: String?
    /// Saved scans that were found but could not be read (e.g. missing every analysis field).
    private(set) var skippedCount = 0

    func record(_ scan: ScanRecord) {
        latest = scan
        // Show the new scan in the list straight away, before the next reload from Firestore.
        if scan.ownerUID != nil, scan.ownerUID == AuthService.shared.uid {
            history.insert(scan, at: 0)
        }
    }

    func reset() {
        latest = nil
        history = []
        historyError = nil
        skippedCount = 0
    }

    /// Called when the signed-in account changes (sign-in, sign-out, or restored at launch).
    /// Drops anything that belongs to a different account, then loads the new account's latest scan.
    func userDidChange(to uid: String?) {
        if latest?.ownerUID != uid {
            latest = nil
        }
        history.removeAll { $0.ownerUID != uid }
        historyError = nil
        Task { await refreshFromCloud() }
    }

    /// Refreshes what Home shows. Reads a few recent scans and uses the newest readable one,
    /// so one unreadable document never leaves Home empty.
    func refreshFromCloud() async {
        await loadHistory(limit: 20)
    }

    /// Loads the signed-in account's scan history from Firestore, newest first.
    func loadHistory(limit: Int = 50) async {
        guard FirestoreService.canSync, let uid = AuthService.shared.uid else {
            history = []
            return
        }

        isLoadingHistory = true
        historyError = nil
        defer { isLoadingHistory = false }

        do {
            let documents = try await FirestoreService.shared.fetchScanHistory(limit: limit)
                // Older Android builds also logged reports here; reports are listed in My Health.
                .filter { ($0.data["kind"] as? String) != "medical_report" }
            guard AuthService.shared.uid == uid else { return }

            let records = documents.compactMap {
                ScanRecord.from(firestore: $0.data, docID: $0.id, ownerUID: uid)
            }
            skippedCount = documents.count - records.count
            history = records
            if let newest = history.first {
                adoptLatest(newest, for: uid)
            }
        } catch {
            historyError = "Could not load your history: \(error.localizedDescription)"
        }
    }

    /// A scan made on this device that is newer than the saved one (e.g. its save is still pending) is kept.
    private func adoptLatest(_ cloud: ScanRecord, for uid: String) {
        if let local = latest, local.ownerUID == uid, local.scannedAt > cloud.scannedAt {
            return
        }
        latest = cloud
    }
}
