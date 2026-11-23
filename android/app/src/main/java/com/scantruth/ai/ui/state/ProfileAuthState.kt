package com.scantruth.ai.ui.state

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.scantruth.ai.FirestoreRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** What the Profile screen knows about the current Firebase Auth session. */
sealed interface AuthSession {
    /** The first auth callback has not arrived yet. */
    data object Loading : AuthSession

    /** No Firebase user: show the Google Sign-In entry point. */
    data object SignedOut : AuthSession

    /** A real Firebase user is signed in; every field comes from that account. */
    data class SignedIn(
        val uid: String,
        val displayName: String?,
        val email: String?,
        val photoUrl: String?,
        val createdAtMillis: Long?
    ) : AuthSession
}

/**
 * UI + auth state for the Profile screen.
 *
 * Sign-in goes through Credential Manager (Google ID token) and is exchanged for a
 * Firebase session. No OAuth secret is stored here: the server client ID is the public
 * web client ID that the google-services plugin writes into resources, and the
 * resulting session is always the real Firebase user.
 */
@Stable
class ProfileAuthState(
    private val auth: FirebaseAuth,
    private val appContext: Context
) {

    var session by mutableStateOf<AuthSession>(AuthSession.Loading)
        private set

    var isSigningIn by mutableStateOf(false)
        private set

    var isSigningOut by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    private val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        session = firebaseAuth.currentUser?.toSession() ?: AuthSession.SignedOut
    }

    /** Registers the auth listener; the listener fires immediately with current state. */
    fun start() {
        auth.addAuthStateListener(authListener)
    }

    fun stop() {
        auth.removeAuthStateListener(authListener)
    }

    fun clearError() {
        errorMessage = null
    }

    /**
     * Launches the Google account chooser and signs the chosen account into Firebase.
     * Dismissing the chooser is a normal outcome: the user stays signed out and no
     * error is shown.
     */
    suspend fun signIn(context: Context) {
        if (isSigningIn) return
        isSigningIn = true
        errorMessage = null
        try {
            val serverClientId = context.resolveWebClientId()
                ?: throw SignInSetupException(
                    "Google Sign-In is not configured for this build yet. " +
                        "Add the web OAuth client to Firebase and re-download " +
                        "google-services.json."
                )
            val idToken = context.requestGoogleIdToken(serverClientId)
            val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
            auth.signInWithCredential(firebaseCredential).await()
        } catch (e: GetCredentialCancellationException) {
            // The user closed the account picker or pressed back.
            errorMessage = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: SignInSetupException) {
            errorMessage = e.message
        } catch (e: NoCredentialException) {
            errorMessage = "No Google account is available on this device. " +
                "Add one in Android Settings, then try again."
        } catch (e: GetCredentialException) {
            errorMessage = e.toUserMessage()
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            errorMessage = "Google returned a credential Firebase could not accept. " +
                "Check that the Google provider is enabled for this project."
        } catch (e: FirebaseAuthUserCollisionException) {
            errorMessage = "This Google account is already linked to a different " +
                "ScanTruthAI sign-in method."
        } catch (e: Exception) {
            errorMessage = e.localizedMessage
                ?.takeIf { it.isNotBlank() }
                ?.let { "Sign-in failed: $it" }
                ?: "Sign-in failed. Check your connection and try again."
        } finally {
            isSigningIn = false
        }
    }

    /**
     * Signs the current Firebase user out and clears everything this device cached for
     * them: the Firestore offline cache and captured label photos. Signed-out screens
     * can then never show the previous user's health data.
     */
    suspend fun signOut() {
        if (isSigningOut) return
        isSigningOut = true
        errorMessage = null
        try {
            auth.signOut()
            FirestoreRepository.clearLocalState(appContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = "Could not sign out. Please try again."
        } finally {
            isSigningOut = false
        }
    }
}

@Composable
fun rememberProfileAuthState(): ProfileAuthState {
    val context = LocalContext.current.applicationContext
    return remember(context) { ProfileAuthState(Firebase.auth, context) }
}

private fun FirebaseUser.toSession(): AuthSession.SignedIn = AuthSession.SignedIn(
    uid = uid,
    displayName = displayName,
    email = email,
    photoUrl = photoUrl?.toString(),
    createdAtMillis = metadata?.creationTimestamp?.takeIf { it > 0L }
)

/**
 * The google-services plugin writes the project's public web OAuth client ID into this
 * generated resource. Resolving it at runtime means nothing is hardcoded and a missing
 * entry degrades into a readable setup message instead of a crash.
 */
private fun Context.resolveWebClientId(): String? {
    val resourceId = resources.getIdentifier("default_web_client_id", "string", packageName)
    if (resourceId == 0) return null
    return getString(resourceId).takeIf { it.isNotBlank() }
}

private suspend fun Context.requestGoogleIdToken(serverClientId: String): String {
    val credentialManager = CredentialManager.create(this)

    // Preferred path: accounts already authorized for this app. If there are none,
    // fall back to the full account picker instead of failing.
    val response = try {
        credentialManager.getCredential(this, googleIdRequest(serverClientId, filterByAuthorized = true))
    } catch (e: NoCredentialException) {
        credentialManager.getCredential(this, googleIdRequest(serverClientId, filterByAuthorized = false))
    }

    val credential = response.credential
    if (credential is CustomCredential &&
        credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
    ) {
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }
    throw SignInSetupException("Google did not return an ID token. Please try again.")
}

private fun googleIdRequest(serverClientId: String, filterByAuthorized: Boolean): GetCredentialRequest {
    val option = GetGoogleIdOption.Builder()
        .setServerClientId(serverClientId)
        .setFilterByAuthorizedAccounts(filterByAuthorized)
        .setAutoSelectEnabled(false)
        .build()
    return GetCredentialRequest.Builder()
        .addCredentialOption(option)
        .build()
}

private fun GetCredentialException.toUserMessage(): String {
    val detail = message?.takeIf { it.isNotBlank() }
    return when (this) {
        is NoCredentialException -> "No Google account is available on this device."
        else -> if (detail != null) {
            "Google Sign-In failed: $detail"
        } else {
            "Google Sign-In failed. Check your connection and try again."
        }
    }
}

/** A configuration problem we can explain to the user, not a crash. */
private class SignInSetupException(message: String) : Exception(message)
