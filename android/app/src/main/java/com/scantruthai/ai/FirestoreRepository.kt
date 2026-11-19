package com.scantruthai.ai

import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.tasks.await

object FirestoreRepository {
    private val db = Firebase.firestore

    private fun currentUid(): String =
        Firebase.auth.currentUser?.uid ?: throw IllegalStateException("User not signed in")

    suspend fun getMedicalProfile(): Map<String, Any>? {
        val snap = db.collection("users")
            .document(currentUid())
            .collection("medicalProfile")
            .document("current")
            .get()
            .await()
        return snap.data
    }

    suspend fun saveMedicalReportAndProfile(
        reportTitle: String,
        markersCount: Int,
        redactedText: String,
        profileFields: Map<String, Any>
    ) {
        val uid = currentUid()
        val userDoc = db.collection("users").document(uid)

        userDoc.collection("medicalProfile").document("current")
            .set(profileFields + ("updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge())
            .await()

        userDoc.collection("medicalReports").add(
            mapOf(
                "reportTitle" to reportTitle,
                "markersCount" to markersCount,
                "maskedOnDevice" to true,
                "redactedText" to redactedText,
                "uploadedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    suspend fun saveScanHistory(scanPayload: Map<String, Any>) {
        val uid = currentUid()
        db.collection("users")
            .document(uid)
            .collection("scanHistory")
            .add(scanPayload + ("scannedAt" to FieldValue.serverTimestamp()))
            .await()
    }
}