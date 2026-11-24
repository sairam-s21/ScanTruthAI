import { useCallback, useState } from "react";

export const PREF_KEYS = {
  criticalAllergenAlerts: "pref.criticalAllergenAlerts",
  intoleranceAlerts: "pref.intoleranceAlerts",
  weeklyDigest: "pref.weeklyDigest",
  strictSafetyPenalties: "pref.strictSafetyPenalties",
} as const;

const DIGEST_LAST_SHOWN = "pref.weeklyDigest.lastShown";
const WEEK_MS = 7 * 24 * 60 * 60 * 1000;

export function readBoolPref(key: string, fallback: boolean): boolean {
  try {
    const raw = localStorage.getItem(key);
    return raw === null ? fallback : raw === "true";
  } catch {
    return fallback;
  }
}

function writeBoolPref(key: string, value: boolean) {
  try {
    localStorage.setItem(key, String(value));
  } catch {
    // Storage blocked (private mode): the setting lasts for this page only.
  }
}

export function useBoolPref(key: string, fallback: boolean): [boolean, (v: boolean) => void] {
  const [value, setValue] = useState(() => readBoolPref(key, fallback));
  const update = useCallback(
    (v: boolean) => {
      writeBoolPref(key, v);
      setValue(v);
    },
    [key],
  );
  return [value, update];
}

/** Turns the weekly digest on or off. Returns false when the browser does not allow notifications. */
export async function setDigestEnabled(enabled: boolean): Promise<boolean> {
  if (!enabled) return true;
  if (typeof Notification === "undefined") return false;
  const permission =
    Notification.permission === "default"
      ? await Notification.requestPermission()
      : Notification.permission;
  if (permission !== "granted") return false;
  try {
    localStorage.setItem(DIGEST_LAST_SHOWN, String(Date.now()));
  } catch {
    // ignore
  }
  return true;
}

/**
 * Browsers cannot schedule a notification while the page is closed, so the weekly reminder
 * is shown the first time the app is opened a week or more after the previous one.
 */
export function showDigestIfDue() {
  if (!readBoolPref(PREF_KEYS.weeklyDigest, false)) return;
  if (typeof Notification === "undefined" || Notification.permission !== "granted") return;
  try {
    const last = Number(localStorage.getItem(DIGEST_LAST_SHOWN) || 0);
    if (Date.now() - last < WEEK_MS) return;
    localStorage.setItem(DIGEST_LAST_SHOWN, String(Date.now()));
  } catch {
    return;
  }
  new Notification("Your weekly scan digest", {
    body: "Open ScanTruthAI to review this week's scanned products against your health goals.",
  });
}
