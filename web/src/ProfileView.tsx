import { type ReactNode, useState } from "react";
import type { User } from "firebase/auth";
import { PREF_KEYS, setDigestEnabled, useBoolPref } from "./preferences";
import {
  BrandHeader,
  Dialog,
  FilledButton,
  Icon,
  type IconName,
  IconTile,
  OutlinedButton,
  Toggle,
} from "./ui";

function providerLabel(user: User): string {
  const id = user.providerData[0]?.providerId;
  if (id === "google.com") return "Google";
  if (id === "password") return "Email and password";
  if (id === "apple.com") return "Apple";
  return id ?? "your account";
}

function InfoRows({ rows }: { rows: [string, string][] }) {
  return (
    <div className="info-rows">
      {rows.map(([title, text]) => (
        <div key={title}>
          <strong>{title}</strong>
          <p>{text}</p>
        </div>
      ))}
    </div>
  );
}

function ExpandableCard({
  title,
  subtitle,
  icon,
  purple,
  children,
}: {
  title: string;
  subtitle: string;
  icon: IconName;
  purple?: boolean;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  return (
    <section className="card stack gap-14">
      <button className="expand-header" onClick={() => setOpen(!open)} aria-expanded={open}>
        <IconTile name={icon} size={40} tone={purple ? "purple" : "mint"} />
        <span className="row-copy">
          <strong>{title}</strong>
          <small>{subtitle}</small>
        </span>
        <span className={`chevron${open ? " open" : ""}`}>
          <Icon name="chevronDown" size={14} stroke={2.4} />
        </span>
      </button>
      {open && (
        <>
          <hr />
          {children}
        </>
      )}
    </section>
  );
}

export default function ProfileView({
  user,
  authLoading,
  authWorking,
  authError,
  onSignIn,
  onSignOut,
  onClearSession,
}: {
  user: User | null;
  authLoading: boolean;
  authWorking: boolean;
  authError: string | null;
  onSignIn: () => void;
  onSignOut: () => void;
  onClearSession: () => void;
}) {
  const [allergenAlerts, setAllergenAlerts] = useBoolPref(PREF_KEYS.criticalAllergenAlerts, true);
  const [intoleranceAlerts, setIntoleranceAlerts] = useBoolPref(PREF_KEYS.intoleranceAlerts, true);
  const [weeklyDigest, setWeeklyDigest] = useBoolPref(PREF_KEYS.weeklyDigest, false);
  const [strictPenalties, setStrictPenalties] = useBoolPref(PREF_KEYS.strictSafetyPenalties, true);

  const [aboutOpen, setAboutOpen] = useState(false);
  const [confirmClear, setConfirmClear] = useState(false);
  const [clearedMessage, setClearedMessage] = useState<string | null>(null);
  const [notificationNote, setNotificationNote] = useState<string | null>(null);

  const toggleDigest = async (enabled: boolean) => {
    if (await setDigestEnabled(enabled)) {
      setWeeklyDigest(enabled);
      setNotificationNote(null);
    } else {
      setWeeklyDigest(false);
      setNotificationNote("Allow notifications for this site in your browser settings to turn on the weekly digest.");
    }
  };

  const memberSince = user?.metadata.creationTime
    ? new Date(user.metadata.creationTime).toLocaleDateString(undefined, {
        day: "numeric",
        month: "short",
        year: "numeric",
      })
    : "-";

  return (
    <div className="stack gap-18">
      <BrandHeader subtitle="ACCOUNT & PRIVACY" />
      <h1 className="screen-title">Profile</h1>

      {user ? (
        <section className="card account-card stack gap-16">
          <div className="row gap-14">
            <span className="avatar">
              {user.photoURL ? (
                <img src={user.photoURL} alt="" referrerPolicy="no-referrer" />
              ) : (
                <Icon name="user" size={28} />
              )}
            </span>
            <div className="stack gap-4 min0">
              <h2 className="account-name">{user.displayName || "Signed in"}</h2>
              <p className="muted truncate">{user.email}</p>
              <span className="provider-pill">
                <i /> Signed in with {providerLabel(user)}
              </span>
            </div>
          </div>
          <hr />
          <div className="stack gap-12">
            <button className="about-toggle" onClick={() => setAboutOpen(!aboutOpen)} aria-expanded={aboutOpen}>
              <Icon name="info" size={15} />
              About
              <span className={`chevron${aboutOpen ? " open" : ""}`}>
                <Icon name="chevronDown" size={13} stroke={2.4} />
              </span>
            </button>
            {aboutOpen && (
              <dl className="about-rows">
                <dt>Account ID</dt>
                <dd className="mono">{user.uid}</dd>
                <dt>Member since</dt>
                <dd>{memberSince}</dd>
                <dt>Sign-in method</dt>
                <dd>{providerLabel(user)}</dd>
              </dl>
            )}
          </div>
          <OutlinedButton icon="logout" danger full large onClick={onSignOut}>
            Sign out
          </OutlinedButton>
        </section>
      ) : (
        <section className="card account-card stack gap-14">
          <div className="row gap-14">
            <span className="avatar">
              <Icon name="user" size={28} />
            </span>
            <div className="stack gap-4">
              <h2 className="account-name small">
                {authLoading ? "Checking sign-in status…" : "You are not signed in"}
              </h2>
              <p className="muted">
                Sign in to save your health profile and scan history to your account, and see them on every device.
              </p>
            </div>
          </div>
          <FilledButton icon={authWorking ? undefined : "key"} disabled={authWorking || authLoading} onClick={onSignIn}>
            {authWorking ? "Signing in..." : "Sign in with Google"}
          </FilledButton>
        </section>
      )}

      {authError && <p className="error-text">{authError}</p>}

      <ExpandableCard title="Privacy & Data" subtitle="What leaves this device, and when" icon="shield">
        <InfoRows
          rows={[
            [
              "HIPAA Safe Harbor & ABDM Redaction",
              "Patient names, doctor references, medical record IDs, Aadhaar/SSN, phone numbers and dates of birth are automatically stripped on this device before any health analysis.",
            ],
            [
              "Clinical Data Preservation",
              "Only anonymized clinical markers, diagnoses, lab ranges and allergies are kept to personalize product safety checks.",
            ],
            [
              "Local Storage Isolation",
              "Your health context is stored under your own account. This browser keeps only a temporary cache in the site's private storage.",
            ],
          ]}
        />
        <OutlinedButton danger full onClick={() => setConfirmClear(true)}>
          Clear session health context
        </OutlinedButton>
        {clearedMessage && <p className="fine-print">{clearedMessage}</p>}
      </ExpandableCard>

      <ExpandableCard title="AI Preferences" subtitle="How the analysis engines are chosen" icon="sparkles" purple>
        <InfoRows
          rows={[
            [
              "Cloud Engine: Gemini 3.5 Flash Lite",
              "High-speed clinical reasoning that evaluates allergen conflicts, glycemic impact and nutrient interactions. Only masked text and ingredient names are sent.",
            ],
            [
              "On-Device Guard: Gemini Nano",
              "A model that runs inside Chrome to mask private details and, when you choose On-device, to analyze ingredients with zero network transmission. Needs a browser with built-in AI.",
            ],
            [
              "Analysis modes",
              "Auto uses the cloud when it is reachable and falls back to on-device. Gemini Flash is cloud only. On-device never leaves your device.",
            ],
          ]}
        />
        <hr />
        <Toggle
          title="Strict Clinical Safety Penalties"
          detail='Enforce an automatic "Avoid" verdict for high-risk conflicts (e.g. whey protein with lactose intolerance).'
          checked={strictPenalties}
          onChange={setStrictPenalties}
        />
      </ExpandableCard>

      <ExpandableCard title="Notifications" subtitle="Ingredient alert settings" icon="bell">
        <p className="muted small">Customize the safety warnings you get when scanning food or medicine labels.</p>
        <Toggle
          title="Critical Allergen Warnings"
          detail="Instant alerts for nuts, dairy, gluten and severe allergens."
          checked={allergenAlerts}
          onChange={setAllergenAlerts}
        />
        <hr />
        <Toggle
          title="Intolerance & Sensitivity Alerts"
          detail="Flag lactose, high sodium and glycemic sugar spikes."
          checked={intoleranceAlerts}
          onChange={setIntoleranceAlerts}
        />
        <hr />
        <Toggle
          title="Weekly Health Scan Digest"
          detail="A weekly reminder to review your scanned products against your health goals."
          checked={weeklyDigest}
          onChange={toggleDigest}
        />
        {notificationNote && <p className="error-text">{notificationNote}</p>}
      </ExpandableCard>

      <ExpandableCard title="Security" subtitle="How your data and session are protected" icon="lock">
        <InfoRows
          rows={[
            [
              "Firebase App Check",
              "Requests are attested with Firebase App Check (reCAPTCHA Enterprise, or a debug token in development) to block unauthorized API use.",
            ],
            ["Per-User Data Isolation", "Firestore rules enforce strict isolation: request.auth.uid == userId."],
            ["Encryption in Transit & at Rest", "TLS 1.3 transport and encrypted database storage."],
            ["Current Session", user?.email ? `Authenticated as ${user.email}` : "Not signed in"],
          ]}
        />
      </ExpandableCard>

      {confirmClear && (
        <Dialog
          title="Clear session health context?"
          message="This removes the health details and scans held in this browser for this session. Your saved account data is not deleted."
          onClose={() => setConfirmClear(false)}
          actions={
            <>
              <button className="bar-button" onClick={() => setConfirmClear(false)}>
                Cancel
              </button>
              <button
                className="bar-button danger"
                onClick={() => {
                  onClearSession();
                  setConfirmClear(false);
                  setClearedMessage(
                    "Cleared from this browser session. Data saved in your account is not deleted and still syncs from the cloud.",
                  );
                }}
              >
                Clear
              </button>
            </>
          }
        />
      )}
    </div>
  );
}
