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
import com.scantruth.ai.model.HealthContextSuggestion
import com.scantruth.ai.model.MedicalProfile
import com.scantruth.ai.model.ReportRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The four lists of the stored health profile, in the order My Health shows them. */
enum class ProfileCategory(val title: String, val singular: String) {
    CONDITIONS("Diseases", "disease"),
    ALLERGIES("Allergies", "allergy"),
    MEDICATIONS("Medications", "medication"),
    DIETARY_RESTRICTIONS("Dietary Restrictions", "dietary restriction"),
    HEALTH_NOTES("Health Notes", "health note");

    companion object {
        val ACTIVE_CATEGORIES = listOf(CONDITIONS, ALLERGIES, MEDICATIONS, DIETARY_RESTRICTIONS)
    }
}

/**
 * My Health state: the stored profile, its editable lists, saved report metadata and the
 * free-text health note. Writes go straight to Firestore for the signed-in user.
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

    var error by mutableStateOf<String?>(null)
        private set

    var isSavingProfile by mutableStateOf(false)
        private set

    var noteDraft by mutableStateOf("")
        private set

    var noteMessage by mutableStateOf<String?>(null)
        private set

    var isSavingNote by mutableStateOf(false)
        private set

    var aiAssistantInput by mutableStateOf("")
        private set

    var isAiAssistantAnalyzing by mutableStateOf(false)
        private set

    var aiAssistantError by mutableStateOf<String?>(null)
        private set

    var aiAssistantClarification by mutableStateOf<String?>(null)
        private set

    var aiAssistantSuggestion by mutableStateOf<HealthContextSuggestion?>(null)
        private set

    var aiAssistantSuccessMessage by mutableStateOf<String?>(null)
        private set

    private var listener: FirebaseAuth.AuthStateListener? = null
    private var lastObservedUid: String? = runCatching { Firebase.auth.currentUser?.uid }.getOrNull()
    private var loadJob: Job? = null

    fun start(scope: CoroutineScope) {
        val auth = runCatching { Firebase.auth }.getOrNull() ?: return
        if (listener == null) {
            listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
                val user = firebaseAuth.currentUser
                val newUid = user?.uid
                val uidChanged = newUid != lastObservedUid
                lastObservedUid = newUid
                isSignedIn = user != null

                if (user != null) {
                    if (uidChanged || isLoading) {
                        load(scope)
                    }
                } else {
                    loadJob?.cancel()
                    loadJob = null
                    profile = MedicalProfile()
                    reports = emptyList()
                    noteDraft = ""
                    noteMessage = null
                    aiAssistantInput = ""
                    isAiAssistantAnalyzing = false
                    aiAssistantError = null
                    aiAssistantClarification = null
                    aiAssistantSuggestion = null
                    aiAssistantSuccessMessage = null
                    error = null
                    isLoading = false
                }
            }
            auth.addAuthStateListener(listener!!)
        }
    }

    fun stop() {
        val auth = runCatching { Firebase.auth }.getOrNull()
        listener?.let { auth?.removeAuthStateListener(it) }
        listener = null
    }

    fun load(scope: CoroutineScope) {
        isSignedIn = FirestoreRepository.isSignedIn()
        if (!isSignedIn) {
            loadJob?.cancel()
            loadJob = null
            isLoading = false
            profile = MedicalProfile()
            reports = emptyList()
            return
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            isLoading = true
            error = null

            when (val stored = FirestoreRepository.loadMedicalProfile()) {
                is StoreResult.Success -> profile = stored.value ?: MedicalProfile()
                is StoreResult.Failure -> error = stored.message
            }

            when (val storedReports = FirestoreRepository.loadReports()) {
                is StoreResult.Success -> reports = storedReports.value
                is StoreResult.Failure ->
                    if (!storedReports.notSignedIn) error = error ?: storedReports.message
            }

            isLoading = false
        }
    }

    fun updateNoteDraft(value: String) {
        noteDraft = value
        noteMessage = null
    }

    fun saveNote(scope: CoroutineScope) {
        val note = noteDraft.trim()
        if (note.isEmpty() || isSavingNote) return
        isSavingNote = true
        noteMessage = null

        scope.launch {
            val updated = profile.withHealthNote(note)
            when (val result = FirestoreRepository.saveMedicalProfile(updated)) {
                is StoreResult.Success -> {
                    profile = updated
                    noteDraft = ""
                    noteMessage = "Health note saved to your profile."
                }

                is StoreResult.Failure -> noteMessage = result.message
            }
            isSavingNote = false
        }
    }

    fun addItem(scope: CoroutineScope, category: ProfileCategory, value: String) {
        val item = value.trim()
        if (item.isEmpty()) return
        persist(scope, profile.withItem(category, item))
    }

    fun removeItem(scope: CoroutineScope, category: ProfileCategory, value: String) {
        persist(scope, profile.withoutItem(category, value))
    }

    fun itemsFor(category: ProfileCategory): List<String> = profile.itemsFor(category)

    /**
     * Replaces one category in a single write, used by the batch "Update My Conditions"
     * editor so the profile is not written on every keystroke.
     */
    fun updateCategory(
        scope: CoroutineScope,
        category: ProfileCategory,
        values: List<String>
    ) {
        val cleaned = values.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(MedicalProfile.MAX_ITEMS_PER_LIST)
        val updated = profile.withItems(category, cleaned)
        if (updated == profile) return
        persist(scope, updated)
    }

    fun updateAiAssistantInput(text: String) {
        aiAssistantInput = text
        aiAssistantError = null
        aiAssistantClarification = null
        aiAssistantSuccessMessage = null
    }

    fun clearAiAssistant() {
        aiAssistantInput = ""
        aiAssistantError = null
        aiAssistantClarification = null
        aiAssistantSuggestion = null
        aiAssistantSuccessMessage = null
    }

    fun dismissAiAssistantSuggestion() {
        aiAssistantSuggestion = null
        aiAssistantClarification = null
        aiAssistantError = null
    }

    internal fun setAiAssistantSuggestionForTesting(suggestion: HealthContextSuggestion?) {
        aiAssistantSuggestion = suggestion
    }

    internal fun setAiAssistantClarificationForTesting(clarification: String?) {
        aiAssistantClarification = clarification
    }

    fun analyzeHealthStatement(scope: CoroutineScope, statementOverride: String? = null) {
        val statement = (statementOverride ?: aiAssistantInput).trim()
        if (statement.isEmpty() || isAiAssistantAnalyzing) return
        if (!isSignedIn) {
            aiAssistantError = "Sign in with Google to organize your health profile."
            return
        }

        isAiAssistantAnalyzing = true
        aiAssistantError = null
        aiAssistantClarification = null
        aiAssistantSuccessMessage = null
        aiAssistantSuggestion = null

        scope.launch {
            try {
                val plan = HybridAIService.classifyHealthActions(statement, profile)
                if (plan.isAmbiguous) {
                    aiAssistantClarification = plan.clarificationNeeded
                        ?: plan.actions.firstOrNull { it.clarificationNeeded != null }?.clarificationNeeded
                        ?: "Please clarify which item you are referring to."
                } else if (plan.actions.isNotEmpty()) {
                    when (val res = FirestoreRepository.applyHealthActions(plan.actions)) {
                        is StoreResult.Success -> {
                            val (updatedProfile, changes) = res.value
                            profile = updatedProfile
                            if (changes.isNotEmpty()) {
                                aiAssistantSuccessMessage = buildString {
                                    appendLine("Updated your health profile:")
                                    changes.forEach { change ->
                                        appendLine("- $change")
                                    }
                                }.trimEnd()
                            } else {
                                aiAssistantSuccessMessage = "Profile is already up to date."
                            }
                            aiAssistantInput = ""
                        }
                        is StoreResult.Failure -> {
                            aiAssistantError = res.message
                        }
                    }
                } else {
                    aiAssistantError = "No health details or actions were identified from your statement."
                }
            } catch (e: Exception) {
                aiAssistantError = e.message ?: "Could not organize health details. Please check your connection."
            } finally {
                isAiAssistantAnalyzing = false
            }
        }
    }

    fun removeSuggestionItem(category: ProfileCategory, item: String) {
        val current = aiAssistantSuggestion ?: return
        aiAssistantSuggestion = when (category) {
            ProfileCategory.CONDITIONS -> current.copy(
                conditions = current.conditions.filterNot { it.equals(item, ignoreCase = true) }
            )
            ProfileCategory.ALLERGIES -> current.copy(
                allergies = current.allergies.filterNot { it.equals(item, ignoreCase = true) }
            )
            ProfileCategory.MEDICATIONS -> current.copy(
                medications = current.medications.filterNot { it.equals(item, ignoreCase = true) }
            )
            ProfileCategory.DIETARY_RESTRICTIONS -> current.copy(
                dietaryRestrictions = current.dietaryRestrictions.filterNot { it.equals(item, ignoreCase = true) }
            )
            ProfileCategory.HEALTH_NOTES -> current.copy(
                healthNotes = current.healthNotes.filterNot { it.equals(item, ignoreCase = true) }
            )
        }
    }

    fun removeSuggestionNote(note: String) {
        removeSuggestionItem(ProfileCategory.HEALTH_NOTES, note)
    }

    fun updateSuggestionItem(category: ProfileCategory, oldItem: String, newItem: String) {
        val trimmed = newItem.trim()
        if (trimmed.isEmpty()) {
            removeSuggestionItem(category, oldItem)
            return
        }
        val current = aiAssistantSuggestion ?: return
        aiAssistantSuggestion = when (category) {
            ProfileCategory.CONDITIONS -> current.copy(
                conditions = current.conditions.map { if (it.equals(oldItem, ignoreCase = true)) trimmed else it }.distinctBy { it.lowercase() }
            )
            ProfileCategory.ALLERGIES -> current.copy(
                allergies = current.allergies.map { if (it.equals(oldItem, ignoreCase = true)) trimmed else it }.distinctBy { it.lowercase() }
            )
            ProfileCategory.MEDICATIONS -> current.copy(
                medications = current.medications.map { if (it.equals(oldItem, ignoreCase = true)) trimmed else it }.distinctBy { it.lowercase() }
            )
            ProfileCategory.DIETARY_RESTRICTIONS -> current.copy(
                dietaryRestrictions = current.dietaryRestrictions.map { if (it.equals(oldItem, ignoreCase = true)) trimmed else it }.distinctBy { it.lowercase() }
            )
            ProfileCategory.HEALTH_NOTES -> current.copy(
                healthNotes = current.healthNotes.map { if (it.equals(oldItem, ignoreCase = true)) trimmed else it }.distinctBy { it.lowercase() }
            )
        }
    }

    fun updateSuggestionNote(oldNote: String, newNote: String) {
        updateSuggestionItem(ProfileCategory.HEALTH_NOTES, oldNote, newNote)
    }

    fun moveSuggestionItem(
        item: String,
        fromCategory: ProfileCategory,
        toCategory: ProfileCategory
    ) {
        if (fromCategory == toCategory) return
        val current = aiAssistantSuggestion ?: return
        val afterRemoval = when (fromCategory) {
            ProfileCategory.CONDITIONS -> current.copy(
                conditions = current.conditions.filterNot { it.equals(item, ignoreCase = true) }
            )
            ProfileCategory.ALLERGIES -> current.copy(
                allergies = current.allergies.filterNot { it.equals(item, ignoreCase = true) }
            )
            ProfileCategory.MEDICATIONS -> current.copy(
                medications = current.medications.filterNot { it.equals(item, ignoreCase = true) }
            )
            ProfileCategory.DIETARY_RESTRICTIONS -> current.copy(
                dietaryRestrictions = current.dietaryRestrictions.filterNot { it.equals(item, ignoreCase = true) }
            )
            ProfileCategory.HEALTH_NOTES -> current.copy(
                healthNotes = current.healthNotes.filterNot { it.equals(item, ignoreCase = true) }
            )
        }
        aiAssistantSuggestion = when (toCategory) {
            ProfileCategory.CONDITIONS -> afterRemoval.copy(
                conditions = (afterRemoval.conditions + item).distinctBy { it.lowercase() }
            )
            ProfileCategory.ALLERGIES -> afterRemoval.copy(
                allergies = (afterRemoval.allergies + item).distinctBy { it.lowercase() }
            )
            ProfileCategory.MEDICATIONS -> afterRemoval.copy(
                medications = (afterRemoval.medications + item).distinctBy { it.lowercase() }
            )
            ProfileCategory.DIETARY_RESTRICTIONS -> afterRemoval.copy(
                dietaryRestrictions = (afterRemoval.dietaryRestrictions + item).distinctBy { it.lowercase() }
            )
            ProfileCategory.HEALTH_NOTES -> afterRemoval.copy(
                healthNotes = (afterRemoval.healthNotes + item).distinctBy { it.lowercase() }
            )
        }
    }

    fun saveAiSuggestions(scope: CoroutineScope) {
        val suggestion = aiAssistantSuggestion ?: return
        if (suggestion.isEmpty) {
            aiAssistantError = "No suggestions to save."
            return
        }
        if (isSavingProfile) return
        isSavingProfile = true
        aiAssistantError = null

        scope.launch {
            when (val result = FirestoreRepository.mergeHealthContext(suggestion)) {
                is StoreResult.Success -> {
                    profile = result.value
                    val count = suggestion.totalCount
                    aiAssistantSuccessMessage = "Successfully added $count item" + (if (count == 1) "" else "s") + " to your health profile!"
                    aiAssistantSuggestion = null
                    aiAssistantInput = ""
                    noteDraft = ""
                }
                is StoreResult.Failure -> {
                    aiAssistantError = result.message
                }
            }
            isSavingProfile = false
        }
    }

    private fun persist(scope: CoroutineScope, updated: MedicalProfile) {
        if (isSavingProfile) return
        isSavingProfile = true
        error = null
        scope.launch {
            when (val result = FirestoreRepository.saveMedicalProfile(updated)) {
                is StoreResult.Success -> profile = updated
                is StoreResult.Failure -> error = result.message
            }
            isSavingProfile = false
        }
    }
}

private fun MedicalProfile.itemsFor(category: ProfileCategory): List<String> = when (category) {
    ProfileCategory.CONDITIONS -> conditions
    ProfileCategory.ALLERGIES -> allergies
    ProfileCategory.MEDICATIONS -> medications
    ProfileCategory.DIETARY_RESTRICTIONS -> dietaryRestrictions
    ProfileCategory.HEALTH_NOTES -> healthNotes
}

private fun MedicalProfile.withItem(category: ProfileCategory, value: String): MedicalProfile {
    val existing = itemsFor(category)
    if (existing.any { it.equals(value, ignoreCase = true) }) return this
    val updated = existing + value
    return when (category) {
        ProfileCategory.CONDITIONS -> copy(conditions = updated)
        ProfileCategory.ALLERGIES -> copy(allergies = updated)
        ProfileCategory.MEDICATIONS -> copy(medications = updated)
        ProfileCategory.DIETARY_RESTRICTIONS -> copy(dietaryRestrictions = updated)
        ProfileCategory.HEALTH_NOTES -> copy(healthNotes = updated)
    }
}

private fun MedicalProfile.withItems(
    category: ProfileCategory,
    values: List<String>
): MedicalProfile = when (category) {
    ProfileCategory.CONDITIONS -> copy(conditions = values)
    ProfileCategory.ALLERGIES -> copy(allergies = values)
    ProfileCategory.MEDICATIONS -> copy(medications = values)
    ProfileCategory.DIETARY_RESTRICTIONS -> copy(dietaryRestrictions = values)
    ProfileCategory.HEALTH_NOTES -> copy(healthNotes = values)
}

private fun MedicalProfile.withoutItem(category: ProfileCategory, value: String): MedicalProfile {
    val updated = itemsFor(category).filterNot { it.equals(value, ignoreCase = true) }
    return when (category) {
        ProfileCategory.CONDITIONS -> copy(conditions = updated)
        ProfileCategory.ALLERGIES -> copy(allergies = updated)
        ProfileCategory.MEDICATIONS -> copy(medications = updated)
        ProfileCategory.DIETARY_RESTRICTIONS -> copy(dietaryRestrictions = updated)
        ProfileCategory.HEALTH_NOTES -> copy(healthNotes = updated)
    }
}
