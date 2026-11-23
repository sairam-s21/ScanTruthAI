# Firebase setup: what is still missing

Nothing in the Firebase Console has been changed by the app work. This file records the
exact gap and the steps needed, so the configuration can be applied deliberately.

## Current state (verified locally)

- `app/google-services.json`: project `scantruthai`, package `com.scantruth.ai`,
  `appinvite_service` configured.
- Its only `oauth_client` entry has `client_type: 3` — a **web** client.
- There is **no Android OAuth client (`client_type: 1`)** and therefore no SHA-1
  registered for `com.scantruth.ai`.
- Consequence: `Credential Manager` returns no Google ID token for this app. Sign-in
  cannot complete, so every Firebase-dependent test (save, history, profile, sign-out,
  account switch) is blocked. The Profile screen currently shows
  "Authentication problem — No Google account is available on this device."
- Firestore denies unauthenticated reads: `403 PERMISSION_DENIED` on
  `/v1/projects/scantruthai/databases/(default)/documents/users/unauth-probe/medicalProfile/current`.
  So the deployed rules are not open, but UID isolation with a real session is unverified.

Debug signing certificate of this machine:

```
SHA1: D3:EC:0D:5E:E9:15:45:CA:B6:74:6C:8D:57:E1:D2:F7:3F:80:58:15
```

Read it again any time with `./gradlew :app:signingReport`.

## Required steps (Firebase Console, needs approval)

1. **Register the Android app fingerprint(s)**
   Firebase Console → Project settings → Your apps → Android app `com.scantruth.ai`
   → *Add fingerprint* → paste the debug SHA-1 above. Add the release/Play signing SHA-1
   when a release build exists.
2. **Download the regenerated `google-services.json`**
   Replace `android/app/google-services.json` with it. The new file must contain an
   `oauth_client` with `client_type: 1`. Without that, sign-in still fails.
3. **Enable the Google provider**
   Authentication → Sign-in method → Google → Enable, with a support email.
4. **Firestore rules (deploy only when approved)**
   Data lives under `users/{uid}/medicalProfile/current`,
   `users/{uid}/medicalReports/{id}` and `users/{uid}/scanHistory/{id}`. Rules must allow
   read/write only where `request.auth.uid == uid`, and deny everything else. The app
   never sends a caller-supplied uid, so a correct ruleset makes cross-account reads
   impossible.
5. **Firebase AI Logic / App Check**
   Enable AI Logic and the Gemini API for the project. AI Logic requires App Check;
   register the debug App Check token (from logcat:
   `Firebase App Check debug token: ...`) in App Check → Apps → Manage debug tokens, or
   registration will block cloud calls even after sign-in.
6. **Emulator account**
   The AVD needs a signed-in Google account
   (Settings → Passwords & accounts → Add account) before step 3's flow can return a
   credential.

## How to verify after configuration

```
./gradlew :app:signingReport          # SHA-1 must match the Console entry
node -e "const c=require('./app/google-services.json');console.log((c.client[0].oauth_client||[]).map(o=>o.client_type))"
                                      # must include 1
```

Then, with the debug APK on an emulator that has a Google account: sign in, open My
Health, approve and save a report, check `users/{uid}/medicalReports` in the Firestore
console, sign out, sign in as a second account and confirm neither profile nor history
from the first account is visible.
