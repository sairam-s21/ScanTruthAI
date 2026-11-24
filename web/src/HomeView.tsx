import type { User } from "firebase/auth";
import {
  BrandHeader,
  ChipList,
  FilledButton,
  Icon,
  IconTile,
  PrivacyNoteCard,
  type ScanRecordView,
  ScorePill,
  SectionHeader,
} from "./ui";
import type { HealthLists } from "./MyHealthView";

function greeting(user: User | null): string {
  const hour = new Date().getHours();
  const part = hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening";
  const first = user?.displayName?.split(" ")[0];
  return first ? `${part}, ${first}` : part;
}

export default function HomeView({
  user,
  lists,
  history,
  latestScan,
  onOpenScanner,
  onCompleteProfile,
  onAnalyseReport,
  onShowHistory,
  onOpenRecord,
}: {
  user: User | null;
  lists: HealthLists;
  history: ScanRecordView[];
  latestScan: ScanRecordView | null;
  onOpenScanner: () => void;
  onCompleteProfile: () => void;
  onAnalyseReport: () => void;
  onShowHistory: () => void;
  onOpenRecord: (record: ScanRecordView) => void;
}) {
  const detailCount =
    lists.conditions.length +
    lists.allergies.length +
    lists.medications.length +
    lists.dietaryRestrictions.length;
  // The account's newest scans; falls back to this session's scan when nothing is loaded yet.
  const recent = history.length ? history.slice(0, 4) : latestScan ? [latestScan] : [];

  return (
    <div className="stack gap-22">
      <BrandHeader
        subtitle="ON-DEVICE PRIVACY VERIFIED"
        trailing={
          <span className="round-button" aria-label="Notifications" role="img">
            <Icon name="bell" size={18} />
          </span>
        }
      />

      <section className="greeting">
        <p>{greeting(user)}</p>
        <h1>Make a safer choice.</h1>
        <span>Scan ingredients or review medical records with guaranteed on-device privacy.</span>
      </section>

      <section className="hero-card">
        <div className="hero-copy">
          <span className="hero-tag">SCAN &amp; KNOW</span>
          <h2>Scan Ingredients</h2>
          <p>Check what is really inside before it goes in your basket.</p>
          <button className="hero-button" onClick={onOpenScanner}>
            <Icon name="scan" size={16} stroke={2.2} /> Start scanning
          </button>
        </div>
        <div className="hero-illustration" aria-hidden="true">
          <span className="vf-corners small" />
          <i style={{ width: 34 }} />
          <i style={{ width: 46 }} />
          <i style={{ width: 28 }} />
          <b>
            <Icon name="check" size={13} stroke={2.8} />
          </b>
        </div>
      </section>

      <section className="stack gap-12">
        <SectionHeader title="Medical Context" actionTitle="View All" onAction={onCompleteProfile} />
        <div className="card stack gap-14">
          <div className="row-top gap-12">
            <IconTile name="heartFile" />
            <div className="stack gap-4">
              <h3 className="card-title">
                {detailCount === 0 ? "No medical context yet" : "Your health context"}
              </h3>
              <p className="muted">
                {detailCount === 0
                  ? "Add conditions and allergies so every scan is personal."
                  : `${detailCount} ${detailCount === 1 ? "detail" : "details"} personalize every scan.`}
              </p>
            </div>
          </div>
          {detailCount > 0 && (
            <ChipList items={[...lists.conditions, ...lists.allergies, ...lists.medications].slice(0, 6)} />
          )}
          <FilledButton icon="plus" onClick={onAnalyseReport}>
            Analyse Medical Report
          </FilledButton>
          <button className="link-button center" onClick={onCompleteProfile}>
            Build your health context <Icon name="chevronRight" size={12} stroke={2.6} />
          </button>
        </div>
      </section>

      <section className="stack gap-12">
        <SectionHeader title="Recent Analysis" actionTitle="View All" onAction={onShowHistory} />
        {recent.length === 0 ? (
          <div className="dashed-card">
            <Icon name="file" size={26} />
            <p>{user ? "No saved analyses yet" : "Sign in from the Profile tab to see your saved scans"}</p>
            <button className="link-button" onClick={onOpenScanner}>
              Scan a product
            </button>
          </div>
        ) : (
          recent.map((record) => {
            const r = record.result;
            const isMedicine = r.productCategory?.toLowerCase() === "medicine";
            const category = r.productCategory
              ? r.productCategory[0].toUpperCase() + r.productCategory.slice(1)
              : "Scan";
            const date = record.scannedAt?.toLocaleDateString(undefined, { day: "numeric", month: "short" });
            return (
              <button key={record.id} className="card row-card" onClick={() => onOpenRecord(record)}>
                <IconTile name={isMedicine ? "pills" : "leaf"} />
                <span className="row-copy">
                  <strong>{r.productName ?? "Scanned product"}</strong>
                  <small>{date ? `${category} · ${date}` : category}</small>
                </span>
                <ScorePill score={r.score} />
              </button>
            );
          })
        )}
      </section>

      <PrivacyNoteCard
        title="Your privacy is protected"
        text="Names, dates of birth, phone numbers and IDs are masked on this device. Reports reach the cloud only after you approve the redacted text."
      />
    </div>
  );
}
