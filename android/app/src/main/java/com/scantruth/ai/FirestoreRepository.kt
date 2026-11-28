package com.scantruth.ai

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore
import com.scantruth.ai.model.IngredientAnalysis
import com.scantruth.ai.model.MedicalExtraction
import com.scantruth.ai.model.MedicalProfile
import com.scantruth.ai.model.AiEngine
import com.scantruth.ai.model.FactorDirection
import com.scantruth.ai.model.ReportRecord
import com.scantruth.ai.model.SavedHealthFactor
import com.scantruth.ai.model.SavedIngredientNote
import com.scantruth.ai.model.ScanKind
import com.scantruth.ai.model.ScanRecord
import com.scantruth.ai.model.VerdictLevel
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
    private const val HEALTH_NOTES_SUBCOLLECTION = "healthNotes"
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
     * Saves a typed health note (already masked on this device and approved by the user)
     * to healthNotes, the same collection the web and iOS apps write.
     */
    suspend fun saveHealthNote(userNote: String, extraction: MedicalExtraction): StoreResult<String> {
        val uid = userIdOrNull() ?: return signedOut()
        return firestoreCall {
            val summary = extraction.conditions.joinToString(", ").ifBlank { extraction.reportTitle }
            val ref = db.collection(USERS).document(uid).collection(HEALTH_NOTES_SUBCOLLECTION).document()
            ref.set(
                mapOf(
                    "userNote" to userNote.take(5000),
                    "extractedSummary" to summary.take(1000),
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
            ref.id
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
            // Reports live only in medicalReports; like iOS and web, no scan-history entry is
            // added. uploadedAt is the shared field; savedAt keeps older app versions sorting.
            val reportRef = db.collection(USERS).document(uid)
                .collection(REPORTS_SUBCOLLECTION).document()
            reportRef.set(
                mapOf(
                    "reportTitle" to extraction.reportTitle.take(200),
                    "reportType" to extraction.reportType.take(100),
                    "markersCount" to extraction.markers.size.coerceAtMost(500),
                    "identifiersMasked" to identifiersMasked,
                    "maskedOnDevice" to true,
                    "pagesScanned" to pagesScanned,
                    "truncated" to truncated,
                    "aiEngine" to extraction.engine.name,
                    "uploadedAt" to FieldValue.serverTimestamp(),
                    "savedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            reportRef.id
        }
    }

    /**
     * Sorted here rather than with orderBy: Firestore drops documents that lack the sort
     * field, and reports saved by different app versions use uploadedAt or savedAt.
     */
    suspend fun loadReports(limit: Int = 20): StoreResult<List<ReportRecord>> {
        val uid = userIdOrNull() ?: return signedOut()
        return firestoreCall {
            db.collection(USERS).document(uid)
                .collection(REPORTS_SUBCOLLECTION)
                .get()
                .await()
                .documents
                .map { document ->
                    ReportRecord(
                        id = document.id,
                        reportTitle = document.getString("reportTitle") ?: "Medical report",
                        reportType = document.getString("reportType") ?: "Medical report",
                        markersCount = (document.get("markersCount") as? Number)?.toInt() ?: 0,
                        identifiersMasked = (document.get("identifiersMasked") as? Number)?.toInt() ?: 0,
                        savedAtMillis = document.millisAt("uploadedAt") ?: document.millisAt("savedAt")
                    )
                }
                .sortedByDescending { it.savedAtMillis ?: 0L }
                .take(limit)
        }
    }

    // ------------------------------------------------------------------ scan history

    /** Saved in the shared SPEC.md schema, so the iOS and web apps show the full result. */
    suspend fun saveIngredientScan(
        analysis: IngredientAnalysis,
        ingredients: List<String>
    ): StoreResult<String> {
        val uid = userIdOrNull() ?: return signedOut()
        return firestoreCall {
            val reference = db.collection(USERS).document(uid)
                .collection(SCANS_SUBCOLLECTION)
                .add(sharedScanFields(analysis, ingredients) + ("scannedAt" to FieldValue.serverTimestamp()))
                .await()
            reference.id
        }
    }

    /**
     * Maps an Android analysis onto the shared scan schema. Text and lists are trimmed to
     * the limits in firestore.rules so a long model answer cannot make the save fail.
     */
    internal fun sharedScanFields(analysis: IngredientAnalysis, ingredients: List<String>): Map<String, Any> {
        val score = analysis.safetyScore.coerceIn(0, 100)
        return buildMap {
            put("kind", ScanKind.INGREDIENT.wireValue)
            put("productName", analysis.productName.ifBlank { "Scanned product" }.take(200))
            analysis.productCategory.takeIf { it.isNotBlank() }?.let { put("productCategory", it.take(50)) }
            put("safetyScore", score)
            put(
                "badgeLabel",
                when {
                    score >= 70 -> "Good match for you"
                    score >= 40 -> "Use with care"
                    else -> "Avoid"
                }
            )
            analysis.headline.takeIf { it.isNotBlank() }?.let { put("headline", it.take(500)) }
            analysis.aiSummary.takeIf { it.isNotBlank() }?.let { put("aiSummary", it.take(4000)) }
            put(
                "healthFactors",
                analysis.healthFactors.take(40).map { factor ->
                    mapOf(
                        "title" to factor.label,
                        "description" to factor.detail,
                        "impactBadge" to when (factor.direction) {
                            FactorDirection.POSITIVE -> "Safe"
                            FactorDirection.CAUTION -> "Caution"
                            else -> "Note"
                        },
                        "sentiment" to when (factor.direction) {
                            FactorDirection.POSITIVE -> "positive"
                            FactorDirection.CAUTION -> "warning"
                            else -> "neutral"
                        }
                    )
                }
            )
            put(
                "ingredientBreakdown",
                analysis.ingredientBreakdown.take(100).map { item ->
                    buildMap {
                        put("name", item.name)
                        item.note?.takeIf { it.isNotBlank() }?.let { put("note", it) }
                        put(
                            "status",
                            when (item.verdict) {
                                VerdictLevel.CAUTION -> "Caution"
                                VerdictLevel.AVOID -> "Avoid"
                                else -> "Okay"
                            }
                        )
                    }
                }
            )
            analysis.recommendedIntake.takeIf { it.isNotBlank() }?.let {
                put("recommendedIntake", mapOf("guidance" to it))
            }
            put("rawIngredients", ingredients.take(500))
            put("modelUsed", analysis.modelUsedLabel)
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
                .map { document -> document.toScanRecord() }
                // Older builds also logged reports here; reports are listed in My Health.
                .filter { it.kind != ScanKind.MEDICAL_REPORT }
        }
    }

    suspend fun deleteScan(scanId: String): StoreResult<Unit> {
        val uid = userIdOrNull() ?: return signedOut()
        return firestoreCall {
            db.collection(USERS).document(uid).collection(SCANS_SUBCOLLECTION).document(scanId).delete().await()
            Unit
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

    /**
     * Reads both scan formats: this app's (`title`, `subtitle`, `aiEngine`) and the shared
     * SPEC.md schema the iOS and web apps write (`productName`, `healthFactors`, …).
     * Fields of an unexpected type are skipped, so one odd document never breaks the list.
     */
    private fun DocumentSnapshot.toScanRecord(): ScanRecord {
        fun text(field: String) = (get(field) as? String)?.trim()?.takeIf { it.isNotEmpty() }
        fun objects(field: String) = (get(field) as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        fun Map<*, *>.text(key: String) = (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

        val intake = get("recommendedIntake")
        val intakeMap = intake as? Map<*, *>
        return ScanRecord(
            id = id,
            kind = ScanKind.fromWire(text("kind")),
            title = text("productName") ?: text("title") ?: "Scanned product",
            subtitle = text("productCategory") ?: text("subtitle") ?: "",
            safetyScore = (get("safetyScore") as? Number)?.toInt()?.coerceIn(0, 100),
            badgeLabel = text("badgeLabel"),
            scannedAtMillis = millisAt("scannedAt"),
            headline = text("headline"),
            aiSummary = text("aiSummary"),
            healthFactors = objects("healthFactors").mapNotNull { f ->
                f.text("title")?.let { SavedHealthFactor(it, f.text("description"), f.text("impactBadge")) }
            },
            ingredientBreakdown = objects("ingredientBreakdown").mapNotNull { i ->
                i.text("name")?.let { SavedIngredientNote(it, i.text("note"), i.text("status")) }
            },
            intakeHeadline = intakeMap?.text("dosageHeadline"),
            intakeGuidance = intakeMap?.text("guidance") ?: (intake as? String)?.takeIf { it.isNotBlank() },
            ingredientCount = (get("ingredientCount") as? Number)?.toInt()
                ?: (get("rawIngredients") as? List<*>)?.size,
            modelUsed = text("modelUsed") ?: text("aiEngine")?.let { engine ->
                AiEngine.entries.firstOrNull { it.name == engine }?.label ?: engine
            }
        )
    }

    private fun DocumentSnapshot.stringList(field: String): List<String> =
        (get(field) as? List<*>)?.mapNotNull { it as? String }?.filter { it.isNotBlank() }
            ?: emptyList()

    private fun DocumentSnapshot.millisAt(field: String): Long? =
        getTimestamp(field)?.toDate()?.time ?: getLong(field)
}
