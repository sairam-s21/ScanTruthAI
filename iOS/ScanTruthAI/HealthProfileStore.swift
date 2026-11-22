//
//  HealthProfileStore.swift
//  ScanTruthAI
//

import Foundation

// MARK: - Pipeline Models

/// Output of `HybridAIService.runOnDevicePrivacyGate`. Original values are deliberately not decoded.
struct PrivacyGateResult: Decodable {
    struct Item: Decodable, Hashable {
        var label: String?
        var maskedPreview: String?
        var category: String?
    }

    var redactedText: String
    var detectedItems: [Item]?
    var usedFallback: Bool?
    var fallbackNotice: String?
}

/// A masked document waiting for the user's approval.
struct PendingReview: Identifiable {
    let id = UUID()
    let gate: PrivacyGateResult
    let sourceLabel: String
    let isTypedNote: Bool
}

/// Output of `HybridAIService.extractMedicalContext`. Optional because model output varies.
struct ExtractedProfile: Decodable {
    struct Marker: Decodable {
        var category: String?
        var value: String?
        var tone: String?
        var iconType: String?

        /// Only the spec's allowed values are written; anything else falls back to a safe default.
        var validTone: String {
            ["coral", "green", "blue"].contains(tone ?? "") ? tone! : "blue"
        }

        var validIconType: String {
            ["glucose", "allergy", "heart", "medication"].contains(iconType ?? "") ? iconType! : "heart"
        }

        var dictionary: [String: Any] {
            [
                "category": category ?? "",
                "value": value ?? "",
                "tone": validTone,
                "iconType": validIconType
            ]
        }
    }

    var reportTitle: String? = nil
    var healthMarkers: [Marker]? = nil
    var conditions: [String]? = nil
    var allergies: [String]? = nil
    var medications: [String]? = nil
    var dietaryRestrictions: [String]? = nil
}

/// Result of checking a hand-typed item against its category (see `HybridAIService.validateHealthItem`).
struct HealthItemValidation: Decodable {
    var isValid: Bool
    var normalizedName: String?
    var reason: String?
}

/// The four lists on the My Health screen.
enum HealthCategory: String, CaseIterable, Identifiable {
    case condition, allergy, medication, dietary

    var id: String { rawValue }

    /// Singular noun used in prompts and messages, e.g. "medication".
    var singularName: String {
        switch self {
        case .condition: return "medical condition"
        case .allergy: return "allergy"
        case .medication: return "medication"
        case .dietary: return "dietary restriction"
        }
    }

    /// Cheap check that runs before any model call: rejects empty, very short or very long text,
    /// text with fewer than two letters, and text with unusual symbols.
    static func passesBasicCheck(_ text: String) -> Bool {
        guard (2...60).contains(text.count),
              text.filter(\.isLetter).count >= 2 else { return false }
        let allowedSymbols = Set(" .,-()%'+/&")
        return text.allSatisfy { $0.isLetter || $0.isNumber || allowedSymbols.contains($0) }
    }

    /// Title of the "+ Add …" button and the add dialog.
    var addTitle: String {
        switch self {
        case .condition: return "Add a condition"
        case .allergy: return "Add allergy"
        case .medication: return "Add medication"
        case .dietary: return "Add dietary restriction"
        }
    }

    /// Field name of this list in users/{uid}/medicalProfile/current.
    var firestoreField: String {
        switch self {
        case .condition: return "conditions"
        case .allergy: return "allergies"
        case .medication: return "medications"
        case .dietary: return "dietaryRestrictions"
        }
    }

    var placeholder: String {
        switch self {
        case .condition: return "e.g. Hypertension"
        case .allergy: return "e.g. Peanuts"
        case .medication: return "e.g. Metformin 500 mg"
        case .dietary: return "e.g. Low sodium"
        }
    }
}

/// In-memory profile shared across tabs. When signed in it always belongs to the signed-in account and is loaded
/// from Firestore; it is not persisted locally.
@Observable
@MainActor
final class HealthProfileStore {
    static let shared = HealthProfileStore()

    var conditions: [String] = []
    var allergies: [String] = []
    var medications: [String] = []
    var dietaryRestrictions: [String] = []
    var reports: [String] = []

    /// Account the data above belongs to; nil when signed out.
    private var ownerUID: String?

    /// How many health details are recorded, for the "N details added" pill.
    var detailCount: Int {
        conditions.count + allergies.count + medications.count + dietaryRestrictions.count
    }

    func items(for category: HealthCategory) -> [String] {
        switch category {
        case .condition: return conditions
        case .allergy: return allergies
        case .medication: return medications
        case .dietary: return dietaryRestrictions
        }
    }

    /// Adds one item by hand (the "+ Add …" buttons). Duplicates and blanks are ignored.
    func add(_ item: String, to category: HealthCategory) {
        switch category {
        case .condition: merge(ExtractedProfile(conditions: [item]))
        case .allergy: merge(ExtractedProfile(allergies: [item]))
        case .medication: merge(ExtractedProfile(medications: [item]))
        case .dietary: merge(ExtractedProfile(dietaryRestrictions: [item]))
        }
    }

    /// Removes one item from a list (the delete button in edit mode).
    func remove(_ item: String, from category: HealthCategory) {
        switch category {
        case .condition: conditions.removeAll { $0 == item }
        case .allergy: allergies.removeAll { $0 == item }
        case .medication: medications.removeAll { $0 == item }
        case .dietary: dietaryRestrictions.removeAll { $0 == item }
        }
    }

    /// Clears everything, e.g. on sign-out.
    func reset() {
        conditions = []
        allergies = []
        medications = []
        dietaryRestrictions = []
        reports = []
    }

    /// Called when the signed-in account changes. Drops data from another account (or from a signed-out session)
    /// and loads the new account's saved profile.
    func userDidChange(to uid: String?) {
        if uid != ownerUID {
            reset()
            ownerUID = uid
        }
        Task { await loadFromCloud() }
    }

    /// Loads the signed-in account's saved profile from Firestore.
    func loadFromCloud() async {
        guard FirestoreService.canSync,
              let data = try? await FirestoreService.shared.fetchMedicalProfile() else { return }

        merge(ExtractedProfile(
            conditions: data["conditions"] as? [String],
            allergies: data["allergies"] as? [String],
            medications: data["medications"] as? [String],
            dietaryRestrictions: data["dietaryRestrictions"] as? [String]
        ))
    }

    func merge(_ profile: ExtractedProfile) {
        conditions = Self.union(conditions, profile.conditions)
        allergies = Self.union(allergies, profile.allergies)
        medications = Self.union(medications, profile.medications)
        dietaryRestrictions = Self.union(dietaryRestrictions, profile.dietaryRestrictions)
    }

    /// Appends new items, skipping case-insensitive duplicates and blanks, keeping order.
    private static func union(_ existing: [String], _ new: [String]?) -> [String] {
        var result = existing
        for item in new ?? [] {
            let trimmed = item.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty,
                  !result.contains(where: { $0.caseInsensitiveCompare(trimmed) == .orderedSame }) else { continue }
            result.append(trimmed)
        }
        return result
    }
}
