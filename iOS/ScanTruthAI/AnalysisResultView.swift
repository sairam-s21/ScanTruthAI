//
//  AnalysisResultView.swift
//  ScanTruthAI
//

import SwiftUI

// MARK: - Model (decoded from the Stage 4 JSON; every field is optional because LLM output varies)

struct AnalysisResult: Codable {
    struct HealthFactor: Codable, Hashable {
        var title: String?
        var description: String?
        var impactBadge: String?
        var sentiment: String?
    }

    struct IngredientNote: Codable, Hashable {
        var name: String?
        var note: String?
        var status: String?
    }

    struct Intake: Codable {
        var dosageHeadline: String?
        var guidance: String?
    }

    var productName: String?
    var productCategory: String?
    var safetyScore: Double?
    var badgeLabel: String?
    var headline: String?
    var aiSummary: String?
    var healthFactors: [HealthFactor]?
    var ingredientBreakdown: [IngredientNote]?
    var recommendedIntake: Intake?

    enum CodingKeys: String, CodingKey {
        case productName, productCategory, safetyScore, badgeLabel, headline, aiSummary
        case healthFactors, ingredientBreakdown, recommendedIntake
    }

    /// Tolerant decoding: scans written by the Web and Android apps (or by an LLM) can have a field of an
    /// unexpected type. One bad field must not make the whole scan unreadable, so each field decodes on its own.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)

        productName = try? c.decodeIfPresent(String.self, forKey: .productName)
        productCategory = try? c.decodeIfPresent(String.self, forKey: .productCategory)
        badgeLabel = try? c.decodeIfPresent(String.self, forKey: .badgeLabel)
        headline = try? c.decodeIfPresent(String.self, forKey: .headline)
        aiSummary = try? c.decodeIfPresent(String.self, forKey: .aiSummary)

        // The score may arrive as a number or as text such as "86".
        if let number = try? c.decodeIfPresent(Double.self, forKey: .safetyScore) {
            safetyScore = number
        } else if let text = try? c.decodeIfPresent(String.self, forKey: .safetyScore) {
            safetyScore = Double(text.trimmingCharacters(in: .whitespaces))
        } else {
            safetyScore = nil
        }

        healthFactors = (try? c.decodeIfPresent([Lossy<HealthFactor>].self, forKey: .healthFactors))?
            .compactMap(\.value)
        ingredientBreakdown = (try? c.decodeIfPresent([Lossy<IngredientNote>].self, forKey: .ingredientBreakdown))?
            .compactMap(\.value)
        recommendedIntake = try? c.decodeIfPresent(Intake.self, forKey: .recommendedIntake)
    }

    /// Score clamped to 0...100, or nil if the model did not return one.
    var clampedScore: Int? {
        safetyScore.map { Int(min(max($0, 0), 100).rounded()) }
    }

    /// Badge text from the shared rubric (70-100 / 40-69 / 0-39), so every platform shows the same label.
    var displayBadge: String {
        switch clampedScore ?? 0 {
        case 70...: return "Good match for you"
        case 40..<70: return "Use with care"
        default: return "Avoid"
        }
    }

    static func decode(from json: String) throws -> AnalysisResult {
        try JSONDecoder().decode(AnalysisResult.self, from: Data(json.utf8))
    }
}

/// Decodes one array element without failing the whole array when that element is malformed.
private struct Lossy<T: Decodable>: Decodable {
    let value: T?

    init(from decoder: Decoder) throws {
        value = try? T(from: decoder)
    }
}

extension AnalysisResult {
    /// Green / orange / red by the shared score rubric. Used by the results screen and the Home card.
    var scoreColor: Color {
        switch clampedScore ?? 0 {
        case 70...: return Color(red: 0.2, green: 0.65, blue: 0.4)
        case 40..<70: return Color.orange
        default: return Color.red
        }
    }
}

// MARK: - View

struct AnalysisResultView: View {
    let result: AnalysisResult
    let modelUsedLabel: String
    var fallbackNotice: String? = nil
    var onDismiss: () -> Void

    private var scoreColor: Color { result.scoreColor }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    scoreCard

                    if let fallbackNotice {
                        Label(fallbackNotice, systemImage: "info.circle")
                            .font(.system(size: 12))
                            .foregroundStyle(Theme.textSecondary)
                    }

                    if let summary = result.aiSummary, !summary.isEmpty {
                        Text(summary)
                            .font(.system(size: 14))
                            .foregroundStyle(Theme.textPrimary)
                    }

                    if let factors = result.healthFactors, !factors.isEmpty {
                        section("HEALTH FACTORS") {
                            ForEach(factors, id: \.self) { factor in
                                factorRow(factor)
                            }
                        }
                    }

                    if let items = result.ingredientBreakdown, !items.isEmpty {
                        section("INGREDIENT BREAKDOWN") {
                            ForEach(items, id: \.self) { item in
                                ingredientRow(item)
                            }
                        }
                    }

                    if let intake = result.recommendedIntake,
                       intake.dosageHeadline != nil || intake.guidance != nil {
                        section("RECOMMENDED INTAKE") {
                            VStack(alignment: .leading, spacing: 4) {
                                if let headline = intake.dosageHeadline {
                                    Text(headline)
                                        .font(.system(size: 14, weight: .semibold))
                                        .foregroundStyle(Theme.textPrimary)
                                }
                                if let guidance = intake.guidance {
                                    Text(guidance)
                                        .font(.system(size: 13))
                                        .foregroundStyle(Theme.textSecondary)
                                }
                            }
                            .padding(14)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .primaryCard(cornerRadius: 14)
                        }
                    }

                    Text("Analyzed with \(modelUsedLabel). AI-generated guidance is not medical advice. Check with a doctor or pharmacist before changing your diet or medication.")
                        .font(.system(size: 11))
                        .foregroundStyle(Theme.textSecondary)
                }
                .padding(20)
            }
            .background(Theme.background.ignoresSafeArea())
            .navigationTitle(result.productName ?? "Analysis")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done", action: onDismiss)
                        .foregroundStyle(Theme.primary)
                }
            }
        }
    }

    private var scoreCard: some View {
        HStack(spacing: 16) {
            ZStack {
                Circle().stroke(scoreColor.opacity(0.2), lineWidth: 8)
                Circle()
                    .trim(from: 0, to: CGFloat(result.clampedScore ?? 0) / 100)
                    .stroke(scoreColor, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                Text(result.clampedScore.map(String.init) ?? "–")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(Theme.textPrimary)
            }
            .frame(width: 76, height: 76)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Safety score \(result.clampedScore.map(String.init) ?? "unknown") out of 100")

            VStack(alignment: .leading, spacing: 4) {
                Text(result.displayBadge)
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(scoreColor)
                Text(result.headline ?? "Analysis complete")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(Theme.textPrimary)
            }
            Spacer()
        }
        .padding(16)
        .primaryCard(cornerRadius: 18)
    }

    private func section<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(Theme.textSecondary)
                .tracking(1.0)
            content()
        }
    }

    private func factorRow(_ factor: AnalysisResult.HealthFactor) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(factor.title ?? "")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Theme.textPrimary)
                Spacer()
                if let badge = factor.impactBadge {
                    Text(badge)
                        .font(.system(size: 11, weight: .semibold))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Theme.accentMint)
                        .clipShape(Capsule())
                }
            }
            if let description = factor.description {
                Text(description)
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.textSecondary)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .primaryCard(cornerRadius: 14)
    }

    private func ingredientRow(_ item: AnalysisResult.IngredientNote) -> some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 2) {
                Text(item.name ?? "")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Theme.textPrimary)
                if let note = item.note {
                    Text(note)
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.textSecondary)
                }
            }
            Spacer()
            if let status = item.status {
                Text(status)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(Theme.textSecondary)
            }
        }
        .padding(12)
        .primaryCard(cornerRadius: 12)
    }
}
