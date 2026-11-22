//
//  OcrService.swift
//  ScanTruthAI
//

import Foundation
import UIKit
import Vision
import PDFKit

enum OcrService {
    // Stage 1A: Extract text from a captured camera photo or gallery image on-device
    static func extractText(from image: UIImage) async throws -> String {
        guard let cgImage = image.cgImage else { return "" }

        return try await withCheckedThrowingContinuation { continuation in
            let request = VNRecognizeTextRequest { request, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                let observations = request.results as? [VNRecognizedTextObservation] ?? []
                let lines = observations.compactMap { $0.topCandidates(1).first?.string }
                continuation.resume(returning: lines.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines))
            }
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = true

            let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
            do {
                try handler.perform([request])
            } catch {
                continuation.resume(throwing: error)
            }
        }
    }

    // Stage 1B: Extract text from a digital PDF medical report on-device
    static func extractText(fromPdfUrl url: URL) -> String {
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }

        guard let pdf = PDFDocument(url: url) else { return "" }
        var pages: [String] = []
        for index in 0..<pdf.pageCount {
            if let text = pdf.page(at: index)?.string, !text.isEmpty {
                pages.append(text)
            }
        }
        return pages.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // MARK: - Ingredient list parsing

    /// Words that mark the end of the ingredient list: allergen lines, nutrition table, directions, legal text.
    private static let stopPattern = #"(?i)(?:\b(?:contains|may contain|allergen|allergy|allergens|nutrition|nutritional|directions|how to|preparation|prepare|instructions|serving|servings|store|storage|best before|use by|expiry|mfd|manufactured|marketed|packed|net wt|net weight|net qty|mrp|fssai|customer care|add approximately)\b|www\.|@)"#

    /// Characters that OCR picks up from bold marks, footnote signs and bracket styles.
    private static let noiseCharacters = CharacterSet(charactersIn: "^*†‡§¶#~_=+<>|\\\"“”‘’{}[]()")

    /// Everything that separates one ingredient from the next (brackets create sub-ingredient levels).
    private static let listSeparators: [String] = ["(", ")", "[", "]", "{", "}", ";", "•", "·", "|", " & "]

    /// Punctuation to trim from both ends of a name.
    private static let edgeCharacters: CharacterSet = {
        var set = CharacterSet.whitespaces
        set.insert(charactersIn: ".:;-–—/'")
        return set
    }()

    /// Turns raw label OCR text into a clean list of ingredient names.
    ///
    /// Handles what real labels do: nested sub-ingredients such as
    /// `Whole Grains 45% {Rolled Oats, Millets 22% [Ragi Flakes (Ragi, Jaggery)]}` are flattened to separate
    /// ingredients, percentages and symbols are removed, and the list ends before allergen or directions text.
    /// Returns an empty list when no ingredient list can be found.
    static func parseIngredients(from ocrText: String) -> [String] {
        // 1. One line of text: join words broken across lines, collapse whitespace.
        var text = ocrText
            .replacingOccurrences(of: "-\n", with: "")
            .replacingOccurrences(of: "\r", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
        text = text.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)

        // 2. Start after the "Ingredients:" header. Without a header only accept something list-like.
        let body: String
        if let header = text.range(
            of: #"(?i)ingredients?(?:\s+list)?\s*[:\-–]?"#,
            options: .regularExpression
        ) {
            body = String(text[header.upperBound...])
        } else if text.filter({ $0 == "," }).count >= 3 {
            body = text
        } else {
            return []
        }

        // 3. End the list at allergen / nutrition / directions text, or at the closing full stop.
        var list = body
        if let stop = list.range(of: stopPattern, options: .regularExpression) {
            list = String(list[..<stop.lowerBound])
        }
        if let end = list.range(of: #"\.\s+(?=[A-Z])"#, options: .regularExpression) {
            list = String(list[..<end.lowerBound])
        }

        // 4. Brackets, "&" and other separators all become commas, so nested sub-ingredients are split too.
        for separator in listSeparators {
            list = list.replacingOccurrences(of: separator, with: ",")
        }

        // 5. Clean each piece, drop noise and repeats.
        var seen = Set<String>()
        var result: [String] = []

        for piece in list.components(separatedBy: ",") {
            guard let name = cleanIngredientName(piece) else { continue }
            if seen.insert(name.lowercased()).inserted {
                result.append(name)
            }
        }
        return Array(result.prefix(80))
    }

    /// One piece of the split list -> a clean ingredient name, or nil if it is not an ingredient.
    private static func cleanIngredientName(_ raw: String) -> String? {
        var name = raw

        // Percentages such as "45%" or "1.5 %".
        name = name.replacingOccurrences(of: #"\d+(?:[.,]\d+)?\s*%"#, with: " ", options: .regularExpression)

        // Stray symbols from OCR.
        name = name.components(separatedBy: noiseCharacters).joined(separator: " ")

        // "and Natural Flavours" -> "Natural Flavours".
        name = name.replacingOccurrences(of: #"(?i)^\s*(?:and|&)\s+"#, with: "", options: .regularExpression)

        // Collapse spaces, then trim punctuation left at either end.
        name = name.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
        name = name.trimmingCharacters(in: edgeCharacters)

        // Not an ingredient: too short, too long (a sentence), or no letters at all.
        let wordCount = name.split(separator: " ").count
        let hasLetters = name.rangeOfCharacter(from: .letters) != nil
        guard name.count >= 2, name.count <= 60, hasLetters, wordCount <= 6 else { return nil }

        return name
    }
}
