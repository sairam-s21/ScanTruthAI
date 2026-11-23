//
//  AuthService.swift
//  ScanTruthAI
//

import Foundation
import FirebaseCore
import FirebaseAuth

/// Firebase Auth state for the UI. Uses Firebase's built-in Google OAuth web flow, so no extra SDK is needed.
/// The reversed client ID from GoogleService-Info.plist must be registered as a URL scheme (CFBundleURLTypes).
@Observable
@MainActor
final class AuthService {
    static let shared = AuthService()

    /// Firebase uid of the signed-in account; Firestore data is stored under users/{uid}.
    private(set) var uid: String?
    private(set) var photoURL: URL?
    private(set) var memberSince: Date?
    /// Human-readable sign-in method, e.g. "Google".
    private(set) var providerLabel: String?
    private(set) var displayName: String?
    private(set) var email: String?
    private(set) var isSignedIn = false
    private(set) var isWorking = false
    var errorMessage: String?

    private var listener: AuthStateDidChangeListenerHandle?

    private init() {}

    /// Call once after `FirebaseApp.configure()`. Safe to call when Firebase is not configured.
    func start() {
        guard FirebaseApp.app() != nil, listener == nil else { return }

        listener = Auth.auth().addStateDidChangeListener { [weak self] _, user in
            let uid = user?.uid
            let name = user?.displayName
            let email = user?.email
            let signedIn = user != nil
            let photoURL = user?.photoURL
            let memberSince = user?.metadata.creationDate
            let providerID = user?.providerData.first?.providerID
            Task { @MainActor in
                self?.uid = uid
                self?.photoURL = photoURL
                self?.memberSince = memberSince
                self?.providerLabel = Self.label(forProviderID: providerID)
                self?.displayName = name
                self?.email = email
                self?.isSignedIn = signedIn
                // Home history must always belong to the account that is signed in now.
                ScanHistoryStore.shared.userDidChange(to: uid)
                HealthProfileStore.shared.userDidChange(to: uid)
            }
        }
    }

    private static func label(forProviderID providerID: String?) -> String? {
        switch providerID {
        case "google.com": return "Google"
        case "password": return "Email and password"
        case "apple.com": return "Apple"
        case .some(let other): return other
        case .none: return nil
        }
    }

    func signInWithGoogle() async {
        guard FirebaseApp.app() != nil else {
            errorMessage = "Sign-in is unavailable because Firebase is not configured."
            return
        }

        isWorking = true
        errorMessage = nil
        defer { isWorking = false }

        do {
            let provider = OAuthProvider(providerID: "google.com")
            provider.scopes = ["email", "profile"]
            // Always show Google's account chooser, so a remembered browser session cannot silently pick the account.
            provider.customParameters = ["prompt": "select_account"]
            let credential = try await provider.credential(with: nil)
            _ = try await Auth.auth().signIn(with: credential)
        } catch {
            let code = (error as NSError).code
            if code == AuthErrorCode.webContextCancelled.rawValue {
                // The sheet was closed. The first attempt after launch often shows Firebase's "missing initial state"
                // page because the in-app browser starts cold; a second try works, so say so.
                errorMessage = "Sign-in was not completed. If the page showed an error, tap Sign In with Google again. It usually works on the second try."
            } else {
                errorMessage = "Sign-in failed: \(error.localizedDescription)"
            }
        }
    }

    func signOut() {
        guard FirebaseApp.app() != nil else { return }
        do {
            try Auth.auth().signOut()
            // Do not leave the previous user's health data on screen.
            HealthProfileStore.shared.reset()
            ScanHistoryStore.shared.reset()
            errorMessage = nil
        } catch {
            errorMessage = "Sign-out failed: \(error.localizedDescription)"
        }
    }
}
