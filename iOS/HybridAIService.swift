import Foundation
import FirebaseCore
import FirebaseAILogic

@MainActor
final class HybridAIService {
    static let shared = HybridAIService()
    private let ai = FirebaseAI.firebaseAI(backend: .googleAI())

    // Stage 2: Local Privacy Gate (Strictly On-Device via Apple Foundation Models)
    func runOnDevicePrivacyGate(rawText: String) async throws -> String {
        let systemModel = FirebaseAI.SystemLanguageModel.default
        let session = ai.generativeModelSession(model: systemModel)

        let prompt = """
        Identify and mask all personal identifiers (names, dates of birth, phone numbers, emails, addresses, patient/hospital IDs) in the medical text below.
        Return ONLY valid JSON with keys: "redactedText", "redactedItemsCount", and "detectedItems" (array of { "label", "originalValue", "maskedPreview", "category" }).

        Medical text:
        \(rawText)
        """
        let response = try await session.respond(to: prompt)
        return cleanJson(response.text)
    }

    // Stage 3: Cloud Medical Profile & Typed Health Note Extractor
    func extractMedicalContext(sanitizedText: String, sourceLabel: String = "Uploaded medical report") async throws -> String {
        let cloudModel = ai.geminiModel(name: "gemini-3.8-flash")
        let session = ai.generativeModelSession(model: cloudModel)

        let prompt = """
        Extract structured health markers, conditions, allergies, medications, and dietary restrictions from the anonymized health text below.
        Return ONLY valid JSON with keys:
        - "reportTitle": string (default "\(sourceLabel)")
        - "markersCount": integer
        - "healthMarkers": array of { "category", "value", "tone" ("coral"|"green"|"blue"), "iconType" ("glucose"|"allergy"|"heart"|"medication") }
        - "conditions": array of strings
        - "allergies": array of strings
        - "medications": array of strings
        - "dietaryRestrictions": array of strings

        Anonymized health text:
        \(sanitizedText)
        """
        let response = try await session.respond(to: prompt)
        return cleanJson(response.text)
    }

    // Stage 4: Hybrid Ingredient Analyzer ("auto" | "flash" | "nano")
    func analyzeIngredients(
        ingredients: [String],
        medicalProfileJson: String,
        mode: String = "auto"
    ) async throws -> (json: String, modelUsedLabel: String) {
        let cloudModel = ai.geminiModel(name: "gemini-3.8-flash")
        let systemModel = FirebaseAI.SystemLanguageModel.default

        let selectedModel: GenerativeModel
        switch mode {
        case "nano":
            selectedModel = systemModel
        case "flash":
            selectedModel = cloudModel
        default:
            selectedModel = .hybridModel(primary: cloudModel, secondary: systemModel)
        }

        let session = ai.generativeModelSession(model: selectedModel)

        let prompt = """
        Evaluate these product ingredients against the user's medical profile and return ONLY valid JSON with keys:
        "productName", "productCategory" ("food"|"medicine"), "safetyScore" (0-100: 70-100 Good match, 40-69 Use with care, 0-39 Avoid), "badgeLabel", "headline", "aiSummary", "healthFactors" (array of { "title", "description", "impactBadge", "sentiment" }), "ingredientBreakdown" (array of { "name", "note", "status" }), and "recommendedIntake" ({ "dosageHeadline", "guidance" }).

        User Health Profile:
        \(medicalProfileJson)

        Ingredients:
        \(ingredients.joined(separator: ", "))
        """

        let response = try await session.respond(to: prompt)
        let label = response.inferenceSource == .onDevice
            ? "Apple Foundation Models (On-Device)"
            : "Gemini 3.8 Flash"
        return (cleanJson(response.text), label)
    }

    private func cleanJson(_ raw: String?) -> String {
        (raw ?? "{}")
            .replacingOccurrences(of: "```json", with: "")
            .replacingOccurrences(of: "```", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}