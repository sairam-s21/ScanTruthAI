import Foundation
import FirebaseAuth
import FirebaseFirestore

@MainActor
final class FirestoreService {
    static let shared = FirestoreService()
    private let db = Firestore.firestore()

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

    func saveMedicalReportAndProfile(
        reportTitle: String,
        markersCount: Int,
        redactedText: String,
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
            "reportTitle": reportTitle,
            "markersCount": markersCount,
            "maskedOnDevice": true,
            "redactedText": redactedText,
            "uploadedAt": FieldValue.serverTimestamp()
        ])
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
}