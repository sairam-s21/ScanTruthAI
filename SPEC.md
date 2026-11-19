# ScanTruthAI — Cross-Platform Architecture & Schema Specification

## 1. System Overview & Hybrid AI Pipeline
ScanTruthAI is a privacy-first food and medicine ingredient analyzer built on **Firebase (`scantruthai`)** and **Firebase AI Logic**. Every platform executes the exact same 4-stage pipeline:

1. **Stage 1 — Local OCR & PDF Extraction (100% On-Device):**
   * **Web:** `tesseract.js` (images) + `pdfjs-dist` (digital PDFs)
   * **Android:** Google ML Kit `text-recognition:16.0.1` + `PdfRenderer`
   * **iOS:** Apple `Vision` (`VNRecognizeTextRequest`) + `PDFKit`
2. **Stage 2 — On-Device Privacy Gate (`ONLY_ON_DEVICE`):**
   * Detects and masks PII (names, DOB, phone numbers, emails, addresses, patient/hospital IDs) locally before any cloud call.
   * **Web / Android:** Gemini Nano (`InferenceMode.ONLY_ON_DEVICE`) with regex fallback.
   * **iOS:** Apple Foundation Models (`FirebaseAI.SystemLanguageModel.default`).
3. **Stage 3 — Cloud Medical Profile & Health Note Extraction (`ONLY_IN_CLOUD`):**
   * Model: **`gemini-3.5-flash-lite`**
   * Converts anonymized medical report text or typed user health notes into structured health markers, conditions, allergies, medications, and dietary restrictions.
4. **Stage 4 — Hybrid Ingredient Risk Analyzer (`auto` | `flash` | `nano`):**
   * **`auto` (`PREFER_IN_CLOUD`):** Uses `gemini-3.5-flash-lite` when online and automatically falls back to on-device Gemini Nano / Apple Foundation Models offline.
   * **`flash` (`ONLY_IN_CLOUD`):** Forces `gemini-3.5-flash-lite` for cloud inference.
   * **`nano` (`ONLY_ON_DEVICE`):** Runs 100% locally on the device with zero cloud traffic.

---

## 2. Shared Firestore Data Schema (`users/{uid}/...`)
All three clients read and write to the exact same Firestore paths under the signed-in Firebase Auth `uid`. Do **not** use Firebase Cloud Storage; anonymized report text and structured markers are stored directly in Firestore.

### A. Active Health Context: `users/{uid}/medicalProfile/current`
Single merged document representing the user's current health state:
```json
{
  "reportTitle": "Blood test report",
  "markersCount": 2,
  "healthMarkers": [
    {
      "category": "Glucose",
      "value": "Pre-diabetic",
      "tone": "coral",
      "iconType": "glucose"
    },
    {
      "category": "Allergies",
      "value": "Peanuts",
      "tone": "coral",
      "iconType": "allergy"
    }
  ],
  "conditions": ["Pre-diabetic"],
  "allergies": ["Peanuts"],
  "medications": [],
  "dietaryRestrictions": ["Low added sugar"],
  "updatedAt": "<serverTimestamp>"
}
```
* Allowed `tone` values: `"coral" | "green" | "blue"`
* Allowed `iconType` values: `"glucose" | "allergy" | "heart" | "medication"`

### B. Uploaded Medical Reports: `users/{uid}/medicalReports/{reportId}`
Created automatically when a user uploads a PDF/image medical report and approves the on-device privacy check:
```json
{
  "reportTitle": "Blood test report",
  "markersCount": 2,
  "maskedOnDevice": true,
  "redactedText": "Patient Name: [REDACTED_NAME], DOB: [REDACTED_DOB]...",
  "uploadedAt": "<serverTimestamp>"
}
```

### C. Typed Health Notes: `users/{uid}/healthNotes/{noteId}`
Created automatically when a user types a health condition in *"Tell AI about your health"* and clicks **Update my conditions**:
```json
{
  "userNote": "I was diagnosed with high blood pressure last year",
  "extractedSummary": "High blood pressure",
  "createdAt": "<serverTimestamp>"
}
```

### D. Scan History: `users/{uid}/scanHistory/{scanId}`
Created automatically when a user scans or enters product ingredients and clicks **Analyze ingredients**:
```json
{
  "productName": "Organic Oat Bar",
  "productCategory": "food",
  "safetyScore": 86,
  "badgeLabel": "Good match for you",
  "headline": "Safe with minor sugar note",
  "aiSummary": "Whole grain oats provide slow-release fiber...",
  "healthFactors": [
    {
      "title": "Blood Sugar Impact",
      "description": "Moderate glycemic load from honey; keep to 1 bar.",
      "impactBadge": "Caution",
      "sentiment": "warning"
    }
  ],
  "ingredientBreakdown": [
    {
      "name": "Whole grain oats",
      "note": "High beta-glucan soluble fiber supports steady glucose.",
      "status": "Great"
    }
  ],
  "recommendedIntake": {
    "dosageHeadline": "1 bar (40g) per serving",
    "guidance": "Pair with protein or unsweetened yogurt to blunt glucose spikes."
  },
  "rawIngredients": ["Whole grain oats", "Honey", "Almonds"],
  "modelUsed": "Gemini 3.5 Flash Lite",
  "scannedAt": "<serverTimestamp>"
}
```
* **Score Rubric (`safetyScore`):**
  * `70–100`: `"Good match for you"`
  * `40–69`: `"Use with care"`
  * `0–39`: `"Avoid"`

---

## 3. Firestore Security Rules
```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /users/{userId}/{document=**} {
      allow read, write: if request.auth != null && request.auth.uid == userId;
    }
  }
}
```

---

## 4. Team Setup & Credential Handoff Rules
* **Never commit credentials:** `**/google-services.json`, `**/GoogleService-Info.plist`, and `.env` are gitignored. Request them privately from the repo owner.
* **App Check Debug Tokens:** Firebase AI Logic enforces App Check on all platforms. Use your platform's debug token (`Web`, `Android-Dev-1`, or `iOS-Dev-1`) in local builds.