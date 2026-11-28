package com.scantruth.ai.model

/** Which engine produced an analysis. Always shown to the user, never guessed. */
enum class AiEngine(val label: String) {
    CLOUD("Gemini Flash · cloud"),
    ON_DEVICE("Gemini Nano · on-device"),
    OFFLINE("Offline rules · on-device")
}

/** The AI modes the Scan screen offers. */
enum class AiMode(val label: String) {
    AUTO("Auto"),
    NANO("Nano"),
    ON_DEVICE("On-device");

    companion object {
        fun fromWire(value: String?): AiMode = when (value?.trim()?.uppercase()) {
            "FLASH" -> AUTO
            "LOCAL", "OFFLINE", "ON_DEVICE" -> ON_DEVICE
            else -> entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: AUTO
        }
    }
}

/** The kinds of personal identifier the local privacy layer looks for. */
enum class IdentifierCategory(val label: String, val tokenPrefix: String) {
    PATIENT_NAME("Patient name", "NAME"),
    DATE_OF_BIRTH("Date of birth", "DOB"),
    PHONE("Phone number", "PHONE"),
    EMAIL("Email address", "EMAIL"),
    ADDRESS("Address", "ADDRESS"),
    PATIENT_ID("Patient ID", "PATIENT_ID"),
    HOSPITAL_ID("Hospital or lab ID", "HOSPITAL_ID"),
    OTHER("Other identifier", "ID")
}

/**
 * One class of identifier found in a document.
 *
 * Deliberately holds no original value: only the token that replaced it in the
 * redacted text and how many times it occurred. Original identifiers never leave
 * the masking call, so they cannot be logged, stored or uploaded by accident.
 */
data class DetectedIdentifier(
    val category: IdentifierCategory,
    val token: String,
    val occurrences: Int
)

/** How urgently a review alert needs the user's attention before approval. */
enum class AlertSeverity { HIGH, MEDIUM }

/** Where a review alert came from. */
enum class AlertArea { RESIDUAL, OCR, SUSPICIOUS }

/**
 * One thing the user should look at while reviewing a report.
 *
 * Deliberately value-free: a title, an explanation and an optional line number. Neither
 * the original identifier nor the original text ever reaches this model, so it cannot be
 * logged, stored, uploaded or shown by accident.
 */
data class PrivacyAlert(
    val area: AlertArea,
    val severity: AlertSeverity,
    val title: String,
    val detail: String,
    val lineNumber: Int? = null
)

/** Medical text with every detected personal identifier replaced by a token. */
data class RedactedDocument(
    val redactedText: String,
    val detectedIdentifiers: List<DetectedIdentifier>,
    val totalReplacements: Int,
    val sourcePages: Int,
    val truncated: Boolean,
    /**
     * The review copy of the original layout, with every masked value shown as bullets
     * instead of characters. Never contains an identifier, only its position.
     */
    val protectedOriginalText: String = redactedText,
    val alerts: List<PrivacyAlert> = emptyList()
) {
    val isUsable: Boolean get() = redactedText.isNotBlank()

    val highRiskAlerts: List<PrivacyAlert>
        get() = alerts.filter { it.severity == AlertSeverity.HIGH }

    /** Approval stays blocked while any high-risk identifier is still visible. */
    val isSafeToApprove: Boolean
        get() = isUsable && highRiskAlerts.isEmpty()

    fun summaryLine(): String = when {
        detectedIdentifiers.isEmpty() -> "No personal identifiers were detected."
        totalReplacements == 1 -> "1 identifier masked across ${detectedIdentifiers.size} categor" +
            (if (detectedIdentifiers.size == 1) "y." else "ies.")

        else -> "$totalReplacements identifiers masked across ${detectedIdentifiers.size} " +
            (if (detectedIdentifiers.size == 1) "category." else "categories.")
    }
}

enum class MarkerStatus { NORMAL, HIGH, LOW, UNKNOWN }

data class HealthMarker(
    val name: String,
    val value: String,
    val unit: String?,
    val status: MarkerStatus
)

/** Structured result of the cloud medical extraction step. */
data class MedicalExtraction(
    val reportTitle: String,
    val reportType: String,
    val conditions: List<String>,
    val allergies: List<String>,
    val medications: List<String>,
    val dietaryRestrictions: List<String>,
    val healthNotes: List<String>,
    val markers: List<HealthMarker>,
    val engine: AiEngine,
    /** True when this result came from a fallback path rather than the requested one. */
    val usedFallback: Boolean = false,
    /** Value-free explanation of the fallback, or null when none happened. */
    val fallbackNotice: String? = null
) {
    val isEmpty: Boolean
        get() = conditions.isEmpty() && allergies.isEmpty() && medications.isEmpty() &&
            dietaryRestrictions.isEmpty() && healthNotes.isEmpty() && markers.isEmpty()
}

/** Structured suggestions from the AI Health Context Assistant. */
data class HealthContextSuggestion(
    val conditions: List<String> = emptyList(),
    val allergies: List<String> = emptyList(),
    val medications: List<String> = emptyList(),
    val dietaryRestrictions: List<String> = emptyList(),
    val healthNotes: List<String> = emptyList(),
    val clarificationNeeded: String? = null
) {
    val isEmpty: Boolean
        get() = conditions.isEmpty() && allergies.isEmpty() && medications.isEmpty() &&
            dietaryRestrictions.isEmpty() && healthNotes.isEmpty()

    val totalCount: Int
        get() = conditions.size + allergies.size + medications.size +
            dietaryRestrictions.size + healthNotes.size
}

enum class HealthActionType {
    ADD,
    REMOVE,
    CLARIFY
}

data class HealthActionItem(
    val item: String,
    val category: String,
    val action: HealthActionType,
    val confidence: String = "high",
    val clarificationNeeded: String? = null
)

data class HealthActionPlan(
    val actions: List<HealthActionItem> = emptyList(),
    val clarificationNeeded: String? = null
) {
    val isEmpty: Boolean
        get() = actions.isEmpty() && clarificationNeeded == null

    val isAmbiguous: Boolean
        get() = clarificationNeeded != null || actions.any { it.action == HealthActionType.CLARIFY || it.clarificationNeeded != null }
}

/** Result of checking a hand-typed health item against its list. */
data class HealthItemValidation(
    val isValid: Boolean,
    val normalizedName: String? = null,
    val reason: String? = null
) {
    companion object {
        private val ALLOWED_SYMBOLS = " .,-()%'+/&".toSet()

        /**
         * Cheap check before any model call: 2-60 characters, at least two letters and no
         * unusual symbols. Same rule as iOS and web.
         */
        fun passesBasicCheck(text: String): Boolean {
            if (text.length !in 2..60) return false
            if (text.count { it.isLetter() } < 2) return false
            return text.all { it.isLetterOrDigit() || it in ALLOWED_SYMBOLS }
        }
    }
}

/** The user's approved health context, as stored in Firestore. */
data class MedicalProfile(
    val conditions: List<String> = emptyList(),
    val allergies: List<String> = emptyList(),
    val medications: List<String> = emptyList(),
    val dietaryRestrictions: List<String> = emptyList(),
    val healthNotes: List<String> = emptyList(),
    val updatedAtMillis: Long? = null
) {
    val isEmpty: Boolean
        get() = conditions.isEmpty() && allergies.isEmpty() && medications.isEmpty() &&
            dietaryRestrictions.isEmpty() && healthNotes.isEmpty()

    val itemCount: Int
        get() = conditions.size + allergies.size + medications.size +
            dietaryRestrictions.size + healthNotes.size

    /** Appends extraction results, de-duplicating case-insensitively. */
    fun mergedWith(extraction: MedicalExtraction): MedicalProfile = copy(
        conditions = merge(conditions, extraction.conditions),
        allergies = merge(allergies, extraction.allergies),
        medications = merge(medications, extraction.medications),
        dietaryRestrictions = merge(dietaryRestrictions, extraction.dietaryRestrictions),
        healthNotes = merge(healthNotes, extraction.healthNotes)
    )

    /** Appends AI health context suggestions, de-duplicating case-insensitively. */
    fun mergedWith(suggestion: HealthContextSuggestion): MedicalProfile = copy(
        conditions = merge(conditions, suggestion.conditions),
        allergies = merge(allergies, suggestion.allergies),
        medications = merge(medications, suggestion.medications),
        dietaryRestrictions = merge(dietaryRestrictions, suggestion.dietaryRestrictions),
        healthNotes = merge(healthNotes, suggestion.healthNotes)
    )

    /** Applies structured ADD and REMOVE actions atomically, returning the updated profile and summary log. */
    fun applyingActions(actions: List<HealthActionItem>): Pair<MedicalProfile, List<String>> {
        val currentConditions = conditions.toMutableList()
        val currentAllergies = allergies.toMutableList()
        val currentMedications = medications.toMutableList()
        val currentDietary = dietaryRestrictions.toMutableList()
        val changes = mutableListOf<String>()

        actions.forEach { actionItem ->
            val value = actionItem.item.trim()
            if (value.isEmpty()) return@forEach
            val catNorm = actionItem.category.lowercase().replace(" ", "").replace("_", "")
            when (actionItem.action) {
                HealthActionType.ADD -> {
                    when {
                        catNorm.contains("disease") || catNorm.contains("condition") -> {
                            if (!currentConditions.any { it.equals(value, ignoreCase = true) }) {
                                currentConditions.add(value)
                                changes.add("Added $value to Diseases.")
                            }
                        }
                        catNorm.contains("allerg") -> {
                            if (!currentAllergies.any { it.equals(value, ignoreCase = true) }) {
                                currentAllergies.add(value)
                                changes.add("Added $value to Allergies.")
                            }
                        }
                        catNorm.contains("med") -> {
                            if (!currentMedications.any { it.equals(value, ignoreCase = true) }) {
                                currentMedications.add(value)
                                changes.add("Added $value to Medications.")
                            }
                        }
                        catNorm.contains("diet") -> {
                            if (!currentDietary.any { it.equals(value, ignoreCase = true) }) {
                                currentDietary.add(value)
                                changes.add("Added $value to Dietary Restrictions.")
                            }
                        }
                    }
                }
                HealthActionType.REMOVE -> {
                    when {
                        catNorm.contains("disease") || catNorm.contains("condition") -> {
                            val removed = currentConditions.removeAll { it.equals(value, ignoreCase = true) }
                            if (removed) {
                                changes.add("Removed $value from Diseases.")
                            }
                        }
                        catNorm.contains("allerg") -> {
                            val removed = currentAllergies.removeAll { it.equals(value, ignoreCase = true) }
                            if (removed) {
                                changes.add("Removed $value from Allergies.")
                            }
                        }
                        catNorm.contains("med") -> {
                            val removed = currentMedications.removeAll { it.equals(value, ignoreCase = true) }
                            if (removed) {
                                changes.add("Removed $value from Medications.")
                            }
                        }
                        catNorm.contains("diet") -> {
                            val removed = currentDietary.removeAll { it.equals(value, ignoreCase = true) }
                            if (removed) {
                                changes.add("Removed $value from Dietary Restrictions.")
                            }
                        }
                    }
                }
                HealthActionType.CLARIFY -> {}
            }
        }

        val updated = copy(
            conditions = currentConditions.take(MAX_ITEMS_PER_LIST),
            allergies = currentAllergies.take(MAX_ITEMS_PER_LIST),
            medications = currentMedications.take(MAX_ITEMS_PER_LIST),
            dietaryRestrictions = currentDietary.take(MAX_ITEMS_PER_LIST)
        )
        return updated to changes
    }

    fun withHealthNote(note: String): MedicalProfile =
        copy(healthNotes = merge(healthNotes, listOf(note)))

    companion object {
        const val MAX_ITEMS_PER_LIST = 50

        private fun merge(existing: List<String>, incoming: List<String>): List<String> {
            val seen = LinkedHashMap<String, String>()
            (existing + incoming).forEach { raw ->
                val value = raw.trim()
                if (value.isNotEmpty()) seen.putIfAbsent(value.lowercase(), value)
            }
            return seen.values.take(MAX_ITEMS_PER_LIST)
        }
    }
}

enum class FactorDirection { POSITIVE, CAUTION, NEUTRAL, UNKNOWN }

data class HealthFactor(
    val label: String,
    val detail: String,
    val direction: FactorDirection
)

enum class VerdictLevel { SAFE, CAUTION, AVOID, UNKNOWN }

data class IngredientVerdict(
    val name: String,
    val verdict: VerdictLevel,
    val note: String?,
    val conflict: String? = null
)

/** Stage-by-stage timing breakdown for scanning & inference diagnostics (in milliseconds). */
data class ScanPerformanceMetrics(
    val ocrDurationMs: Long = 0L,
    val parseDurationMs: Long = 0L,
    val profileLoadMs: Long = 0L,
    val aiInferenceDurationMs: Long = 0L,
    val postProcessDurationMs: Long = 0L
) {
    val totalScanMs: Long get() = ocrDurationMs + parseDurationMs
    val totalAnalysisMs: Long get() = profileLoadMs + aiInferenceDurationMs + postProcessDurationMs
}

/** Structured result of one ingredient analysis. */
data class IngredientAnalysis(
    val productName: String,
    val productCategory: String,
    val safetyScore: Int,
    val badgeLabel: String,
    val headline: String,
    val aiSummary: String,
    val healthFactors: List<HealthFactor>,
    val ingredientBreakdown: List<IngredientVerdict>,
    val recommendedIntake: String,
    val uncertaintyNotes: List<String>,
    val engine: AiEngine,
    /** True when Auto fell back to the cloud because on-device analysis could not run. */
    val usedFallback: Boolean = false,
    /** Cloud-fallback notice, or null for an on-device or explicitly-cloud run. */
    val fallbackNotice: String? = null
) {
    /** 70-100 good match, 40-69 use with care, 0-39 avoid. */
    val band: SafetyBand
        get() = when {
            safetyScore >= 70 -> SafetyBand.GOOD
            safetyScore >= 40 -> SafetyBand.CARE
            else -> SafetyBand.AVOID
        }
}

enum class SafetyBand(val label: String) {
    GOOD("Good match"),
    CARE("Use with care"),
    AVOID("Avoid")
}

enum class ScanKind(val wireValue: String) {
    INGREDIENT("ingredient"),
    MEDICAL_REPORT("medical_report");

    companion object {
        fun fromWire(value: String?): ScanKind =
            entries.firstOrNull { it.wireValue == value } ?: INGREDIENT
    }
}

/**
 * One row in Home / scan history. The detail fields are filled when the saved document
 * carries them (scans saved by the iOS and web apps use the full SPEC.md schema).
 */
data class ScanRecord(
    val id: String,
    val kind: ScanKind,
    val title: String,
    val subtitle: String,
    val safetyScore: Int?,
    val badgeLabel: String?,
    val scannedAtMillis: Long?,
    val headline: String? = null,
    val aiSummary: String? = null,
    val healthFactors: List<SavedHealthFactor> = emptyList(),
    val ingredientBreakdown: List<SavedIngredientNote> = emptyList(),
    val intakeHeadline: String? = null,
    val intakeGuidance: String? = null,
    val ingredientCount: Int? = null,
    val modelUsed: String? = null
) {
    /** Badge from the shared rubric (70-100 / 40-69 / 0-39), so every platform shows the same label. */
    val displayBadge: String?
        get() = safetyScore?.let {
            when {
                it >= 70 -> "Good match for you"
                it >= 40 -> "Use with care"
                else -> "Avoid"
            }
        }
}

data class SavedHealthFactor(val title: String, val description: String?, val badge: String?)

data class SavedIngredientNote(val name: String, val note: String?, val status: String?)

/** One saved medical report, metadata only. */
data class ReportRecord(
    val id: String,
    val reportTitle: String,
    val reportType: String,
    val markersCount: Int,
    val identifiersMasked: Int,
    val savedAtMillis: Long?
)
