// src/services/firestoreService.ts
import {
  collection,
  doc,
  addDoc,
  setDoc,
  deleteDoc,
  onSnapshot,
  query,
  orderBy,
  limit,
  serverTimestamp,
  Timestamp,
  arrayUnion,
  arrayRemove,
} from "firebase/firestore";
import type { ProfileListField } from "./health";
import { db, type ExtractedMedicalProfile, type IngredientAnalysisResult } from "./firebase";

export interface StoredMedicalReport {
  id: string;
  reportTitle: string;
  markersCount: number;
  maskedOnDevice: boolean;
  /** Shared field; older Android builds wrote only savedAt. */
  uploadedAt?: Timestamp | null;
  savedAt?: Timestamp | null;
}

export interface StoredScanRecord extends IngredientAnalysisResult {
  id: string;
  rawIngredients: string[];
  modelUsed: string;
  scannedAt: Timestamp | null;
  kind?: string;
}

const millis = (t?: Timestamp | null) => t?.toMillis?.() ?? 0;

// ── Real-Time Listeners ──────────────────────────────────────────────────

export function subscribeToMedicalProfile(
  uid: string,
  callback: (profile: ExtractedMedicalProfile | null) => void,
) {
  const ref = doc(db, "users", uid, "medicalProfile", "current");
  return onSnapshot(ref, (snap) => {
    callback(snap.exists() ? (snap.data() as ExtractedMedicalProfile) : null);
  });
}

/**
 * Sorted here rather than with orderBy: Firestore drops documents that lack the sort
 * field, and reports from different app versions use uploadedAt or savedAt.
 */
export function subscribeToMedicalReports(
  uid: string,
  callback: (reports: StoredMedicalReport[]) => void,
) {
  return onSnapshot(collection(db, "users", uid, "medicalReports"), (snap) => {
    const items = snap.docs
      .map((d) => ({ id: d.id, ...(d.data() as Omit<StoredMedicalReport, "id">) }))
      .sort((a, b) => millis(b.uploadedAt ?? b.savedAt) - millis(a.uploadedAt ?? a.savedAt));
    callback(items);
  });
}

export function subscribeToScanHistory(
  uid: string,
  callback: (scans: StoredScanRecord[]) => void,
  maxItems = 20,
) {
  const q = query(
    collection(db, "users", uid, "scanHistory"),
    orderBy("scannedAt", "desc"),
    limit(maxItems),
  );
  return onSnapshot(q, (snap) => {
    const items = snap.docs
      .map((d) => ({ id: d.id, ...(d.data() as Omit<StoredScanRecord, "id">) }))
      // Older Android builds also logged reports here; reports are listed in My Health.
      .filter((s) => s.kind !== "medical_report");
    callback(items);
  });
}

// ── Write & Delete Helpers ───────────────────────────────────────────────

function profileMerge(extracted: ExtractedMedicalProfile) {
  return {
    ...(extracted.healthMarkers?.length ? { healthMarkers: arrayUnion(...extracted.healthMarkers) } : {}),
    ...(extracted.conditions?.length ? { conditions: arrayUnion(...extracted.conditions) } : {}),
    ...(extracted.allergies?.length ? { allergies: arrayUnion(...extracted.allergies) } : {}),
    ...(extracted.medications?.length ? { medications: arrayUnion(...extracted.medications) } : {}),
    ...(extracted.dietaryRestrictions?.length
      ? { dietaryRestrictions: arrayUnion(...extracted.dietaryRestrictions) }
      : {}),
    updatedAt: serverTimestamp(),
  };
}

/** Only report metadata is stored; the report text, even redacted, never leaves the session. */
export async function saveMedicalReportAndMergeProfile(uid: string, extracted: ExtractedMedicalProfile) {
  await setDoc(doc(db, "users", uid, "medicalProfile", "current"), profileMerge(extracted), { merge: true });

  await addDoc(collection(db, "users", uid, "medicalReports"), {
    reportTitle: (extracted.reportTitle || "Medical Report").slice(0, 200),
    markersCount: Math.min(extracted.markersCount || extracted.healthMarkers?.length || 0, 500),
    maskedOnDevice: true,
    uploadedAt: serverTimestamp(),
  });
}

export async function saveTypedHealthNoteAndMergeProfile(
  uid: string,
  redactedNote: string,
  extracted: ExtractedMedicalProfile,
) {
  await addDoc(collection(db, "users", uid, "healthNotes"), {
    userNote: redactedNote.slice(0, 5000),
    extractedSummary: (extracted.conditions?.join(", ") || extracted.reportTitle || "").slice(0, 1000),
    createdAt: serverTimestamp(),
  });

  await setDoc(doc(db, "users", uid, "medicalProfile", "current"), profileMerge(extracted), { merge: true });
}

/** Writes exactly the shared scan schema (SPEC.md 2D), trimmed to the firestore.rules limits. */
export async function saveScanResult(
  uid: string,
  analysis: IngredientAnalysisResult,
  rawIngredients: string[],
  modelUsed: string,
) {
  const docRef = await addDoc(collection(db, "users", uid, "scanHistory"), {
    productName: (analysis.productName || "Scanned product").slice(0, 200),
    productCategory: (analysis.productCategory || "food").slice(0, 50),
    safetyScore: Math.round(Math.min(Math.max(analysis.safetyScore, 0), 100)),
    badgeLabel: analysis.badgeLabel.slice(0, 50),
    headline: (analysis.headline || "").slice(0, 500),
    aiSummary: (analysis.aiSummary || "").slice(0, 4000),
    healthFactors: (analysis.healthFactors || []).slice(0, 40).map((f) => ({
      title: f.title || "",
      description: f.description || "",
      impactBadge: f.impactBadge || "",
      sentiment: f.sentiment || "neutral",
    })),
    ingredientBreakdown: (analysis.ingredientBreakdown || []).slice(0, 100).map((i) => ({
      name: i.name || "",
      note: i.note || "",
      status: i.status || "Okay",
    })),
    recommendedIntake: {
      dosageHeadline: analysis.recommendedIntake?.dosageHeadline || "",
      guidance: analysis.recommendedIntake?.guidance || "",
    },
    rawIngredients: rawIngredients.slice(0, 500),
    modelUsed: modelUsed.slice(0, 100),
    scannedAt: serverTimestamp(),
  });
  return docRef.id;
}

export async function addProfileItem(uid: string, field: ProfileListField, item: string) {
  await setDoc(
    doc(db, "users", uid, "medicalProfile", "current"),
    { [field]: arrayUnion(item), updatedAt: serverTimestamp() },
    { merge: true },
  );
}

export async function removeProfileItem(uid: string, field: ProfileListField, item: string) {
  await setDoc(
    doc(db, "users", uid, "medicalProfile", "current"),
    { [field]: arrayRemove(item), updatedAt: serverTimestamp() },
    { merge: true },
  );
}

export async function deleteScanRecord(uid: string, scanId: string) {
  await deleteDoc(doc(db, "users", uid, "scanHistory", scanId));
}
