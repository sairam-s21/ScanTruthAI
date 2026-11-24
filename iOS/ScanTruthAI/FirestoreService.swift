//
//  FirestoreService.swift
//  ScanTruthAI
//

import Foundation
import FirebaseCore
import FirebaseAuth
import FirebaseFirestore

@MainActor
final class FirestoreService {
    static let shared = FirestoreService()
    // Lazy so creating the service never touches Firebase before it is configured.
    private lazy var db = Firestore.firestore()

    /// True only when Firebase is configured and a user is signed in.
    static var canSync: Bool {
        FirebaseApp.app() != nil && Auth.auth().currentUser != nil
    }

    /// Medical profile as JSON for AI prompts. Returns "{}" when unavailable.
    /// Only plain profile fields are kept (Firestore Timestamps are not JSON-serializable).
    func medicalProfileJson() async -> String {
        guard Self.canSync,
              let data = try? await fetchMedicalProfile() else { return "{}" }

        let keys = ["conditions", "allergies", "medications", "dietaryRestrictions", "healthMarkers"]
        let plain = data.filter { keys.contains($0.key) }

        guard JSONSerialization.isValidJSONObject(plain),
              let json = try? JSONSerialization.data(withJSONObject: plain),
              let string = String(data: json, encoding: .utf8) else { return "{}" }
        return string
    }

    private func currentUid() throws -> String {
        guard let uid = Auth.auth().currentUser?.uid else {
            throw NSError(domain: "ScanTruthAI", code: 401, userInfo: [NSLocalizedDescriptionKey: "User not signed in"])
        }
        return uid
    }

    func fetchMedicalProfile() async throws -> [String: Any]? {
        let uid = try currentUid()
        let snap = try await db.collection("users")
            .document(uid)
            .collection("medicalProfile")
            .document("current")
            .getDocument()
        return snap.data()
    }

    /// Removes one item from a list field of users/{uid}/medicalProfile/current (e.g. "allergies").
    func removeProfileItem(field: String, item: String) async throws {
        let uid = try currentUid()
        try await db.collection("users")
            .document(uid)
            .collection("medicalProfile")
            .document("current")
            .setData([
                field: FieldValue.arrayRemove([item]),
                "updatedAt": FieldValue.serverTimestamp()
            ], merge: true)
    }

    /// Only report metadata is stored; the report text, even redacted, never leaves the device.
    func saveMedicalReportAndProfile(
        reportTitle: String,
        markersCount: Int,
        profileFields: [String: Any]
    ) async throws {
        let uid = try currentUid()
        let userDoc = db.collection("users").document(uid)

        var mergedProfile = profileFields
        mergedProfile["updatedAt"] = FieldValue.serverTimestamp()

        try await userDoc.collection("medicalProfile")
            .document("current")
            .setData(mergedProfile, merge: true)

        try await userDoc.collection("medicalReports").addDocument(data: [
            "reportTitle": String(reportTitle.prefix(200)),
            "markersCount": min(max(markersCount, 0), 500),
            "maskedOnDevice": true,
            "uploadedAt": FieldValue.serverTimestamp()
        ])
    }

    /// Newest document in users/{uid}/scanHistory, or nil if there is none.
    func fetchLatestScan() async throws -> [String: Any]? {
        let uid = try currentUid()
        let snapshot = try await db.collection("users")
            .document(uid)
            .collection("scanHistory")
            .order(by: "scannedAt", descending: true)
            .limit(to: 1)
            .getDocuments()
        return snapshot.documents.first?.data()
    }

    /// Scan history for the signed-in account, newest first. The Web and Android apps write to the same
    /// collection with the same schema (SPEC.md section 2D), so scans from every platform appear here.
    func fetchScanHistory(limit: Int = 50) async throws -> [(id: String, data: [String: Any])] {
        let uid = try currentUid()
        let snapshot = try await db.collection("users")
            .document(uid)
            .collection("scanHistory")
            .order(by: "scannedAt", descending: true)
            .limit(to: limit)
            .getDocuments()
        return snapshot.documents.map { ($0.documentID, $0.data()) }
    }

    func saveScanHistory(scanPayload: [String: Any]) async throws {
        let uid = try currentUid()
        var payload = scanPayload
        payload["scannedAt"] = FieldValue.serverTimestamp()

        try await db.collection("users")
            .document(uid)
            .collection("scanHistory")
            .addDocument(data: payload)
    }

    // MARK: - iOS Member 5 Assigned Implementations

    /// Task 1 & 2: Save typed health note to users/{uid}/healthNotes/{noteId}
    func saveTypedHealthNote(userNote: String, extractedSummary: String) async throws {
        let uid = try currentUid()
        // Lengths match the limits in firestore.rules.
        let noteData: [String: Any] = [
            "userNote": String(userNote.prefix(5000)),
            "extractedSummary": String(extractedSummary.prefix(1000)),
            "createdAt": FieldValue.serverTimestamp()
        ]

        try await db.collection("users")
            .document(uid)
            .collection("healthNotes")
            .addDocument(data: noteData)
    }

    /// Task 3: Merge newly extracted health markers into the active medical profile at users/{uid}/medicalProfile/current
    func mergeExtractedHealthMarkers(
        newConditions: [String],
        newAllergies: [String],
        newMedications: [String],
        newDietaryRestrictions: [String],
        newHealthMarkers: [[String: Any]] = [],
        reportTitle: String? = nil
    ) async throws {
        let uid = try currentUid()
        let profileRef = db.collection("users")
            .document(uid)
            .collection("medicalProfile")
            .document("current")

        // Fetch existing profile to merge arrays cleanly without duplicates
        let existingSnap = try? await profileRef.getDocument()
        let existingData = existingSnap?.data() ?? [:]

        var mergedConditions = Set(existingData["conditions"] as? [String] ?? [])
        mergedConditions.formUnion(newConditions)

        var mergedAllergies = Set(existingData["allergies"] as? [String] ?? [])
        mergedAllergies.formUnion(newAllergies)

        var mergedMedications = Set(existingData["medications"] as? [String] ?? [])
        mergedMedications.formUnion(newMedications)

        var mergedDietary = Set(existingData["dietaryRestrictions"] as? [String] ?? [])
        mergedDietary.formUnion(newDietaryRestrictions)

        var existingMarkers = existingData["healthMarkers"] as? [[String: Any]] ?? []
        existingMarkers.append(contentsOf: newHealthMarkers)
        // firestore.rules caps the list at 500; keep the newest markers.
        existingMarkers = Array(existingMarkers.suffix(500))

        var updatePayload: [String: Any] = [
            "conditions": Array(mergedConditions),
            "allergies": Array(mergedAllergies),
            "medications": Array(mergedMedications),
            "dietaryRestrictions": Array(mergedDietary),
            "healthMarkers": existingMarkers,
            "markersCount": existingMarkers.count,
            "updatedAt": FieldValue.serverTimestamp()
        ]
        if let reportTitle {
            updatePayload["reportTitle"] = String(reportTitle.prefix(200))
        }

        try await profileRef.setData(updatePayload, merge: true)
    }
}
