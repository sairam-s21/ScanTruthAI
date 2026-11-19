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
} from "firebase/firestore";
import {
  db,
  ExtractedMedicalProfile,
  IngredientAnalysisResult,
} from "../firebase";

export interface StoredMedicalReport {
  id: string;
  reportTitle: string;
  markersCount: number;
  maskedOnDevice: boolean;
  redactedText: string;
  uploadedAt: Timestamp | null;
}

export interface StoredScanRecord extends IngredientAnalysisResult {
  id: string;
  rawIngredients: string[];
  modelUsed: string;
  scannedAt: Timestamp | null;
}

// ── Real-Time Listeners ──────────────────────────────────────────────────

export function subscribeToMedicalProfile(
  uid: string,
  callback: (profile: ExtractedMedicalProfile | null) => void
) {
  const ref = doc(db, "users", uid, "medicalProfile", "current");
  return onSnapshot(ref, (snap) => {
    callback(snap.exists() ? (snap.data() as ExtractedMedicalProfile) : null);
  });
}

export function subscribeToMedicalReports(
  uid: string,
  callback: (reports: StoredMedicalReport[]) => void
) {
  const q = query(
    collection(db, "users", uid, "medicalReports"),
    orderBy("uploadedAt", "desc")
  );
  return onSnapshot(q, (snap) => {
    const items = snap.docs.map((d) => ({
      id: d.id,
      ...(d.data() as Omit<StoredMedicalReport, "id">),
    }));
    callback(items);
  });
}

export function subscribeToScanHistory(
  uid: string,
  callback: (scans: StoredScanRecord[]) => void,
  maxItems = 20
) {
  const q = query(
    collection(db, "users", uid, "scanHistory"),
    orderBy("scannedAt", "desc"),
    limit(maxItems)
  );
  return onSnapshot(q, (snap) => {
    const items = snap.docs.map((d) => ({
      id: d.id,
      ...(d.data() as Omit<StoredScanRecord, "id">),
    }));
    callback(items);
  });
}

// ── Write & Delete Helpers ───────────────────────────────────────────────

export async function saveMedicalReportAndMergeProfile(
  uid: string,
  extracted: ExtractedMedicalProfile,
  redactedText: string
) {
  const profileRef = doc(db, "users", uid, "medicalProfile", "current");
  await setDoc(
    profileRef,
    {
      ...(extracted.healthMarkers?.length
        ? { healthMarkers: arrayUnion(...extracted.healthMarkers) }
        : {}),
      ...(extracted.conditions?.length
        ? { conditions: arrayUnion(...extracted.conditions) }
        : {}),
      ...(extracted.allergies?.length
        ? { allergies: arrayUnion(...extracted.allergies) }
        : {}),
      ...(extracted.medications?.length
        ? { medications: arrayUnion(...extracted.medications) }
        : {}),
      ...(extracted.dietaryRestrictions?.length
        ? { dietaryRestrictions: arrayUnion(...extracted.dietaryRestrictions) }
        : {}),
      updatedAt: serverTimestamp(),
    },
    { merge: true }
  );

  await addDoc(collection(db, "users", uid, "medicalReports"), {
    reportTitle: extracted.reportTitle || "Medical Report",
    markersCount: extracted.markersCount || extracted.healthMarkers?.length || 0,
    maskedOnDevice: true,
    redactedText,
    uploadedAt: serverTimestamp(),
  });
}

export async function saveTypedHealthNoteAndMergeProfile(
  uid: string,
  rawNote: string,
  extracted: ExtractedMedicalProfile
) {
  await addDoc(collection(db, "users", uid, "healthNotes"), {
    userNote: rawNote,
    extractedSummary:
      extracted.conditions?.join(", ") || extracted.reportTitle || rawNote,
    createdAt: serverTimestamp(),
  });

  const profileRef = doc(db, "users", uid, "medicalProfile", "current");
  await setDoc(
    profileRef,
    {
      ...(extracted.healthMarkers?.length
        ? { healthMarkers: arrayUnion(...extracted.healthMarkers) }
        : {}),
      ...(extracted.conditions?.length
        ? { conditions: arrayUnion(...extracted.conditions) }
        : {}),
      ...(extracted.allergies?.length
        ? { allergies: arrayUnion(...extracted.allergies) }
        : {}),
      ...(extracted.medications?.length
        ? { medications: arrayUnion(...extracted.medications) }
        : {}),
      ...(extracted.dietaryRestrictions?.length
        ? { dietaryRestrictions: arrayUnion(...extracted.dietaryRestrictions) }
        : {}),
      updatedAt: serverTimestamp(),
    },
    { merge: true }
  );
}

export async function saveScanResult(
  uid: string,
  analysis: IngredientAnalysisResult,
  rawIngredients: string[],
  modelUsed: string
) {
  const docRef = await addDoc(collection(db, "users", uid, "scanHistory"), {
    ...analysis,
    rawIngredients,
    modelUsed,
    scannedAt: serverTimestamp(),
  });
  return docRef.id;
}

export async function deleteScanRecord(uid: string, scanId: string) {
  await deleteDoc(doc(db, "users", uid, "scanHistory", scanId));
}