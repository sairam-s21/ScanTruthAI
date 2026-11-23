package com.scantruth.ai.ui.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.scantruth.ai.FirestoreRepository
import com.scantruth.ai.FirestoreRepository.StoreResult
import com.scantruth.ai.model.MedicalProfile
import com.scantruth.ai.model.ScanRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Home dashboard state: who is signed in, whether a health profile exists, and the
 * most recent saved scans. Everything comes from FirebaseAuth and Firestore; there is
 * no placeholder user and no demo data.
 */
@Stable
class HomeUiState {

    var isSignedIn by mutableStateOf(runCatching { Firebase.auth.currentUser != null }.getOrDefault(false))
        private set

    var displayName by mutableStateOf<String?>(null)
        private set

    var email by mutableStateOf<String?>(null)
        private set

    var profile by mutableStateOf(MedicalProfile())
        private set

    var recentScans by mutableStateOf<List<ScanRecord>>(emptyList())
        private set

    var isLoading by mutableStateOf(true)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    private var listener: FirebaseAuth.AuthStateListener? = null

    fun start(scope: CoroutineScope) {
        val auth = runCatching { Firebase.auth }.getOrNull() ?: return
        if (listener == null) {
            listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
                val user = firebaseAuth.currentUser
                isSignedIn = user != null
                displayName = user?.displayName?.takeIf { it.isNotBlank() }
                    ?: user?.email?.substringBefore('@')
                email = user?.email
                if (user != null) {
                    refresh(scope)
                } else {
                    profile = MedicalProfile()
                    recentScans = emptyList()
                    isLoading = false
                }
            }
            auth.addAuthStateListener(listener!!)
        }
    }

    fun stop() {
        val auth = runCatching { Firebase.auth }.getOrNull()
        listener?.let { auth?.removeAuthStateListener(it) }
        listener = null
    }

    fun refresh(scope: CoroutineScope) {
        if (!isSignedIn) {
            isLoading = false
            return
        }
        scope.launch {
            isLoading = true
            error = null

            when (val stored = FirestoreRepository.loadMedicalProfile()) {
                is StoreResult.Success -> profile = stored.value ?: MedicalProfile()
                is StoreResult.Failure ->
                    if (!stored.notSignedIn) error = stored.message
            }

            when (val scans = FirestoreRepository.loadRecentScans(limit = 5)) {
                is StoreResult.Success -> recentScans = scans.value
                is StoreResult.Failure ->
                    if (!scans.notSignedIn) error = error ?: scans.message
            }

            isLoading = false
        }
    }
}
