// src/services/aiPipelineService.ts
import {
  getPrivacyGateModel,
  getProfileExtractorModel,
  getIngredientAnalyzerModel,
  PrivacyGateResult,
  ExtractedMedicalProfile,
  IngredientAnalysisResult,
  DetectedPiiItem,
} from "../firebase";

// Deterministic local regex fallback when desktop Chrome lacks Gemini Nano weights
function runLocalRegexPrivacyMask(rawText: string): PrivacyGateResult {
  let redacted = rawText;
  const detectedItems: DetectedPiiItem[] = [];

  const patterns: Array<{
    label: string;
    category: "Personal" | "Identifier";
    regex: RegExp;
    replacement: string;
    mask: string;
  }> = [
    {
      label: "Patient name",
      category: "Personal",
      regex: /(?:Patient\s*Name|Name)\s*[:\-]\s*([A-Z][a-z]+(?:\s+[A-Z][a-z]+){0,2})/gi,
      replacement: "Patient Name: [REDACTED_NAME]",
      mask: "••••••••",
    },
    {
      label: "Date of birth",
      category: "Personal",
      regex: /\b(?:\d{1,2}[\/\-]\d{1,2}[\/\-]\d{2,4}|\d{4}[\/\-]\d{1,2}[\/\-]\d{1,2})\b/g,
      replacement: "[REDACTED_DOB]",
      mask: "•• / •• / ••••",
    },
    {
      label: "Phone number",
      category: "Personal",
      regex: /(?:\+?\d{1,3}[\s\-]?)?\(?\d{3}\)?[\s\-]?\d{3}[\s\-]?\d{4}\b/g,
      replacement: "[REDACTED_PHONE]",
      mask: "•••-•••-••••",
    },
    {
      label: "Email address",
      category: "Personal",
      regex: /[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/g,
      replacement: "[REDACTED_EMAIL]",
      mask: "••••@••••.•••",
    },
    {
      label: "Patient / Medical ID",
      category: "Identifier",
      regex: /(?:Patient\s*ID|MRN|UHID|ID)\s*[:\-#]\s*([A-Z0-9\-]{4,})/gi,
      replacement: "ID: [REDACTED_ID]",
      mask: "••••••••",
    },
  ];

  for (const p of patterns) {
    const matches = rawText.match(p.regex);
    if (matches && matches.length > 0) {
      redacted = redacted.replace(p.regex, p.replacement);
      detectedItems.push({
        label: p.label,
        originalValue: matches[0],
        maskedPreview: p.mask,
        category: p.category,
      });
    }
  }

  return {
    redactedText: redacted,
    redactedItemsCount: detectedItems.length,
    detectedItems,
  };
}

// Stage 2: Strictly on-device PII detection & masking
export async function runOnDevicePrivacyGate(
  rawText: string
): Promise<PrivacyGateResult> {
  try {
    const model = getPrivacyGateModel();
    const prompt = `Identify and mask all personal identifiers (names, dates of birth, phone numbers, emails, addresses, patient/hospital IDs) in the medical text below.
Return ONLY valid JSON with keys:
- "redactedText": string
- "redactedItemsCount": integer
- "detectedItems": array of { "label": string, "originalValue": string, "maskedPreview": string, "category": "Personal" | "Identifier" }

Medical text:
${rawText}`;

    const res = await model.generateContent(prompt);
    const cleaned = res.response.text().replace(/```json|```/g, "").trim();
    const parsed = JSON.parse(cleaned) as PrivacyGateResult;
    if (parsed && typeof parsed.redactedText === "string") {
      return parsed;
    }
  } catch {
    // Fallback to deterministic local regex PII redaction if on-device Nano is unavailable
  }
  return runLocalRegexPrivacyMask(rawText);
}

// Stage 3: Extract structured medical markers from masked text or user health note
export async function extractMedicalContext(
  sanitizedText: string,
  sourceLabel = "Uploaded medical report"
): Promise<ExtractedMedicalProfile> {
  const model = getProfileExtractorModel();
  const prompt = `Extract structured health markers, medical conditions, allergies, medications, and dietary restrictions from the anonymized health text below.
Rules:
- For each item in healthMarkers:
  - "category": short label (e.g., "Glucose", "Blood Pressure", "Allergies", "Cholesterol", "Condition")
  - "value": concise status (e.g., "Pre-diabetic", "High BP", "Peanuts")
  - "tone": "coral" for alerts/conditions/allergies, "green" for normal/healthy markers, "blue" for medications/notes
  - "iconType": one of "glucose", "allergy", "heart", or "medication"
- Set "reportTitle" to a clean title (default: "${sourceLabel}").

Anonymized health text:
${sanitizedText}`;

  const res = await model.generateContent(prompt);
  const cleaned = res.response.text().replace(/```json|```/g, "").trim();
  return JSON.parse(cleaned) as ExtractedMedicalProfile;
}

// Stage 4: Analyze ingredients against user's health profile
export async function analyzeIngredientsWithHybridAI(
  ingredients: string[],
  profile: ExtractedMedicalProfile | null,
  mode: "auto" | "flash" | "nano"
): Promise<{ result: IngredientAnalysisResult; modelUsedLabel: string }> {
  const model = getIngredientAnalyzerModel(mode);
  const healthContext = profile
    ? JSON.stringify({
        healthMarkers: profile.healthMarkers,
        conditions: profile.conditions,
        allergies: profile.allergies,
        medications: profile.medications,
        dietaryRestrictions: profile.dietaryRestrictions,
      })
    : "No medical conditions or allergies reported yet.";

  const prompt = `Evaluate these product ingredients against the user's medical profile and return ONLY valid JSON matching the schema.
Scoring rubric for safetyScore (0-100):
- 70 to 100: Safe / good match (badgeLabel: "Good match for you")
- 40 to 69: Caution / moderate risk (badgeLabel: "Use with care")
- 0 to 39: High risk / allergen or condition conflict (badgeLabel: "Avoid")

For healthFactors:
- "sentiment": "positive" | "warning" | "neutral"
- "impactBadge": e.g. "Safe", "Caution", "High risk", or "+5 pts" / "-15 pts"

For ingredientBreakdown:
- "status": "Great" | "Okay" | "Caution" | "Avoid"

User Health Profile:
${healthContext}

Ingredients List:
${ingredients.join(", ")}`;

  const res = await model.generateContent(prompt);
  const cleaned = res.response.text().replace(/```json|```/g, "").trim();
  const parsed = JSON.parse(cleaned) as IngredientAnalysisResult;

  const modelUsedLabel =
    mode === "nano" || (mode === "auto" && !navigator.onLine)
      ? "Gemini Nano 4 (On-Device)"
      : "Gemini 3.5 Flash Lite";

  return { result: parsed, modelUsedLabel };
}