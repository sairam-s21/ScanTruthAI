const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const fs = require("fs");
const path = require("path");

const PROJECT_ID = "scantruthai";
const RULES_PATH = path.resolve(__dirname, "../firestore.rules");

describe("ScanTruthAI Firestore Security Rules (Emulator Suite)", () => {
  let testEnv;

  beforeAll(async () => {
    const hostPort = process.env.FIRESTORE_EMULATOR_HOST
      ? process.env.FIRESTORE_EMULATOR_HOST.split(":")
      : ["127.0.0.1", "8080"];
    testEnv = await initializeTestEnvironment({
      projectId: PROJECT_ID,
      firestore: {
        rules: fs.readFileSync(RULES_PATH, "utf8"),
        host: hostPort[0],
        port: parseInt(hostPort[1], 10),
      },
    });
  });

  afterAll(async () => {
    if (testEnv) await testEnv.cleanup();
  });

  beforeEach(async () => {
    if (testEnv) await testEnv.clearFirestore();
  });

  // Valid fixture matching Android FirestoreRepository.kt
  const validProfile = {
    conditions: ["Hypertension"],
    allergies: ["Penicillin"],
    medications: ["Amlodipine 5mg"],
    dietaryRestrictions: ["Low sodium"],
    healthNotes: ["Active"],
    updatedAt: new Date(),
  };

  const validReport = {
    reportTitle: "Complete Blood Count",
    reportType: "Hematology",
    markersCount: 12,
    identifiersMasked: 3,
    maskedOnDevice: true,
    pagesScanned: 2,
    truncated: false,
    aiEngine: "CLOUD",
    savedAt: new Date(),
  };

  const validScanHistory = {
    kind: "ingredient",
    title: "Organic Granola",
    subtitle: "Cereal",
    safetyScore: 85,
    badgeLabel: "Good match",
    headline: "Healthy choice",
    ingredientCount: 14,
    aiEngine: "CLOUD",
    scannedAt: new Date(),
  };

  describe("1. Authenticated Owner Access", () => {
    it("allows owner to read and write their own medical profile", async () => {
      const db = testEnv.authenticatedContext("user_123").firestore();
      const ref = db.doc("users/user_123/medicalProfile/current");

      await assertSucceeds(ref.set(validProfile));
      await assertSucceeds(ref.get());
      await assertSucceeds(ref.delete());
    });

    it("allows owner to create and read their medical reports", async () => {
      const db = testEnv.authenticatedContext("user_123").firestore();
      const col = db.collection("users/user_123/medicalReports");

      await assertSucceeds(col.add(validReport));
      await assertSucceeds(col.get());
    });

    it("allows owner to create and read scan history", async () => {
      const db = testEnv.authenticatedContext("user_123").firestore();
      const col = db.collection("users/user_123/scanHistory");

      await assertSucceeds(col.add(validScanHistory));
      await assertSucceeds(col.get());
    });
  });

  describe("2. Cross-User Access Denial", () => {
    it("denies user A from reading or modifying user B's profile", async () => {
      const db = testEnv.authenticatedContext("attacker_456").firestore();
      const ref = db.doc("users/victim_123/medicalProfile/current");

      await assertFails(ref.get());
      await assertFails(ref.set(validProfile));
      await assertFails(ref.delete());
    });

    it("denies user A from reading user B's reports or scans", async () => {
      const db = testEnv.authenticatedContext("attacker_456").firestore();
      await assertFails(db.collection("users/victim_123/medicalReports").get());
      await assertFails(db.collection("users/victim_123/scanHistory").get());
    });
  });

  describe("3. Unauthenticated Access Denial", () => {
    it("denies unauthenticated guests from accessing any collection", async () => {
      const db = testEnv.unauthenticatedContext().firestore();
      await assertFails(db.doc("users/user_123/medicalProfile/current").get());
      await assertFails(db.collection("users/user_123/medicalReports").get());
      await assertFails(db.collection("users/user_123/scanHistory").get());
    });
  });

  describe("4. Invalid Schema Rejection", () => {
    it("rejects medical reports containing raw OCR text", async () => {
      const db = testEnv.authenticatedContext("user_123").firestore();
      const ref = db.collection("users/user_123/medicalReports").doc();

      const tainted = { ...validReport, rawOcrText: "Patient Name: Secret PII" };
      await assertFails(ref.set(tainted));
    });

    it("rejects medical reports with maskedOnDevice == false", async () => {
      const db = testEnv.authenticatedContext("user_123").firestore();
      const ref = db.collection("users/user_123/medicalReports").doc();

      const unmasked = { ...validReport, maskedOnDevice: false };
      await assertFails(ref.set(unmasked));
    });

    it("rejects scan history with out-of-range safety scores", async () => {
      const db = testEnv.authenticatedContext("user_123").firestore();
      const ref = db.collection("users/user_123/scanHistory").doc();

      await assertFails(ref.set({ ...validScanHistory, safetyScore: 120 }));
      await assertFails(ref.set({ ...validScanHistory, safetyScore: -5 }));
    });
  });

  // Shared schema (SPEC.md) payloads written by the iOS, web and Android apps
  const sharedScan = {
    productName: "Organic Oat Bar",
    productCategory: "food",
    safetyScore: 86,
    badgeLabel: "Good match for you",
    headline: "Safe with minor sugar note",
    aiSummary: "Whole grain oats provide slow-release fiber.",
    healthFactors: [
      {
        title: "Blood Sugar Impact",
        description: "Moderate glycemic load.",
        impactBadge: "Caution",
        sentiment: "warning",
      },
    ],
    ingredientBreakdown: [{ name: "Whole grain oats", note: "High beta-glucan.", status: "Great" }],
    recommendedIntake: { dosageHeadline: "1 bar", guidance: "Pair with protein." },
    rawIngredients: ["Whole grain oats", "Honey"],
    modelUsed: "Gemini 3.5 Flash Lite",
    scannedAt: new Date(),
  };

  const sharedReport = {
    reportTitle: "Blood test report",
    markersCount: 2,
    maskedOnDevice: true,
    uploadedAt: new Date(),
  };

  const healthNote = {
    userNote: "I was diagnosed with high blood pressure last year",
    extractedSummary: "High blood pressure",
    createdAt: new Date(),
  };

  describe("6. Shared Schema Across Platforms", () => {
    it("accepts shared-schema scans from iOS, web and Android", async () => {
      const col = testEnv.authenticatedContext("user_123").firestore().collection("users/user_123/scanHistory");
      await assertSucceeds(col.add(sharedScan));
      await assertSucceeds(col.add({ ...sharedScan, kind: "ingredient" }));
    });

    it("still accepts scans from Android builds before the shared schema", async () => {
      const col = testEnv.authenticatedContext("user_123").firestore().collection("users/user_123/scanHistory");
      await assertSucceeds(col.add(validScanHistory));
    });

    it("rejects scans without a name, with unknown fields, or with a text intake", async () => {
      const col = testEnv.authenticatedContext("user_123").firestore().collection("users/user_123/scanHistory");
      const { productName, ...nameless } = sharedScan;
      await assertFails(col.add(nameless));
      await assertFails(col.add({ ...sharedScan, rawOcrText: "label text" }));
      await assertFails(col.add({ ...sharedScan, recommendedIntake: "1 bar" }));
    });

    it("accepts partial profile merges from web and iOS", async () => {
      const ref = testEnv.authenticatedContext("user_123").firestore().doc("users/user_123/medicalProfile/current");
      await assertSucceeds(ref.set({ allergies: ["Peanuts"], updatedAt: new Date() }, { merge: true }));
      await assertSucceeds(
        ref.set(
          {
            conditions: ["Pre-diabetic"],
            healthMarkers: [{ category: "Glucose", value: "Pre-diabetic", tone: "coral", iconType: "glucose" }],
            markersCount: 1,
            reportTitle: "Blood test report",
            updatedAt: new Date(),
          },
          { merge: true },
        ),
      );
      // Android then writes its full profile into the same document.
      await assertSucceeds(ref.set(validProfile, { merge: true }));
    });

    it("rejects profiles with unknown fields", async () => {
      const ref = testEnv.authenticatedContext("user_123").firestore().doc("users/user_123/medicalProfile/current");
      await assertFails(ref.set({ ...validProfile, patientName: "Ramesh Sharma" }));
    });

    it("accepts report metadata but never report text", async () => {
      const col = testEnv.authenticatedContext("user_123").firestore().collection("users/user_123/medicalReports");
      await assertSucceeds(col.add(sharedReport));
      await assertFails(col.add({ ...sharedReport, redactedText: "[REDACTED_NAME] ..." }));
      const { uploadedAt, ...undated } = sharedReport;
      await assertFails(col.add(undated));
    });

    it("allows owners to add health notes, append-only", async () => {
      const db = testEnv.authenticatedContext("user_123").firestore();
      const ref = db.collection("users/user_123/healthNotes").doc("note1");
      await assertSucceeds(ref.set(healthNote));
      await assertFails(ref.update({ extractedSummary: "Changed" }));
      await assertFails(db.collection("users/user_123/healthNotes").add({ ...healthNote, rawText: "PII" }));
      await assertFails(
        testEnv.authenticatedContext("attacker_456").firestore().collection("users/user_123/healthNotes").get(),
      );
    });
  });

  describe("5. Append-Only Audit Records", () => {
    it("denies updating existing medical reports", async () => {
      const adminDb = testEnv.authenticatedContext("user_123").firestore();
      const ref = adminDb.collection("users/user_123/medicalReports").doc("rep1");
      await assertSucceeds(ref.set(validReport));

      // Attempting to modify existing report
      await assertFails(ref.update({ reportTitle: "Tampered Title" }));
    });

    it("denies updating existing scan history entries", async () => {
      const adminDb = testEnv.authenticatedContext("user_123").firestore();
      const ref = adminDb.collection("users/user_123/scanHistory").doc("scan1");
      await assertSucceeds(ref.set(validScanHistory));

      // Attempting to modify existing scan history
      await assertFails(ref.update({ safetyScore: 99 }));
    });
  });
});
