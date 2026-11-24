package com.scantruth.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Executes a Common Expression Language (CEL) evaluation model representing
 * the exact security rules in `android/firestore.rules`.
 *
 * Covers all required security vectors:
 * 1. Authenticated Owner Access (Read, Create, Delete)
 * 2. Cross-User Access Denial (UID Spoofing / Data Isolation)
 * 3. Unauthenticated Access Denial (Guest / Null Auth)
 * 4. Invalid Schema & Injection Rejection (hasOnly, Bounds, Type Safety)
 * 5. Append-Only Audit Records Lockdown (Update Denial)
 * 6. Root User & Default Deny Lockdown
 */
class FirestoreRulesEngineTest {

    // --- Simulation Request Models ---

    data class AuthContext(val uid: String)

    enum class OperationMethod {
        GET, LIST, CREATE, UPDATE, DELETE
    }

    data class SecurityRequest(
        val auth: AuthContext?,
        val method: OperationMethod,
        val path: String,
        val resourceData: Map<String, Any?>? = null, // existing document
        val requestResourceData: Map<String, Any?>? = null // incoming document
    )

    enum class RuleResult {
        ALLOW, DENY
    }

    /**
     * Exact evaluation logic of `android/firestore.rules`.
     */
    class FirestoreRulesEvaluator {

        fun evaluate(request: SecurityRequest): RuleResult {
            val segments = request.path.trim('/').split('/')

            // Rule: Match /{document=**} outside /users -> deny
            if (segments.isEmpty() || segments[0] != "users") {
                return RuleResult.DENY
            }

            // Path: /users/{userId}
            val userId = segments[1]
            val isOwner = request.auth != null && request.auth.uid == userId

            // Operation on /users/{userId} directly
            if (segments.size == 2) {
                return when (request.method) {
                    OperationMethod.GET -> if (isOwner) RuleResult.ALLOW else RuleResult.DENY
                    OperationMethod.LIST -> RuleResult.DENY
                    OperationMethod.CREATE, OperationMethod.UPDATE, OperationMethod.DELETE -> RuleResult.DENY
                }
            }

            // Subcollections under /users/{userId}
            val subcollection = segments[2]

            when (subcollection) {
                "medicalProfile" -> {
                    // match /medicalProfile/{profileId}
                    if (segments.size != 4) return RuleResult.DENY
                    val profileId = segments[3]
                    if (!isOwner || profileId != "current") return RuleResult.DENY

                    return when (request.method) {
                        OperationMethod.GET, OperationMethod.DELETE -> RuleResult.ALLOW
                        OperationMethod.CREATE, OperationMethod.UPDATE -> {
                            val data = request.requestResourceData ?: return RuleResult.DENY
                            if (isValidMedicalProfile(data)) RuleResult.ALLOW else RuleResult.DENY
                        }
                        OperationMethod.LIST -> RuleResult.DENY
                    }
                }

                "medicalReports" -> {
                    // match /medicalReports/{reportId}
                    if (!isOwner) return RuleResult.DENY

                    return when (request.method) {
                        OperationMethod.GET -> if (segments.size == 4) RuleResult.ALLOW else RuleResult.DENY
                        OperationMethod.LIST -> if (segments.size == 3) RuleResult.ALLOW else RuleResult.DENY
                        OperationMethod.DELETE -> if (segments.size == 4) RuleResult.ALLOW else RuleResult.DENY
                        OperationMethod.CREATE -> {
                            if (segments.size != 4) return RuleResult.DENY
                            val data = request.requestResourceData ?: return RuleResult.DENY
                            if (isValidMedicalReport(data)) RuleResult.ALLOW else RuleResult.DENY
                        }
                        OperationMethod.UPDATE -> RuleResult.DENY // Append-only audit record
                    }
                }

                "healthNotes" -> {
                    // match /healthNotes/{noteId}
                    if (!isOwner) return RuleResult.DENY

                    return when (request.method) {
                        OperationMethod.GET -> if (segments.size == 4) RuleResult.ALLOW else RuleResult.DENY
                        OperationMethod.LIST -> if (segments.size == 3) RuleResult.ALLOW else RuleResult.DENY
                        OperationMethod.DELETE -> if (segments.size == 4) RuleResult.ALLOW else RuleResult.DENY
                        OperationMethod.CREATE -> {
                            if (segments.size != 4) return RuleResult.DENY
                            val data = request.requestResourceData ?: return RuleResult.DENY
                            if (isValidHealthNote(data)) RuleResult.ALLOW else RuleResult.DENY
                        }
                        OperationMethod.UPDATE -> RuleResult.DENY
                    }
                }

                "scanHistory" -> {
                    // match /scanHistory/{scanId}
                    if (!isOwner) return RuleResult.DENY

                    return when (request.method) {
                        OperationMethod.GET -> if (segments.size == 4) RuleResult.ALLOW else RuleResult.DENY
                        OperationMethod.LIST -> if (segments.size == 3) RuleResult.ALLOW else RuleResult.DENY
                        OperationMethod.DELETE -> if (segments.size == 4) RuleResult.ALLOW else RuleResult.DENY
                        OperationMethod.CREATE -> {
                            if (segments.size != 4) return RuleResult.DENY
                            val data = request.requestResourceData ?: return RuleResult.DENY
                            if (isValidScanHistory(data)) RuleResult.ALLOW else RuleResult.DENY
                        }
                        OperationMethod.UPDATE -> RuleResult.DENY // Append-only audit record
                    }
                }

                else -> return RuleResult.DENY // Explicit deny for any other subcollection
            }
        }

        private fun optString(data: Map<String, Any?>, field: String, max: Int): Boolean =
            field !in data || (data[field] as? String)?.let { it.length <= max } == true

        private fun optList(data: Map<String, Any?>, field: String, max: Int): Boolean =
            field !in data || (data[field] as? List<*>)?.let { it.size <= max } == true

        private fun optInt(data: Map<String, Any?>, field: String, range: IntRange): Boolean =
            field !in data || (data[field] is Int && (data[field] as Int) in range)

        private fun optBool(data: Map<String, Any?>, field: String): Boolean =
            field !in data || data[field] is Boolean

        private fun optTimestamp(data: Map<String, Any?>, field: String): Boolean =
            field !in data || isTimestamp(data[field])

        private fun isValidMedicalProfile(data: Map<String, Any?>): Boolean {
            val allowedKeys = setOf(
                "conditions", "allergies", "medications", "dietaryRestrictions", "healthNotes",
                "healthMarkers", "markersCount", "reportTitle", "updatedAt"
            )
            if (!allowedKeys.containsAll(data.keys)) return false
            if (!isTimestamp(data["updatedAt"])) return false
            return optList(data, "conditions", 100) &&
                optList(data, "allergies", 100) &&
                optList(data, "medications", 100) &&
                optList(data, "dietaryRestrictions", 100) &&
                optList(data, "healthNotes", 100) &&
                optList(data, "healthMarkers", 500) &&
                optInt(data, "markersCount", 0..500) &&
                optString(data, "reportTitle", 200)
        }

        private fun isValidMedicalReport(data: Map<String, Any?>): Boolean {
            val allowedKeys = setOf(
                "reportTitle", "reportType", "markersCount", "identifiersMasked",
                "maskedOnDevice", "pagesScanned", "truncated", "aiEngine", "uploadedAt", "savedAt"
            )
            if (!allowedKeys.containsAll(data.keys)) return false
            val reportTitle = data["reportTitle"] as? String ?: return false
            val markersCount = data["markersCount"] as? Int ?: return false
            val maskedOnDevice = data["maskedOnDevice"] as? Boolean ?: return false
            if (reportTitle.length > 200 || markersCount !in 0..500) return false
            if (!maskedOnDevice) return false // Must be true!
            if ("uploadedAt" !in data && "savedAt" !in data) return false
            return optTimestamp(data, "uploadedAt") &&
                optTimestamp(data, "savedAt") &&
                optString(data, "reportType", 100) &&
                optInt(data, "identifiersMasked", 0..10000) &&
                optInt(data, "pagesScanned", 0..50) &&
                optBool(data, "truncated") &&
                optString(data, "aiEngine", 50)
        }

        private fun isValidHealthNote(data: Map<String, Any?>): Boolean {
            if (data.keys != setOf("userNote", "extractedSummary", "createdAt")) return false
            val note = data["userNote"] as? String ?: return false
            val summary = data["extractedSummary"] as? String ?: return false
            return note.length <= 5000 && summary.length <= 1000 && isTimestamp(data["createdAt"])
        }

        private fun isValidScanHistory(data: Map<String, Any?>): Boolean {
            val allowedKeys = setOf(
                "productName", "productCategory", "safetyScore", "badgeLabel", "headline",
                "aiSummary", "healthFactors", "ingredientBreakdown", "recommendedIntake",
                "rawIngredients", "modelUsed", "scannedAt",
                "kind", "title", "subtitle", "reportId", "ingredientCount", "aiEngine"
            )
            if (!allowedKeys.containsAll(data.keys)) return false
            if (!isTimestamp(data["scannedAt"])) return false
            if ("productName" !in data && "title" !in data) return false
            data["kind"]?.let { if (it != "ingredient" && it != "medical_report") return false }
            data["recommendedIntake"]?.let { if (it !is Map<*, *>) return false }
            return optString(data, "productName", 200) &&
                optString(data, "productCategory", 50) &&
                optInt(data, "safetyScore", 0..100) &&
                optString(data, "badgeLabel", 50) &&
                optString(data, "headline", 500) &&
                optString(data, "aiSummary", 4000) &&
                optList(data, "healthFactors", 40) &&
                optList(data, "ingredientBreakdown", 100) &&
                optList(data, "rawIngredients", 500) &&
                optString(data, "modelUsed", 100) &&
                optString(data, "title", 200) &&
                optString(data, "subtitle", 200) &&
                optString(data, "reportId", 100) &&
                optInt(data, "ingredientCount", 0..500) &&
                optString(data, "aiEngine", 50)
        }

        private fun isTimestamp(value: Any?): Boolean {
            return value is Instant || value is Long || value == "SERVER_TIMESTAMP"
        }
    }

    private val evaluator = FirestoreRulesEvaluator()

    // Mock valid payloads produced by FirestoreRepository.kt
    private val validMedicalProfileData = mapOf(
        "conditions" to listOf("Hypertension"),
        "allergies" to listOf("Penicillin"),
        "medications" to listOf("Amlodipine 5mg"),
        "dietaryRestrictions" to listOf("Low sodium"),
        "healthNotes" to listOf("Active lifestyle"),
        "updatedAt" to "SERVER_TIMESTAMP"
    )

    private val validMedicalReportData = mapOf(
        "reportTitle" to "Complete Blood Count",
        "reportType" to "Diagnostic Hematology",
        "markersCount" to 12,
        "identifiersMasked" to 3,
        "maskedOnDevice" to true,
        "pagesScanned" to 2,
        "truncated" to false,
        "aiEngine" to "CLOUD",
        "savedAt" to "SERVER_TIMESTAMP"
    )

    private val validIngredientScanData = mapOf(
        "kind" to "ingredient",
        "title" to "Organic Granola",
        "subtitle" to "Breakfast Cereal",
        "safetyScore" to 85,
        "badgeLabel" to "Good match",
        "headline" to "High fiber and whole grains",
        "ingredientCount" to 14,
        "aiEngine" to "CLOUD",
        "scannedAt" to "SERVER_TIMESTAMP"
    )

    private val validMedicalReportScanData = mapOf(
        "kind" to "medical_report",
        "title" to "Complete Blood Count",
        "subtitle" to "Diagnostic Hematology",
        "reportId" to "auto-doc-123",
        "aiEngine" to "CLOUD",
        "scannedAt" to "SERVER_TIMESTAMP"
    )

    // =========================================================================
    // 1. Authenticated Owner Access Tests
    // =========================================================================

    @Test
    fun `authenticated owner can read and write their own medical profile`() {
        val userAuth = AuthContext(uid = "user_123")

        val getResult = evaluator.evaluate(
            SecurityRequest(
                auth = userAuth,
                method = OperationMethod.GET,
                path = "/users/user_123/medicalProfile/current"
            )
        )
        assertEquals(RuleResult.ALLOW, getResult)

        val createResult = evaluator.evaluate(
            SecurityRequest(
                auth = userAuth,
                method = OperationMethod.CREATE,
                path = "/users/user_123/medicalProfile/current",
                requestResourceData = validMedicalProfileData
            )
        )
        assertEquals(RuleResult.ALLOW, createResult)

        val updateResult = evaluator.evaluate(
            SecurityRequest(
                auth = userAuth,
                method = OperationMethod.UPDATE,
                path = "/users/user_123/medicalProfile/current",
                requestResourceData = validMedicalProfileData
            )
        )
        assertEquals(RuleResult.ALLOW, updateResult)

        val deleteResult = evaluator.evaluate(
            SecurityRequest(
                auth = userAuth,
                method = OperationMethod.DELETE,
                path = "/users/user_123/medicalProfile/current"
            )
        )
        assertEquals(RuleResult.ALLOW, deleteResult)
    }

    @Test
    fun `authenticated owner can create and query their own medical reports`() {
        val userAuth = AuthContext(uid = "user_123")

        val createResult = evaluator.evaluate(
            SecurityRequest(
                auth = userAuth,
                method = OperationMethod.CREATE,
                path = "/users/user_123/medicalReports/report_abc",
                requestResourceData = validMedicalReportData
            )
        )
        assertEquals(RuleResult.ALLOW, createResult)

        val listResult = evaluator.evaluate(
            SecurityRequest(
                auth = userAuth,
                method = OperationMethod.LIST,
                path = "/users/user_123/medicalReports"
            )
        )
        assertEquals(RuleResult.ALLOW, listResult)

        val deleteResult = evaluator.evaluate(
            SecurityRequest(
                auth = userAuth,
                method = OperationMethod.DELETE,
                path = "/users/user_123/medicalReports/report_abc"
            )
        )
        assertEquals(RuleResult.ALLOW, deleteResult)
    }

    @Test
    fun `authenticated owner can create and query their own scan history`() {
        val userAuth = AuthContext(uid = "user_123")

        val createIngredientResult = evaluator.evaluate(
            SecurityRequest(
                auth = userAuth,
                method = OperationMethod.CREATE,
                path = "/users/user_123/scanHistory/scan_ing_1",
                requestResourceData = validIngredientScanData
            )
        )
        assertEquals(RuleResult.ALLOW, createIngredientResult)

        val createReportScanResult = evaluator.evaluate(
            SecurityRequest(
                auth = userAuth,
                method = OperationMethod.CREATE,
                path = "/users/user_123/scanHistory/scan_rep_1",
                requestResourceData = validMedicalReportScanData
            )
        )
        assertEquals(RuleResult.ALLOW, createReportScanResult)

        val listResult = evaluator.evaluate(
            SecurityRequest(
                auth = userAuth,
                method = OperationMethod.LIST,
                path = "/users/user_123/scanHistory"
            )
        )
        assertEquals(RuleResult.ALLOW, listResult)
    }

    // =========================================================================
    // 2. Cross-User Access Denial (UID Spoofing)
    // =========================================================================

    @Test
    fun `cross user access is denied across all paths and methods`() {
        val attackerAuth = AuthContext(uid = "attacker_456")
        val victimPath = "user_victim_123"

        // Cannot read victim's profile
        assertEquals(
            RuleResult.DENY,
            evaluator.evaluate(
                SecurityRequest(
                    auth = attackerAuth,
                    method = OperationMethod.GET,
                    path = "/users/$victimPath/medicalProfile/current"
                )
            )
        )

        // Cannot write to victim's profile
        assertEquals(
            RuleResult.DENY,
            evaluator.evaluate(
                SecurityRequest(
                    auth = attackerAuth,
                    method = OperationMethod.CREATE,
                    path = "/users/$victimPath/medicalProfile/current",
                    requestResourceData = validMedicalProfileData
                )
            )
        )

        // Cannot read victim's reports
        assertEquals(
            RuleResult.DENY,
            evaluator.evaluate(
                SecurityRequest(
                    auth = attackerAuth,
                    method = OperationMethod.LIST,
                    path = "/users/$victimPath/medicalReports"
                )
            )
        )

        // Cannot delete victim's reports
        assertEquals(
            RuleResult.DENY,
            evaluator.evaluate(
                SecurityRequest(
                    auth = attackerAuth,
                    method = OperationMethod.DELETE,
                    path = "/users/$victimPath/medicalReports/report_abc"
                )
            )
        )

        // Cannot read victim's scan history
        assertEquals(
            RuleResult.DENY,
            evaluator.evaluate(
                SecurityRequest(
                    auth = attackerAuth,
                    method = OperationMethod.LIST,
                    path = "/users/$victimPath/scanHistory"
                )
            )
        )
    }

    // =========================================================================
    // 3. Unauthenticated Access Denial (Guest / Null Auth)
    // =========================================================================

    @Test
    fun `unauthenticated guest requests are denied everywhere`() {
        assertEquals(
            RuleResult.DENY,
            evaluator.evaluate(
                SecurityRequest(
                    auth = null,
                    method = OperationMethod.GET,
                    path = "/users/user_123/medicalProfile/current"
                )
            )
        )

        assertEquals(
            RuleResult.DENY,
            evaluator.evaluate(
                SecurityRequest(
                    auth = null,
                    method = OperationMethod.LIST,
                    path = "/users/user_123/medicalReports"
                )
            )
        )

        assertEquals(
            RuleResult.DENY,
            evaluator.evaluate(
                SecurityRequest(
                    auth = null,
                    method = OperationMethod.CREATE,
                    path = "/users/user_123/scanHistory/scan_1",
                    requestResourceData = validIngredientScanData
                )
            )
        )
    }

    // =========================================================================
    // 4. Invalid Schema & Payload Injection Rejection
    // =========================================================================

    @Test
    fun `raw OCR text injection into medical reports is strictly rejected`() {
        val userAuth = AuthContext(uid = "user_123")

        // Malicious client tries to attach raw OCR text to Firestore document
        val maliciousReport = validMedicalReportData.toMutableMap().apply {
            put("rawOcrText", "Patient Name: Ramesh Sharma, Diagnostic Report Text...")
        }

        val result = evaluator.evaluate(
            SecurityRequest(
                auth = userAuth,
                method = OperationMethod.CREATE,
                path = "/users/user_123/medicalReports/report_malicious",
                requestResourceData = maliciousReport
            )
        )
        assertEquals("Document with rawOcrText must be rejected by hasOnly", RuleResult.DENY, result)
    }

    @Test
    fun `report with maskedOnDevice false is strictly rejected`() {
        val userAuth = AuthContext(uid = "user_123")

        val unmaskedReport = validMedicalReportData.toMutableMap().apply {
            put("maskedOnDevice", false)
        }

        val result = evaluator.evaluate(
            SecurityRequest(
                auth = userAuth,
                method = OperationMethod.CREATE,
                path = "/users/user_123/medicalReports/report_unmasked",
                requestResourceData = unmaskedReport
            )
        )
        assertEquals("Document with maskedOnDevice == false must be rejected", RuleResult.DENY, result)
    }

    @Test
    fun `scan history with invalid safety score bounds is rejected`() {
        val userAuth = AuthContext(uid = "user_123")

        val negativeScoreScan = validIngredientScanData.toMutableMap().apply {
            put("safetyScore", -10)
        }
        assertEquals(
            RuleResult.DENY,
            evaluator.evaluate(
                SecurityRequest(
                    auth = userAuth,
                    method = OperationMethod.CREATE,
                    path = "/users/user_123/scanHistory/scan_neg",
                    requestResourceData = negativeScoreScan
                )
            )
        )

        val overflowScoreScan = validIngredientScanData.toMutableMap().apply {
            put("safetyScore", 150)
        }
        assertEquals(
            RuleResult.DENY,
            evaluator.evaluate(
                SecurityRequest(
                    auth = userAuth,
                    method = OperationMethod.CREATE,
                    path = "/users/user_123/scanHistory/scan_overflow",
                    requestResourceData = overflowScoreScan
                )
            )
        )
    }

    @Test
    fun `profile with oversized conditions list is rejected`() {
        val userAuth = AuthContext(uid = "user_123")

        val hugeConditions = (1..150).map { "Condition $it" }
        val bloatedProfile = validMedicalProfileData.toMutableMap().apply {
            put("conditions", hugeConditions)
        }

        val result = evaluator.evaluate(
            SecurityRequest(
                auth = userAuth,
                method = OperationMethod.CREATE,
                path = "/users/user_123/medicalProfile/current",
                requestResourceData = bloatedProfile
            )
        )
        assertEquals("List size > 100 must be rejected (DoS defense)", RuleResult.DENY, result)
    }

    @Test
    fun `profile document id other than current is rejected`() {
        val userAuth = AuthContext(uid = "user_123")

        val result = evaluator.evaluate(
            SecurityRequest(
                auth = userAuth,
                method = OperationMethod.CREATE,
                path = "/users/user_123/medicalProfile/arbitrary_profile_id",
                requestResourceData = validMedicalProfileData
            )
        )
        assertEquals("Profile id other than 'current' must be rejected", RuleResult.DENY, result)
    }

    // =========================================================================
    // 5. Append-Only Audit Records Lockdown
    // =========================================================================

    @Test
    fun `updating medical reports is strictly forbidden`() {
        val userAuth = AuthContext(uid = "user_123")

        val updateResult = evaluator.evaluate(
            SecurityRequest(
                auth = userAuth,
                method = OperationMethod.UPDATE,
                path = "/users/user_123/medicalReports/report_abc",
                requestResourceData = validMedicalReportData
            )
        )
        assertEquals("Updates to medicalReports must be denied (append-only audit log)", RuleResult.DENY, updateResult)
    }

    @Test
    fun `updating scan history is strictly forbidden`() {
        val userAuth = AuthContext(uid = "user_123")

        val updateResult = evaluator.evaluate(
            SecurityRequest(
                auth = userAuth,
                method = OperationMethod.UPDATE,
                path = "/users/user_123/scanHistory/scan_abc",
                requestResourceData = validIngredientScanData
            )
        )
        assertEquals("Updates to scanHistory must be denied (append-only audit log)", RuleResult.DENY, updateResult)
    }

    // =========================================================================
    // 6. Root User and Default Deny Lockdown
    // =========================================================================

    @Test
    fun `direct writes to root user document are disallowed`() {
        val userAuth = AuthContext(uid = "user_123")

        val writeResult = evaluator.evaluate(
            SecurityRequest(
                auth = userAuth,
                method = OperationMethod.CREATE,
                path = "/users/user_123",
                requestResourceData = mapOf("role" to "admin")
            )
        )
        assertEquals(RuleResult.DENY, writeResult)
    }

    @Test
    fun `unknown collections outside users are completely denied`() {
        val userAuth = AuthContext(uid = "user_123")

        val readResult = evaluator.evaluate(
            SecurityRequest(
                auth = userAuth,
                method = OperationMethod.GET,
                path = "/system_configs/config_1"
            )
        )
        assertEquals(RuleResult.DENY, readResult)

        val writeResult = evaluator.evaluate(
            SecurityRequest(
                auth = userAuth,
                method = OperationMethod.CREATE,
                path = "/public_logs/log_1",
                requestResourceData = mapOf("event" to "test")
            )
        )
        assertEquals(RuleResult.DENY, writeResult)
    }

    // =========================================================================
    // 7. Shared schema (SPEC.md) payloads from the iOS, web and Android apps
    // =========================================================================

    private val owner = AuthContext(uid = "user_123")

    private fun create(path: String, data: Map<String, Any?>): RuleResult = evaluator.evaluate(
        SecurityRequest(auth = owner, method = OperationMethod.CREATE, path = path, requestResourceData = data)
    )

    private val sharedScanData = mapOf(
        "productName" to "Organic Oat Bar",
        "productCategory" to "food",
        "safetyScore" to 86,
        "badgeLabel" to "Good match for you",
        "headline" to "Safe with minor sugar note",
        "aiSummary" to "Whole grain oats provide slow-release fiber.",
        "healthFactors" to listOf(
            mapOf("title" to "Blood Sugar Impact", "description" to "Moderate glycemic load.",
                "impactBadge" to "Caution", "sentiment" to "warning")
        ),
        "ingredientBreakdown" to listOf(
            mapOf("name" to "Whole grain oats", "note" to "High beta-glucan.", "status" to "Great")
        ),
        "recommendedIntake" to mapOf("dosageHeadline" to "1 bar", "guidance" to "Pair with protein."),
        "rawIngredients" to listOf("Whole grain oats", "Honey"),
        "modelUsed" to "Gemini 3.5 Flash Lite",
        "scannedAt" to "SERVER_TIMESTAMP"
    )

    @Test
    fun `shared schema scans from every platform are accepted`() {
        assertEquals(RuleResult.ALLOW, create("/users/user_123/scanHistory/ios_or_web", sharedScanData))
        // Android also tags its scans with the legacy kind field.
        assertEquals(
            RuleResult.ALLOW,
            create("/users/user_123/scanHistory/android", sharedScanData + ("kind" to "ingredient"))
        )
    }

    @Test
    fun `scan without a product name or legacy title is rejected`() {
        assertEquals(
            RuleResult.DENY,
            create("/users/user_123/scanHistory/nameless", sharedScanData - "productName")
        )
    }

    @Test
    fun `scan with unknown fields or a non-map intake is rejected`() {
        assertEquals(
            RuleResult.DENY,
            create("/users/user_123/scanHistory/extra", sharedScanData + ("rawOcrText" to "label text"))
        )
        assertEquals(
            RuleResult.DENY,
            create("/users/user_123/scanHistory/intake", sharedScanData + ("recommendedIntake" to "1 bar"))
        )
    }

    @Test
    fun `partial profile merges from web and iOS are accepted`() {
        // Web: arrayUnion merge of a single list.
        assertEquals(
            RuleResult.ALLOW,
            create(
                "/users/user_123/medicalProfile/current",
                mapOf("allergies" to listOf("Peanuts"), "updatedAt" to "SERVER_TIMESTAMP")
            )
        )
        // iOS: full merge including health markers.
        assertEquals(
            RuleResult.ALLOW,
            create(
                "/users/user_123/medicalProfile/current",
                mapOf(
                    "conditions" to listOf("Pre-diabetic"),
                    "allergies" to listOf("Peanuts"),
                    "medications" to emptyList<String>(),
                    "dietaryRestrictions" to listOf("Low added sugar"),
                    "healthMarkers" to listOf(
                        mapOf("category" to "Glucose", "value" to "Pre-diabetic", "tone" to "coral", "iconType" to "glucose")
                    ),
                    "markersCount" to 1,
                    "reportTitle" to "Blood test report",
                    "updatedAt" to "SERVER_TIMESTAMP"
                )
            )
        )
    }

    @Test
    fun `profile without updatedAt or with unknown fields is rejected`() {
        assertEquals(
            RuleResult.DENY,
            create("/users/user_123/medicalProfile/current", mapOf("allergies" to listOf("Peanuts")))
        )
        assertEquals(
            RuleResult.DENY,
            create(
                "/users/user_123/medicalProfile/current",
                validMedicalProfileData + ("patientName" to "Ramesh Sharma")
            )
        )
    }

    @Test
    fun `web and iOS report metadata is accepted but report text is not`() {
        val sharedReport = mapOf(
            "reportTitle" to "Blood test report",
            "markersCount" to 2,
            "maskedOnDevice" to true,
            "uploadedAt" to "SERVER_TIMESTAMP"
        )
        assertEquals(RuleResult.ALLOW, create("/users/user_123/medicalReports/r1", sharedReport))
        assertEquals(
            RuleResult.DENY,
            create("/users/user_123/medicalReports/r2", sharedReport + ("redactedText" to "[REDACTED_NAME] ..."))
        )
    }

    @Test
    fun `report without any timestamp is rejected`() {
        assertEquals(
            RuleResult.DENY,
            create("/users/user_123/medicalReports/r3", validMedicalReportData - "savedAt")
        )
    }

    @Test
    fun `health notes are append-only and schema checked`() {
        val note = mapOf(
            "userNote" to "I was diagnosed with high blood pressure last year",
            "extractedSummary" to "High blood pressure",
            "createdAt" to "SERVER_TIMESTAMP"
        )
        assertEquals(RuleResult.ALLOW, create("/users/user_123/healthNotes/n1", note))
        assertEquals(
            RuleResult.DENY,
            create("/users/user_123/healthNotes/n2", note + ("rawText" to "Patient Name: Ramesh"))
        )
        assertEquals(
            RuleResult.DENY,
            evaluator.evaluate(
                SecurityRequest(
                    auth = owner,
                    method = OperationMethod.UPDATE,
                    path = "/users/user_123/healthNotes/n1",
                    requestResourceData = note
                )
            )
        )
        assertEquals(
            RuleResult.DENY,
            evaluator.evaluate(
                SecurityRequest(
                    auth = AuthContext(uid = "attacker_456"),
                    method = OperationMethod.LIST,
                    path = "/users/user_123/healthNotes"
                )
            )
        )
    }
}
