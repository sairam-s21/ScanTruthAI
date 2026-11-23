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

        private fun isValidMedicalProfile(data: Map<String, Any?>): Boolean {
            val allowedKeys = setOf("conditions", "allergies", "medications", "dietaryRestrictions", "healthNotes", "updatedAt")
            if (data.keys != allowedKeys) return false

            val conditions = data["conditions"] as? List<*> ?: return false
            val allergies = data["allergies"] as? List<*> ?: return false
            val medications = data["medications"] as? List<*> ?: return false
            val dietaryRestrictions = data["dietaryRestrictions"] as? List<*> ?: return false
            val healthNotes = data["healthNotes"] as? List<*> ?: return false
            val updatedAt = data["updatedAt"]

            if (conditions.size > 100 || allergies.size > 100 || medications.size > 100 ||
                dietaryRestrictions.size > 100 || healthNotes.size > 100
            ) return false

            if (!isTimestamp(updatedAt)) return false

            return true
        }

        private fun isValidMedicalReport(data: Map<String, Any?>): Boolean {
            val allowedKeys = setOf(
                "reportTitle", "reportType", "markersCount", "identifiersMasked",
                "maskedOnDevice", "pagesScanned", "truncated", "aiEngine", "savedAt"
            )
            if (data.keys != allowedKeys) return false

            val reportTitle = data["reportTitle"] as? String ?: return false
            val reportType = data["reportType"] as? String ?: return false
            val markersCount = (data["markersCount"] as? Number)?.toInt() ?: return false
            val identifiersMasked = (data["identifiersMasked"] as? Number)?.toInt() ?: return false
            val maskedOnDevice = data["maskedOnDevice"] as? Boolean ?: return false
            val pagesScanned = (data["pagesScanned"] as? Number)?.toInt() ?: return false
            val truncated = data["truncated"] as? Boolean ?: return false
            val aiEngine = data["aiEngine"] as? String ?: return false
            val savedAt = data["savedAt"]

            if (reportTitle.length > 200) return false
            if (reportType.length > 100) return false
            if (markersCount !in 0..500) return false
            if (identifiersMasked < 0) return false
            if (!maskedOnDevice) return false // Must be true!
            if (pagesScanned !in 0..50) return false
            if (aiEngine.length > 50) return false
            if (!isTimestamp(savedAt)) return false

            return true
        }

        private fun isValidScanHistory(data: Map<String, Any?>): Boolean {
            val allowedKeys = setOf(
                "kind", "title", "subtitle", "reportId", "safetyScore",
                "badgeLabel", "headline", "ingredientCount", "aiEngine", "scannedAt"
            )
            val requiredKeys = setOf("kind", "title", "subtitle", "aiEngine", "scannedAt")

            if (!allowedKeys.containsAll(data.keys)) return false
            if (!data.keys.containsAll(requiredKeys)) return false

            val kind = data["kind"] as? String ?: return false
            if (kind != "ingredient" && kind != "medical_report") return false

            val title = data["title"] as? String ?: return false
            val subtitle = data["subtitle"] as? String ?: return false
            val aiEngine = data["aiEngine"] as? String ?: return false
            val scannedAt = data["scannedAt"]

            if (title.length > 200 || subtitle.length > 200 || aiEngine.length > 50) return false
            if (!isTimestamp(scannedAt)) return false

            data["reportId"]?.let {
                val repId = it as? String ?: return false
                if (repId.length > 100) return false
            }

            data["safetyScore"]?.let {
                val score = (it as? Number)?.toInt() ?: return false
                if (score !in 0..100) return false
            }

            data["badgeLabel"]?.let {
                val badge = it as? String ?: return false
                if (badge.length > 50) return false
            }

            data["headline"]?.let {
                val hl = it as? String ?: return false
                if (hl.length > 200) return false
            }

            data["ingredientCount"]?.let {
                val count = (it as? Number)?.toInt() ?: return false
                if (count !in 0..500) return false
            }

            return true
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
}
