import { type ReactNode, useEffect } from "react";

export type IconName =
  | "leaf"
  | "scan"
  | "heartFile"
  | "user"
  | "bell"
  | "shield"
  | "shieldLock"
  | "camera"
  | "photo"
  | "pencil"
  | "file"
  | "fileUp"
  | "chevronRight"
  | "chevronDown"
  | "sparkles"
  | "plus"
  | "close"
  | "xCircle"
  | "check"
  | "checkCircle"
  | "heart"
  | "lock"
  | "arrowLeft"
  | "alert"
  | "pills"
  | "info"
  | "logout"
  | "trash"
  | "key";

const PATHS: Record<IconName, ReactNode> = {
  leaf: (
    <>
      <path d="M5 19C5 9 11 4 20 4c0 9-5 15-15 15Z" />
      <path d="M5 19c4-5 7-7 12-10" />
    </>
  ),
  scan: (
    <>
      <path d="M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3" />
      <path d="M8 12h8" />
    </>
  ),
  heartFile: (
    <>
      <rect x="3.5" y="3.5" width="17" height="17" rx="3" />
      <path d="M12 16.5s-4.5-2.6-4.5-5.6A2.3 2.3 0 0 1 12 9.6a2.3 2.3 0 0 1 4.5 1.3c0 3-4.5 5.6-4.5 5.6Z" />
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
  shieldLock: (
    <>
      <path d="M12 3 4.5 6v5.4c0 4.6 3.2 8 7.5 9.6 4.3-1.6 7.5-5 7.5-9.6V6z" />
      <rect x="9" y="11" width="6" height="5" rx="1" />
      <path d="M10.5 11V9.5a1.5 1.5 0 0 1 3 0V11" />
    </>
  ),
  camera: (
    <>
      <path d="M4 7h3l1.5-2h7L17 7h3v12H4z" />
      <circle cx="12" cy="13" r="3.5" />
    </>
  ),
  photo: (
    <>
      <rect x="3.5" y="4.5" width="17" height="15" rx="2" />
      <circle cx="9" cy="10" r="1.6" />
      <path d="m4 17 5-5 4 4 3-3 4 4" />
    </>
  ),
  pencil: <path d="m14 5 5 5M4 20l3.5-.8L20 6.7 17.3 4 4.8 16.5z" />,
  file: (
    <>
      <path d="M6 3h9l3 3v15H6z" />
      <path d="M14 3v4h4M9 12h6M9 16h4" />
    </>
  ),
  fileUp: (
    <>
      <path d="M6 3h9l3 3v15H6z" />
      <path d="M14 3v4h4M12 17v-6m0 0-2.5 2.5M12 11l2.5 2.5" />
    </>
  ),
  chevronRight: <path d="m9 18 6-6-6-6" />,
  chevronDown: <path d="m6 9 6 6 6-6" />,
  sparkles: (
    <path d="m12 3 1.2 3.8L17 8l-3.8 1.2L12 13l-1.2-3.8L7 8l3.8-1.2zM5 14l.8 2.2L8 17l-2.2.8L5 20l-.8-2.2L2 17l2.2-.8zM19 13l.6 1.4L21 15l-1.4.6L19 17l-.6-1.4L17 15l1.4-.6z" />
  ),
  plus: <path d="M12 5v14M5 12h14" />,
  close: <path d="m6 6 12 12M18 6 6 18" />,
  xCircle: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="m9 9 6 6M15 9l-6 6" />
    </>
  ),
  check: <path d="m5 12 4 4L19 6" />,
  checkCircle: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="m8 12 3 3 5-6" />
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
  arrowLeft: <path d="M19 12H5M10 7l-5 5 5 5" />,
  alert: (
    <>
      <path d="M12 3 2.8 20h18.4z" />
      <path d="M12 9v5M12 17h.01" />
    </>
  ),
  pills: (
    <>
      <rect x="3" y="8.5" width="11" height="7" rx="3.5" transform="rotate(-45 8.5 12)" />
      <path d="m6.5 10 4 4" />
      <circle cx="17.5" cy="16.5" r="3.5" />
      <path d="M15 19l5-5" />
    </>
  ),
  info: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 11v5M12 8h.01" />
    </>
  ),
  logout: (
    <>
      <path d="M14 4H6v16h8" />
      <path d="M10 12h10m0 0-3.5-3.5M20 12l-3.5 3.5" />
    </>
  ),
  trash: <path d="M4 7h16M9 7V4h6v3M7 7l1 14h8l1-14M10 11v6M14 11v6" />,
  key: (
    <>
      <circle cx="8" cy="14" r="4" />
      <path d="m11 11 8-8M16 6l2 2M14 8l2 2" />
    </>
  ),
};

export function Icon({ name, size = 20, stroke = 1.9 }: { name: IconName; size?: number; stroke?: number }) {
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
      {PATHS[name]}
    </svg>
  );
}

// ── Analysis model (tolerant: scans from any platform or an LLM may have odd fields) ──

export interface HealthFactorView {
  title?: string;
  description?: string;
  impactBadge?: string;
  sentiment?: string;
}

export interface IngredientNoteView {
  name?: string;
  note?: string;
  status?: string;
}

export interface AnalysisView {
  productName?: string;
  productCategory?: string;
  score: number | null;
  headline?: string;
  aiSummary?: string;
  healthFactors: HealthFactorView[];
  ingredientBreakdown: IngredientNoteView[];
  recommendedIntake?: { dosageHeadline?: string; guidance?: string };
}

const str = (v: unknown) => (typeof v === "string" && v.trim() ? v : undefined);

export function toAnalysisView(raw: Record<string, unknown>): AnalysisView {
  const rawScore = raw.safetyScore;
  const n = typeof rawScore === "number" ? rawScore : typeof rawScore === "string" ? Number(rawScore) : NaN;
  const objects = (v: unknown) =>
    Array.isArray(v) ? v.filter((x): x is Record<string, unknown> => !!x && typeof x === "object") : [];
  const intake = raw.recommendedIntake as Record<string, unknown> | undefined;

  return {
    productName: str(raw.productName),
    productCategory: str(raw.productCategory),
    score: Number.isFinite(n) ? Math.round(Math.min(Math.max(n, 0), 100)) : null,
    headline: str(raw.headline),
    aiSummary: str(raw.aiSummary),
    healthFactors: objects(raw.healthFactors).map((f) => ({
      title: str(f.title),
      description: str(f.description),
      impactBadge: str(f.impactBadge),
      sentiment: str(f.sentiment),
    })),
    ingredientBreakdown: objects(raw.ingredientBreakdown).map((i) => ({
      name: str(i.name),
      note: str(i.note),
      status: str(i.status),
    })),
    recommendedIntake:
      intake && typeof intake === "object"
        ? { dosageHeadline: str(intake.dosageHeadline), guidance: str(intake.guidance) }
        : undefined,
  };
}

/** Badge from the shared rubric (70-100 / 40-69 / 0-39), so every platform shows the same label. */
export function displayBadge(score: number | null): string {
  const s = score ?? 0;
  return s >= 70 ? "Good match for you" : s >= 40 ? "Use with care" : "Avoid";
}

export function scoreColor(score: number | null): string {
  const s = score ?? 0;
  return s >= 70 ? "var(--good)" : s >= 40 ? "var(--care)" : "var(--avoid)";
}

export interface ScanRecordView {
  id: string;
  result: AnalysisView;
  ingredients: string[];
  modelUsedLabel: string;
  scannedAt: Date | null;
}

// ── Building blocks (laid out like the iOS / Android apps) ──

export function BrandHeader({ subtitle, trailing }: { subtitle: string; trailing?: ReactNode }) {
  return (
    <div className="brand-header">
      <span className="brand-mark">
        <Icon name="shield" size={20} stroke={2.2} />
      </span>
      <div className="brand-copy">
        <strong>ScanTruthAI</strong>
        <span>
          <i />
          {subtitle}
        </span>
      </div>
      {trailing}
    </div>
  );
}

export function SectionHeader({
  title,
  actionTitle,
  actionIcon = "chevronRight",
  onAction,
}: {
  title: string;
  actionTitle?: string;
  actionIcon?: IconName;
  onAction?: () => void;
}) {
  return (
    <div className="section-header">
      <h2>{title}</h2>
      {actionTitle && onAction && (
        <button className="link-button" onClick={onAction}>
          {actionTitle}
          <Icon name={actionIcon} size={12} stroke={2.6} />
        </button>
      )}
    </div>
  );
}

export function FilledButton({
  children,
  icon,
  disabled,
  onClick,
}: {
  children: ReactNode;
  icon?: IconName;
  disabled?: boolean;
  onClick: () => void;
}) {
  return (
    <button className="filled-button" onClick={onClick} disabled={disabled}>
      {icon && <Icon name={icon} size={17} stroke={2.2} />}
      {children}
    </button>
  );
}

export function OutlinedButton({
  children,
  icon,
  danger,
  full,
  large,
  disabled,
  onClick,
}: {
  children: ReactNode;
  icon?: IconName;
  danger?: boolean;
  full?: boolean;
  large?: boolean;
  disabled?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      className={`outlined-button${danger ? " danger" : ""}${full ? " full" : ""}${large ? " large" : ""}`}
      onClick={onClick}
      disabled={disabled}
    >
      {icon && <Icon name={icon} size={15} stroke={2.2} />}
      {children}
    </button>
  );
}

export function IconTile({
  name,
  size = 44,
  tone = "mint",
}: {
  name: IconName;
  size?: number;
  tone?: "mint" | "purple";
}) {
  return (
    <span className={`icon-tile ${tone}`} style={{ width: size, height: size }}>
      <Icon name={name} size={Math.round(size * 0.46)} />
    </span>
  );
}

export function ScorePill({ score }: { score: number | null }) {
  const color = scoreColor(score);
  return (
    <span
      className="score-pill"
      style={{ ["--tone" as string]: color }}
      aria-label={`Score ${score ?? "unknown"} out of 100`}
    >
      <i />
      <strong>{score ?? "–"}</strong>
      <small>/100</small>
    </span>
  );
}

export function ScoreRing({ score, size, width }: { score: number | null; size: number; width: number }) {
  const r = (size - width) / 2;
  const c = 2 * Math.PI * r;
  const color = scoreColor(score);
  return (
    <span className="score-ring" style={{ width: size, height: size }} aria-label={`Safety score ${score ?? "unknown"} out of 100`}>
      <svg width={size} height={size}>
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke={color} strokeOpacity={0.2} strokeWidth={width} />
        <circle
          cx={size / 2}
          cy={size / 2}
          r={r}
          fill="none"
          stroke={color}
          strokeWidth={width}
          strokeLinecap="round"
          strokeDasharray={c}
          strokeDashoffset={c * (1 - (score ?? 0) / 100)}
          transform={`rotate(-90 ${size / 2} ${size / 2})`}
        />
      </svg>
      <strong style={{ fontSize: size * 0.29 }}>{score ?? "–"}</strong>
    </span>
  );
}

export function PrivacyNoteCard({ title, text }: { title: string; text: string }) {
  return (
    <div className="privacy-note-card">
      <Icon name="shield" size={18} />
      <div>
        <strong>{title}</strong>
        <p>{text}</p>
      </div>
    </div>
  );
}

export function ChipList({
  items,
  editing,
  onDelete,
}: {
  items: string[];
  editing?: boolean;
  onDelete?: (item: string) => void;
}) {
  return (
    <div className="chip-list">
      {items.map((item) => (
        <span key={item} className={`chip${editing ? " editing" : ""}`}>
          {item}
          {editing && onDelete && (
            <button onClick={() => onDelete(item)} aria-label={`Remove ${item}`}>
              <Icon name="xCircle" size={16} />
            </button>
          )}
        </span>
      ))}
    </div>
  );
}

export function Spinner() {
  return <span className="spinner" aria-hidden="true" />;
}

/** Bottom sheet with an iOS-style navigation bar. */
export function Sheet({
  title,
  leading,
  trailing,
  onClose,
  children,
}: {
  title: string;
  leading?: ReactNode;
  trailing?: ReactNode;
  onClose: () => void;
  children: ReactNode;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      window.removeEventListener("keydown", onKey);
      document.body.style.overflow = prev;
    };
  }, [onClose]);

  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <div className="sheet" role="dialog" aria-modal="true" aria-label={title} onClick={(e) => e.stopPropagation()}>
        <div className="sheet-bar">
          <span className="sheet-side">{leading}</span>
          <strong>{title}</strong>
          <span className="sheet-side end">{trailing}</span>
        </div>
        <div className="sheet-body">{children}</div>
      </div>
    </div>
  );
}

export function Dialog({
  title,
  message,
  children,
  actions,
  onClose,
}: {
  title: string;
  message?: string;
  children?: ReactNode;
  actions: ReactNode;
  onClose: () => void;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div className="dialog-backdrop" onClick={onClose}>
      <div className="dialog" role="alertdialog" aria-modal="true" aria-label={title} onClick={(e) => e.stopPropagation()}>
        <h3>{title}</h3>
        {message && <p>{message}</p>}
        {children}
        <div className="dialog-actions">{actions}</div>
      </div>
    </div>
  );
}

export function Toggle({
  title,
  detail,
  checked,
  onChange,
}: {
  title: string;
  detail: string;
  checked: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <label className="toggle-row">
      <span>
        <strong>{title}</strong>
        <small>{detail}</small>
      </span>
      <input type="checkbox" role="switch" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      <i aria-hidden="true" />
    </label>
  );
}
