// src/services/ocrService.ts
import { createWorker } from "tesseract.js";
import * as pdfjsLib from "pdfjs-dist";
import pdfWorkerUrl from "pdfjs-dist/build/pdf.worker.min.mjs?url";

pdfjsLib.GlobalWorkerOptions.workerSrc = pdfWorkerUrl;

// Stage 1A: Extract raw text from a photo/image on-device
export async function extractTextFromImage(
  imageSource: File | Blob | string
): Promise<string> {
  const worker = await createWorker("eng");
  try {
    const {
      data: { text },
    } = await worker.recognize(imageSource);
    return text.trim();
  } finally {
    await worker.terminate();
  }
}

// Stage 1B: Extract raw text from a PDF medical report on-device
export async function extractTextFromPdf(file: File): Promise<string> {
  const buffer = await file.arrayBuffer();
  const pdf = await pdfjsLib.getDocument({ data: buffer }).promise;
  const pageTexts: string[] = [];

  for (let i = 1; i <= pdf.numPages; i++) {
    const page = await pdf.getPage(i);
    const content = await page.getTextContent();
    const strings = content.items
      .map((item) => ("str" in item ? item.str : ""))
      .filter(Boolean);
    pageTexts.push(strings.join(" "));
  }

  return pageTexts.join("\n").trim();
}

// Unified file text extractor for medical reports (PDF or Image)
export async function extractTextFromReportFile(file: File): Promise<string> {
  if (file.type === "application/pdf" || file.name.toLowerCase().endsWith(".pdf")) {
    return extractTextFromPdf(file);
  }
  return extractTextFromImage(file);
}

// Parse raw OCR label text into a clean list of ingredient names
export function parseIngredientsFromOcrText(ocrText: string): string[] {
  const normalized = ocrText.replace(/\r?\n/g, " ").replace(/\s+/g, " ").trim();
  const match = normalized.match(/ingredients?\s*[:\-]\s*(.+)/i);
  const body = match ? match[1] : normalized;

  // Stop at nutrition facts / allergens / distributed by sections
  const cut = body.split(
    /\b(nutrition facts|contains:|allergen|distributed by|manufactured by|storage)\b/i
  )[0];

  return cut
    .split(/[,;•]/)
    .map((item) => item.replace(/^\d+[\.\)]\s*/, "").replace(/\.$/, "").trim())
    .filter((item) => item.length >= 2 && item.length <= 60);
}