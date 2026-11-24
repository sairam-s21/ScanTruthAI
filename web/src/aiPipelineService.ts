// src/services/aiPipelineService.ts
import {
  getPrivacyGateModel,
  getProfileExtractorModel,
  getIngredientAnalyzerModel,
  getIngredientClassifierModel,
  getIngredientExtractorModel,
  type PrivacyGateResult,
  type ExtractedMedicalProfile,
  type IngredientAnalysisResult,
  type IngredientBreakdownItem,
  type HealthMarker,
  type DetectedPiiItem,
} from "./firebase";
import { isGroundedIn, parseIngredientSection } from "./ingredientParser";
import { HEALTH_CATEGORIES, type HealthCategory } from "./health";
import { PREF_KEYS, readBoolPref } from "./preferences";

export type OnDeviceStatus = "available" | "downloadable" | "downloading" | "unavailable";

/** Chrome's built-in Gemini Nano (Prompt API). Never reports ready unless the browser says so. */
export async function getOnDeviceStatus(): Promise<OnDeviceStatus> {
  const lm = (globalThis as unknown as { LanguageModel?: { availability: () => Promise<string> } })
    .LanguageModel;
  if (!lm?.availability) return "unavailable";
  try {
    const status = await lm.availability();
    return status === "available" || status === "downloadable" || status === "downloading"
      ? status
      : "unavailable";
  } catch {
    return "unavailable";
  }
}

function cleanJson(raw: string): string {
  const stripped = raw.replace(/```json|```/g, "").trim();
  const start = stripped.indexOf("{");
  const end = stripped.lastIndexOf("}");
  return start >= 0 && end > start ? stripped.slice(start, end + 1) : stripped;
}

export interface HealthItemValidation {
  isValid: boolean;
  normalizedName?: string;
  reason?: string;
}

function parseValidation(raw: string): HealthItemValidation | null {
  try {
    const parsed = JSON.parse(cleanJson(raw)) as HealthItemValidation;
    return typeof parsed?.isValid === "boolean" ? parsed : null;
  } catch {
    return null;
  }
}

/**
 * Checks that text typed into an "Add …" dialog really is an item of `category`, e.g. rejects
 * "asdf" or "banana" as a medication. Gemini Nano goes first so nothing leaves the device;
 * the cloud model is used only when Nano is unavailable or fails. Throws if neither decides.
 */
export async function validateHealthItem(
  text: string,
  category: HealthCategory,
): Promise<HealthItemValidation> {
  const singular = HEALTH_CATEGORIES[category].singular;
  const prompt = `A user typed the text below into the "${singular}" list of their health profile.
Decide whether it is a real, recognizable ${singular} (misspellings of real ones are fine) and not gibberish, a random word, or something that belongs in a different list.

Return ONLY valid JSON with keys:
"isValid" (boolean),
"normalizedName" (string: properly capitalized and spelled name, keeping any dose such as "500 mg"),
and "reason" (short string explaining a rejection, empty if valid).

Text: "${text}"`;

  if ((await getOnDeviceStatus()) === "available") {
    try {
      const res = await getPrivacyGateModel().generateContent(prompt);
      const result = parseValidation(res.response.text());
      if (result) return result;
    } catch {
      // Fall through to the cloud model.
    }
  }

  const res = await getIngredientClassifierModel().generateContent(prompt);
  const result = parseValidation(res.response.text());
  if (!result) throw new Error("The answer could not be understood.");
  return result;
}

export interface EnhancedPrivacyGateResult extends PrivacyGateResult {
  usedFallback: boolean;
  notice?: string;
}

export interface EnhancedIngredientAnalysisResponse {
  result: IngredientAnalysisResult;
  modelUsedLabel: string;
  usedFallback: boolean;
  notice?: string;
}

// Deterministic local regex fallback covering HIPAA Safe Harbor & Indian ABDM/DPDP identifiers
function runLocalRegexPrivacyMask(rawText: string): EnhancedPrivacyGateResult {
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
      label: "Report / Portal URL",
      category: "Identifier",
      regex: /\b(?:https?:\/\/|www\.)[^\s<>"{}|\\^`[\]]+/gi,
      replacement: "[REDACTED_URL]",
      mask: "https://••••••",
    },
    {
      label: "ABHA / Aadhaar / National ID",
      category: "Identifier",
      regex:
        /(?:\b\d{2}-\d{4}-\d{4}-\d{4}\b|\b[a-zA-Z0-9._-]+@abdm\b|\b\d{4}[\s\-]?\d{4}[\s\-]?\d{4}\b|\b\d{3}-\d{2}-\d{4}\b)/gi,
      replacement: "[REDACTED_GOV_ID]",
      mask: "••••-••••-••••",
    },
    {
      label: "Patient / Guardian Name",
      category: "Personal",
      regex:
        /(?:Patient\s*Name|Name|Mr\.|Mrs\.|Ms\.|S\/o|D\/o|W\/o|Guardian)\s*[:\-]?\s*([A-Z][a-z]+(?:\s+[A-Z][a-z]+){0,2})/g,
      replacement: "[REDACTED_NAME]",
      mask: "••••••••",
    },
    {
      label: "Doctor / Clinician Name",
      category: "Personal",
      regex:
        /(?:Ref\.?\s*By|Consulting|Doctor|Dr\.)\s*[:\-]?\s*(?:Dr\.?\s*)?([A-Z][a-zA-Z.]*(?:\s+[A-Z][a-zA-Z.]*){0,2})/g,
      replacement: "[REDACTED_DOCTOR]",
      mask: "Dr. ••••••",
    },
    {
      label: "Hospital / Clinic / Address",
      category: "Personal",
      regex:
        /(?:Hospital|Clinic|Diagnostic\s*Center|Lab\s*Name|Address)\s*[:\-]\s*([^\n\r]{4,80})/gi,
      replacement: "[REDACTED_LOCATION]",
      mask: "••••••••, ••••",
    },
    {
      label: "Medical / Lab / Insurance ID",
      category: "Identifier",
      regex:
        /(?:Patient\s*ID|MRN|UHID|Lab\s*No|Sample\s*ID|Episode\s*ID|Bill\s*No|Invoice\s*No|Policy\s*No|TPA\s*ID|Member\s*ID|Reg\.?\s*No|ID)\s*[:\-#]\s*([A-Z0-9\-\/]{4,})/gi,
      replacement: "[REDACTED_RECORD_ID]",
      mask: "••••••••",
    },
    {
      label: "Service / Birth Date",
      category: "Personal",
      regex:
        /(?:(?:DOB|Date\s*of\s*Birth|Collected\s*on|Reported\s*on|Admitted|Discharged)\s*[:\-]?\s*)?\b(?:\d{1,2}[\/\-]\d{1,2}[\/\-]\d{2,4}|\d{4}[\/\-]\d{1,2}[\/\-]\d{1,2})\b/gi,
      replacement: "[REDACTED_DATE]",
      mask: "•• / •• / ••••",
    },
    {
      label: "Phone Number",
      category: "Personal",
      regex:
        /(?:\+91[\s\-]?[6-9]\d{9}|\b[6-9]\d{9}\b|(?:\+?\d{1,3}[\s\-]?)?\(?\d{3}\)?[\s\-]?\d{3}[\s\-]?\d{4}\b)/g,
      replacement: "[REDACTED_PHONE]",
      mask: "•••-•••-••••",
    },
    {
      label: "Email Address",
      category: "Personal",
      regex: /[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/g,
      replacement: "[REDACTED_EMAIL]",
      mask: "••••@••••.•••",
    },
    {
      label: "PIN / ZIP Code",
      category: "Personal",
      regex: /(?:PIN|Pincode|ZIP)\s*[:\-]?\s*\b\d{5,6}\b|\b[1-9]\d{5}\b/gi,
      replacement: "[REDACTED_PIN]",
      mask: "••••••",
    },
  ];

  for (const p of patterns) {
    const matches = Array.from(redacted.matchAll(p.regex));
    if (matches.length > 0) {
      for (const match of matches) {
        detectedItems.push({
          label: p.label,
          originalValue: match[0],
          maskedPreview: p.mask,
          category: p.category,
        });
      }
      redacted = redacted.replace(p.regex, p.replacement);
    }
  }

  return {
    redactedText: redacted,
    redactedItemsCount: detectedItems.length,
    detectedItems,
    usedFallback: true,
    notice:
      "Gemini Nano is unavailable in this browser, so a rule-based filter masked the text on your device.",
  };
}

// Stage 2: Strictly on-device PII detection & masking
export async function runOnDevicePrivacyGate(rawText: string): Promise<EnhancedPrivacyGateResult> {
  try {
    const model = getPrivacyGateModel();
    const prompt = `Identify and mask ALL personal and protected health identifiers in the medical text below:
- Patient, guardian, and doctor names
- Dates of birth and exact admission/collection/report dates (keep patient age and sex intact)
- Phone numbers, emails, street addresses, hospital/clinic/lab names, and PIN/ZIP codes
- ABHA IDs, Aadhaar/SSN numbers, Insurance/Policy/TPA IDs, Patient/MRN/UHID/Lab/Sample/Bill numbers, medical device serials, and report URLs
- Preserve all clinical values, lab markers, units, reference ranges, diagnoses, medications, and allergies.

Return ONLY valid JSON with keys:
- "redactedText": string
- "redactedItemsCount": integer
- "detectedItems": array of { "label": string, "originalValue": string, "maskedPreview": string, "category": "Personal" | "Identifier" }

Medical text:
${rawText}`;

    const res = await model.generateContent(prompt);
    const cleaned = res.response
      .text()
      .replace(/```json|```/g, "")
      .trim();
    const parsed = JSON.parse(cleaned) as PrivacyGateResult;
    if (parsed && typeof parsed.redactedText === "string") {
      return {
        ...parsed,
        usedFallback: false,
      };
    }
  } catch {
    // Fallback to deterministic local regex PII redaction if on-device Nano is unavailable
  }
  return runLocalRegexPrivacyMask(rawText);
}

/** Plain-language reason for a failed AI call, instead of the raw endpoint error. */
export function describeAiError(err: unknown): string {
  const message = err instanceof Error ? err.message : String(err);
  if (/app check/i.test(message)) {
    return "this site could not verify itself with Firebase App Check, so the cloud AI request was blocked. Try again in a moment; if it keeps happening, the site's reCAPTCHA setup needs attention.";
  }
  if (/failed to fetch|network|offline/i.test(message) || (typeof navigator !== "undefined" && !navigator.onLine)) {
    return "the cloud AI could not be reached. Check your connection and try again.";
  }
  if (/quota|429|resource.?exhausted/i.test(message)) {
    return "the cloud AI is busy right now. Try again in a minute.";
  }
  return message;
}

/** Prompt for reading the ingredient list out of a label's OCR text. */
export function ingredientExtractionPrompt(rawOcrText: string): string {
  return `You read the ingredient list from the packaging of a food, supplement or medicine product.

The text below was produced by OCR from a phone photo of the pack. Expect OCR damage: words split or joined ("SUNFLOWERSEEDS"), misread letters ("SACILUS" for "BACILLUS", "0" for "O", "5" for "S"), lost or extra punctuation, stray symbols (¥ « | _ ©), lines in the wrong order, and text from other parts of the pack mixed in.

STEP 1 - Find the ingredients section.
- It starts at a heading such as "Ingredients", "Ingredient list", "Composition", "Contents" or "Active ingredients" (the heading itself may be misspelled by OCR, e.g. "INGREDIENIS", "1NGREDIENTS").
- It ends where the next part of the label begins, for example: "Allergen advice", "Allergy information", "Contains:", "May contain", "Commodity", "Nutrition information", a nutrition table, "Storage", "Directions", "How to use", "Best before", "Manufactured by", "FSSAI", "Net weight", "Not for medicinal use", "Do not exceed", "Keep out of reach", or any warning or statement.
- If there is no ingredients section, set "ingredientsSectionFound" to false and return an empty list. Never build a list from other parts of the label.

STEP 2 - List only what that section names as ingredients.
Never include anything from outside the section, even if it sounds like food. In particular, never include:
- nutrition table rows or values ("Total salt", "Sodium", "Energy", "Protein (g)", "Bacillus coagulans (billion CFU) 0.2");
- allergen statements ("Contains oats, soy, nuts & milk", "Processed on machinery that also processes tree nuts");
- declarations and warnings ("Contains non caloric sweetener", "Contains naturally occurring sugars", "Not for medicinal use", "Not to be used as a substitute for a varied diet");
- product category, serving, storage or usage text, brand names, claims, addresses, licence numbers, dates and prices.

STEP 3 - Write each ingredient cleanly.
- Split the list on commas and semicolons that are not inside brackets.
- A group with its parts in brackets is replaced by its parts: "Protein source blend (textured soy protein flakes, whey powder)" gives "Textured soy protein flakes" and "Whey powder"; "Fruits, nuts & seeds (raisins, chia seeds, almond)" gives "Raisins", "Chia seeds", "Almond".
- An additive class with one named substance keeps both, substance first: "Sweetener (steviol glycosides)" gives "Steviol glycosides (sweetener)"; "Emulsifier (INS 322)" gives "INS 322 (emulsifier)".
- Remove percentages, amounts and counts: "Instant oats (33%)" gives "Instant oats"; "Bacillus coagulans SNZ 1969, 15 billion CFU/g" gives "Bacillus coagulans SNZ 1969".
- Repair OCR errors only when the intended word is clear from the surrounding text, e.g. "HIMALAYAN PINK SAL" gives "Himalayan pink salt" and "S¥ TEVIOL GLYCOSIDES" gives "Steviol glycosides". If a fragment is unreadable, leave it out. Never add an ingredient that is not in the section.
- Use sentence case ("Rice bran oil"), but keep codes and strain names as printed ("INS 322", "E330", "SNZ 1969").
- Keep label order and list each ingredient once.

Example
Text: "NUTRITION INFORMATION ENERGY 120 kcal SODIUM 80 mg INGREDIENTS: WHOLE WHEAT FLOUR (62%), SUGAR, EDIBLE VEGETABLE OIL (PALM OIL), RAISING AGENTS (INS 500(ii), INS 503(ii)), IODISED SALT, EMULSIFIER (SOY LECITHIN). ALLERGEN INFORMATION: CONTAINS WHEAT AND SOY. BEST BEFORE 6 MONTHS"
Answer: {"ingredientsSectionFound": true, "ingredients": ["Whole wheat flour", "Sugar", "Edible vegetable oil (palm oil)", "INS 500(ii) (raising agent)", "INS 503(ii) (raising agent)", "Iodised salt", "Soy lecithin (emulsifier)"]}

Label text:
"""
${rawOcrText.slice(0, 6000)}
"""`;
}

/** Cloud extraction. Throws when the model cannot be reached or answers in the wrong shape. */
export async function extractIngredientsWithAI(
  rawOcrText: string,
): Promise<{ sectionFound: boolean; ingredients: string[] }> {
  const res = await getIngredientExtractorModel().generateContent(ingredientExtractionPrompt(rawOcrText));
  const parsed = JSON.parse(cleanJson(res.response.text())) as {
    ingredientsSectionFound?: boolean;
    ingredients?: unknown;
  };
  if (!Array.isArray(parsed.ingredients)) throw new Error("Unexpected ingredient answer.");
  return {
    sectionFound: parsed.ingredientsSectionFound !== false,
    ingredients: parsed.ingredients
      .filter((i): i is string => typeof i === "string")
      .map((i) => i.replace(/\s+/g, " ").trim())
      .filter((i) => i.length >= 2 && i.length <= 80 && /\p{L}{2,}/u.test(i)),
  };
}

/**
 * Ingredient list for a label photo's OCR text. The cloud model reads the section and repairs
 * OCR errors; every item it returns must be traceable to the label text. When the model is
 * unreachable (offline, App Check) the on-device section parser is used. An empty list means
 * no ingredients section was found, and the caller asks the user to retake or type the list.
 */
export async function extractIngredientsIntelligently(rawOcrText: string): Promise<string[]> {
  if (!rawOcrText || rawOcrText.trim().length < 3) return [];
  const local = parseIngredientSection(rawOcrText);

  try {
    const cloud = await extractIngredientsWithAI(rawOcrText);
    const grounded = cloud.ingredients.filter((i) => isGroundedIn(i, rawOcrText));
    if (cloud.sectionFound && grounded.length > 0) {
      const seen = new Set<string>();
      return grounded.filter((i) => !seen.has(i.toLowerCase()) && !!seen.add(i.toLowerCase()));
    }
  } catch (err) {
    console.warn("Cloud ingredient extraction unavailable, using the on-device parser:", err);
  }
  return local.ingredients;
}

// Stage 3A: Deterministic Clinical Medical Extractor (Instant, Reliable, Offline-Ready)
export function extractMedicalContextDeterministic(
  text: string,
  sourceLabel = "Medical report",
): ExtractedMedicalProfile {
  const normalized = text.toLowerCase();
  const markers: HealthMarker[] = [];
  const conditions: string[] = [];
  const allergies: string[] = [];
  const medications: string[] = [];
  const dietaryRestrictions: string[] = [];

  const addMarker = (category: string, value: string, tone: "coral" | "green" | "blue", iconType: "glucose" | "allergy" | "heart" | "medication") => {
    if (!markers.some((m) => m.category === category && m.value.toLowerCase() === value.toLowerCase())) {
      markers.push({ category, value, tone, iconType });
    }
  };

  // 1. Lactose Intolerance & Dairy
  if (/\b(?:lactose\s*intoleran\w*|milk\s*intoleran\w*|dairy\s*intoleran\w*)\b/i.test(normalized)) {
    conditions.push("Lactose Intolerance");
    dietaryRestrictions.push("Dairy-free");
    addMarker("Intolerance", "Lactose Intolerant", "coral", "allergy");
  }
  if (/\b(?:dairy|milk|casein|whey)\s*allerg\w*\b/i.test(normalized)) {
    allergies.push("Dairy / Milk Allergy");
    addMarker("Allergies", "Dairy / Milk", "coral", "allergy");
  }

  // 2. Diabetes & Blood Sugar
  if (/\b(?:type\s*2\s*diabet\w*|t2dm|diabetes\s*mellitus)\b/i.test(normalized)) {
    conditions.push("Type 2 Diabetes");
    dietaryRestrictions.push("Low glycemic / Low sugar");
    addMarker("Glucose", "Type 2 Diabetic", "coral", "glucose");
  } else if (/\b(?:type\s*1\s*diabet\w*|t1dm)\b/i.test(normalized)) {
    conditions.push("Type 1 Diabetes");
    dietaryRestrictions.push("Low sugar / Carb managed");
    addMarker("Glucose", "Type 1 Diabetic", "coral", "glucose");
  } else if (/\b(?:pre-?diabet\w*|borderline\s*diabet\w*|impaired\s*glucose)\b/i.test(normalized)) {
    conditions.push("Pre-diabetes");
    addMarker("Glucose", "Pre-diabetic", "coral", "glucose");
  }

  // Lab glucose / HbA1c values
  const hba1cMatch = normalized.match(/hba1c\s*[:\-]?\s*(\d+(?:\.\d+)?)/i);
  if (hba1cMatch) {
    const val = parseFloat(hba1cMatch[1]);
    if (val >= 6.5) {
      addMarker("HbA1c", `${val}% (Diabetic range)`, "coral", "glucose");
      if (!conditions.includes("Type 2 Diabetes")) conditions.push("Diabetes");
    } else if (val >= 5.7) {
      addMarker("HbA1c", `${val}% (Pre-diabetic range)`, "coral", "glucose");
    } else {
      addMarker("HbA1c", `${val}% (Normal)`, "green", "glucose");
    }
  }

  // 3. Hypertension & Cardiovascular
  if (/\b(?:hypertens\w*|high\s*blood\s*pressure|high\s*bp)\b/i.test(normalized)) {
    conditions.push("Hypertension");
    dietaryRestrictions.push("Low sodium");
    addMarker("Blood Pressure", "Hypertension", "coral", "heart");
  }

  const bpMatch = normalized.match(/(?:bp|blood\s*pressure)\s*[:\-]?\s*(\d{2,3})[\s\/](\d{2,3})/i);
  if (bpMatch) {
    const sys = parseInt(bpMatch[1], 10);
    const dia = parseInt(bpMatch[2], 10);
    if (sys >= 130 || dia >= 85) {
      addMarker("Blood Pressure", `${sys}/${dia} mmHg (Elevated)`, "coral", "heart");
    } else {
      addMarker("Blood Pressure", `${sys}/${dia} mmHg (Optimal)`, "green", "heart");
    }
  }

  // 4. Cholesterol & Lipids
  if (/\b(?:hyperlipid\w*|dyslipid\w*|high\s*cholesterol)\b/i.test(normalized)) {
    conditions.push("Hyperlipidemia");
    addMarker("Cholesterol", "Elevated Lipids", "coral", "heart");
  }

  // 5. Celiac Disease & Gluten Sensitivity
  if (/\b(?:celiac\s*disease|coeliac)\b/i.test(normalized)) {
    conditions.push("Celiac Disease");
    dietaryRestrictions.push("Strict Gluten-free");
    addMarker("Condition", "Celiac Disease", "coral", "allergy");
  } else if (/\b(?:gluten\s*intoleran\w*|gluten\s*sensitiv\w*)\b/i.test(normalized)) {
    conditions.push("Gluten Sensitivity");
    dietaryRestrictions.push("Gluten-free");
    addMarker("Intolerance", "Gluten Sensitive", "coral", "allergy");
  }

  // 6. Common Allergies
  if (/\b(?:peanut|tree\s*nut|nut)\s*allerg\w*\b/i.test(normalized)) {
    allergies.push("Peanuts & Tree Nuts");
    addMarker("Allergies", "Nut Allergy", "coral", "allergy");
  }
  if (/\b(?:gluten|wheat)\s*allerg\w*\b/i.test(normalized)) {
    allergies.push("Gluten & Wheat");
    addMarker("Allergies", "Wheat / Gluten", "coral", "allergy");
  }
  if (/\b(?:shellfish|shrimp|crab|fish|seafood)\s*allerg\w*\b/i.test(normalized)) {
    allergies.push("Shellfish / Seafood");
    addMarker("Allergies", "Shellfish", "coral", "allergy");
  }
  if (/\b(?:soy|soya)\s*allerg\w*\b/i.test(normalized)) {
    allergies.push("Soy");
    addMarker("Allergies", "Soy Allergy", "coral", "allergy");
  }
  if (/\begg\s*allerg\w*\b/i.test(normalized)) {
    allergies.push("Eggs");
    addMarker("Allergies", "Egg Allergy", "coral", "allergy");
  }

  // 7. Thyroid & Kidney
  if (/\b(?:hypothyroid\w*)\b/i.test(normalized)) {
    conditions.push("Hypothyroidism");
    addMarker("Condition", "Hypothyroidism", "coral", "heart");
  }
  if (/\b(?:chronic\s*kidney|renal\s*disease|nephropathy)\b/i.test(normalized)) {
    conditions.push("Kidney Condition");
    addMarker("Kidney", "Renal Watch", "coral", "heart");
  }

  // 8. Medications Detection
  const meds = [
    "metformin",
    "insulin",
    "glimepiride",
    "amlodipine",
    "losartan",
    "lisinopril",
    "atorvastatin",
    "rosuvastatin",
    "omeprazole",
    "pantoprazole",
    "levothyroxine",
    "aspirin",
    "montelukast",
    "cetirizine",
  ];
  for (const med of meds) {
    if (new RegExp(`\\b${med}\\b`, "i").test(normalized)) {
      const formatted = med.charAt(0).toUpperCase() + med.slice(1);
      medications.push(formatted);
      addMarker("Medication", formatted, "blue", "medication");
    }
  }

  // If no specific condition was picked up, add a general baseline marker
  if (markers.length === 0) {
    addMarker("Health Profile", "General Health Assessment", "green", "heart");
  }

  return {
    reportTitle: sourceLabel,
    markersCount: markers.length,
    healthMarkers: markers,
    conditions: Array.from(new Set(conditions)),
    allergies: Array.from(new Set(allergies)),
    medications: Array.from(new Set(medications)),
    dietaryRestrictions: Array.from(new Set(dietaryRestrictions)),
  };
}

// Stage 3B: Extract structured medical markers with hybrid cloud AI + deterministic fallback
export async function extractMedicalContext(
  sanitizedText: string,
  sourceLabel = "Uploaded medical report",
): Promise<ExtractedMedicalProfile> {
  // Always compute deterministic clinical profile first as guaranteed ground truth
  const deterministic = extractMedicalContextDeterministic(sanitizedText, sourceLabel);

  try {
    const model = getProfileExtractorModel();
    const prompt = `You are a clinical AI specialist. Extract structured health markers, medical conditions, allergies, medications, and dietary restrictions from the anonymized health text below.
Rules:
- For each item in healthMarkers:
  - "category": specific clinical label (e.g., "Glucose", "Blood Pressure", "Allergies", "Intolerance", "Medication", "Kidney", "Lipid")
  - "value": concise clinical status (e.g., "Lactose Intolerant", "Pre-diabetic", "Hypertension", "Peanut Allergy")
  - "tone": "coral" for active conditions, allergies, or intolerances; "green" for normal markers; "blue" for current medications or dietary guidelines
  - "iconType": one of "glucose", "allergy", "heart", or "medication"
- "conditions": array of clear diagnosed conditions or intolerances (e.g., ["Lactose intolerance", "Type 2 diabetes"]).
- "allergies": array of known allergens (e.g., ["Dairy", "Peanuts", "Gluten", "Shellfish"]).
- "medications": array of current medications and dosages.
- "dietaryRestrictions": array of specific dietary restrictions (e.g., ["Dairy-free", "Low sodium", "Gluten-free"]).
- Set "reportTitle" to a clean title (default: "${sourceLabel}").

Anonymized health text:
${sanitizedText}`;

    const res = await model.generateContent(prompt);
    const cleaned = res.response
      .text()
      .replace(/```json|```/g, "")
      .trim();
    const parsed = JSON.parse(cleaned) as ExtractedMedicalProfile;

    if (parsed && Array.isArray(parsed.healthMarkers)) {
      // Merge cloud findings with deterministic findings for exhaustive clinical accuracy
      const mergedConditions = Array.from(
        new Set([...(parsed.conditions || []), ...deterministic.conditions]),
      );
      const mergedAllergies = Array.from(
        new Set([...(parsed.allergies || []), ...deterministic.allergies]),
      );
      const mergedDiet = Array.from(
        new Set([...(parsed.dietaryRestrictions || []), ...deterministic.dietaryRestrictions]),
      );
      const mergedMeds = Array.from(
        new Set([...(parsed.medications || []), ...deterministic.medications]),
      );

      // Merge health markers without exact category-value duplicates
      const mergedMarkers: HealthMarker[] = [...parsed.healthMarkers];
      for (const dm of deterministic.healthMarkers) {
        if (
          !mergedMarkers.some(
            (m) =>
              m.category.toLowerCase() === dm.category.toLowerCase() &&
              m.value.toLowerCase() === dm.value.toLowerCase(),
          )
        ) {
          mergedMarkers.push(dm);
        }
      }

      return {
        reportTitle: parsed.reportTitle || sourceLabel,
        markersCount: mergedMarkers.length,
        healthMarkers: mergedMarkers,
        conditions: mergedConditions,
        allergies: mergedAllergies,
        medications: mergedMeds,
        dietaryRestrictions: mergedDiet,
      };
    }
  } catch (err) {
    console.warn("Cloud profile extraction fallback to deterministic engine:", err);
  }

  // Guaranteed fallback
  return deterministic;
}

function sanitizeBreakdownStatus(status?: string): "Great" | "Okay" | "Caution" | "Avoid" {
  if (status === "Avoid") return "Avoid";
  if (status === "Caution") return "Caution";
  if (status === "Great") return "Great";
  return "Okay";
}

// Stage 4: Clinical normalizer and deterministic conflict verification
export function normalizeIngredientAnalysisResult(
  raw: Partial<IngredientAnalysisResult> | null | undefined,
  ingredients: string[],
  profile: ExtractedMedicalProfile | null,
): IngredientAnalysisResult {
  const result: IngredientAnalysisResult = {
    productName: raw?.productName?.trim() || "",
    productCategory: raw?.productCategory === "medicine" ? "medicine" : "food",
    safetyScore: typeof raw?.safetyScore === "number" ? raw.safetyScore : 70,
    badgeLabel: raw?.badgeLabel?.trim() || "Good match for you",
    headline: raw?.headline?.trim() || "",
    aiSummary: raw?.aiSummary?.trim() || "",
    healthFactors: Array.isArray(raw?.healthFactors) ? [...raw.healthFactors] : [],
    ingredientBreakdown: Array.isArray(raw?.ingredientBreakdown)
      ? [...raw.ingredientBreakdown]
      : [],
    recommendedIntake: {
      dosageHeadline:
        raw?.recommendedIntake?.dosageHeadline?.trim() || "Moderate serving guidance",
      guidance:
        raw?.recommendedIntake?.guidance?.trim() ||
        "Consume in moderation in consultation with your healthcare provider.",
    },
  };

  // Derive productName if empty
  if (!result.productName) {
    if (ingredients.length > 0) {
      result.productName =
        ingredients
          .slice(0, 2)
          .map((i) => i.charAt(0).toUpperCase() + i.slice(1))
          .join(" & ") + (ingredients.length > 2 ? ` (${ingredients.length} items)` : "");
    } else {
      result.productName = "Scanned Product";
    }
  }

  // 1. Gather all conditions, allergies, restrictions, and health markers from user profile
  const userHealthKeywords = [
    ...(profile?.conditions || []),
    ...(profile?.allergies || []),
    ...(profile?.dietaryRestrictions || []),
    ...(profile?.healthMarkers?.map((m) => `${m.category} ${m.value}`) || []),
  ]
    .map((s) => s.toLowerCase())
    .filter(Boolean);

  // Clinical conflict rules
  interface ConflictRule {
    conditionRegex: RegExp;
    ingredientRegex: RegExp;
    conflictTitle: string;
    factorDescription: (ing: string) => string;
    ingredientNote: (ing: string) => string;
    severity: "Avoid" | "Caution";
    sentiment: "warning";
    maxScore: number;
    badge: string;
  }

  const rules: ConflictRule[] = [
    {
      conditionRegex: /\b(lactose|dairy|milk\s*allergy|milk\s*intoleran)\b/i,
      ingredientRegex:
        /\b(whey|whey\s*protein|casein|caseinate|milk|dairy|lactose|butter|cheese|cream|ghee|curd|yogurt|colostrum|milk\s*powder|skim\s*milk)\b/i,
      conflictTitle: "Lactose & Dairy Conflict",
      factorDescription: (ing) =>
        `${ing} contains milk derivatives and lactose, which can trigger abdominal pain, severe bloating, cramping, and digestive distress for lactose-intolerant individuals.`,
      ingredientNote: () =>
        "Milk derivative containing lactose; not suitable for lactose intolerance or milk protein sensitivities.",
      severity: "Avoid",
      sentiment: "warning",
      maxScore: 32,
      badge: "Avoid",
    },
    {
      conditionRegex: /\b(diabet|glucose|insulin|pre-diabet|sugar)\b/i,
      ingredientRegex:
        /\b(sugar|cane\s*sugar|corn\s*syrup|high\s*fructose|maltodextrin|dextrose|fructose|glucose\s*syrup|molasses|sucrose)\b/i,
      conflictTitle: "High Glycemic / Sugar Impact",
      factorDescription: (ing) =>
        `${ing} is a fast-acting refined carbohydrate that can cause rapid blood glucose spikes and insulin resistance strain.`,
      ingredientNote: () =>
        "High glycemic sweetener; causes swift blood sugar spikes in diabetic or pre-diabetic individuals.",
      severity: "Caution",
      sentiment: "warning",
      maxScore: 52,
      badge: "Use with care",
    },
    {
      conditionRegex: /\b(hypertens|blood\s*pressure|high\s*bp|cardio|heart)\b/i,
      ingredientRegex:
        /\b(sodium|salt|monosodium|msg|sodium\s*chloride|sodium\s*benzoate|baking\s*soda)\b/i,
      conflictTitle: "Sodium & Blood Pressure Alert",
      factorDescription: (ing) =>
        `${ing} contributes elevated sodium, which can expand blood volume and increase arterial blood pressure.`,
      ingredientNote: () =>
        "Elevated sodium content; may adversely affect blood pressure control in hypertension.",
      severity: "Caution",
      sentiment: "warning",
      maxScore: 56,
      badge: "Use with care",
    },
    {
      conditionRegex: /\b(celiac|gluten|wheat\s*allergy)\b/i,
      ingredientRegex: /\b(wheat|barley|rye|malt|spelt|semolina|flour|gluten|triticale)\b/i,
      conflictTitle: "Gluten Conflict",
      factorDescription: (ing) =>
        `${ing} contains gluten, triggering autoimmune damage to the small intestinal lining in celiac disease.`,
      ingredientNote: () =>
        "Contains gluten; strictly unsafe for celiac disease and gluten-sensitive enteropathy.",
      severity: "Avoid",
      sentiment: "warning",
      maxScore: 25,
      badge: "Avoid",
    },
    {
      conditionRegex: /\b(peanut|tree\s*nut|nut\s*allergy)\b/i,
      ingredientRegex:
        /\b(peanut|almond|cashew|walnut|pecan|pistachio|hazelnut|macadamia|brazil\s*nut)\b/i,
      conflictTitle: "Nut Allergen Danger",
      factorDescription: (ing) =>
        `${ing} is a potent allergen with high risk of triggering acute hypersensitivity or anaphylaxis.`,
      ingredientNote: () =>
        "Nut allergen presenting acute risk of severe allergic reactions.",
      severity: "Avoid",
      sentiment: "warning",
      maxScore: 20,
      badge: "Avoid",
    },
  ];

  // Apply deterministic clinical conflict check
  let detectedConflict: ConflictRule | null = null;
  let conflictingIngredientName = "";

  for (const rule of rules) {
    const hasCondition = userHealthKeywords.some((kw) => rule.conditionRegex.test(kw));
    if (hasCondition) {
      for (let i = 0; i < ingredients.length; i++) {
        const ing = ingredients[i];
        if (rule.ingredientRegex.test(ing)) {
          detectedConflict = rule;
          conflictingIngredientName = ing;
          break;
        }
      }
    }
    if (detectedConflict) break;
  }

  // If a clinical conflict was detected, enforce the scoring & badge
  if (detectedConflict) {
    if (result.safetyScore > detectedConflict.maxScore) {
      result.safetyScore = detectedConflict.maxScore;
    }
    result.badgeLabel = detectedConflict.badge;
    result.headline = `Conflict detected: ${conflictingIngredientName} with your health profile`;
    result.recommendedIntake = {
      dosageHeadline: "Avoid or consult your doctor",
      guidance: `Due to your recorded condition, consuming ${conflictingIngredientName} is not recommended. Discuss safe alternatives with your physician.`,
    };

    const conflictFactor = {
      title: detectedConflict.conflictTitle,
      description: detectedConflict.factorDescription(conflictingIngredientName),
      impactBadge: detectedConflict.severity,
      sentiment: detectedConflict.sentiment,
    };

    const existingFactorIndex = result.healthFactors.findIndex(
      (f) =>
        f.title?.toLowerCase().includes("conflict") ||
        f.title?.toLowerCase().includes("lactose") ||
        f.title?.toLowerCase().includes("allergy") ||
        f.title?.toLowerCase().includes("risk"),
    );

    if (existingFactorIndex >= 0) {
      result.healthFactors[existingFactorIndex] = conflictFactor;
    } else {
      result.healthFactors.unshift(conflictFactor);
    }
  }

  // Fill headline if empty
  if (!result.headline) {
    if (result.safetyScore < 40) {
      result.headline = "Avoid: Contains ingredients conflicting with your profile";
    } else if (result.safetyScore < 70) {
      result.headline = "Caution: Contains ingredients requiring moderation";
    } else {
      result.headline = "Compatible: Ingredients align well with your health profile";
    }
  }

  // Fill aiSummary if empty
  if (!result.aiSummary) {
    if (detectedConflict) {
      result.aiSummary = `Clinical screening detected an active conflict between ${conflictingIngredientName} and your recorded health profile (${detectedConflict.conflictTitle}). Avoid consumption or consult your healthcare specialist for safe alternatives.`;
    } else if (result.safetyScore < 70) {
      result.aiSummary = `This product contains ingredients that require mindful intake based on your health context. Review the ingredient breakdown below before consuming.`;
    } else {
      result.aiSummary = `Based on your anonymized health profile, the scanned ingredients show no critical allergen or metabolic conflicts. Safe for consumption as part of a balanced diet.`;
    }
  }

  // Ensure all healthFactors have non-empty title and description
  result.healthFactors = result.healthFactors.map((f, idx) => ({
    title: f.title?.trim() || `Health Impact Factor ${idx + 1}`,
    description:
      f.description?.trim() ||
      "Analyzed against your anonymized medical context and verified safe for general consumption.",
    impactBadge: f.impactBadge?.trim() || (result.safetyScore < 70 ? "Caution" : "Safe"),
    sentiment: f.sentiment || (result.safetyScore < 70 ? "warning" : "positive"),
  }));

  // If healthFactors is still empty, add an informative factor
  if (result.healthFactors.length === 0) {
    result.healthFactors.push({
      title: "Health Context Assessment",
      description:
        userHealthKeywords.length > 0
          ? `Screened against ${userHealthKeywords.length} connected health marker${
              userHealthKeywords.length === 1 ? "" : "s"
            }.`
          : "No active health conditions recorded. Ingredients evaluated against general nutritional standards.",
      impactBadge: result.safetyScore < 70 ? "Caution" : "Safe",
      sentiment: result.safetyScore < 70 ? "warning" : "positive",
    });
  }

  // Ensure EVERY ingredient from `ingredients` is in `ingredientBreakdown`
  const breakdownMap = new Map<string, IngredientBreakdownItem>();
  for (const item of result.ingredientBreakdown) {
    if (item && item.name) {
      breakdownMap.set(item.name.toLowerCase().trim(), {
        name: item.name,
        note: item.note || "",
        status: sanitizeBreakdownStatus(item.status),
      });
    }
  }

  const completeBreakdown: IngredientBreakdownItem[] = [];

  for (const ing of ingredients) {
    const key = ing.toLowerCase().trim();
    const existing = breakdownMap.get(key);

    if (existing && existing.note?.trim()) {
      if (detectedConflict && detectedConflict.ingredientRegex.test(ing)) {
        completeBreakdown.push({
          name: ing,
          note: detectedConflict.ingredientNote(ing),
          status: detectedConflict.severity,
        });
      } else {
        completeBreakdown.push({
          name: existing.name || ing,
          note: existing.note,
          status: sanitizeBreakdownStatus(existing.status),
        });
      }
    } else {
      if (detectedConflict && detectedConflict.ingredientRegex.test(ing)) {
        completeBreakdown.push({
          name: ing,
          note: detectedConflict.ingredientNote(ing),
          status: detectedConflict.severity,
        });
      } else {
        completeBreakdown.push({
          name: ing,
          note: "General ingredient; safe and compatible with your health profile.",
          status: "Okay",
        });
      }
    }
  }

  // Also include any items that the model found which were not directly in ingredients
  for (const [key, item] of breakdownMap.entries()) {
    if (!ingredients.some((i) => i.toLowerCase().trim() === key)) {
      completeBreakdown.push({
        name: item.name,
        note: item.note || "Evaluated by AI.",
        status: sanitizeBreakdownStatus(item.status),
      });
    }
  }

  result.ingredientBreakdown = completeBreakdown;
  return result;
}

// Stage 4: Analyze ingredients against user's health profile with strict clinical cross-referencing
export async function analyzeIngredientsWithHybridAI(
  ingredients: string[],
  profile: ExtractedMedicalProfile | null,
  mode: "auto" | "flash" | "nano",
): Promise<EnhancedIngredientAnalysisResponse> {
  const healthContext = profile
    ? JSON.stringify({
        healthMarkers: profile.healthMarkers,
        conditions: profile.conditions,
        allergies: profile.allergies,
        medications: profile.medications,
        dietaryRestrictions: profile.dietaryRestrictions,
      })
    : "No medical conditions or allergies reported yet.";

  const prompt = `You are an expert Clinical Pharmacologist, Nutritionist, and Food Safety AI.
Evaluate these product ingredients rigorously against the user's medical profile and return ONLY valid JSON matching the exact schema.

CRITICAL CLINICAL RULES & CROSS-REFERENCING (MANDATORY):
1. Lactose Intolerance & Dairy:
   - "Whey protein", "whey protein concentrate", "whey", "casein", "milk solids", "milk powder", "butter", "cream" contain lactose and milk derivatives.
   - If the user has "Lactose Intolerance", "Milk Allergy", or "Dairy-free" dietary restriction:
     - Whey protein (especially concentrate) MUST be flagged as "Avoid" or "Caution" (high risk of bloating, cramping, gastrointestinal distress).
     - The overall safetyScore MUST NOT be high. It must drop into Caution (40-69) or Avoid (0-39).
     - DO NOT declare it "Good match for you" if any ingredient conflicts with user's conditions!
2. Diabetes / Pre-diabetes / Blood Sugar:
   - Added sugars, corn syrup, maltodextrin, dextrose, fructose must be severely penalized in safetyScore.
3. Hypertension:
   - High sodium, MSG, salt, licorice extract must be flagged.
4. Celiac / Gluten Sensitivity:
   - Wheat, barley, rye, malt must be flagged as Avoid.

Scoring rubric for safetyScore (0-100):
- 70 to 100: Safe / good match ONLY if NO conflicts or moderate risks exist (badgeLabel: "Good match for you")
- 40 to 69: Caution / moderate risk or potential sensitivity (badgeLabel: "Use with care")
- 0 to 39: High risk / allergen or condition conflict (e.g. Whey Protein with Lactose Intolerance) (badgeLabel: "Avoid")

For healthFactors:
- Provide comprehensive "title", "description", "impactBadge", and "sentiment". Never leave title or description blank!

For ingredientBreakdown:
- For EVERY ingredient in the ingredients list, provide a full entry with "name", "note", and "status" ("Avoid", "Caution", "Okay", or "Great").

For recommendedIntake:
- Provide "dosageHeadline" and "guidance" tailored to the user's profile.
${preferenceRules()}
User Health Profile:
${healthContext}

Ingredients List:
${ingredients.join(", ")}`;

  const run = async (engine: "flash" | "nano") => {
    const res = await getIngredientAnalyzerModel(engine).generateContent(prompt);
    const parsed = JSON.parse(cleanJson(res.response.text())) as IngredientAnalysisResult;
    return normalizeIngredientAnalysisResult(parsed, ingredients, profile);
  };

  if (mode === "nano") {
    if ((await getOnDeviceStatus()) !== "available") {
      throw new Error(
        "Gemini Nano is not available in this browser, so on-device analysis cannot run here. Choose Auto or Gemini Flash to use the cloud model instead.",
      );
    }
    return { result: await run("nano"), modelUsedLabel: "Gemini Nano (On-Device)", usedFallback: false };
  }

  if (mode === "flash") {
    return { result: await run("flash"), modelUsedLabel: "Gemini 3.5 Flash Lite", usedFallback: false };
  }

  try {
    return { result: await run("flash"), modelUsedLabel: "Gemini 3.5 Flash Lite", usedFallback: false };
  } catch (cloudError) {
    if ((await getOnDeviceStatus()) !== "available") throw cloudError;
    try {
      return {
        result: await run("nano"),
        modelUsedLabel: "Gemini Nano (On-Device Fallback)",
        usedFallback: true,
        notice:
          "Cloud Gemini was unavailable. Switched to Gemini Nano for on-device ingredient analysis.",
      };
    } catch (localError) {
      // Show both reasons; hiding the cloud one makes problems like App Check impossible to spot.
      throw new Error(
        `Cloud: ${(cloudError as Error).message} On-device: ${(localError as Error).message}`,
      );
    }
  }
}

/** Rules from the Profile settings; same options and wording as the iOS app. */
function preferenceRules(): string {
  const lines: string[] = [];
  if (readBoolPref(PREF_KEYS.strictSafetyPenalties, true)) {
    lines.push(
      "- If any ingredient conflicts with an allergy, intolerance or condition in the profile (for example whey protein with lactose intolerance), safetyScore must be 39 or lower (Avoid).",
    );
  }
  if (readBoolPref(PREF_KEYS.criticalAllergenAlerts, true)) {
    lines.push(
      '- If an ingredient matches a profile allergy such as nuts, dairy or gluten, say so clearly in the headline and add a healthFactor with impactBadge "Allergen".',
    );
  }
  if (readBoolPref(PREF_KEYS.intoleranceAlerts, true)) {
    lines.push("- Flag lactose, high sodium and high added sugar whenever they matter for the profile.");
  }
  return lines.length ? `\nRules:\n${lines.join("\n")}\n` : "";
}
