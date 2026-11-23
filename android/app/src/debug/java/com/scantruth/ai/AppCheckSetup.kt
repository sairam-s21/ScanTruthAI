package com.scantruth.ai

import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug builds use the App Check debug provider, so a local emulator/device can obtain a
 * token as soon as its debug token is registered in the Firebase console.
 *
 * This lives in the debug source set because `firebase-appcheck-debug` is a
 * `debugImplementation` dependency; referencing it from `main` broke release compilation.
 */
internal fun installAppCheckProvider() {
    Firebase.appCheck.installAppCheckProviderFactory(
        DebugAppCheckProviderFactory.getInstance()
    )
}
