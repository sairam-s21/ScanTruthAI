import { type ReactNode, useState } from "react";
import type { User } from "firebase/auth";
import { deleteScanRecord } from "./firestoreService";
import { displayBadge, Icon, type ScanRecordView, ScoreRing, scoreColor, Sheet } from "./ui";

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="stack gap-8">
      <p className="caps-label">{title}</p>
      {children}
    </section>
  );
}

export function AnalysisResultSheet({
  record,
  fallbackNotice,
  onClose,
}: {
  record: ScanRecordView;
  fallbackNotice?: string;
  onClose: () => void;
}) {
  const r = record.result;
  const color = scoreColor(r.score);
  const intake = r.recommendedIntake;

  return (
    <Sheet
      title={r.productName ?? "Analysis"}
      onClose={onClose}
      trailing={
        <button className="bar-button strong" onClick={onClose}>
          Done
        </button>
      }
    >
      <div className="stack gap-18">
        <div className="card row gap-16">
          <ScoreRing score={r.score} size={76} width={8} />
          <div className="stack gap-4">
            <strong className="badge-text" style={{ color }}>
              {displayBadge(r.score)}
            </strong>
            <h3 className="result-headline">{r.headline ?? "Analysis complete"}</h3>
          </div>
        </div>

        {fallbackNotice && (
          <p className="muted small row-top gap-6">
            <Icon name="info" size={14} /> {fallbackNotice}
          </p>
        )}

        {r.aiSummary && <p className="body-text">{r.aiSummary}</p>}

        {r.healthFactors.length > 0 && (
          <Section title="HEALTH FACTORS">
            {r.healthFactors.map((f, i) => (
              <div key={i} className="card tight stack gap-4">
                <div className="row spread gap-8">
                  <strong className="item-title">{f.title ?? ""}</strong>
                  {f.impactBadge && <span className="mini-badge">{f.impactBadge}</span>}
                </div>
                {f.description && <p className="muted">{f.description}</p>}
              </div>
            ))}
          </Section>
        )}

        {r.ingredientBreakdown.length > 0 && (
          <Section title="INGREDIENT BREAKDOWN">
            {r.ingredientBreakdown.map((item, i) => (
              <div key={i} className="card tighter row-top spread gap-8">
                <div className="stack gap-2">
                  <strong className="item-title small">{item.name ?? ""}</strong>
                  {item.note && <p className="muted small">{item.note}</p>}
                </div>
                {item.status && <span className="status-text">{item.status}</span>}
              </div>
            ))}
          </Section>
        )}

        {intake && (intake.dosageHeadline || intake.guidance) && (
          <Section title="RECOMMENDED INTAKE">
            <div className="card tight stack gap-4">
              {intake.dosageHeadline && <strong className="item-title">{intake.dosageHeadline}</strong>}
              {intake.guidance && <p className="muted">{intake.guidance}</p>}
            </div>
          </Section>
        )}

        <p className="fine-print">
          Analyzed with {record.modelUsedLabel}. AI-generated guidance is not medical advice. Check with a doctor or
          pharmacist before changing your diet or medication.
        </p>
      </div>
    </Sheet>
  );
}

/** Every scan saved to the signed-in account, including scans made in the iOS and Android apps. */
export function HistorySheet({
  user,
  history,
  onClose,
  onOpen,
}: {
  user: User | null;
  history: ScanRecordView[];
  onClose: () => void;
  onOpen: (record: ScanRecordView) => void;
}) {
  const [error, setError] = useState<string | null>(null);

  const message = (icon: "user" | "file", text: string) => (
    <div className="empty-message">
      <Icon name={icon} size={28} />
      <p>{text}</p>
    </div>
  );

  return (
    <Sheet
      title="Scan history"
      onClose={onClose}
      trailing={
        <button className="bar-button strong" onClick={onClose}>
          Done
        </button>
      }
    >
      {!user ? (
        message(
          "user",
          "Sign in from the Profile tab to see scans saved to your account, including ones from the iOS and Android apps.",
        )
      ) : history.length === 0 ? (
        message("file", "No saved scans yet. Scans you make in any ScanTruthAI app will show up here.")
      ) : (
        <div className="stack gap-10">
          {error && <p className="error-text">{error}</p>}
          {history.map((record) => {
            const r = record.result;
            const date = record.scannedAt?.toLocaleString(undefined, {
              day: "numeric",
              month: "short",
              year: "numeric",
              hour: "numeric",
              minute: "2-digit",
            });
            const detail = [date, record.ingredients.length ? `${record.ingredients.length} ingredients` : null]
              .filter(Boolean)
              .join(" · ");
            return (
              <div key={record.id} className="card history-row">
                <button className="history-open" onClick={() => onOpen(record)}>
                  <ScoreRing score={r.score} size={48} width={5} />
                  <span className="row-copy">
                    <strong>{r.productName ?? "Scanned product"}</strong>
                    <em style={{ color: scoreColor(r.score) }}>{displayBadge(r.score)}</em>
                    <small>{detail}</small>
                  </span>
                  <Icon name="chevronRight" size={15} stroke={2.4} />
                </button>
                <button
                  className="icon-only"
                  aria-label={`Delete ${r.productName ?? "scan"}`}
                  onClick={() =>
                    deleteScanRecord(user.uid, record.id).catch((err) =>
                      setError(`Could not delete that scan: ${(err as Error).message}`),
                    )
                  }
                >
                  <Icon name="trash" size={15} />
                </button>
              </div>
            );
          })}
        </div>
      )}
    </Sheet>
  );
}
