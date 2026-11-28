package com.scantruth.ai.ui.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.scantruth.ai.FirestoreRepository
import com.scantruth.ai.FirestoreRepository.StoreResult
import com.scantruth.ai.HybridAIService
import com.scantruth.ai.model.HealthItemValidation
import com.scantruth.ai.model.MedicalExtraction
import com.scantruth.ai.model.MedicalProfile
import com.scantruth.ai.model.ReportRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The lists of the health profile, with the same names and copy as the website. */
enum class ProfileCategory(
    val title: String,
    val singular: String,
    val addTitle: String,
    val placeholder: String
) {
    CONDITIONS("Known Conditions", "medical condition", "Add a condition", "e.g. Hypertension"),
    ALLERGIES("Allergies", "allergy", "Add allergy", "e.g. Peanuts"),
    MEDICATIONS("Medications", "medication", "Add medication", "e.g. Metformin 500 mg"),
    DIETARY_RESTRICTIONS("Dietary restrictions", "dietary restriction", "Add dietary restriction", "e.g. Low sodium"),
    HEALTH_NOTES("Health notes", "health note", "Add a note", "Add a note");

    companion object {
        val ACTIVE_CATEGORIES = listOf(CONDITIONS, ALLERGIES, MEDICATIONS, DIETARY_RESTRICTIONS)

        /** The three cards under "Health Context"; conditions have their own section. */
        val CONTEXT_CARDS = listOf(ALLERGIES, MEDICATIONS, DIETARY_RESTRICTIONS)
    }
}

/**
 * My Health state: the health profile, saved report titles and the status line.
 *
 * Signed in, every change is written to Firestore. Signed out, changes live only in this
 * object for the session (as on the website) and are dropped when the app closes.
 */
@Stable
class MyHealthUiState {

    var isLoading by mutableStateOf(true)
        private set

    var isSignedIn by mutableStateOf(FirestoreRepository.isSignedIn())
        private set

    var profile by mutableStateOf(MedicalProfile())
        private set

    var reports by mutableStateOf<List<ReportRecord>>(emptyList())
        private set

    /** Report titles added while signed out; session only. */
    var guestReportTitles by mutableStateOf<List<String>>(emptyList())
        private set

    /** One status line under the title, like the website's status banner. */
    var status by mutableStateOf<String?>(null)
        private set

    /** True while an added item is being checked. */
    var isProcessing by mutableStateOf(false)
        private set

    var noteDraft by mutableStateOf("")
        private set

    var noteMessage by mutableStateOf<String?>(null)
        private set

    /** Categories whose chips currently show remove buttons. */
    var editing by mutableStateOf<Set<ProfileCategory>>(emptySet())
        private set

    /** Set by Home's "Analyse Medical Report"; My Health opens the file picker once. */
    var reportPickerRequested by mutableStateOf(false)
        private set

    val reportTitles: List<String>
        get() = if (isSignedIn) reports.map { it.reportTitle } else guestReportTitles

    val detailCount: Int
        get() = profile.conditions.size + profile.allergies.size +
            profile.medications.size + profile.dietaryRestrictions.size

    private var listener: FirebaseAuth.AuthStateListener? = null
    private var lastObservedUid: String? = runCatching { Firebase.auth.currentUser?.uid }.getOrNull()
    private var loadJob: Job? = null

    fun start(scope: CoroutineScope) {
        val auth = runCatching { Firebase.auth }.getOrNull() ?: return
        if (listener != null) return
        listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            val newUid = user?.uid
            val uidChanged = newUid != lastObservedUid
            lastObservedUid = newUid
            isSignedIn = user != null
            if (user != null) {
                if (uidChanged || isLoading) load(scope)
            } else if (uidChanged) {
                // Signed out: nothing from the previous account stays on screen.
                clearAll()
            } else {
                isLoading = false
            }
        }
        auth.addAuthStateListener(listener!!)
    }

    fun stop() {
        val auth = runCatching { Firebase.auth }.getOrNull()
        listener?.let { auth?.removeAuthStateListener(it) }
        listener = null
    }

    fun load(scope: CoroutineScope) {
        isSignedIn = FirestoreRepository.isSignedIn()
        if (!isSignedIn) {
            // Guest data is session-only and is kept until sign-out or app close.
            loadJob?.cancel()
            loadJob = null
            isLoading = false
            reports = emptyList()
            return
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            isLoading = true
            when (val stored = FirestoreRepository.loadMedicalProfile()) {
                is StoreResult.Success -> profile = stored.value ?: MedicalProfile()
                is StoreResult.Failure -> status = stored.message
            }
            when (val storedReports = FirestoreRepository.loadReports()) {
                is StoreResult.Success -> reports = storedReports.value
                is StoreResult.Failure -> if (!storedReports.notSignedIn) status = status ?: storedReports.message
            }
            isLoading = false
        }
    }

    fun showStatus(message: String?) {
        status = message
    }

    fun updateNoteDraft(value: String) {
        noteDraft = value
        noteMessage = null
    }

    fun requestReportPicker() {
        reportPickerRequested = true
    }

    fun onReportPickerOpened() {
        reportPickerRequested = false
    }

    fun itemsFor(category: ProfileCategory): List<String> = profile.itemsFor(category)

    fun isEditing(category: ProfileCategory): Boolean = category in editing

    fun toggleEditing(category: ProfileCategory) {
        editing = if (category in editing) editing - category else editing + category
    }

    /**
     * Only real entries reach the health context; junk would skew every scan verdict.
     * The cheap local check runs first, then an AI check.
     */
    fun addItem(scope: CoroutineScope, category: ProfileCategory, value: String) {
        val item = value.trim()
        if (item.isEmpty() || isProcessing) return
        val rejected = "\"$item\" doesn't look like a ${category.singular}. Check the spelling and try again."
        if (!HealthItemValidation.passesBasicCheck(item)) {
            status = rejected
            return
        }

        isProcessing = true
        status = "Checking \"$item\"..."
        scope.launch {
            try {
                val result = try {
                    HybridAIService.validateHealthItem(item, category.singular)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    status = "Couldn't verify \"$item\" right now, so it wasn't added. Try again."
                    return@launch
                }
                if (!result.isValid) {
                    status = rejected
                    return@launch
                }
                val normalized = result.normalizedName.orEmpty().trim()
                val finalItem = if (HealthItemValidation.passesBasicCheck(normalized)) normalized else item
                val updated = profile.withItem(category, finalItem)

                if (!isSignedIn) {
                    profile = updated
                    status = "Added for this session only. Sign in to save it to your account."
                    return@launch
                }
                when (val saved = FirestoreRepository.saveMedicalProfile(updated)) {
                    is StoreResult.Success -> {
                        profile = updated
                        status = "Saved to your account."
                    }
                    is StoreResult.Failure -> status = "Saving failed: ${saved.message}"
                }
            } finally {
                isProcessing = false
            }
        }
    }

    fun removeItem(scope: CoroutineScope, category: ProfileCategory, value: String) {
        if (itemsFor(category).size <= 1) editing = editing - category
        val updated = profile.withoutItem(category, value)
        if (!isSignedIn) {
            profile = updated
            status = "Removed for this session only. Sign in to keep your account in sync."
            return
        }
        scope.launch {
            when (val saved = FirestoreRepository.saveMedicalProfile(updated)) {
                is StoreResult.Success -> {
                    profile = updated
                    status = "Removed \"$value\"."
                }
                is StoreResult.Failure -> status = "Removing it from your account failed: ${saved.message}"
            }
        }
    }

    /** Signed out: merges an approved extraction into the session-only profile. */
    fun applyGuestExtraction(extraction: MedicalExtraction, reportTitle: String?) {
        profile = profile.mergedWith(extraction.copy(healthNotes = emptyList()))
        if (reportTitle != null) guestReportTitles = guestReportTitles + reportTitle
    }

    private fun clearAll() {
        loadJob?.cancel()
        loadJob = null
        profile = MedicalProfile()
        reports = emptyList()
        guestReportTitles = emptyList()
        noteDraft = ""
        noteMessage = null
        status = null
        editing = emptySet()
        isLoading = false
    }
}

private fun MedicalProfile.itemsFor(category: ProfileCategory): List<String> = when (category) {
    ProfileCategory.CONDITIONS -> conditions
    ProfileCategory.ALLERGIES -> allergies
    ProfileCategory.MEDICATIONS -> medications
    ProfileCategory.DIETARY_RESTRICTIONS -> dietaryRestrictions
    ProfileCategory.HEALTH_NOTES -> healthNotes
}

private fun MedicalProfile.withItems(category: ProfileCategory, values: List<String>): MedicalProfile =
    when (category) {
        ProfileCategory.CONDITIONS -> copy(conditions = values)
        ProfileCategory.ALLERGIES -> copy(allergies = values)
        ProfileCategory.MEDICATIONS -> copy(medications = values)
        ProfileCategory.DIETARY_RESTRICTIONS -> copy(dietaryRestrictions = values)
        ProfileCategory.HEALTH_NOTES -> copy(healthNotes = values)
    }

private fun MedicalProfile.withItem(category: ProfileCategory, value: String): MedicalProfile {
    val existing = itemsFor(category)
    if (existing.any { it.equals(value, ignoreCase = true) }) return this
    return withItems(category, (existing + value).take(MedicalProfile.MAX_ITEMS_PER_LIST))
}

private fun MedicalProfile.withoutItem(category: ProfileCategory, value: String): MedicalProfile =
    withItems(category, itemsFor(category).filterNot { it.equals(value, ignoreCase = true) })
