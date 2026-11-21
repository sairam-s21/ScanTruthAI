// src/services/aiPipelineService.ts
import {
  getPrivacyGateModel,
  getProfileExtractorModel,
  getIngredientAnalyzerModel,
  getIngredientClassifierModel,
  PrivacyGateResult,
  ExtractedMedicalProfile,
  IngredientAnalysisResult,
  IngredientBreakdownItem,
  HealthMarker,
  HealthFactor,
  DetectedPiiItem,
} from "./firebase";
import { cleanAndFilterIngredientsFromText } from "./ocrService";

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
      "On-device Gemini Nano is not supported in this browser. Switched to Local Rule-Based Privacy Shield and Cloud Gemini Flash for analysis.",
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

// Stage 1.5: Intelligent OCR Ingredient Extraction with AI & Heuristic Shield
export async function extractIngredientsWithAI(rawOcrText: string): Promise<string[]> {
  if (!rawOcrText || rawOcrText.trim().length < 3) return [];

  const prompt = `You are a specialized Food, Supplement, and Pharmaceutical Packaging Analyst.
Given the raw OCR text extracted from a product label, extract ONLY the genuine edible or active product ingredients.

CRITICAL EXCLUSION RULES (Do NOT include any of the following):
- Manufacturer, packer, marketer, or distributor names and addresses (e.g., "Manufactured by", "Marketed by", "Plot No", "Industrial Area", "Pvt Ltd")
- License & regulatory numbers (e.g., "FSSAI", "FDA", "Lic No", "ISO", "GMP", "Halal")
- Batch, lot, barcodes, serial numbers, QR codes
- Manufacturing dates, expiry dates, "Best before", "Use by"
- Pricing, currency, MRP, taxes
- Net weight, net quantity, serving size, gross weight (e.g., "500g", "Net Wt 1kg")
- Nutrition table values and facts (e.g., "Calories 120", "Total fat 2g", "Protein 25g", "% Daily Value")
- Storage instructions, directions for use, precautions, disclaimers
- Marketing slogans, branding statements (e.g., "100% pure", "Immunity booster")

Return ONLY valid JSON with format:
{
  "ingredients": ["ingredient 1", "ingredient 2", ...]
}

Raw label text:
${rawOcrText.slice(0, 4000)}`;

  try {
    const model = getIngredientClassifierModel();
    const res = await model.generateContent(prompt);
    const cleaned = res.response
      .text()
      .replace(/```json|```/g, "")
      .trim();
    const parsed = JSON.parse(cleaned) as { ingredients?: string[] };
    if (parsed && Array.isArray(parsed.ingredients) && parsed.ingredients.length > 0) {
      return parsed.ingredients
        .map((i) => i.trim())
        .filter((i) => i.length >= 2 && i.length <= 60 && /[a-zA-Z]/.test(i));
    }
  } catch (err) {
    console.warn("AI ingredient classification skipped, using heuristic cleaner:", err);
  }
  return cleanAndFilterIngredientsFromText(rawOcrText);
}

// Unified fast intelligent ingredient extractor
export async function extractIngredientsIntelligently(rawOcrText: string): Promise<string[]> {
  try {
    const aiList = await extractIngredientsWithAI(rawOcrText);
    if (aiList && aiList.length > 0) {
      return aiList;
    }
  } catch {
    // Graceful fallback to heuristic cleaner
  }
  return cleanAndFilterIngredientsFromText(rawOcrText);
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

User Health Profile:
${healthContext}

Ingredients List:
${ingredients.join(", ")}`;

  try {
    const model = getIngredientAnalyzerModel(mode);
    const res = await model.generateContent(prompt);
    const cleaned = res.response
      .text()
      .replace(/```json|```/g, "")
      .trim();
    const parsed = JSON.parse(cleaned) as IngredientAnalysisResult;
    const normalized = normalizeIngredientAnalysisResult(parsed, ingredients, profile);

    const modelUsedLabel =
      mode === "nano" || (mode === "auto" && !navigator.onLine)
        ? "Gemini Nano (On-Device)"
        : "Gemini 3.5 Flash Lite";

    return {
      result: normalized,
      modelUsedLabel,
      usedFallback: false,
    };
  } catch (err) {
    if (mode !== "flash") {
      // Fallback gracefully to Cloud Gemini Flash if local Nano fails or is unavailable
      const cloudModel = getIngredientAnalyzerModel("flash");
      const fallbackRes = await cloudModel.generateContent(prompt);
      const cleaned = fallbackRes.response
        .text()
        .replace(/```json|```/g, "")
        .trim();
      const parsed = JSON.parse(cleaned) as IngredientAnalysisResult;
      const normalized = normalizeIngredientAnalysisResult(parsed, ingredients, profile);

      return {
        result: normalized,
        modelUsedLabel: "Gemini 3.5 Flash Lite (Cloud Fallback)",
        usedFallback: true,
        notice:
          "On-device Gemini Nano is not supported on this browser/device. Switched to Cloud Gemini Flash for ingredient analysis.",
      };
    }
    throw err;
  }
}
