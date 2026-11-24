package com.scantruth.ai

import android.app.Application
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Firebase
import com.google.firebase.firestore.firestore
import com.google.firebase.firestore.firestoreSettings
import com.google.firebase.firestore.memoryCacheSettings
import com.google.firebase.firestore.persistentCacheSettings
import com.google.firebase.initialize
import kotlin.concurrent.thread

class ScanTruthApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Firebase.initialize(this)
        AppPreferences.init(this)

        // 1. Configure App Check before any Firebase AI Logic calls. The provider lives in
        //    the variant source sets: debug uses the debug token provider, release uses
        //    Play Integrity. Neither variant references the other's factory class.
        installAppCheckProvider()

        // 2. Firestore cache. After a sign-out the previous account's disk cache must
        //    never be readable again, so a pending clear means this process runs with a
        //    memory-only cache until the disk cache has actually been wiped.
        val cacheClearPending = FirestoreRepository.firestoreCacheClearPending(this)

        Firebase.firestore.firestoreSettings = firestoreSettings {
            setLocalCacheSettings(
                if (cacheClearPending) memoryCacheSettings {} else persistentCacheSettings {}
            )
        }

        // 3. Finish the pending sign-out clear. clearPersistence() only succeeds before
        //    Firestore is used in a process, so it runs here, before the repository is
        //    released to any screen.
        if (cacheClearPending) {
            thread(name = "firestore-cache-hygiene", isDaemon = true) {
                try {
                    Tasks.await(Firebase.firestore.clearPersistence())
                    FirestoreRepository.markFirestoreCacheCleared(this)
                } catch (error: Throwable) {
                    // Keep the flag: the next launch retries and stays memory-only.
                } finally {
                    FirestoreRepository.markReady()
                }
            }
        } else {
            FirestoreRepository.markReady()
        }
    }
}
