import { useCallback, useEffect, useMemo, useState } from "react";
import { onAuthStateChanged, signInWithPopup, signOut, type User } from "firebase/auth";
import { auth, googleProvider, type ExtractedMedicalProfile } from "./firebase";
import {
  subscribeToMedicalProfile,
  subscribeToMedicalReports,
  subscribeToScanHistory,
  type StoredMedicalReport,
  type StoredScanRecord,
} from "./firestoreService";
import { showDigestIfDue } from "./preferences";
import { Icon, type IconName, type ScanRecordView, toAnalysisView } from "./ui";
import HomeView from "./HomeView";
import ScanView from "./ScanView";
import MyHealthView, { type HealthLists } from "./MyHealthView";
import ProfileView from "./ProfileView";
import { AnalysisResultSheet, HistorySheet } from "./ResultSheets";

type Tab = "home" | "scan" | "myHealth" | "profile";

const TABS: [Tab, IconName, string][] = [
  ["home", "leaf", "Home"],
  ["scan", "scan", "Scan"],
  ["myHealth", "heartFile", "My Health"],
  ["profile", "user", "Profile"],
];

const EMPTY_LISTS: HealthLists = {
  conditions: [],
  allergies: [],
  medications: [],
  dietaryRestrictions: [],
};

function toRecord(scan: StoredScanRecord): ScanRecordView {
  return {
    id: scan.id,
    result: toAnalysisView(scan as unknown as Record<string, unknown>),
    ingredients: Array.isArray(scan.rawIngredients) ? scan.rawIngredients : [],
    modelUsedLabel: typeof scan.modelUsed === "string" ? scan.modelUsed : "Saved analysis",
    scannedAt: scan.scannedAt?.toDate?.() ?? null,
  };
}

export interface OpenResult {
  record: ScanRecordView;
  fallbackNotice?: string;
}

export default function App() {
  const [user, setUser] = useState<User | null>(null);
  const [authLoading, setAuthLoading] = useState(true);
  const [authWorking, setAuthWorking] = useState(false);
  const [authError, setAuthError] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>("home");
  const [openReportPicker, setOpenReportPicker] = useState(false);

  const [cloudProfile, setCloudProfile] = useState<ExtractedMedicalProfile | null>(null);
  const [cloudReports, setCloudReports] = useState<StoredMedicalReport[]>([]);
  const [cloudScans, setCloudScans] = useState<StoredScanRecord[]>([]);

  // Session-only data for a signed-out visitor; never written anywhere.
  const [guestLists, setGuestLists] = useState<HealthLists>(EMPTY_LISTS);
  const [guestReports, setGuestReports] = useState<string[]>([]);
  const [latestScan, setLatestScan] = useState<ScanRecordView | null>(null);

  const [ingredients, setIngredients] = useState<string[]>([]);
  const [openResult, setOpenResult] = useState<OpenResult | null>(null);
  const [showHistory, setShowHistory] = useState(false);

  useEffect(() => {
    showDigestIfDue();
    return onAuthStateChanged(auth, (current) => {
      setUser(current);
      setAuthLoading(false);
    });
  }, []);

  useEffect(() => {
    // Health data on screen must always belong to the account signed in now.
    setCloudProfile(null);
    setCloudReports([]);
    setCloudScans([]);
    setLatestScan(null);
    if (!user) return;
    const unsubs = [
      subscribeToMedicalProfile(user.uid, setCloudProfile),
      subscribeToMedicalReports(user.uid, setCloudReports),
      subscribeToScanHistory(user.uid, setCloudScans, 50),
    ];
    return () => unsubs.forEach((u) => u());
  }, [user]);

  const lists: HealthLists = useMemo(
    () =>
      user
        ? {
            conditions: cloudProfile?.conditions ?? [],
            allergies: cloudProfile?.allergies ?? [],
            medications: cloudProfile?.medications ?? [],
            dietaryRestrictions: cloudProfile?.dietaryRestrictions ?? [],
          }
        : guestLists,
    [user, cloudProfile, guestLists],
  );

  const analysisProfile: ExtractedMedicalProfile = useMemo(
    () => ({
      reportTitle: cloudProfile?.reportTitle ?? "",
      markersCount: cloudProfile?.markersCount ?? 0,
      healthMarkers: user ? (cloudProfile?.healthMarkers ?? []) : [],
      ...lists,
    }),
    [user, cloudProfile, lists],
  );

  const reportTitles = user ? cloudReports.map((r) => r.reportTitle) : guestReports;
  const history = useMemo(() => cloudScans.map(toRecord), [cloudScans]);

  const handleSignIn = useCallback(async () => {
    setAuthWorking(true);
    setAuthError(null);
    try {
      await signInWithPopup(auth, googleProvider);
    } catch (err) {
      const code = (err as { code?: string }).code;
      setAuthError(
        code === "auth/popup-closed-by-user" || code === "auth/cancelled-popup-request"
          ? "Sign-in was not completed."
          : `Sign-in failed: ${(err as Error).message}`,
      );
    } finally {
      setAuthWorking(false);
    }
  }, []);

  const handleSignOut = useCallback(async () => {
    try {
      await signOut(auth);
      setGuestLists(EMPTY_LISTS);
      setGuestReports([]);
      setIngredients([]);
      setAuthError(null);
    } catch (err) {
      setAuthError(`Sign-out failed: ${(err as Error).message}`);
    }
  }, []);

  const clearSession = useCallback(() => {
    setGuestLists(EMPTY_LISTS);
    setGuestReports([]);
    setLatestScan(null);
    setIngredients([]);
    setOpenResult(null);
  }, []);

  return (
    <div className="app">
      <main className="screen">
        {tab === "home" && (
          <HomeView
            user={user}
            lists={lists}
            history={history}
            latestScan={latestScan}
            onOpenScanner={() => setTab("scan")}
            onCompleteProfile={() => setTab("myHealth")}
            onAnalyseReport={() => {
              setOpenReportPicker(true);
              setTab("myHealth");
            }}
            onShowHistory={() => setShowHistory(true)}
            onOpenRecord={(record) => setOpenResult({ record })}
          />
        )}
        {tab === "scan" && (
          <ScanView
            user={user}
            ingredients={ingredients}
            setIngredients={setIngredients}
            profile={analysisProfile}
            onBack={() => setTab("home")}
            onResult={(record, fallbackNotice) => {
              setLatestScan(record);
              setOpenResult({ record, fallbackNotice });
            }}
          />
        )}
        {tab === "myHealth" && (
          <MyHealthView
            user={user}
            lists={lists}
            reportTitles={reportTitles}
            setGuestLists={setGuestLists}
            addGuestReport={(title) => setGuestReports((prev) => [...prev, title])}
            openReportPicker={openReportPicker}
            onReportPickerOpened={() => setOpenReportPicker(false)}
          />
        )}
        {tab === "profile" && (
          <ProfileView
            user={user}
            authLoading={authLoading}
            authWorking={authWorking}
            authError={authError}
            onSignIn={handleSignIn}
            onSignOut={handleSignOut}
            onClearSession={clearSession}
          />
        )}
      </main>

      <nav className="tab-bar" aria-label="Main">
        {TABS.map(([id, icon, label]) => (
          <button
            key={id}
            className={tab === id ? "selected" : ""}
            aria-current={tab === id ? "page" : undefined}
            onClick={() => setTab(id)}
          >
            <Icon name={icon} size={21} />
            <span>{label}</span>
          </button>
        ))}
      </nav>

      {showHistory && (
        <HistorySheet
          user={user}
          history={history}
          onClose={() => setShowHistory(false)}
          onOpen={(record) => setOpenResult({ record })}
        />
      )}
      {openResult && (
        <AnalysisResultSheet
          record={openResult.record}
          fallbackNotice={openResult.fallbackNotice}
          onClose={() => setOpenResult(null)}
        />
      )}
    </div>
  );
}
