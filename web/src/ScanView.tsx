import { useEffect, useRef, useState } from "react";
import type { User } from "firebase/auth";
import type { ExtractedMedicalProfile } from "./firebase";
import { saveScanResult } from "./firestoreService";
import { extractTextFromImage } from "./ocrService";
import {
  analyzeIngredientsWithHybridAI,
  describeAiError,
  extractIngredientsIntelligently,
  getOnDeviceStatus,
  type OnDeviceStatus,
} from "./aiPipelineService";
import {
  displayBadge,
  FilledButton,
  Icon,
  OutlinedButton,
  PrivacyNoteCard,
  type ScanRecordView,
  Spinner,
  toAnalysisView,
} from "./ui";

type ModelOption = "auto" | "flash" | "nano";

const MODEL_OPTIONS: { id: ModelOption; title: string; detail: string }[] = [
  {
    id: "auto",
    title: "Auto",
    detail:
      "Gemini cloud model when it is reachable, otherwise Gemini Nano in this browser. The result always names the engine used.",
  },
  {
    id: "flash",
    title: "Gemini Flash",
    detail: "The Gemini cloud model. Only ingredient names and your approved profile are sent.",
  },
  {
    id: "nano",
    title: "On-device",
    detail: "Stays in this browser using Gemini Nano. Never falls back to the cloud.",
  },
];

const STATUS_TEXT: Record<OnDeviceStatus, string> = {
  available: "Gemini Nano is ready in this browser.",
  downloadable:
    "Gemini Nano is supported but not downloaded yet. Chrome downloads it the first time an on-device feature runs.",
  downloading: "Gemini Nano is downloading in this browser. Check again when it finishes.",
  unavailable:
    "Gemini Nano is not available in this browser, so on-device analysis cannot run here. Choose Auto or Gemini Flash to use the cloud model instead.",
};

export default function ScanView({
  user,
  ingredients,
  setIngredients,
  profile,
  onBack,
  onResult,
}: {
  user: User | null;
  ingredients: string[];
  setIngredients: (update: (prev: string[]) => string[]) => void;
  profile: ExtractedMedicalProfile;
  onBack: () => void;
  onResult: (record: ScanRecordView, fallbackNotice?: string) => void;
}) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const [cameraOn, setCameraOn] = useState(false);
  const [entry, setEntry] = useState("");
  const [model, setModel] = useState<ModelOption>("auto");
  const [ocrBusy, setOcrBusy] = useState(false);
  const [ocrMessage, setOcrMessage] = useState<string | null>(null);
  const [analyzing, setAnalyzing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [onDevice, setOnDevice] = useState<OnDeviceStatus>("unavailable");

  useEffect(() => {
    getOnDeviceStatus().then(setOnDevice);
    let stream: MediaStream | null = null;
    let active = true;
    navigator.mediaDevices
      ?.getUserMedia({ video: { facingMode: { ideal: "environment" } }, audio: false })
      .then((s) => {
        if (!active) {
          s.getTracks().forEach((t) => t.stop());
          return;
        }
        stream = s;
        if (videoRef.current) videoRef.current.srcObject = s;
        setCameraOn(true);
      })
      .catch(() => setCameraOn(false));
    return () => {
      active = false;
      stream?.getTracks().forEach((t) => t.stop());
    };
  }, []);

  const runOcr = async (source: File | string) => {
    setOcrBusy(true);
    setOcrMessage("Analyzing label on-device...");
    try {
      const text = await extractTextFromImage(source);
      const found = await extractIngredientsIntelligently(text);
      if (found.length === 0) {
        // Do not guess by turning every line of the label into an "ingredient".
        setOcrMessage(
          'Could not find an ingredients list in that photo. Keep the word "Ingredients" in the frame, or type them below.',
        );
      } else {
        setIngredients(() => found);
        setOcrMessage(null);
      }
    } catch (err) {
      setOcrMessage(`Error scanning image: ${(err as Error).message}`);
    } finally {
      setOcrBusy(false);
    }
  };

  const capture = () => {
    const video = videoRef.current;
    if (!cameraOn || !video?.videoWidth) {
      setOcrMessage("Camera unavailable. Allow camera access in your browser or upload a photo instead.");
      return;
    }
    const canvas = document.createElement("canvas");
    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;
    canvas.getContext("2d")?.drawImage(video, 0, 0);
    runOcr(canvas.toDataURL("image/jpeg", 0.9));
  };

  /** Adds what was typed; several ingredients can be separated by commas. */
  const addTyped = () => {
    const parts = entry
      .split(/[,\n]/)
      .map((p) => p.trim())
      .filter(Boolean);
    setIngredients((prev) => [...prev, ...parts.filter((p, i) => !prev.includes(p) && parts.indexOf(p) === i)]);
    setEntry("");
  };

  const analyze = async () => {
    setAnalyzing(true);
    setError(null);
    try {
      const output = await analyzeIngredientsWithHybridAI(ingredients, profile, model);
      const view = toAnalysisView(output.result as unknown as Record<string, unknown>);
      onResult(
        {
          id: `local-${Date.now()}`,
          result: view,
          ingredients,
          modelUsedLabel: output.modelUsedLabel,
          scannedAt: new Date(),
        },
        output.notice,
      );
      if (user) {
        try {
          // Keys match the shared schema (SPEC.md 2D) so the iOS and Android apps read them.
          await saveScanResult(
            user.uid,
            { ...output.result, safetyScore: view.score ?? 0, badgeLabel: displayBadge(view.score) },
            ingredients,
            output.modelUsedLabel,
          );
        } catch (err) {
          setError(`Analysis finished, but saving it to your account failed: ${(err as Error).message}`);
        }
      }
    } catch (err) {
      setError(`Analysis failed: ${describeAiError(err)}`);
    } finally {
      setAnalyzing(false);
    }
  };

  const selected = MODEL_OPTIONS.find((o) => o.id === model)!;

  return (
    <div className="stack gap-18">
      <header className="row gap-14">
        <button className="round-button" onClick={onBack} aria-label="Back">
          <Icon name="arrowLeft" size={18} stroke={2.2} />
        </button>
        <div className="stack gap-3">
          <h1 className="screen-title small">Scan ingredients</h1>
          <p className="muted">Point at the ingredient list on the pack</p>
        </div>
      </header>

      <section className="viewfinder" aria-label="Camera viewfinder. Keep the whole ingredients panel inside the frame.">
        <video ref={videoRef} autoPlay playsInline muted className={cameraOn ? "" : "hidden"} />
        {!cameraOn && (
          <div className="label-sample" aria-hidden="true">
            <b>NUTRITION FACTS</b>
            <span className="thick" />
            <span />
            <span />
            <span />
          </div>
        )}
        <span className="scan-line" />
        <span className="vf-corners" />
        <span className="vf-badge">
          <i /> Ingredient label
        </span>
        <span className="vf-hint">Keep the whole ingredients panel inside the frame</span>
        {ocrBusy && (
          <span className="vf-progress">
            <Spinner />
          </span>
        )}
      </section>

      <div className="capture-controls">
        <button className="circle-control" onClick={() => fileRef.current?.click()}>
          <span>
            <Icon name="photo" size={19} />
          </span>
          Upload
        </button>
        <button className="shutter" onClick={capture} disabled={ocrBusy} aria-label="Capture photo of the ingredient list">
          <Icon name={ocrBusy ? "sparkles" : "camera"} size={27} stroke={2} />
        </button>
        <button className="circle-control" onClick={() => inputRef.current?.focus()}>
          <span>
            <Icon name="pencil" size={18} />
          </span>
          Type list
        </button>
        <input
          ref={fileRef}
          type="file"
          accept="image/*"
          className="sr-only"
          onChange={(e) => {
            const file = e.target.files?.[0];
            e.target.value = "";
            if (file) runOcr(file);
          }}
        />
      </div>

      <section className="card stack gap-14">
        <div className="row spread">
          <h2 className="card-heading">Detected Ingredients</h2>
          <span className="count-badge">{ingredients.length}</span>
        </div>
        {ingredients.length === 0 ? (
          <p className="muted">
            {ocrMessage ?? "Nothing detected yet. Capture a label, upload a photo, or type the ingredients below."}
          </p>
        ) : (
          <div className="chip-list">
            {ingredients.map((item) => (
              <span key={item} className="chip">
                {item}
                <button
                  className="chip-x"
                  onClick={() => setIngredients((prev) => prev.filter((i) => i !== item))}
                  aria-label={`Remove ${item}`}
                >
                  <Icon name="close" size={11} stroke={2.8} />
                </button>
              </span>
            ))}
          </div>
        )}
        <label className="field">
          <span>Add an ingredient</span>
          <input
            ref={inputRef}
            value={entry}
            onChange={(e) => setEntry(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && addTyped()}
            placeholder="e.g. sugar, palm oil, salt"
            enterKeyHint="done"
          />
        </label>
        <OutlinedButton icon="plus" full onClick={addTyped}>
          Add to list
        </OutlinedButton>
      </section>

      <section className="card stack gap-14">
        <div className="stack gap-4">
          <h2 className="card-heading">AI Model</h2>
          <p className="muted">Choose where the analysis runs.</p>
        </div>
        <div className="segmented" role="radiogroup" aria-label="AI model">
          {MODEL_OPTIONS.map((o) => (
            <button
              key={o.id}
              role="radio"
              aria-checked={model === o.id}
              className={model === o.id ? "selected" : ""}
              onClick={() => setModel(o.id)}
            >
              {o.title}
            </button>
          ))}
        </div>
        <p className="muted small">{selected.detail}</p>
        <div className="status-box stack gap-8">
          <div className="row gap-6">
            <i className={`status-dot ${onDevice === "available" ? "ok" : "warn"}`} />
            <strong>On-device model status</strong>
          </div>
          <p className="muted small">{STATUS_TEXT[onDevice]}</p>
          <div>
            <OutlinedButton onClick={() => getOnDeviceStatus().then(setOnDevice)}>Re-check availability</OutlinedButton>
          </div>
        </div>
      </section>

      <div className="stack gap-8">
        <FilledButton
          icon={analyzing ? undefined : "sparkles"}
          disabled={analyzing || ingredients.length === 0}
          onClick={analyze}
        >
          {analyzing ? "Analyzing..." : "Analyze Ingredients"}
        </FilledButton>
        {error && <p className="error-text">{error}</p>}
      </div>

      <PrivacyNoteCard
        title="Read on this device"
        text="The label photo stays on your device. Only the ingredient names and the health context you approved are sent for analysis."
      />
    </div>
  );
}
