import { type Dispatch, type SetStateAction, useEffect, useRef, useState } from "react";
import type { User } from "firebase/auth";
import {
  addProfileItem,
  removeProfileItem,
  saveMedicalReportAndMergeProfile,
  saveTypedHealthNoteAndMergeProfile,
} from "./firestoreService";
import { extractTextFromReportFile } from "./ocrService";
import {
  type EnhancedPrivacyGateResult,
  extractMedicalContext,
  runOnDevicePrivacyGate,
  validateHealthItem,
} from "./aiPipelineService";
import { HEALTH_CATEGORIES, type HealthCategory, passesBasicCheck, unionItems } from "./health";
import {
  ChipList,
  Dialog,
  FilledButton,
  Icon,
  type IconName,
  IconTile,
  OutlinedButton,
  PrivacyNoteCard,
  SectionHeader,
  Sheet,
  Spinner,
} from "./ui";

export interface HealthLists {
  conditions: string[];
  allergies: string[];
  medications: string[];
  dietaryRestrictions: string[];
}

interface PendingReview {
  gate: EnhancedPrivacyGateResult;
  sourceLabel: string;
  isTypedNote: boolean;
}

const CONTEXT_CARDS: { category: HealthCategory; title: string; icon: IconName }[] = [
  { category: "allergy", title: "Allergies", icon: "alert" },
  { category: "medication", title: "Medications", icon: "pills" },
  { category: "dietary", title: "Dietary restrictions", icon: "leaf" },
];

export default function MyHealthView({
  user,
  lists,
  reportTitles,
  setGuestLists,
  addGuestReport,
  openReportPicker,
  onReportPickerOpened,
}: {
  user: User | null;
  lists: HealthLists;
  reportTitles: string[];
  setGuestLists: Dispatch<SetStateAction<HealthLists>>;
  addGuestReport: (title: string) => void;
  openReportPicker: boolean;
  onReportPickerOpened: () => void;
}) {
  const fileRef = useRef<HTMLInputElement>(null);
  const [note, setNote] = useState("");
  const [processing, setProcessing] = useState(false);
  const [status, setStatus] = useState<string | null>(null);
  const [review, setReview] = useState<PendingReview | null>(null);
  const [addCategory, setAddCategory] = useState<HealthCategory | null>(null);
  const [newItem, setNewItem] = useState("");
  const [editing, setEditing] = useState<Set<HealthCategory>>(new Set());

  useEffect(() => {
    // Home's "Analyse Medical Report" sent the user here: open the report picker once.
    if (!openReportPicker) return;
    onReportPickerOpened();
    fileRef.current?.click();
  }, [openReportPicker, onReportPickerOpened]);

  const itemsFor = (c: HealthCategory) => lists[HEALTH_CATEGORIES[c].field];
  const detailCount =
    lists.conditions.length + lists.allergies.length + lists.medications.length + lists.dietaryRestrictions.length;

  const toggleEditing = (c: HealthCategory) =>
    setEditing((prev) => {
      const next = new Set(prev);
      if (next.has(c)) next.delete(c);
      else next.add(c);
      return next;
    });

  const removeItem = async (item: string, c: HealthCategory) => {
    const field = HEALTH_CATEGORIES[c].field;
    if (itemsFor(c).length <= 1) setEditing((prev) => new Set([...prev].filter((x) => x !== c)));
    if (!user) {
      setGuestLists((prev) => ({ ...prev, [field]: prev[field].filter((i) => i !== item) }));
      setStatus("Removed for this session only. Sign in to keep your account in sync.");
      return;
    }
    try {
      await removeProfileItem(user.uid, field, item);
      setStatus(`Removed "${item}".`);
    } catch (err) {
      setStatus(`Removing it from your account failed: ${(err as Error).message}`);
    }
  };

  const addManualItem = async (text: string, c: HealthCategory) => {
    const item = text.trim();
    if (!item) return;
    const { singular, field } = HEALTH_CATEGORIES[c];
    const rejected = `"${item}" doesn't look like a ${singular}. Check the spelling and try again.`;
    if (!passesBasicCheck(item)) {
      setStatus(rejected);
      return;
    }

    // Only real entries reach the health context; junk would skew every scan verdict.
    setProcessing(true);
    setStatus(`Checking "${item}"...`);
    try {
      let validation;
      try {
        validation = await validateHealthItem(item, c);
      } catch {
        setStatus(`Couldn't verify "${item}" right now, so it wasn't added. Try again.`);
        return;
      }
      if (!validation.isValid) {
        setStatus(rejected);
        return;
      }
      const normalized = validation.normalizedName?.trim() ?? "";
      const finalItem = passesBasicCheck(normalized) ? normalized : item;

      if (!user) {
        setGuestLists((prev) => ({ ...prev, [field]: unionItems(prev[field], [finalItem]) }));
        setStatus("Added for this session only. Sign in to save it to your account.");
        return;
      }
      try {
        await addProfileItem(user.uid, field, finalItem);
        setStatus("Saved to your account.");
      } catch (err) {
        setStatus(`Saving failed: ${(err as Error).message}`);
      }
    } finally {
      setProcessing(false);
    }
  };

  /** Stage 2: mask private details on-device, then show exactly what will be sent. */
  const beginReview = async (rawText: string, sourceLabel: string, isTypedNote: boolean) => {
    if (!rawText.trim()) {
      setStatus(
        "No readable text was found. Scanned PDFs without a text layer are not supported yet; try a photo instead.",
      );
      return;
    }
    setStatus("Masking private details on-device...");
    try {
      const gate = await runOnDevicePrivacyGate(rawText);
      setStatus(null);
      if (isTypedNote) setNote("");
      setReview({ gate, sourceLabel, isTypedNote });
    } catch {
      setStatus("Privacy masking failed, so nothing was sent.");
    }
  };

  /** Stage 1: read text from the picked PDF or image on-device. */
  const handleFile = async (file: File) => {
    setProcessing(true);
    setStatus("Reading document on-device...");
    try {
      const isPdf = file.type === "application/pdf" || file.name.toLowerCase().endsWith(".pdf");
      let text = "";
      try {
        text = await extractTextFromReportFile(file);
      } catch (err) {
        setStatus(`Could not read text from the file: ${(err as Error).message}`);
        return;
      }
      // Generic label on purpose: file names often contain the patient's name.
      await beginReview(text, isPdf ? "Medical report (PDF)" : "Medical report (image)", false);
    } finally {
      setProcessing(false);
    }
  };

  const submitNote = async () => {
    const trimmed = note.trim();
    if (!trimmed) return;
    setProcessing(true);
    try {
      await beginReview(trimmed, "Typed health note", true);
    } finally {
      setProcessing(false);
    }
  };

  /** Stage 3: send only the approved, masked text to the cloud model, then merge and save. */
  const extractAndSave = async (r: PendingReview) => {
    setProcessing(true);
    setStatus("Extracting health markers...");
    try {
      let extracted;
      try {
        extracted = await extractMedicalContext(r.gate.redactedText, r.sourceLabel);
      } catch (err) {
        setStatus(`Extraction failed: ${(err as Error).message}`);
        return;
      }
      const title = extracted.reportTitle || r.sourceLabel;

      if (!user) {
        setGuestLists((prev) => ({
          conditions: unionItems(prev.conditions, extracted.conditions),
          allergies: unionItems(prev.allergies, extracted.allergies),
          medications: unionItems(prev.medications, extracted.medications),
          dietaryRestrictions: unionItems(prev.dietaryRestrictions, extracted.dietaryRestrictions),
        }));
        if (!r.isTypedNote) addGuestReport(title);
        setStatus("Profile updated for this session only. Sign in to save it to your account.");
        return;
      }
      try {
        if (r.isTypedNote) {
          await saveTypedHealthNoteAndMergeProfile(user.uid, r.gate.redactedText, extracted);
        } else {
          await saveMedicalReportAndMergeProfile(user.uid, { ...extracted, reportTitle: title });
        }
        setStatus("Profile updated and saved.");
      } catch (err) {
        setStatus(`Saving failed: ${(err as Error).message}`);
      }
    } finally {
      setProcessing(false);
    }
  };

  const editAction = (c: HealthCategory) =>
    itemsFor(c).length === 0
      ? {}
      : {
          actionTitle: editing.has(c) ? "Done" : "Edit",
          actionIcon: (editing.has(c) ? "check" : "pencil") as IconName,
          onAction: () => toggleEditing(c),
        };

  return (
    <div className="stack gap-20">
      <div className="stack gap-4">
        <h1 className="screen-title">My Health</h1>
        <p className="muted">Personal health context for tailored scan verdicts and saved reports.</p>
      </div>

      {(processing || status) && (
        <div className="status-banner" role="status">
          {processing && <Spinner />}
          <span>{status ?? "Working..."}</span>
        </div>
      )}

      <section className="hero-card column">
        <div className="row gap-12">
          <span className="hero-icon">
            <Icon name="heartFile" size={22} />
          </span>
          <h2>Build Your Health Context</h2>
        </div>
        <p>
          The more your profile knows, the more personal every ingredient verdict is. All data stays strictly in your
          own account.
        </p>
        <span className="hero-pill">
          <i />
          {detailCount === 0 ? "No details added yet" : detailCount === 1 ? "1 detail added" : `${detailCount} details added`}
        </span>
      </section>

      <section className="stack gap-12">
        <SectionHeader title="Known Conditions" {...editAction("condition")} />
        <div className="card stack gap-14">
          <div className="row-top gap-12">
            <IconTile name="heart" />
            <div className="stack gap-4">
              <h3 className="card-title">
                {lists.conditions.length ? "Recorded conditions" : "No known conditions recorded"}
              </h3>
              <p className="muted">
                {lists.conditions.length
                  ? "These personalize every scan analysis."
                  : "Add conditions to make every scan analysis personalized and relevant."}
              </p>
            </div>
          </div>
          {lists.conditions.length > 0 && (
            <ChipList
              items={lists.conditions}
              editing={editing.has("condition")}
              onDelete={(i) => removeItem(i, "condition")}
            />
          )}
          <div>
            <OutlinedButton icon="plus" onClick={() => setAddCategory("condition")}>
              {HEALTH_CATEGORIES.condition.addTitle}
            </OutlinedButton>
          </div>
        </div>
      </section>

      <section className="stack gap-12">
        <SectionHeader title="Health Context" />
        {CONTEXT_CARDS.map(({ category, title, icon }) => {
          const items = itemsFor(category);
          const isEditing = editing.has(category);
          return (
            <div key={category} className="card stack gap-12">
              <div className="row gap-12">
                <IconTile name={icon} size={40} />
                <h3 className="card-heading grow">{title}</h3>
                {items.length > 0 && (
                  <button
                    className="link-button"
                    onClick={() => toggleEditing(category)}
                    aria-label={`${isEditing ? "Finish editing" : "Edit"} ${title}`}
                  >
                    {isEditing ? "Done" : "Edit"}
                    <Icon name={isEditing ? "check" : "pencil"} size={12} stroke={2.6} />
                  </button>
                )}
              </div>
              {items.length === 0 ? (
                <p className="muted">Nothing recorded yet. Add items so scans check for them.</p>
              ) : (
                <ChipList items={items} editing={isEditing} onDelete={(i) => removeItem(i, category)} />
              )}
              <div>
                <OutlinedButton icon="plus" onClick={() => setAddCategory(category)}>
                  {HEALTH_CATEGORIES[category].addTitle}
                </OutlinedButton>
              </div>
            </div>
          );
        })}
      </section>

      <section className="card stack gap-12">
        <h2 className="card-heading">Health notes</h2>
        <p className="muted small">
          Anything else that should be considered, e.g. a recent diagnosis or a symptom that is not in the lists above.
        </p>
        <textarea
          className="note-input"
          value={note}
          onChange={(e) => setNote(e.target.value)}
          placeholder="Add a note"
          rows={3}
        />
        <FilledButton disabled={processing || !note.trim()} onClick={submitNote}>
          Save note
        </FilledButton>
        <p className="fine-print row-top gap-8">
          <Icon name="lock" size={12} />
          Your note is masked on this device and shown to you for review before anything is sent to the cloud.
        </p>
      </section>

      <section className="stack gap-12">
        <SectionHeader title="Medical Documents" />
        {reportTitles.map((title, i) => (
          <div key={`${title}-${i}`} className="card tight row gap-12">
            <IconTile name="file" size={40} />
            <strong className="item-title">{title}</strong>
          </div>
        ))}
        <div className="card stack gap-12">
          {reportTitles.length === 0 && <p className="muted">No medical reports uploaded.</p>}
          <div>
            <OutlinedButton icon="fileUp" disabled={processing} onClick={() => fileRef.current?.click()}>
              Add a medical report
            </OutlinedButton>
          </div>
          <p className="fine-print">PDF or photo. Private details are masked on this device first.</p>
        </div>
        <input
          ref={fileRef}
          type="file"
          accept="application/pdf,image/*"
          className="sr-only"
          onChange={(e) => {
            const file = e.target.files?.[0];
            e.target.value = "";
            if (file) handleFile(file);
          }}
        />
      </section>

      <PrivacyNoteCard
        title="Your privacy is protected"
        text="Gemini Nano checks and masks private details on your device before anything is sent to the cloud, and only after you approve the redacted text."
      />

      {addCategory && (
        <Dialog
          title={HEALTH_CATEGORIES[addCategory].addTitle}
          onClose={() => {
            setAddCategory(null);
            setNewItem("");
          }}
          actions={
            <>
              <button
                className="bar-button"
                onClick={() => {
                  setAddCategory(null);
                  setNewItem("");
                }}
              >
                Cancel
              </button>
              <button
                className="bar-button strong"
                onClick={() => {
                  const c = addCategory;
                  const text = newItem;
                  setAddCategory(null);
                  setNewItem("");
                  addManualItem(text, c);
                }}
              >
                Add
              </button>
            </>
          }
        >
          <input
            className="dialog-input"
            autoFocus
            value={newItem}
            placeholder={HEALTH_CATEGORIES[addCategory].placeholder}
            onChange={(e) => setNewItem(e.target.value)}
            onKeyDown={(e) => {
              if (e.key !== "Enter") return;
              const c = addCategory;
              const text = newItem;
              setAddCategory(null);
              setNewItem("");
              addManualItem(text, c);
            }}
          />
        </Dialog>
      )}

      {review && (
        <PrivacyReviewSheet
          gate={review.gate}
          onCancel={() => setReview(null)}
          onApprove={() => {
            const r = review;
            setReview(null);
            extractAndSave(r);
          }}
        />
      )}
    </div>
  );
}

function PrivacyReviewSheet({
  gate,
  onApprove,
  onCancel,
}: {
  gate: EnhancedPrivacyGateResult;
  onApprove: () => void;
  onCancel: () => void;
}) {
  const items = gate.detectedItems ?? [];
  const header = gate.usedFallback
    ? (gate.notice ?? "Gemini Nano is unavailable, so a rule-based filter masked the text on your device.")
    : "Gemini Nano masked personal identifiers locally. Only the text below is sent for analysis.";

  return (
    <Sheet
      title="Privacy Review"
      onClose={onCancel}
      leading={
        <button className="bar-button" onClick={onCancel}>
          Cancel
        </button>
      }
    >
      <div className="stack gap-18">
        <div className="mint-panel stack gap-6">
          <div className="row gap-8">
            <Icon name="shieldLock" size={22} />
            <h3 className="card-heading">On-Device Privacy Gate</h3>
          </div>
          <p className="muted">{header}</p>
        </div>

        {items.length === 0 && (
          <p className="warning-text row-top gap-6">
            <Icon name="alert" size={14} />
            No private details were detected. Read the text below carefully before approving.
          </p>
        )}

        {items.length > 0 && (
          <div className="stack gap-8">
            <p className="caps-label">MASKED DETAILS ({items.length})</p>
            <div className="chip-list">
              {items.map((item, i) => (
                <span key={i} className="chip gray small">
                  {item.label || "Private detail"}: {item.maskedPreview || "••••"}
                </span>
              ))}
            </div>
          </div>
        )}

        <div className="stack gap-8">
          <p className="caps-label">TEXT THAT WILL BE SENT</p>
          <pre className="sent-text">{gate.redactedText}</pre>
        </div>

        <FilledButton icon="checkCircle" onClick={onApprove}>
          Approve &amp; Extract Profile
        </FilledButton>
      </div>
    </Sheet>
  );
}
