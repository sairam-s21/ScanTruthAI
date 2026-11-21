// src/services/ocrService.ts
import { createWorker } from "tesseract.js";
import * as pdfjsLib from "pdfjs-dist";
import pdfWorkerUrl from "pdfjs-dist/build/pdf.worker.min.mjs?url";

pdfjsLib.GlobalWorkerOptions.workerSrc = pdfWorkerUrl;

// Stage 1A: Extract raw text from a photo, image, or canvas on-device
export async function extractTextFromImage(
  imageSource: File | Blob | string | HTMLCanvasElement,
): Promise<string> {
  const worker = await createWorker("eng");
  try {
    // Mode 3 is fully automatic page segmentation (no OSD traineddata required)
    await worker.setParameters({
      tessedit_pageseg_mode: "3" as unknown as never,
    });
    const {
      data: { text },
    } = await worker.recognize(imageSource);
    return text.trim();
  } catch (err) {
    console.warn("Tesseract recognize fallback attempt:", err);
    try {
      const {
        data: { text },
      } = await worker.recognize(imageSource);
      return text.trim();
    } catch {
      return "";
    }
  } finally {
    await worker.terminate();
  }
}

// Stage 1B: Extract raw text from a PDF medical report on-device (with scanned PDF fallback)
export async function extractTextFromPdf(file: File): Promise<string> {
  try {
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

    const digitalText = pageTexts.join("\n").trim();

    // If PDF has selectable text, return it immediately
    if (digitalText.length >= 25) {
      return digitalText;
    }

    // Scanned PDF Fallback: Render pages to canvas and perform OCR
    if (pdf.numPages > 0) {
      const ocrPages: string[] = [];
      const pagesToProcess = Math.min(pdf.numPages, 4);
      for (let i = 1; i <= pagesToProcess; i++) {
        const page = await pdf.getPage(i);
        const viewport = page.getViewport({ scale: 1.5 });
        const canvas = document.createElement("canvas");
        const ctx = canvas.getContext("2d");
        canvas.width = viewport.width;
        canvas.height = viewport.height;
        if (ctx) {
          await page.render({ canvasContext: ctx, viewport, canvas } as unknown as never).promise;
          const pageOcr = await extractTextFromImage(canvas);
          if (pageOcr) ocrPages.push(pageOcr);
        }
      }
      if (ocrPages.length > 0) {
        return ocrPages.join("\n").trim();
      }
    }

    return digitalText;
  } catch (err) {
    console.warn("PDF extraction error, trying direct image OCR:", err);
    return extractTextFromImage(file);
  }
}

// Unified file text extractor for medical reports (PDF or Image)
export async function extractTextFromReportFile(file: File): Promise<string> {
  if (file.type === "application/pdf" || file.name.toLowerCase().endsWith(".pdf")) {
    return extractTextFromPdf(file);
  }
  return extractTextFromImage(file);
}

// Patterns representing non-ingredient corporate and manufacturing jargon
const NON_INGREDIENT_BLOCKLIST: RegExp[] = [
  /\b(?:manufactured|mfg|marketed|distributed|packed|imported|bottled|formulated|produced|processed|licensed)\s*(?:by|for|at|in)?\b[:\s\-].+/i,
  /\b(?:mfg\.?\s*lic\.?\s*no|fssai|fda|iso\s*\d+|gmp|halal|kosher|usda\s*organic|cgmp)\b.+/i,
  /\b(?:batch\s*no|lot\s*no|b\.?\s*no|mrp\b|rs\.?|\$|max\.?\s*retail|incl\.?\s*of\s*all\s*taxes|best\s*before|use\s*by|exp\.?\s*date|mfg\.?\s*date|pkd\.?\s*date)\b.+/i,
  /\b(?:net\s*wt|net\s*weight|net\s*qty|net\s*volume|gross\s*weight)\s*[:\-]?\s*[\d.]+\s*(?:g|gm|kg|ml|l|oz|lbs?)\b/i,
  /\b(?:store\s+in\s+a\s+cool|keep\s+out\s+of\s+reach|do\s+not\s+accept\s+if\s+seal|for\s+external\s+use|dosage\s*[:\-]|directions\s*for\s*use|shake\s*well\s*before)\b.+/i,
  /\b(?:nutrition\s*facts|nutritional\s*information|per\s*serving|per\s*100g|energy|calories|total\s*fat|saturated\s*fat|trans\s*fat|cholesterol|sodium|carbohydrate|total\s*sugars|added\s*sugars|protein|dietary\s*fiber|vitamin|calcium|iron)\b.+/i,
  /\b(?:pvt\.?\s*ltd|ltd|inc|llc|co\.|corporation|pharmaceuticals|laboratories|enterprises|industries)\b/i,
  /\b(?:plot\s*no|industrial\s*area|phase\s*\d+|street|road|nagar|city|pin\s*code|zip\s*code|tel|email|care@|website|www\.|https?:\/\/)\b.+/i,
  /\b(?:100%\s*natural|no\s*added\s*sugar|gluten\s*free\s*certified|non-gmo|zero\s*cholesterol|high\s*protein|immunity\s*booster|doctor\s*recommended|clinically\s*tested)\b/i,
  /\b(?:customer\s*care|toll\s*free|feedback|consumer\s*cell|query)\b.+/i,
];

// High-precision intelligent heuristic cleaner for ingredient labels
export function cleanAndFilterIngredientsFromText(ocrText: string): string[] {
  if (!ocrText || !ocrText.trim()) return [];

  const normalized = ocrText.replace(/\r?\n/g, "\n").replace(/[ \t]+/g, " ");

  // Strategy 1: Find ingredient section marker (Ingredients:, Contains:, Composition:, Active Ingredients:)
  let targetSection = "";
  const ingredientHeaderMatch = normalized.match(
    /(?:active\s+ingredients?|ingredients?|contains|composition)\s*[:\-]\s*([\s\S]+)/i,
  );

  if (ingredientHeaderMatch && ingredientHeaderMatch[1]) {
    // Cut off subsequent non-ingredient packaging sections
    const cutoff = ingredientHeaderMatch[1].split(
      /\b(?:nutrition\s*facts|nutritional\s*information|storage|store\s+in|keep\s+in|distributed\s+by|manufactured\s+by|marketed\s+by|mfg\.?|packed\s+by|imported\s+by|net\s+wt|best\s+before|batch\s+no|exp\.?\s+date|mrp\b|for\s+mfg|customer\s+care|directions\s+for\s+use)\b/i,
    );
    targetSection = cutoff[0].trim();
  } else {
    // Strategy 2: If no header, filter out blocklisted packaging lines
    const lines = normalized.split("\n").filter((line) => {
      const trimmed = line.trim();
      if (trimmed.length < 3) return false;
      return !NON_INGREDIENT_BLOCKLIST.some((re) => re.test(trimmed));
    });
    targetSection = lines.join(", ");
  }

  // Split into candidate ingredient items
  const candidates = targetSection
    .replace(/[\n\r]+/g, ", ")
    .split(/[,;•\n\t]/)
    .map((item) =>
      item
        .replace(/^\d+[\.\)]\s*/, "") // Remove leading numbered list (e.g. "1. ")
        .replace(/\b\d+(?:\.\d+)?\s*(?:%|mg|g|mcg|iu|kcal)\b/gi, "") // Remove percentages & amounts
        .replace(/[()[\]{}]/g, " ") // Clean brackets
        .replace(/\.$/, "") // Clean trailing periods
        .replace(/\b(?:and|with|of|less than|contains|per serving|each contains)\b/gi, "")
        .replace(/\s+/g, " ")
        .trim(),
    )
    .filter((item) => {
      // Must be valid length and contain letters
      if (item.length < 2 || item.length > 60 || !/[a-zA-Z]/.test(item)) return false;
      // Must not match non-ingredient keywords
      if (NON_INGREDIENT_BLOCKLIST.some((re) => re.test(item))) return false;
      // Filter out pure numbers, dates, units
      if (/^(?:\d+|\d+\s*[a-zA-Z]+|percent|daily value|dv|g|mg|kcal|approx|serving|weight)$/i.test(item)) {
        return false;
      }
      return true;
    });

  // Deduplicate and capitalize cleanly
  const uniqueList: string[] = [];
  const seen = new Set<string>();

  for (const item of candidates) {
    const lower = item.toLowerCase().trim();
    if (!seen.has(lower) && lower.length >= 2) {
      seen.add(lower);
      const formatted = item.charAt(0).toUpperCase() + item.slice(1);
      uniqueList.push(formatted);
    }
  }

  return uniqueList;
}

// Fallback alias for backward compatibility
export const parseIngredientsFromOcrText = cleanAndFilterIngredientsFromText;
