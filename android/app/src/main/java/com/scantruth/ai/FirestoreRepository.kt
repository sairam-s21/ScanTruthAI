package com.scantruth.ai

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore
import com.scantruth.ai.model.HealthActionItem
import com.scantruth.ai.model.IngredientAnalysis
import com.scantruth.ai.model.HealthContextSuggestion
import com.scantruth.ai.model.MedicalExtraction
import com.scantruth.ai.model.MedicalProfile
import com.scantruth.ai.model.ReportRecord
import com.scantruth.ai.model.ScanKind
import com.scantruth.ai.model.ScanRecord
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Typed Firestore access.
 *
 * Every operation is scoped to the signed-in UID read from FirebaseAuth — never from a
 * caller-supplied id — and every failure comes back as [StoreResult.Failure] so guest
 * and offline states render a message instead of crashing.
 *
 * What is never written here: raw OCR text, unredacted medical text, or detected
 * personal identifiers. Reports store metadata and counts only.
 */
object FirestoreRepository {

    sealed interface StoreResult<out T> {
        data class Success<T>(val value: T) : StoreResult<T>
        data class Failure(
            val message: String,
            val notSignedIn: Boolean = false
        ) : StoreResult<Nothing>
    }

    private const val USERS = "users"
    private const val PROFILE_SUBCOLLECTION = "medicalProfile"
    private const val PROFILE_DOCUMENT = "current"
    private const val REPORTS_SUBCOLLECTION = "medicalReports"
    private const val SCANS_SUBCOLLECTION = "scanHistory"
    private const val SCAN_CAPTURE_DIR = "scan-captures"
    private const val PRIVACY_PREFS = "scantruth_privacy"
    private const val KEY_CLEAR_PENDING = "firestore_cache_clear_pending"

    private val db get() = Firebase.firestore

    /**
     * Released by [markReady] once Application startup has finished any pending
     * sign-out cache hygiene. Every repository call waits here first, so a previous
     * account's disk cache can never be read before it has been cleared.
     */
    private val readyGate = CompletableDeferred<Unit>()

    fun markReady() {
        readyGate.complete(Unit)
    }

    private suspend fun awaitReady() {
        if (readyGate.isCompleted) return
        // Bounded: a stuck hygiene thread must not hang the first screen forever.
        withTimeoutOrNull(5_000) { readyGate.await() }
    }

    fun userId(): String? = runCatching { Firebase.auth.currentUser?.uid }.getOrNull()

    fun isSignedIn(): Boolean = userId() != null

    // --------------------------------------------------------------- medical profile

    suspend fun loadMedicalProfile(): StoreResult<MedicalProfile?> {
        val uid = userIdOrNull() ?: return signedOut()
        return firestoreCall {
            val snapshot = profileDocument(uid).get().await()
            if (snapshot.exists()) snapshot.toMedicalProfile() else null
        }
    }

    suspend fun saveMedicalProfile(profile: MedicalProfile): StoreResult<Unit> {
        val uid = userIdOrNull() ?: return signedOut()
        return firestoreCall {
            profileDocument(uid).set(profile.toFields(), SetOptions.merge()).await()
        }
    }

    /**
     * Merges an approved extraction into the stored profile, de-duplicating entries so
     * re-scanning the same report does not duplicate conditions or medications.
     */
    suspend fun mergeExtraction(extraction: MedicalExtraction): StoreResult<MedicalProfile> {
        val uid = userIdOrNull() ?: return signedOut()
        return firestoreCall {
            val document = profileDocument(uid)
            val existing = document.get().await()
                .takeIf { it.exists() }
                ?.toMedicalProfile()
                ?: MedicalProfile()
            val merged = existing.mergedWith(extraction)
            document.set(merged.toFields(), SetOptions.merge()).await()
            merged
        }
    }

    /**
     * Atomically applies validated ADD and REMOVE actions to the user's stored MedicalProfile
     * and saves directly to Cloud Firestore.
     */
    suspend fun applyHealthActions(actions: List<HealthActionItem>): StoreResult<Pair<MedicalProfile, List<String>>> {
        val uid = userIdOrNull() ?: return signedOut()
        return firestoreCall {
            val document = profileDocument(uid)
            val existing = document.get().await()
                .takeIf { it.exists() }
                ?.toMedicalProfile()
                ?: MedicalProfile()
            val (updated, changes) = existing.applyingActions(actions)
            document.set(updated.toFields(), SetOptions.merge()).await()
            updated to changes
        }
    }

    /**
     * Merges user-approved AI health context suggestions into the stored profile,
     * de-duplicating entries and saving directly to Cloud Firestore.
     */
    suspend fun mergeHealthContext(suggestion: HealthContextSuggestion): StoreResult<MedicalProfile> {
        val uid = userIdOrNull() ?: return signedOut()
        return firestoreCall {
            val document = profileDocument(uid)
            val existing = document.get().await()
                .takeIf { it.exists() }
                ?.toMedicalProfile()
                ?: MedicalProfile()
            val merged = existing.mergedWith(suggestion)
            document.set(merged.toFields(), SetOptions.merge()).await()
            merged
        }
    }

    // ------------------------------------------------------------------- reports

    suspend fun saveReport(
        extraction: MedicalExtraction,
        identifiersMasked: Int,
        pagesScanned: Int,
        truncated: Boolean
    ): StoreResult<String> {
        val uid = userIdOrNull() ?: return signedOut()
        return firestoreCall {
            val userRef = db.collection(USERS).document(uid)
            val reportRef = userRef.collection(REPORTS_SUBCOLLECTION).document()
            val scanRef = userRef.collection(SCANS_SUBCOLLECTION).document()

            val batch = db.batch()
            batch.set(
                reportRef,
                mapOf(
                    "reportTitle" to extraction.reportTitle,
                    "reportType" to extraction.reportType,
                    "markersCount" to extraction.markers.size,
                    "identifiersMasked" to identifiersMasked,
                    "maskedOnDevice" to true,
                    "pagesScanned" to pagesScanned,
                    "truncated" to truncated,
                    "aiEngine" to extraction.engine.name,
                    "savedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.set(
                scanRef,
                mapOf(
                    "kind" to ScanKind.MEDICAL_REPORT.wireValue,
                    "title" to extraction.reportTitle,
                    "subtitle" to extraction.reportType,
                    "reportId" to reportRef.id,
                    "aiEngine" to extraction.engine.name,
                    "scannedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
            reportRef.id
        }
    }

    suspend fun loadReports(limit: Long = 20): StoreResult<List<ReportRecord>> {
        val uid = userIdOrNull() ?: return signedOut()
        return firestoreCall {
            db.collection(USERS).document(uid)
                .collection(REPORTS_SUBCOLLECTION)
                .orderBy("savedAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get()
                .await()
                .documents
                .map { document ->
                    ReportRecord(
                        id = document.id,
                        reportTitle = document.getString("reportTitle") ?: "Medical report",
                        reportType = document.getString("reportType") ?: "Unspecified report",
                        markersCount = document.getLong("markersCount")?.toInt() ?: 0,
                        identifiersMasked = document.getLong("identifiersMasked")?.toInt() ?: 0,
                        savedAtMillis = document.millisAt("savedAt")
                    )
                }
        }
    }

    // ------------------------------------------------------------------ scan history

    suspend fun saveIngredientScan(
        analysis: IngredientAnalysis,
        ingredientCount: Int
    ): StoreResult<String> {
        val uid = userIdOrNull() ?: return signedOut()
        return firestoreCall {
            val reference = db.collection(USERS).document(uid)
                .collection(SCANS_SUBCOLLECTION)
                .add(
                    mapOf(
                        "kind" to ScanKind.INGREDIENT.wireValue,
                        "title" to analysis.productName,
                        "subtitle" to analysis.productCategory,
                        "safetyScore" to analysis.safetyScore,
                        "badgeLabel" to analysis.badgeLabel,
                        "headline" to analysis.headline,
                        "ingredientCount" to ingredientCount,
                        "aiEngine" to analysis.engine.name,
                        "scannedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()
            reference.id
        }
    }

    suspend fun loadRecentScans(limit: Long = 5): StoreResult<List<ScanRecord>> {
        val uid = userIdOrNull() ?: return signedOut()
        return firestoreCall {
            db.collection(USERS).document(uid)
                .collection(SCANS_SUBCOLLECTION)
                .orderBy("scannedAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get()
                .await()
                .documents
                .map { document ->
                    ScanRecord(
                        id = document.id,
                        kind = ScanKind.fromWire(document.getString("kind")),
                        title = document.getString("title") ?: "Scan",
                        subtitle = document.getString("subtitle") ?: "",
                        safetyScore = document.getLong("safetyScore")?.toInt(),
                        badgeLabel = document.getString("badgeLabel"),
                        scannedAtMillis = document.millisAt("scannedAt")
                    )
                }
        }
    }

    // ---------------------------------------------------------------- sign-out hygiene

    /**
     * Drops the on-device Firestore cache and any captured label photos so the next
     * account on this device cannot read the previous user's health data.
     */
    suspend fun clearLocalState(context: Context) {
        withContext(Dispatchers.IO) {
            var cacheCleared = false
            try {
                Firebase.firestore.clearPersistence().await()
                cacheCleared = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // clearPersistence() only succeeds before Firestore is used in this
                // process. After a normal session it fails, so the intent is recorded
                // and finishPendingCacheClear() clears it on the next cold start.
                cacheCleared = false
            }

            runCatching { File(context.cacheDir, SCAN_CAPTURE_DIR).deleteRecursively() }

            if (!cacheCleared) {
                prefs(context).edit().putBoolean(KEY_CLEAR_PENDING, true).apply()
            }
        }
    }

    /** True when a sign-out could not clear the Firestore disk cache in its own process. */
    fun firestoreCacheClearPending(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CLEAR_PENDING, false)

    /** Only call after [com.google.firebase.firestore.FirebaseFirestore.clearPersistence] succeeded. */
    fun markFirestoreCacheCleared(context: Context) {
        prefs(context).edit().putBoolean(KEY_CLEAR_PENDING, false).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PRIVACY_PREFS, Context.MODE_PRIVATE)

    // --------------------------------------------------------------------- helpers

    private fun userIdOrNull(): String? = userId()

    private fun signedOut(): StoreResult.Failure = StoreResult.Failure(
        message = "Sign in with Google to save and load your health data.",
        notSignedIn = true
    )

    private suspend fun <T> firestoreCall(block: suspend () -> T): StoreResult<T> {
        awaitReady()
        return try {
            StoreResult.Success(withContext(Dispatchers.IO) { block() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StoreResult.Failure(friendlyMessage(e))
        }
    }

    private fun friendlyMessage(e: Exception): String {
        val detail = e.localizedMessage?.lowercase().orEmpty()
        return when {
            detail.contains("permission") || detail.contains("permission_denied") ->
                "Firestore rejected this request. Check that you are signed in."

            detail.contains("network") || detail.contains("unavailable") ||
                detail.contains("offline") ->
                "You are offline. ScanTruthAI will sync when the connection is back."

            detail.contains("index") ->
                "This query needs a Firestore index. See firestore.indexes.json."

            else -> "Could not reach your health data. Please try again."
        }
    }

    private fun profileDocument(uid: String) = db.collection(USERS)
        .document(uid)
        .collection(PROFILE_SUBCOLLECTION)
        .document(PROFILE_DOCUMENT)

    private fun MedicalProfile.toFields(): Map<String, Any> = mapOf(
        "conditions" to conditions,
        "allergies" to allergies,
        "medications" to medications,
        "dietaryRestrictions" to dietaryRestrictions,
        "healthNotes" to healthNotes,
        "updatedAt" to FieldValue.serverTimestamp()
    )

    private fun DocumentSnapshot.toMedicalProfile(): MedicalProfile = MedicalProfile(
        conditions = stringList("conditions"),
        allergies = stringList("allergies"),
        medications = stringList("medications"),
        dietaryRestrictions = stringList("dietaryRestrictions"),
        healthNotes = stringList("healthNotes"),
        updatedAtMillis = millisAt("updatedAt")
    )

    private fun DocumentSnapshot.stringList(field: String): List<String> =
        (get(field) as? List<*>)?.mapNotNull { it as? String }?.filter { it.isNotBlank() }
            ?: emptyList()

    private fun DocumentSnapshot.millisAt(field: String): Long? =
        getTimestamp(field)?.toDate()?.time ?: getLong(field)
}
