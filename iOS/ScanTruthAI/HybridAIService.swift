
import Foundation
import FoundationModels
import FirebaseCore
import FirebaseAILogic

@MainActor
final class HybridAIService {

    static let shared = HybridAIService()

    // Lazy so the on-device (Nano) path never touches Firebase.
    private lazy var ai = FirebaseAI.firebaseAI(backend: .googleAI())

    /// Whether Apple Foundation Models can run on this device (needs Apple Intelligence).
    var isOnDeviceModelAvailable: Bool { SystemLanguageModel.default.isAvailable }

    /// Cloud models need a configured Firebase app (GoogleService-Info.plist).
    private var isCloudAvailable: Bool { FirebaseApp.app() != nil }

    private func cloudUnavailableError() -> NSError {
        NSError(
            domain: "HybridAIService",
            code: 2,
            userInfo: [
                NSLocalizedDescriptionKey:
                    "Cloud AI is unavailable because Firebase is not configured."
            ]
        )
    }

    private let geminiModelName = "gemini-3.5-flash-lite"

    // MARK: - Local JSON Cleanup

    private func cleanJson(_ raw: String?) -> String {
        (raw ?? "{}")
            .replacingOccurrences(of: "```json", with: "")
            .replacingOccurrences(of: "```", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // MARK: - Stage 2: Local Privacy Gate
    // This stage uses Apple's Foundation Models only.
    // Raw medical text is never sent to Gemini from this function.

    /// Rule-based redaction. Also run over the Apple model's output as a safety net,
    /// because an LLM can silently miss identifiers.
    private func applyPrivacyRegexRules(
        to rawText: String
    ) -> (redacted: String, items: [[String: String]]) {

        var redacted = rawText
        var detectedItems: [[String: String]] = []

        let rules: [
            (
                label: String,
                category: String,
                pattern: String,
                replacement: String,
                mask: String
            )
        ] = [

            (
                label: "Report / Portal URL",
                category: "Identifier",
                pattern: #"(?i)\b(?:https?://|www\.)[^\s<>"{}|\\^\[\]]+"#,
                replacement: "[REDACTED_URL]",
                mask: "https://••••••"
            ),

            (
                label: "ABHA / Aadhaar / National ID",
                category: "Identifier",
                pattern: #"(?i)(?:\b\d{2}-\d{4}-\d{4}-\d{4}\b|\b[a-zA-Z0-9._-]+@abdm\b|\b\d{4}[\s-]?\d{4}[\s-]?\d{4}\b|\b\d{3}-\d{2}-\d{4}\b)"#,
                replacement: "[REDACTED_GOV_ID]",
                mask: "••••-••••-••••"
            ),

            (
                label: "Patient / Guardian Name",
                category: "Personal",
                // "Test Name: Glucose" etc. are clinical terms, not people. [ \t] keeps matches on one line.
                pattern: #"(?:Patient\s*Name|(?<!Test\s)(?<!Drug\s)(?<!Medicine\s)(?<!Product\s)(?<!Item\s)(?<!Lab\s)\bName|Mr\.|Mrs\.|Ms\.|S/o|D/o|W/o|Guardian)[ \t]*[:\-]?[ \t]*[A-Z][A-Za-z]+(?:[ \t]+[A-Z][A-Za-z]+){0,2}"#,
                replacement: "[REDACTED_NAME]",
                mask: "••••••••"
            ),

            (
                label: "Doctor / Clinician Name",
                category: "Personal",
                pattern: #"(?:Ref\.?\s*By|Consulting|Doctor|Dr\.)[ \t]*[:\-]?[ \t]*(?:Dr\.?[ \t]*)?[A-Z][a-zA-Z.]+(?:[ \t]+[A-Z][a-zA-Z.]+){0,2}"#,
                replacement: "[REDACTED_DOCTOR]",
                mask: "Dr. ••••••"
            ),

            (
                label: "Hospital / Clinic / Address",
                category: "Personal",
                pattern: #"(?i)(?:Hospital|Clinic|Diagnostic\s*Center|Lab\s*Name|Address)\s*[:\-]\s*([^\n\r]{4,80})"#,
                replacement: "[REDACTED_LOCATION]",
                mask: "••••••••, ••••"
            ),

            (
                label: "Medical / Lab / Insurance ID",
                category: "Identifier",
                // \b stops "Lipid: High" matching the bare "ID" label; the value must contain a digit.
                pattern: #"(?i)\b(?:Patient\s*ID|MRN|UHID|Lab\s*No|Sample\s*ID|Episode\s*ID|Bill\s*No|Invoice\s*No|Policy\s*No|TPA\s*ID|Member\s*ID|Reg\.?\s*No|ID)[ \t]*[:\-#][ \t]*(?=[A-Z0-9\-/]{4,})[A-Z0-9\-/]*\d[A-Z0-9\-/]*"#,
                replacement: "[REDACTED_RECORD_ID]",
                mask: "••••••••"
            ),

            (
                label: "Service / Birth Date",
                category: "Personal",
                pattern: #"(?i)(?:(?:DOB|Date\s*of\s*Birth|Collected\s*on|Reported\s*on|Admitted|Discharged)\s*[:\-]?\s*)?\b(?:\d{1,2}[/\-]\d{1,2}[/\-]\d{2,4}|\d{4}[/\-]\d{1,2}[/\-]\d{1,2})\b"#,
                replacement: "[REDACTED_DATE]",
                mask: "•• / •• / ••••"
            ),

            (
                label: "Phone Number",
                category: "Personal",
                pattern: #"(?:\+91[\s-]?[6-9]\d{9}|\b[6-9]\d{9}\b|(?<![\d.])(?:\+?\d{1,3}[\s-]?)?\(?\d{3}\)?[\s-]?\d{3}[\s-]?\d{4}\b)"#,
                replacement: "[REDACTED_PHONE]",
                mask: "•••-•••-••••"
            ),

            (
                label: "Email Address",
                category: "Personal",
                pattern: #"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}"#,
                replacement: "[REDACTED_EMAIL]",
                mask: "••••@••••.•••"
            ),

            (
                label: "PIN / ZIP Code",
                category: "Personal",
                // Labelled only: a bare 6-digit number is often a lab value (e.g. platelets 250000).
                pattern: #"(?i)\b(?:Pin\s*code|PIN|ZIP)[ \t]*[:\-]?[ \t]*\d{5,6}\b"#,
                replacement: "[REDACTED_PIN]",
                mask: "••••••"
            )
        ]

        for rule in rules {

            guard let regex = try? NSRegularExpression(
                pattern: rule.pattern
            ) else {
                continue
            }

            let fullRange = NSRange(
                redacted.startIndex..<redacted.endIndex,
                in: redacted
            )

            let matches = regex.matches(
                in: redacted,
                range: fullRange
            )

            for match in matches {

                if let swiftRange = Range(match.range, in: redacted) {

                    detectedItems.append([
                        "label": rule.label,
                        "originalValue": String(redacted[swiftRange]),
                        "maskedPreview": rule.mask,
                        "category": rule.category
                    ])
                }
            }

            if !matches.isEmpty {

                redacted = regex.stringByReplacingMatches(
                    in: redacted,
                    range: fullRange,
                    withTemplate: rule.replacement
                )
            }
        }

        return (redacted, detectedItems)
    }

    private func runLocalRegexPrivacyMask(rawText: String) -> String {

        let (redacted, detectedItems) = applyPrivacyRegexRules(to: rawText)

        let payload: [String: Any] = [
            "redactedText": redacted,
            "redactedItemsCount": detectedItems.count,
            "detectedItems": detectedItems,
            "usedFallback": true,
            "fallbackNotice": "Apple Foundation Models are unavailable. Used the local rule-based privacy shield."
        ]

        guard
            let data = try? JSONSerialization.data(withJSONObject: payload),
            let jsonString = String(data: data, encoding: .utf8)
        else {
            return "{}"
        }

        return jsonString
    }

    // MARK: - Stage 2: Apple Foundation Models Privacy Gate

    func runOnDevicePrivacyGate(rawText: String) async -> String {

        let systemModel = SystemLanguageModel.default

        // Check availability before creating a session.
        guard systemModel.isAvailable else {
            return runLocalRegexPrivacyMask(rawText: rawText)
        }

        let prompt = """
        Identify and mask ALL personal and protected health identifiers
        in the medical text below.

        - Patient, guardian, and doctor names
        - Dates of birth and exact admission, collection, and report dates
          (keep patient age and sex intact)
        - Phone numbers, emails, street addresses, hospital, clinic,
          and lab names, and PIN/ZIP codes
        - ABHA IDs, Aadhaar/SSN numbers, Insurance/Policy/TPA IDs,
          Patient/MRN/UHID/Lab/Sample/Bill numbers,
          medical device serials, and report URLs
        - Preserve all clinical values, lab markers, units,
          reference ranges, diagnoses, medications, and allergies.

        Return ONLY valid JSON with keys:
        "redactedText",
        "redactedItemsCount",
        and "detectedItems".

        detectedItems must be an array of objects containing:
        "label", "originalValue", "maskedPreview", and "category".

        Medical text:
        \(rawText)
        """

        do {

            // Apple's native on-device model. Permissive guardrails so ordinary medical wording
            // is not rejected (this mode only applies to plain-text output, which is what we use).
            let session = LanguageModelSession(
                model: SystemLanguageModel(guardrails: .permissiveContentTransformations)
            )

            let response = try await session.respond(to: prompt)

            let cleaned = cleanJson(response.content)

            if let data = cleaned.data(using: .utf8),
               var dict = try? JSONSerialization.jsonObject(
                    with: data
               ) as? [String: Any],
               dict["redactedText"] != nil {

                // Safety net: never trust the model's redaction alone.
                let modelRedacted = dict["redactedText"] as? String ?? ""
                let second = applyPrivacyRegexRules(to: modelRedacted)
                let modelItems = dict["detectedItems"] as? [[String: Any]] ?? []
                let allItems = modelItems + second.items.map { $0 as [String: Any] }

                dict["redactedText"] = second.redacted
                dict["detectedItems"] = allItems
                dict["redactedItemsCount"] = allItems.count
                dict["usedFallback"] = false
                dict["fallbackNotice"] = NSNull()

                if let updatedData = try? JSONSerialization.data(
                    withJSONObject: dict
                ),
                   let updatedJson = String(
                    data: updatedData,
                    encoding: .utf8
                   ) {

                    return updatedJson
                }
            }

        } catch {

            // Do not send raw medical text to Gemini.
            // Fall back to the local regex privacy shield.
        }

        return runLocalRegexPrivacyMask(rawText: rawText)
    }

    // MARK: - Stage 3: Cloud Medical Profile Extractor

    func extractMedicalContext(
        sanitizedText: String,
        sourceLabel: String = "Uploaded medical report"
    ) async throws -> String {

        guard isCloudAvailable else { throw cloudUnavailableError() }

        // Firebase 13.2.0 Gemini integration through Foundation Models.
        let cloudModel = ai.geminiLanguageModel(
            name: geminiModelName
        )

        let session = LanguageModelSession(
            model: cloudModel
        )

        let prompt = """
        Extract structured health markers, conditions, allergies,
        medications, and dietary restrictions from the anonymized
        health text below.

        Return ONLY valid JSON with these keys:

        - "reportTitle": string (default "\(sourceLabel)")
        - "markersCount": integer
        - "healthMarkers": array of {
            "category",
            "value",
            "tone" ("coral"|"green"|"blue"),
            "iconType" ("glucose"|"allergy"|"heart"|"medication")
          }
        - "conditions": array of strings
        - "allergies": array of strings
        - "medications": array of strings
        - "dietaryRestrictions": array of strings

        Anonymized health text:
        \(sanitizedText)
        """

        let response = try await session.respond(to: prompt)

        return cleanJson(response.content)
    }

    // MARK: - Manual health item validation

    /// Checks that text typed into a "+ Add …" dialog really is a `category` item, e.g. rejects "asdf" or
    /// "banana" as a medication. Apple's on-device model goes first so nothing leaves the device; the cloud
    /// model is only used when the on-device one is unavailable or fails. Throws if neither could decide.
    func validateHealthItem(_ text: String, category: HealthCategory) async throws -> HealthItemValidation {

        let prompt = """
        A user typed the text below into the "\(category.singularName)" list of their health profile.
        Decide whether it is a real, recognizable \(category.singularName) (misspellings of real
        ones are fine) and not gibberish, a random word, or something that belongs in a different list.

        Return ONLY valid JSON with keys:
        "isValid" (boolean),
        "normalizedName" (string: properly capitalized and spelled name, keeping any dose such as "500 mg"),
        and "reason" (short string explaining a rejection, empty if valid).

        Text: "\(text)"
        """

        if SystemLanguageModel.default.isAvailable {
            let session = LanguageModelSession(
                model: SystemLanguageModel(guardrails: .permissiveContentTransformations)
            )
            if let content = try? await session.respond(to: prompt).content,
               let result = decodeValidation(content) {
                return result
            }
        }

        guard isCloudAvailable else { throw cloudUnavailableError() }

        let session = LanguageModelSession(model: ai.geminiLanguageModel(name: geminiModelName))
        let content = try await session.respond(to: prompt).content

        guard let result = decodeValidation(content) else {
            throw NSError(
                domain: "HybridAIService",
                code: 5,
                userInfo: [NSLocalizedDescriptionKey: "The answer could not be understood."]
            )
        }
        return result
    }

    private func decodeValidation(_ raw: String) -> HealthItemValidation? {
        try? JSONDecoder().decode(HealthItemValidation.self, from: Data(cleanJson(raw).utf8))
    }

    // MARK: - Stage 4: Ingredient Analyzer
    // Modes:
    // auto  = Gemini cloud first, Apple local fallback
    // flash = Gemini cloud only
    // nano  = Apple Foundation Models only

    /// The on-device model has a small context window and its answer has to fit in it too, so the compact
    /// version trims the input and asks for a shorter answer.
    private func analysisPrompt(
        ingredients: [String],
        medicalProfileJson: String,
        compact: Bool
    ) -> String {

        let list = ingredients
            .prefix(compact ? 20 : 60)
            .joined(separator: ", ")

        let profile = compact
            ? String(medicalProfileJson.prefix(1200))
            : medicalProfileJson

        // Rules from the Profile settings (same options as the web app).
        var ruleLines: [String] = []
        if AppPreferences.bool(AppPreferences.strictSafetyPenalties, default: true) {
            ruleLines.append("- If any ingredient conflicts with an allergy, intolerance or condition in the profile (for example whey protein with lactose intolerance), safetyScore must be 39 or lower (Avoid).")
        }
        if AppPreferences.bool(AppPreferences.criticalAllergenAlerts, default: true) {
            ruleLines.append("- If an ingredient matches a profile allergy such as nuts, dairy or gluten, say so clearly in the headline and add a healthFactor with impactBadge \"Allergen\".")
        }
        if AppPreferences.bool(AppPreferences.intoleranceAlerts, default: true) {
            ruleLines.append("- Flag lactose, high sodium and high added sugar whenever they matter for the profile.")
        }
        let rules = ruleLines.isEmpty ? "" : "\nRules:\n" + ruleLines.joined(separator: "\n")

        let limits = compact
            ? """

            Keep it short: every text field under 120 characters,
            at most 3 "healthFactors", and at most 8 entries in
            "ingredientBreakdown" (only the most relevant ingredients).
            """
            : ""

        return """
        Evaluate these product ingredients against the user's
        medical profile and return ONLY valid JSON with keys:

        "productName",
        "productCategory" ("food"|"medicine"),
        "safetyScore" (0-100: 70-100 Good match,
        40-69 Use with care, 0-39 Avoid),
        "badgeLabel",
        "headline",
        "aiSummary",
        "healthFactors" (array of {
            "title",
            "description",
            "impactBadge",
            "sentiment"
        }),
        "ingredientBreakdown" (array of {
            "name",
            "note",
            "status"
        }),
        and "recommendedIntake" ({
            "dosageHeadline",
            "guidance"
        }).
        \(limits)
        \(rules)

        User Health Profile:
        \(profile)

        Ingredients:
        \(list)
        """
    }

    /// Runs a prompt on Apple's on-device model.
    /// Permissive guardrails stop its safety filter from rejecting ordinary health wording. If the full prompt
    /// still fails (for example it overflows the context window), one retry is made with the short prompt.
    private func respondOnDevice(full: String, compact: String) async throws -> String {

        let model = SystemLanguageModel(guardrails: .permissiveContentTransformations)

        do {
            let session = LanguageModelSession(model: model)
            return try await session.respond(to: full).content
        } catch {
            do {
                let session = LanguageModelSession(model: model)
                return try await session.respond(to: compact).content
            } catch {
                throw onDeviceError(error)
            }
        }
    }

    /// Plain-language reason instead of "LanguageModelError error -1".
    private func onDeviceError(_ error: Error) -> NSError {

        let reason: String

        if let modelError = error as? LanguageModelError {
            switch modelError {
            case .contextSizeExceeded:
                reason = "This ingredient list is too long for the on-device model."
            case .guardrailViolation:
                reason = "Apple's on-device safety filter blocked this request."
            case .refusal:
                reason = "The on-device model declined this request."
            case .timeout:
                reason = "The on-device model timed out."
            default:
                reason = "The on-device model could not finish this analysis."
            }
        } else {
            reason = error.localizedDescription
        }

        return NSError(
            domain: "HybridAIService",
            code: 3,
            userInfo: [NSLocalizedDescriptionKey: reason]
        )
    }

    func analyzeIngredients(
        ingredients: [String],
        medicalProfileJson: String,
        mode: String = "auto"
    ) async throws -> (
        json: String,
        modelUsedLabel: String,
        usedFallback: Bool,
        fallbackNotice: String?
    ) {

        let prompt = analysisPrompt(
            ingredients: ingredients,
            medicalProfileJson: medicalProfileJson,
            compact: false
        )

        let compactPrompt = analysisPrompt(
            ingredients: ingredients,
            medicalProfileJson: medicalProfileJson,
            compact: true
        )

        let systemModel = SystemLanguageModel.default

        // MARK: Nano: strictly on-device

        if mode == "nano" {

            guard systemModel.isAvailable else {

                throw NSError(
                    domain: "HybridAIService",
                    code: 1,
                    userInfo: [
                        NSLocalizedDescriptionKey:
                            "Apple Foundation Models are unavailable on this device. On-device mode requires Apple Intelligence."
                    ]
                )
            }

            let text = try await respondOnDevice(
                full: prompt,
                compact: compactPrompt
            )

            return (
                cleanJson(text),
                "Apple Foundation Models (On-Device)",
                false,
                nil
            )
        }

        // MARK: Flash: strictly cloud

        if mode == "flash" {

            guard isCloudAvailable else { throw cloudUnavailableError() }

            let cloudModel = ai.geminiLanguageModel(
                name: geminiModelName
            )

            let session = LanguageModelSession(
                model: cloudModel
            )

            let response = try await session.respond(to: prompt)

            return (
                cleanJson(response.content),
                "Gemini 3.5 Flash Lite",
                false,
                nil
            )
        }

        // MARK: Auto: Cloud first, local fallback

        do {

            guard isCloudAvailable else { throw cloudUnavailableError() }

            let cloudModel = ai.geminiLanguageModel(
                name: geminiModelName
            )

            let cloudSession = LanguageModelSession(
                model: cloudModel
            )

            let cloudResponse = try await cloudSession.respond(
                to: prompt
            )

            return (
                cleanJson(cloudResponse.content),
                "Gemini 3.5 Flash Lite",
                false,
                nil
            )

        } catch let cloudError {

            // Only Auto mode can fall back to the local model.
            guard systemModel.isAvailable else {
                throw cloudError
            }

            do {
                let text = try await respondOnDevice(
                    full: prompt,
                    compact: compactPrompt
                )

                return (
                    cleanJson(text),
                    "Apple Foundation Models (On-Device Fallback)",
                    true,
                    "Cloud Gemini was unavailable. Switched to Apple Foundation Models for on-device ingredient analysis."
                )

            } catch let localError {

                // Show both reasons; hiding the cloud one makes problems like App Check impossible to spot.
                throw NSError(
                    domain: "HybridAIService",
                    code: 4,
                    userInfo: [
                        NSLocalizedDescriptionKey:
                            "Cloud: \(cloudError.localizedDescription) On-device: \(localError.localizedDescription)"
                    ]
                )
            }
        }
    }
}
