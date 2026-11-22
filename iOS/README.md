# ScanTruthAI (iOS)

Privacy-first ingredient analyzer. Scan a food or medicine label (or type the ingredients), and the app
scores it against your health profile. Private details in medical reports are masked on the device
before anything is sent to the cloud.

Part of the cross-platform ScanTruthAI project. Web, Android and iOS share one Firebase project
(`scantruthai`) and the same Firestore schema under `users/{uid}/...`.

## Requirements
- Xcode 27 or newer. The deployment target is iOS 27.2.
- A physical iPhone or the Simulator. Apple Foundation Models (on-device AI) need Apple Intelligence.

## First-time setup
Three things are deliberately not in the repo. Without them the app builds but cloud features fail.

1. **`GoogleService-Info.plist`** - ask the team lead (Sairam) for it and add it to the `ScanTruthAI`
   target, next to `ScanTruthAIApp.swift`. It is git-ignored; never commit it.
2. **An App Check debug token** - Firebase AI Logic rejects requests without App Check.
   - Xcode: *Product > Scheme > Edit Scheme > Run > Arguments > Environment Variables*, add
     `FIRAAppCheckDebugToken` = your own token (the exact name, no trailing space).
   - Have someone with Firebase console access register it under *App Check > Apps > iOS > Manage debug tokens*.
   - The shared scheme is git-ignored so tokens are not committed. Use your own token, and delete it when done.
3. **Run from Xcode (Cmd+R)** for cloud features. The token is only passed when Xcode launches the app;
   opening the app from the home screen afterwards uses a different, unregistered token and returns 401.

Google sign-in uses Firebase's browser flow. The first attempt after launch sometimes shows a
"missing initial state" page; tap Sign In again.

## Layout
| File | Purpose |
|---|---|
| `HybridAIService.swift` | 4-stage pipeline: privacy gate (Apple model + regex), Gemini extraction, ingredient analysis (auto / flash / on-device) |
| `OcrService.swift` | Vision OCR, PDF text, ingredient-list parser |
| `FirestoreService.swift` | Reads and writes `users/{uid}/medicalProfile`, `medicalReports`, `healthNotes`, `scanHistory` |
| `AuthService.swift` | Firebase Auth state and Google sign-in |
| `HomeView`, `ScanView`, `MyHealthView`, `ProfileView`, `HistoryView` | Screens |
| `Components.swift`, `Theme.swift` | Shared UI |

Field names in Firestore must stay identical across platforms (see `SPEC.md` in the team repo).
