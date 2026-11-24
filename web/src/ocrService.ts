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
