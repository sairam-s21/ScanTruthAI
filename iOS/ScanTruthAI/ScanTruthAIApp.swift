//
//  ScanTruthAIApp.swift
//  ScanTruthAI
//
//  Created by s saravana on 19/11/26.
//

import SwiftUI
import FirebaseCore
import FirebaseAppCheck
import FirebaseAuth

@main
struct ScanTruthAIApp: App {
    init() {
        // App Check must be set before configure(). Firebase AI Logic rejects calls without it.
        #if DEBUG
        AppCheck.setAppCheckProviderFactory(AppCheckDebugProviderFactory())
        #endif

        // FirebaseApp.configure() crashes if GoogleService-Info.plist is missing from the app bundle.
        if Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil {
            FirebaseApp.configure()
            AuthService.shared.start()
        } else {
            print("⚠️ GoogleService-Info.plist not found in the app bundle. Firebase is not configured.")
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL { url in
                    // Hands the Google sign-in redirect back to Firebase Auth.
                    if FirebaseApp.app() != nil {
                        _ = Auth.auth().canHandle(url)
                    }
                }
        }
    }
}
