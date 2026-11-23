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
