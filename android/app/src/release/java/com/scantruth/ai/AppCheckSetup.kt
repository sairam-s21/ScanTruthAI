package com.scantruth.ai

import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * Release builds attest with Play Integrity only. No debug provider, no debug token.
 *
 * This lives in the release source set so the release artifact cannot accidentally
 * include the debug App Check factory.
 */
internal fun installAppCheckProvider() {
    Firebase.appCheck.installAppCheckProviderFactory(
        PlayIntegrityAppCheckProviderFactory.getInstance()
    )
}
