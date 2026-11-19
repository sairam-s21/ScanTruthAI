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

    // Parse raw label OCR text into clean ingredient chips
    static func parseIngredients(from ocrText: String) -> [String] {
        let normalized = ocrText.replacingOccurrences(of: "\n", with: " ")
        let body: String
        if let range = normalized.range(of: "ingredients", options: .caseInsensitive) {
            body = String(normalized[range.upperBound...])
                .trimmingCharacters(in: CharacterSet(charactersIn: " :-"))
        } else {
            body = normalized
        }

        let stopMarkers = ["nutrition facts", "contains:", "allergen", "distributed by", "manufactured by"]
        var trimmedBody = body
        for marker in stopMarkers {
            if let stopRange = trimmedBody.range(of: marker, options: .caseInsensitive) {
                trimmedBody = String(trimmedBody[..<stopRange.lowerBound])
            }
        }

        return trimmedBody
            .components(separatedBy: CharacterSet(charactersIn: ",;•"))
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines.union(CharacterSet(charactersIn: "."))) }
            .filter { $0.count >= 2 && $0.count <= 60 }
    }
}