// src/App.tsx
import { useEffect, useMemo, useRef, useState } from "react";
import {
  onAuthStateChanged,
  signInWithPopup,
  signOut,
  User,
} from "firebase/auth";
import {
  auth,
  googleProvider,
  ExtractedMedicalProfile,
  IngredientAnalysisResult,
  PrivacyGateResult,
} from "./firebase";
import {
  subscribeToMedicalProfile,
  subscribeToMedicalReports,
  subscribeToScanHistory,
  saveMedicalReportAndMergeProfile,
  saveTypedHealthNoteAndMergeProfile,
  saveScanResult,
  deleteScanRecord,
  StoredMedicalReport,
  StoredScanRecord,
} from "./services/firestoreService";
import {
  extractTextFromImage,
  extractTextFromReportFile,
  parseIngredientsFromOcrText,
} from "./services/ocrService";
import {
  runOnDevicePrivacyGate,
  extractMedicalContext,
  analyzeIngredientsWithHybridAI,
} from "./services/aiPipelineService";

type Screen = "home" | "scan" | "records" | "profile";
type ModelMode = "auto" | "flash" | "nano";

type IconName =
  | "home"
  | "scan"
  | "records"
  | "user"
  | "bell"
  | "shield"
  | "camera"
  | "upload"
  | "file"
  | "chevron"
  | "sparkles"
  | "plus"
  | "clock"
  | "trash"
  | "close"
  | "check"
  | "edit"
  | "heart"
  | "lock"
  | "more"
  | "arrow"
  | "leaf"
  | "alert";

function Icon({
  name,
  size = 22,
  stroke = 1.8,
}: {
  name: IconName;
  size?: number;
  stroke?: number;
}) {
  const paths: Record<IconName, React.ReactNode> = {
    home: (
      <>
        <path d="m3 10 9-7 9 7" />
        <path d="M5 9v11h14V9M9 20v-6h6v6" />
      </>
    ),
    scan: (
      <>
        <path d="M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3" />
        <path d="M8 12h8" />
      </>
    ),
    records: (
      <>
        <path d="M6 3h9l3 3v15H6z" />
        <path d="M14 3v4h4M9 11h6M9 15h6" />
      </>
    ),
    user: (
      <>
        <circle cx="12" cy="8" r="4" />
        <path d="M4.5 21a7.5 7.5 0 0 1 15 0" />
      </>
    ),
    bell: (
      <>
        <path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9" />
        <path d="M10 21h4" />
      </>
    ),
    shield: (
      <>
        <path d="M12 3 4.5 6v5.4c0 4.6 3.2 8 7.5 9.6 4.3-1.6 7.5-5 7.5-9.6V6z" />
        <path d="m9 12 2 2 4-5" />
      </>
    ),
    camera: (
      <>
        <path d="M4 7h3l1.5-2h7L17 7h3v12H4z" />
        <circle cx="12" cy="13" r="3.5" />
      </>
    ),
    upload: (
      <>
        <path d="M12 16V4m0 0L7.5 8.5M12 4l4.5 4.5" />
        <path d="M4 15v5h16v-5" />
      </>
    ),
    file: (
      <>
        <path d="M6 3h9l3 3v15H6z" />
        <path d="M14 3v4h4M9 12h6M9 16h4" />
      </>
    ),
    chevron: <path d="m9 18 6-6-6-6" />,
    sparkles: (
      <>
        <path d="m12 3 1.2 3.8L17 8l-3.8 1.2L12 13l-1.2-3.8L7 8l3.8-1.2zM5 14l.8 2.2L8 17l-2.2.8L5 20l-.8-2.2L2 17l2.2-.8zM19 13l.6 1.4L21 15l-1.4.6L19 17l-.6-1.4L17 15l1.4-.6z" />
      </>
    ),
    plus: <path d="M12 5v14M5 12h14" />,
    clock: (
      <>
        <circle cx="12" cy="12" r="9" />
        <path d="M12 7v5l3 2" />
      </>
    ),
    trash: (
      <>
        <path d="M4 7h16M9 7V4h6v3M7 7l1 14h8l1-14M10 11v6M14 11v6" />
      </>
    ),
    close: <path d="m6 6 12 12M18 6 6 18" />,
    check: <path d="m5 12 4 4L19 6" />,
    edit: (
      <>
        <path d="m14 5 5 5M4 20l3.5-.8L20 6.7 17.3 4 4.8 16.5z" />
      </>
    ),
    heart: (
      <path d="M20.8 9.2c0 5-8.8 10.2-8.8 10.2S3.2 14.2 3.2 9.2A4.7 4.7 0 0 1 12 6.8a4.7 4.7 0 0 1 8.8 2.4Z" />
    ),
    lock: (
      <>
        <rect x="5" y="10" width="14" height="11" rx="2" />
        <path d="M8 10V7a4 4 0 0 1 8 0v3" />
      </>
    ),
    more: (
      <>
        <circle cx="5" cy="12" r="1" />
        <circle cx="12" cy="12" r="1" />
        <circle cx="19" cy="12" r="1" />
      </>
    ),
    arrow: (
      <>
        <path d="M5 12h14M14 7l5 5-5 5" />
      </>
    ),
    leaf: (
      <>
        <path d="M5 19C5 9 11 4 20 4c0 9-5 15-15 15Z" />
        <path d="M5 19c4-5 7-7 12-10" />
      </>
    ),
    alert: (
      <>
        <path d="M12 3 2.8 20h18.4z" />
        <path d="M12 9v5M12 17h.01" />
      </>
    ),
  };
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={stroke}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {paths[name]}
    </svg>
  );
}

function mapMarkerIcon(
  iconType: "glucose" | "allergy" | "heart" | "medication"
): IconName {
  if (iconType === "allergy") return "alert";
  if (iconType === "medication") return "records";
  if (iconType === "glucose") return "leaf";
  return "heart";
}

function App() {
  const [user, setUser] = useState<User | null>(null);
  const [authLoading, setAuthLoading] = useState(true);
  const [screen, setScreen] = useState<Screen>("home");

  // Scan & AI analysis state
  const [ingredients, setIngredients] = useState<string[]>([]);
  const [entry, setEntry] = useState("");
  const [model, setModel] = useState<ModelMode>("auto");
  const [analyzing, setAnalyzing] = useState(false);
  const [analysisError, setAnalysisError] = useState("");
  const [activeResult, setActiveResult] = useState<{
    data: IngredientAnalysisResult;
    modelUsedLabel: string;
  } | null>(null);

  // Firestore user health & history state
  const [profile, setProfile] = useState<ExtractedMedicalProfile | null>(null);
  const [reports, setReports] = useState<StoredMedicalReport[]>([]);
  const [scanHistory, setScanHistory] = useState<StoredScanRecord[]>([]);

  // On-device Privacy Gate modal state
  const [privacyDraft, setPrivacyDraft] = useState<{
    fileName: string;
    rawText: string;
    gate: PrivacyGateResult;
  } | null>(null);
  const [processingReport, setProcessingReport] = useState(false);

  const reportFileRef = useRef<HTMLInputElement>(null);

  // 1. Subscribe to Firebase Auth
  useEffect(() => {
    const unsub = onAuthStateChanged(auth, (currentUser) => {
      setUser(currentUser);
      setAuthLoading(false);
    });
    return () => unsub();
  }, []);

  // 2. Subscribe to Firestore collections when signed in
  useEffect(() => {
    if (!user) {
      setProfile(null);
      setReports([]);
      setScanHistory([]);
      return;
    }

    const unsubProfile = subscribeToMedicalProfile(user.uid, setProfile);
    const unsubReports = subscribeToMedicalReports(user.uid, setReports);
    const unsubScans = subscribeToScanHistory(user.uid, setScanHistory);

    return () => {
      unsubProfile();
      unsubReports();
      unsubScans();
    };
  }, [user]);

  const handleSignIn = async () => {
    await signInWithPopup(auth, googleProvider);
  };

  const handleSignOut = async () => {
    await signOut(auth);
  };

  const addIngredient = () => {
    const value = entry.trim();
    if (!value) return;
    setIngredients((prev) => [...prev, value]);
    setEntry("");
  };

  // Stage 1 + Stage 2 on medical report upload
  const handleReportSelected = async (file: File) => {
    setProcessingReport(true);
    try {
      const rawText = await extractTextFromReportFile(file);
      const gate = await runOnDevicePrivacyGate(
        rawText || `Uploaded file: ${file.name}`
      );
      setPrivacyDraft({
        fileName: file.name.replace(/\.[^/.]+$/, ""),
        rawText,
        gate,
      });
    } catch (err) {
      console.error("Report extraction failed:", err);
      alert("Could not read this file. Please try a clearer PDF or image.");
    } finally {
      setProcessingReport(false);
    }
  };

  // Stage 3 + Firestore save after user approves PrivacyReview
  const handleApprovePrivacy = async (useMasked: boolean) => {
    if (!privacyDraft) return;
    setProcessingReport(true);
    try {
      const textToSend = useMasked
        ? privacyDraft.gate.redactedText
        : privacyDraft.rawText;
      const extracted = await extractMedicalContext(
        textToSend,
        privacyDraft.fileName
      );

      if (user) {
        await saveMedicalReportAndMergeProfile(user.uid, extracted, textToSend);
      } else {
        // Session-only fallback if user hasn't signed in yet
        setProfile((prev) => ({
          reportTitle: extracted.reportTitle,
          markersCount:
            (prev?.markersCount || 0) + (extracted.markersCount || 1),
          healthMarkers: [
            ...(prev?.healthMarkers || []),
            ...(extracted.healthMarkers || []),
          ],
          conditions: [
            ...(prev?.conditions || []),
            ...(extracted.conditions || []),
          ],
          allergies: [
            ...(prev?.allergies || []),
            ...(extracted.allergies || []),
          ],
          medications: [
            ...(prev?.medications || []),
            ...(extracted.medications || []),
          ],
          dietaryRestrictions: [
            ...(prev?.dietaryRestrictions || []),
            ...(extracted.dietaryRestrictions || []),
          ],
        }));
      }
      setPrivacyDraft(null);
      setScreen("records");
    } catch (err) {
      console.error("Profile extraction failed:", err);
      alert("AI extraction failed. Check your connection and try again.");
    } finally {
      setProcessingReport(false);
    }
  };

  // Stage 4: Analyze ingredients with Hybrid AI & save to Firestore
  const handleAnalyzeIngredients = async () => {
    if (!ingredients.length || analyzing) return;
    setAnalyzing(true);
    setAnalysisError("");
    try {
      const { result, modelUsedLabel } = await analyzeIngredientsWithHybridAI(
        ingredients,
        profile,
        model
      );
      setActiveResult({ data: result, modelUsedLabel });

      if (user) {
        await saveScanResult(user.uid, result, ingredients, modelUsedLabel);
      }
    } catch (err) {
      console.error("Ingredient analysis error:", err);
      setAnalysisError(
        err instanceof Error
          ? err.message
          : "Analysis failed. Please try again or switch AI model."
      );
    } finally {
      setAnalyzing(false);
    }
  };

  const title = useMemo(() => {
    if (screen === "scan") return "Ingredient scan";
    if (screen === "records") return "My health";
    if (screen === "profile") return "Your profile";
    return "";
  }, [screen]);

  return (
    <div className="app-shell">
      <main className="phone">
        {screen === "home" ? (
          <Home
            user={user}
            onSignIn={handleSignIn}
            onScan={() => {
              setActiveResult(null);
              setScreen("scan");
            }}
            onUpload={() => reportFileRef.current?.click()}
            onRecords={() => setScreen("records")}
            profile={profile}
            reportsCount={reports.length}
            scanHistory={scanHistory}
            onSelectScan={(scan) => {
              setIngredients(scan.rawIngredients || []);
              setActiveResult({ data: scan, modelUsedLabel: scan.modelUsed });
              setScreen("scan");
            }}
            onDeleteScan={(scanId) => user && deleteScanRecord(user.uid, scanId)}
          />
        ) : (
          <div className="inner-header">
            <button
              className="back-button"
              onClick={() => setScreen("home")}
              aria-label="Back"
            >
              <Icon name="arrow" size={20} />
            </button>
            <h1>{title}</h1>
            <button
              className="icon-button"
              onClick={() => setScreen("profile")}
              aria-label="Account"
            >
              <Icon name={screen === "profile" ? "edit" : "more"} size={20} />
            </button>
          </div>
        )}

        {screen === "scan" &&
          (activeResult ? (
            <AnalysisResult
              result={activeResult.data}
              modelUsedLabel={activeResult.modelUsedLabel}
              connectedConditionsCount={profile?.healthMarkers?.length || 0}
              onBack={() => setActiveResult(null)}
            />
          ) : (
            <ScanScreen
              ingredients={ingredients}
              entry={entry}
              model={model}
              analyzing={analyzing}
              analysisError={analysisError}
              onEntry={setEntry}
              onModel={setModel}
              onAdd={addIngredient}
              onSetIngredients={setIngredients}
              onRemove={(i) =>
                setIngredients(ingredients.filter((_, index) => index !== i))
              }
              onAnalyze={handleAnalyzeIngredients}
            />
          ))}

        {screen === "records" && (
          <Records
            user={user}
            profile={profile}
            reports={reports}
            processingReport={processingReport}
            onUpload={() => reportFileRef.current?.click()}
            onSaveTypedNote={async (noteText) => {
              const gate = await runOnDevicePrivacyGate(noteText);
              const extracted = await extractMedicalContext(
                gate.redactedText,
                "Typed health note"
              );
              if (user) {
                await saveTypedHealthNoteAndMergeProfile(
                  user.uid,
                  gate.redactedText,
                  extracted
                );
              } else {
                setProfile((prev) => ({
                  reportTitle: "Session health context",
                  markersCount:
                    (prev?.markersCount || 0) +
                    (extracted.healthMarkers?.length || 1),
                  healthMarkers: [
                    ...(prev?.healthMarkers || []),
                    ...(extracted.healthMarkers || []),
                  ],
                  conditions: [
                    ...(prev?.conditions || []),
                    ...(extracted.conditions || []),
                  ],
                  allergies: [
                    ...(prev?.allergies || []),
                    ...(extracted.allergies || []),
                  ],
                  medications: [
                    ...(prev?.medications || []),
                    ...(extracted.medications || []),
                  ],
                  dietaryRestrictions: [
                    ...(prev?.dietaryRestrictions || []),
                    ...(extracted.dietaryRestrictions || []),
                  ],
                }));
              }
            }}
          />
        )}

        {screen === "profile" && (
          <Profile
            user={user}
            authLoading={authLoading}
            onSignIn={handleSignIn}
            onSignOut={handleSignOut}
          />
        )}

        <input
          ref={reportFileRef}
          type="file"
          className="sr-only"
          accept="image/*,.pdf"
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (file) {
              handleReportSelected(file);
              e.target.value = "";
            }
          }}
        />

        <nav className="bottom-nav">
          {(
            [
              ["home", "home", "Home"],
              ["scan", "scan", "Scan"],
              ["records", "records", "My health"],
              ["profile", "user", "Profile"],
            ] as [Screen, IconName, string][]
          ).map(([id, icon, label]) => (
            <button
              key={id}
              className={screen === id ? "active" : ""}
              onClick={() => {
                setScreen(id);
                if (id === "scan") setActiveResult(null);
              }}
            >
              <Icon name={icon} size={21} />
              <span>{label}</span>
            </button>
          ))}
        </nav>
      </main>

      {privacyDraft && (
        <PrivacyReview
          gate={privacyDraft.gate}
          busy={processingReport}
          onClose={() => setPrivacyDraft(null)}
          onApprove={handleApprovePrivacy}
        />
      )}
    </div>
  );
}

function Home({
  user,
  onSignIn,
  onScan,
  onUpload,
  onRecords,
  profile,
  reportsCount,
  scanHistory,
  onSelectScan,
  onDeleteScan,
}: {
  user: User | null;
  onSignIn: () => void;
  onScan: () => void;
  onUpload: () => void;
  onRecords: () => void;
  profile: ExtractedMedicalProfile | null;
  reportsCount: number;
  scanHistory: StoredScanRecord[];
  onSelectScan: (scan: StoredScanRecord) => void;
  onDeleteScan: (scanId: string) => void;
}) {
  const markersCount = profile?.healthMarkers?.length || 0;

  return (
    <div className="home-screen">
      <header className="home-header">
        <div className="brand">
          <span className="brand-mark">
            <Icon name="shield" size={22} />
          </span>
          <strong>
            ScanTruth<span>AI</span>
          </strong>
        </div>
        {user ? (
          <button
            className="icon-button notification"
            onClick={onRecords}
            aria-label="Health profile"
          >
            <Icon name="bell" size={21} />
            <i />
          </button>
        ) : (
          <button
            onClick={onSignIn}
            style={{
              border: "1px solid #cfe1da",
              background: "#fff",
              color: "#185c4a",
              borderRadius: 11,
              padding: "7px 11px",
              fontSize: 11,
              fontWeight: 700,
            }}
          >
            Sign in
          </button>
        )}
      </header>

      <section className="greeting">
        <div>
          <p>
            {user?.displayName
              ? `HELLO, ${user.displayName.split(" ")[0].toUpperCase()}`
              : "GOOD MORNING"}
          </p>
          <h1>Make a safer choice.</h1>
          <span>Scan what’s inside. Know what’s right for you.</span>
        </div>
        <div className="health-orbit">
          <Icon name="heart" size={27} />
          <i />
          <b />
        </div>
      </section>

      <button className="scan-card" onClick={onScan}>
        <div className="scanner-visual">
          <span className="scan-corner c1" />
          <span className="scan-corner c2" />
          <span className="scan-corner c3" />
          <span className="scan-corner c4" />
          <Icon name="camera" size={32} />
          <i />
        </div>
        <div>
          <h2>Scan ingredients</h2>
          <p>Point your camera at any food or medicine label.</p>
          <span>
            Open scanner <Icon name="arrow" size={16} />
          </span>
        </div>
      </button>

      <section className="section">
        <div className="section-title">
          <div>
            <p>YOUR HEALTH PROFILE</p>
            <h2>Medical context</h2>
          </div>
          <button onClick={onRecords}>View all</button>
        </div>
        <div className="profile-card">
          <div className="profile-icon">
            <Icon name="file" size={23} />
          </div>
          <div>
            <h3>
              {markersCount > 0
                ? `${markersCount} active health marker${markersCount === 1 ? "" : "s"}`
                : "Complete your profile"}
            </h3>
            <p>
              {markersCount > 0
                ? `${reportsCount} report${reportsCount === 1 ? "" : "s"} synced · Personalized checks active`
                : "Get advice personalized to your health"}
            </p>
          </div>
          <button onClick={markersCount > 0 ? onRecords : onUpload}>
            {markersCount > 0 ? <Icon name="chevron" size={18} /> : "Add report"}
          </button>
        </div>
      </section>

      <section className="section recent">
        <div className="section-title">
          <div>
            <p>RECENT ANALYSIS</p>
            <h2>Know what you consume</h2>
          </div>
          <button onClick={onScan}>New scan</button>
        </div>

        {scanHistory.length === 0 ? (
          <div className="empty-recent">
            <Icon name="records" size={24} />
            <p>No saved analyses yet</p>
            <button onClick={onScan}>Scan a product</button>
          </div>
        ) : (
          scanHistory.map((scan) => {
            const isCaution = scan.safetyScore < 70;
            return (
              <div
                key={scan.id}
                className="recent-card"
                onClick={() => onSelectScan(scan)}
                role="button"
                tabIndex={0}
              >
                <div
                  className={`product-thumb ${
                    scan.productCategory === "medicine" ? "medicine" : "oats"
                  }`}
                >
                  {scan.productCategory === "medicine" ? (
                    "Rx"
                  ) : (
                    <Icon name="leaf" size={22} />
                  )}
                </div>
                <div className="recent-copy">
                  <h3>{scan.productName || "Scanned Product"}</h3>
                  <p>
                    {scan.rawIngredients?.slice(0, 3).join(", ") ||
                      "Ingredient check"}
                  </p>
                  <span className={isCaution ? "caution-pill" : "safe-pill"}>
                    <Icon name={isCaution ? "alert" : "check"} size={11} />{" "}
                    {scan.badgeLabel}
                  </span>
                </div>
                <div className={`score ${isCaution ? "amber" : ""}`}>
                  <strong>{scan.safetyScore}</strong>
                  <span>/100</span>
                </div>
                <button
                  className="delete-analysis"
                  aria-label="Delete scan"
                  onClick={(e) => {
                    e.stopPropagation();
                    onDeleteScan(scan.id);
                  }}
                >
                  <Icon name="trash" size={13} />
                </button>
              </div>
            );
          })
        )}
      </section>

      <div className="privacy-note">
        <Icon name="lock" size={15} />
        <span>
          <strong>Private by design.</strong> Your sensitive data is masked
          on-device before it leaves your phone.
        </span>
      </div>
    </div>
  );
}

function ScanScreen({
  ingredients,
  entry,
  model,
  analyzing,
  analysisError,
  onEntry,
  onModel,
  onAdd,
  onSetIngredients,
  onRemove,
  onAnalyze,
}: {
  ingredients: string[];
  entry: string;
  model: ModelMode;
  analyzing: boolean;
  analysisError: string;
  onEntry: (v: string) => void;
  onModel: (v: ModelMode) => void;
  onAdd: () => void;
  onSetIngredients: (items: string[]) => void;
  onRemove: (i: number) => void;
  onAnalyze: () => void;
}) {
  const [scanned, setScanned] = useState(false);
  const [cameraOpen, setCameraOpen] = useState(false);
  const [capturedImage, setCapturedImage] = useState("");
  const [extracting, setExtracting] = useState(false);
  const galleryInputRef = useRef<HTMLInputElement>(null);

  const runOcrOnImage = async (source: File | string, previewUrl: string) => {
    setCapturedImage(previewUrl);
    setCameraOpen(false);
    setExtracting(true);
    setScanned(false);
    try {
      const rawText = await extractTextFromImage(source);
      const parsed = parseIngredientsFromOcrText(rawText);
      if (parsed.length > 0) {
        onSetIngredients(parsed);
      }
      setScanned(true);
    } catch (err) {
      console.error("OCR error:", err);
      setScanned(true);
    } finally {
      setExtracting(false);
    }
  };

  return (
    <div className="content scan-content">
      <section className="camera-panel">
        <div className="camera-frame">
          <span className="scan-corner c1" />
          <span className="scan-corner c2" />
          <span className="scan-corner c3" />
          <span className="scan-corner c4" />
          {capturedImage ? (
            <img
              className="captured-label"
              src={capturedImage}
              alt="Captured ingredient label"
            />
          ) : (
            <div className="label-sample">
              <b>NUTRITION FACTS</b>
              <i />
              <i />
              <i />
              <i />
            </div>
          )}
          {extracting && (
            <div className="scan-success extracting">
              <Icon name="sparkles" size={20} /> Extracting ingredients…
            </div>
          )}
          {scanned && !extracting && (
            <div className="scan-success">
              <Icon name="check" size={20} /> Label captured
            </div>
          )}
        </div>
        <p>Keep the ingredient label inside the frame</p>
        <button
          className="capture-button"
          onClick={() => setCameraOpen(true)}
          aria-label="Open camera"
        >
          <span>
            <Icon name="camera" size={25} />
          </span>
        </button>
        <button
          className="gallery-button"
          onClick={() => galleryInputRef.current?.click()}
        >
          <Icon name="upload" size={17} /> Upload
        </button>
        <input
          ref={galleryInputRef}
          type="file"
          className="sr-only"
          accept="image/*"
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (file) {
              const preview = URL.createObjectURL(file);
              runOcrOnImage(file, preview);
              e.target.value = "";
            }
          }}
        />
      </section>

      <section className="ingredient-section">
        <div className="ingredient-heading">
          <div>
            <p>DETECTED INGREDIENTS</p>
            <h2>
              {ingredients.length
                ? `${ingredients.length} ingredients found`
                : "No ingredients yet"}
            </h2>
          </div>
          <span>
            {extracting
              ? "Running OCR…"
              : scanned
                ? "OCR complete"
                : "Ready to scan"}
          </span>
        </div>

        <div className="chips">
          {ingredients.map((item, i) => (
            <span key={`${item}-${i}`}>
              {item}
              <button
                onClick={() => onRemove(i)}
                aria-label={`Remove ${item}`}
              >
                <Icon name="close" size={13} />
              </button>
            </span>
          ))}
        </div>

        {!ingredients.length && (
          <p className="ingredient-empty">
            {scanned
              ? "No ingredients detected automatically — add ingredients manually below."
              : "Capture a label photo, upload from gallery, or type ingredients below."}
          </p>
        )}

        <div className="add-row">
          <input
            value={entry}
            onChange={(e) => onEntry(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && onAdd()}
            placeholder="Add an ingredient (e.g. Oats, Sugar, Peanut oil)"
          />
          <button onClick={onAdd} aria-label="Add ingredient">
            <Icon name="plus" size={19} />
          </button>
        </div>
      </section>

      <section className="model-section">
        <label htmlFor="model">AI MODEL</label>
        <div className="select-wrap">
          <span
            className={`model-icon ${
              model === "nano" ? "private" : model === "auto" ? "auto" : "flash"
            }`}
          >
            <Icon
              name={
                model === "nano"
                  ? "lock"
                  : model === "auto"
                    ? "shield"
                    : "sparkles"
              }
              size={18}
            />
          </span>
          <select
            id="model"
            value={model}
            onChange={(e) => onModel(e.target.value as ModelMode)}
          >
            <option value="auto">Auto — Cloud with offline fallback</option>
            <option value="flash">Gemini Flash — Better performance</option>
            <option value="nano">Gemini Nano 4 — Private</option>
          </select>
        </div>
        <p>
          <Icon
            name={
              model === "nano"
                ? "lock"
                : model === "auto"
                  ? "shield"
                  : "sparkles"
            }
            size={14}
          />
          {model === "auto"
            ? "Uses Gemini Flash by default and Gemini Nano 4 when offline"
            : model === "flash"
              ? "Uses your masked health profile for deeper insights"
              : "Analysis stays completely on this device"}
        </p>
      </section>

      {analysisError && (
        <p style={{ color: "#b46555", fontSize: 11, marginTop: 10 }}>
          {analysisError}
        </p>
      )}

      <button
        className="primary-button"
        onClick={onAnalyze}
        disabled={!ingredients.length || analyzing}
      >
        <Icon name="sparkles" size={19} />
        {analyzing ? "Analyzing with AI…" : "Analyze ingredients"}
      </button>

      {cameraOpen && (
        <FullScreenCamera
          onClose={() => setCameraOpen(false)}
          onUsePhoto={(dataUrl) => runOcrOnImage(dataUrl, dataUrl)}
        />
      )}
    </div>
  );
}

function FullScreenCamera({
  onClose,
  onUsePhoto,
}: {
  onClose: () => void;
  onUsePhoto: (image: string) => void;
}) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const [photo, setPhoto] = useState("");
  const [cameraError, setCameraError] = useState("");

  useEffect(() => {
    if (photo) return;
    let active = true;
    navigator.mediaDevices
      ?.getUserMedia({
        video: { facingMode: { ideal: "environment" } },
        audio: false,
      })
      .then((stream) => {
        if (!active) {
          stream.getTracks().forEach((track) => track.stop());
          return;
        }
        streamRef.current = stream;
        if (videoRef.current) videoRef.current.srcObject = stream;
      })
      .catch(() =>
        setCameraError(
          "Camera access is unavailable. Check your browser permission and try again."
        )
      );
    return () => {
      active = false;
      streamRef.current?.getTracks().forEach((track) => track.stop());
    };
  }, [photo]);

  const takePhoto = () => {
    const video = videoRef.current;
    if (!video || !video.videoWidth) return;
    const canvas = document.createElement("canvas");
    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;
    canvas.getContext("2d")?.drawImage(video, 0, 0);
    setPhoto(canvas.toDataURL("image/jpeg", 0.86));
    streamRef.current?.getTracks().forEach((track) => track.stop());
  };

  return (
    <div
      className="camera-overlay"
      role="dialog"
      aria-modal="true"
      aria-label="Ingredient camera"
    >
      <div className="camera-topbar">
        <button onClick={onClose} aria-label="Close camera">
          <Icon name="close" size={23} />
        </button>
        <div>
          <strong>{photo ? "Review photo" : "Scan ingredient label"}</strong>
          <span>
            {photo
              ? "Make sure the text is clear"
              : "Fill the frame with the ingredients list"}
          </span>
        </div>
        <span />
      </div>
      <div className="camera-viewport">
        {photo ? (
          <img src={photo} alt="Ingredient label preview" />
        ) : (
          <video ref={videoRef} autoPlay playsInline muted />
        )}
        {!photo && !cameraError && (
          <div className="camera-guide">
            <i />
            <i />
            <i />
            <i />
            <span>INGREDIENTS</span>
          </div>
        )}
        {cameraError && (
          <div className="camera-error">
            <Icon name="camera" size={30} />
            <p>{cameraError}</p>
          </div>
        )}
      </div>
      <div className="camera-actions">
        {photo ? (
          <>
            <button className="camera-retake" onClick={() => setPhoto("")}>
              Retake
            </button>
            <button className="camera-use" onClick={() => onUsePhoto(photo)}>
              <Icon name="check" size={19} /> Use photo
            </button>
          </>
        ) : (
          <button
            className="camera-shutter"
            onClick={takePhoto}
            disabled={!!cameraError}
            aria-label="Take photo"
          >
            <span />
          </button>
        )}
      </div>
    </div>
  );
}

function AnalysisResult({
  result,
  modelUsedLabel,
  connectedConditionsCount,
  onBack,
}: {
  result: IngredientAnalysisResult;
  modelUsedLabel: string;
  connectedConditionsCount: number;
  onBack: () => void;
}) {
  const isCaution = result.safetyScore < 70;

  return (
    <div className="content result-content">
      <div className="result-hero">
        <div
          className="score-ring"
          style={{
            background: `conic-gradient(${
              isCaution ? "#e6b24c" : "#2ea77f"
            } 0 ${result.safetyScore}%, #d6e5df ${result.safetyScore}%)`,
            color: isCaution ? "#9b712a" : "#17664f",
          }}
        >
          <strong>{result.safetyScore}</strong>
          <span>/100</span>
        </div>
        <div>
          <span className={isCaution ? "caution-pill" : "safe-pill"}>
            <Icon name={isCaution ? "alert" : "check"} size={12} />{" "}
            {result.badgeLabel}
          </span>
          <h2>{result.headline || result.productName}</h2>
          <p>{result.productName}</p>
        </div>
      </div>

      <div className="ai-summary">
        <span>
          <Icon name="sparkles" size={18} />
        </span>
        <div>
          <b>AI summary</b>
          <p>{result.aiSummary}</p>
        </div>
      </div>

      <section className="personalized-impact">
        <div className="section-title">
          <div>
            <p>PERSONALIZED FOR YOU</p>
            <h2>Health factors</h2>
          </div>
          <span>{connectedConditionsCount} connected</span>
        </div>

        {result.healthFactors?.length ? (
          <div className="score-factors">
            {result.healthFactors.map((factor, index) => (
              <div
                className={`factor ${
                  factor.sentiment === "warning"
                    ? "caution"
                    : factor.sentiment === "neutral"
                      ? "watch"
                      : ""
                }`}
                key={`${factor.title}-${index}`}
              >
                <span>
                  <Icon
                    name={
                      factor.sentiment === "warning"
                        ? "alert"
                        : factor.sentiment === "positive"
                          ? "check"
                          : "heart"
                    }
                    size={17}
                  />
                </span>
                <div>
                  <h3>{factor.title}</h3>
                  <p>{factor.description}</p>
                </div>
                <b>{factor.impactBadge}</b>
              </div>
            ))}
          </div>
        ) : (
          <div className="template-empty">
            <Icon name="heart" size={20} />
            <p>
              Add health conditions in My health to enable personalized health
              factor checks.
            </p>
          </div>
        )}

        <p className="profile-basis">
          <Icon name="lock" size={13} /> Evaluated against your anonymized
          health profile
        </p>
      </section>

      <section className="breakdown">
        <div className="section-title">
          <div>
            <p>INGREDIENT BREAKDOWN</p>
            <h2>What’s inside</h2>
          </div>
        </div>
        {result.ingredientBreakdown?.map((item, idx) => {
          const isWarn = item.status === "Caution" || item.status === "Avoid";
          const isNeutral = item.status === "Okay";
          return (
            <div
              className={`ingredient-detail ${
                isWarn ? "warning" : isNeutral ? "neutral" : ""
              }`}
              key={`${item.name}-${idx}`}
            >
              <div className="breakdown-row">
                <span
                  className={`food-dot ${
                    isWarn ? "warning" : isNeutral ? "neutral" : ""
                  }`}
                >
                  <Icon name={isWarn ? "alert" : "check"} size={17} />
                </span>
                <div>
                  <h3>{item.name}</h3>
                  <p>{item.note}</p>
                </div>
                <b
                  className={isWarn ? "warning" : isNeutral ? "neutral" : ""}
                >
                  {item.status}
                </b>
              </div>
            </div>
          );
        })}
      </section>

      <div className="intake-card">
        <span>
          <Icon name="clock" size={22} />
        </span>
        <div>
          <p>RECOMMENDED INTAKE</p>
          <h3>
            {result.recommendedIntake?.dosageHeadline ||
              "Moderate serving guidance"}
          </h3>
          <small>{result.recommendedIntake?.guidance}</small>
          <div className="medical-disclaimer">
            <Icon name="alert" size={15} />
            <span>
              <b>Health guidance only.</b> This estimate is not medical advice.
              Consult your doctor or a registered dietitian for a reliable
              intake recommendation tailored to your health conditions,
              medications, and nutritional needs.
            </span>
          </div>
        </div>
      </div>

      <p className="model-used">
        <Icon
          name={modelUsedLabel.includes("Nano") ? "lock" : "sparkles"}
          size={14}
        />
        Analyzed with {modelUsedLabel}
      </p>

      <button className="secondary-button" onClick={onBack}>
        Scan another product
      </button>
    </div>
  );
}

function Records({
  user,
  profile,
  reports,
  processingReport,
  onUpload,
  onSaveTypedNote,
}: {
  user: User | null;
  profile: ExtractedMedicalProfile | null;
  reports: StoredMedicalReport[];
  processingReport: boolean;
  onUpload: () => void;
  onSaveTypedNote: (noteText: string) => Promise<void>;
}) {
  const [conditionPrompt, setConditionPrompt] = useState("");
  const [updating, setUpdating] = useState(false);
  const [updateMessage, setUpdateMessage] = useState("");

  const markers = profile?.healthMarkers || [];

  const handleUpdateCondition = async () => {
    const prompt = conditionPrompt.trim();
    if (!prompt || updating) return;
    setUpdating(true);
    setUpdateMessage("");
    try {
      await onSaveTypedNote(prompt);
      setConditionPrompt("");
      setUpdateMessage(
        user
          ? "Health context updated and saved securely to Firebase."
          : "Health context updated for this session. Sign in to sync across devices."
      );
    } catch (err) {
      console.error("Failed to classify health note:", err);
      setUpdateMessage("Could not process note right now. Please try again.");
    } finally {
      setUpdating(false);
    }
  };

  return (
    <div className="content records-content">
      <div className="health-summary">
        <div>
          <p>HEALTH PROFILE</p>
          <h2>
            {markers.length || reports.length
              ? "Health context active"
              : "Build your health context"}
          </h2>
          <span>
            {markers.length
              ? `${markers.length} marker${markers.length === 1 ? "" : "s"} personalizing every scan`
              : "Add reports for personalized analysis"}
          </span>
        </div>
        <span className="shield-large">
          <Icon name="shield" size={28} />
        </span>
      </div>

      <div className="conditions">
        <div className="section-title">
          <div>
            <p>KNOWN CONDITIONS</p>
            <h2>Health context</h2>
          </div>
        </div>

        <div className="condition-grid">
          {markers.map((marker, index) => (
            <div key={`${marker.category}-${marker.value}-${index}`}>
              <span className={`condition-icon ${marker.tone || "coral"}`}>
                <Icon name={mapMarkerIcon(marker.iconType)} size={19} />
              </span>
              <p>{marker.category}</p>
              <h3>{marker.value}</h3>
            </div>
          ))}
        </div>

        {!markers.length && (
          <div className="template-empty">
            <Icon name="heart" size={20} />
            <p>
              No medical conditions added. Use the form below to create your
              health context.
            </p>
          </div>
        )}

        <div className="condition-prompt">
          <div className="prompt-heading">
            <span>
              <Icon name="sparkles" size={18} />
            </span>
            <div>
              <h3>Tell AI about your health</h3>
              <p>
                Describe a diagnosis, allergy, or health condition in your own
                words.
              </p>
            </div>
          </div>
          <textarea
            value={conditionPrompt}
            onChange={(event) => setConditionPrompt(event.target.value)}
            placeholder="For example: I was diagnosed with high blood pressure last year"
            rows={3}
          />
          <button
            onClick={handleUpdateCondition}
            disabled={!conditionPrompt.trim() || updating}
          >
            {updating ? (
              "Classifying & saving…"
            ) : (
              <>
                <Icon name="plus" size={17} /> Update my conditions
              </>
            )}
          </button>
          <small>
            <Icon name="shield" size={13} />{" "}
            {user
              ? "Private details are masked on-device before AI classification and saved to your encrypted profile."
              : "Sign in on the Profile tab to persist your health context across sessions."}
          </small>
          {updateMessage && (
            <p className="condition-success">
              <Icon name="check" size={14} />
              {updateMessage}
            </p>
          )}
        </div>
      </div>

      <section className="section reports">
        <div className="section-title">
          <div>
            <p>MEDICAL DOCUMENTS</p>
            <h2>Your reports</h2>
          </div>
        </div>

        {reports.map((rep) => (
          <div className="report-row" key={rep.id}>
            <span>
              <Icon name="file" size={21} />
            </span>
            <div>
              <h3>{rep.reportTitle}</h3>
              <p>
                {rep.markersCount} marker{rep.markersCount === 1 ? "" : "s"} ·
                Masked on-device
              </p>
            </div>
            <Icon name="shield" size={16} />
          </div>
        ))}

        {!reports.length && (
          <div className="template-empty">
            <Icon name="file" size={20} />
            <p>No medical reports uploaded.</p>
          </div>
        )}

        <button
          className="upload-report"
          onClick={onUpload}
          disabled={processingReport}
        >
          <Icon name="upload" size={20} />
          <div>
            <h3>
              {processingReport
                ? "Running on-device privacy scan…"
                : "Add a medical report"}
            </h3>
            <p>PDF or image · Masked locally before cloud extraction</p>
          </div>
        </button>
      </section>

      <div className="privacy-note">
        <Icon name="lock" size={15} />
        <span>
          Gemini Nano checks and masks private details on your device before
          cloud processing.
        </span>
      </div>
    </div>
  );
}

function Profile({
  user,
  authLoading,
  onSignIn,
  onSignOut,
}: {
  user: User | null;
  authLoading: boolean;
  onSignIn: () => void;
  onSignOut: () => void;
}) {
  const initials = user?.displayName
    ? user.displayName
        .split(" ")
        .map((n) => n[0])
        .slice(0, 2)
        .join("")
        .toUpperCase()
    : null;

  return (
    <div className="content profile-content">
      <div className="avatar">
        {initials ? initials : <Icon name="user" size={30} />}
      </div>
      <h2>{user?.displayName || "Guest user"}</h2>
      <p>
        {authLoading
          ? "Checking sign-in status…"
          : user?.email || "Sign in to sync your health profile and scans"}
      </p>

      <div style={{ marginTop: 16 }}>
        {user ? (
          <button className="secondary-button" onClick={onSignOut}>
            Sign out
          </button>
        ) : (
          <button className="primary-button" onClick={onSignIn}>
            <Icon name="shield" size={18} /> Continue with Google
          </button>
        )}
      </div>

      <div className="profile-list">
        {[
          ["shield", "Privacy & data", "On-device PII redaction enabled"],
          ["sparkles", "AI preferences", "Hybrid Gemini Flash + Nano 4"],
          ["bell", "Notifications", "Health insights and reminders"],
          ["lock", "Security", "App Check + per-user Firestore rules"],
        ].map(([icon, title, copy]) => (
          <button key={title}>
            <span>
              <Icon name={icon as IconName} size={20} />
            </span>
            <div>
              <h3>{title}</h3>
              <p>{copy}</p>
            </div>
            <Icon name="chevron" size={18} />
          </button>
        ))}
      </div>

      <div className="data-promise">
        <Icon name="shield" size={24} />
        <div>
          <h3>Your data belongs to you</h3>
          <p>
            ScanTruthAI protects personal identifiers on-device and never uses
            your health information for advertising.
          </p>
        </div>
      </div>
    </div>
  );
}

function PrivacyReview({
  gate,
  busy,
  onClose,
  onApprove,
}: {
  gate: PrivacyGateResult;
  busy: boolean;
  onClose: () => void;
  onApprove: (useMasked: boolean) => void;
}) {
  const [masked, setMasked] = useState(true);

  return (
    <div className="modal-backdrop">
      <div className="privacy-modal">
        <button className="modal-close" onClick={onClose}>
          <Icon name="close" size={20} />
        </button>
        <div className="nano-badge">
          <Icon name="shield" size={25} />
          <i />
        </div>
        <p className="eyebrow">ON-DEVICE PRIVACY CHECK</p>
        <h2>
          {gate.detectedItems.length > 0
            ? `${gate.detectedItems.length} sensitive detail${
                gate.detectedItems.length === 1 ? "" : "s"
              } found`
            : "Privacy check complete"}
        </h2>
        <p className="modal-intro">
          {gate.detectedItems.length > 0
            ? "Detected locally on your device. Review before extracting health markers."
            : "No obvious personal identifiers were detected in this document."}
        </p>

        {gate.detectedItems.length > 0 && (
          <div className="privacy-findings">
            {gate.detectedItems.map((item, idx) => (
              <div key={`${item.label}-${idx}`}>
                <span>
                  <Icon
                    name={item.category === "Identifier" ? "file" : "user"}
                    size={18}
                  />
                </span>
                <div>
                  <p>{item.label}</p>
                  <h3>{masked ? item.maskedPreview : item.originalValue}</h3>
                </div>
                <b>{item.category}</b>
              </div>
            ))}
          </div>
        )}

        <label className="mask-toggle">
          <div>
            <Icon name="lock" size={19} />
            <span>
              <strong>Mask all sensitive details</strong>
              <small>Recommended for your privacy</small>
            </span>
          </div>
          <input
            type="checkbox"
            checked={masked}
            onChange={(e) => setMasked(e.target.checked)}
          />
          <i />
        </label>

        <button
          className="primary-button"
          onClick={() => onApprove(masked)}
          disabled={busy}
        >
          {masked ? <Icon name="shield" size={19} /> : <Icon name="arrow" size={19} />}
          {busy
            ? "Extracting health markers…"
            : masked
              ? "Mask & continue securely"
              : "Continue without masking"}
        </button>

        <button className="text-button" onClick={onClose} disabled={busy}>
          Cancel upload
        </button>

        <p className="on-device">
          <Icon name="lock" size={13} /> This privacy check happened entirely on
          your device
        </p>
      </div>
    </div>
  );
}

export default App;