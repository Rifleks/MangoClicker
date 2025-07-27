package rifleks.clicker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.SharedPreferences
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import com.google.android.gms.auth.api.identity.BeginSignInRequest
import com.google.android.gms.auth.api.identity.GetSignInIntentRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.SignInClient
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.lang.Exception

object Auth {
    // Authentication clients
    private lateinit var oneTapClient: SignInClient
    private lateinit var signInRequest: BeginSignInRequest

    // Firebase services
    private val auth: FirebaseAuth = Firebase.auth
    private val database: DatabaseReference = Firebase.database.reference

    // Coroutine scope for auth operations
    private val authScope = CoroutineScope(Dispatchers.IO + Job())

    // Current session state
    private var currentUserID: String? = null
    private lateinit var prefs: SharedPreferences

    /**
     * Initializes authentication services
     * @param context Application context
     */
    fun initialize(context: Context) {
        oneTapClient = Identity.getSignInClient(context)
        signInRequest = createSignInRequest(context)
        prefs = context.getSharedPreferences("AuthPrefs", Context.MODE_PRIVATE)
        restoreSession()
    }

    fun setCurrentUserID(id: String?) {
        currentUserID = id
    }

    private fun createSignInRequest(context: Context): BeginSignInRequest {
        return BeginSignInRequest.builder()
            .setGoogleIdTokenRequestOptions(
                BeginSignInRequest.GoogleIdTokenRequestOptions.builder()
                    .setSupported(true)
                    .setServerClientId(context.getString(R.string.default_web_client_id))
                    .setFilterByAuthorizedAccounts(false)
                    .build())
            .setAutoSelectEnabled(true)
            .build()
    }

    private fun restoreSession() {
        currentUserID = auth.currentUser?.uid
    }

    /**
     * Returns current authenticated user ID
     */
    fun getCurrentUserID(): String? = auth.currentUser?.uid

    /**
     * Checks if user is authenticated
     */
    fun isAuthenticated(): Boolean = auth.currentUser != null

    /**
     * Starts the Google sign-in flow
     * @param activity Host activity
     * @param requestCode Request code for activity result
     */
    fun startSignIn(activity: Activity, requestCode: Int) {
        oneTapClient.beginSignIn(signInRequest)
            .addOnSuccessListener { result ->
                launchSignInIntent(activity, result.pendingIntent.intentSender, requestCode)
            }
            .addOnFailureListener { e ->
                Log.e("Auth", "Sign-in failed", e)
                fallbackToBrowserSignIn(activity, requestCode)
            }
    }

    private fun fallbackToBrowserSignIn(activity: Activity, requestCode: Int) {
        val signInRequest = GetSignInIntentRequest.builder()
            .setServerClientId(activity.getString(R.string.default_web_client_id))
            .build()

        Identity.getSignInClient(activity)
            .getSignInIntent(signInRequest)
            .addOnSuccessListener { pendingIntent ->
                try {
                    activity.startIntentSenderForResult(
                        pendingIntent.intentSender,
                        requestCode,
                        null, 0, 0, 0, null
                    )
                } catch (e: IntentSender.SendIntentException) {
                    Log.e("Auth", "Browser sign-in failed to launch", e)
                }
            }
            .addOnFailureListener { e ->
                Log.e("Auth", "Browser sign-in failed", e)
            }
    }


    private fun launchSignInIntent(
        activity: Activity,
        intentSender: IntentSender,
        requestCode: Int
    ) {
        try {
            activity.startIntentSenderForResult(
                intentSender,
                requestCode,
                null, 0, 0, 0, null
            )
        } catch (e: Exception) {
            Log.e("Auth", "Failed to launch sign-in", e)
        }
    }

    /**
     * Handles sign-in result
     * @param data Intent with sign-in result
     * @param onSuccess Callback with user ID
     * @param onFailure Callback with error message
     */
    fun handleSignInResult(
        data: Intent?,
        onSuccess: (userId: String) -> Unit,
        onFailure: (error: String) -> Unit
    ) {
        authScope.launch {
            try {
                // Получаем credential
                val credential = oneTapClient.getSignInCredentialFromIntent(data)

                val idToken = credential.googleIdToken
                    ?: return@launch // ✅ просто выходим без onFailure, если пользователь закрыл окно

                val firebaseUser = authenticateWithFirebase(idToken)
                persistUserSession(firebaseUser)

                withContext(Dispatchers.Main) {
                    onSuccess(firebaseUser.uid)
                }
            } catch (e: Exception) {
                Log.e("Auth", "Sign-in error", e)
                withContext(Dispatchers.Main) {
                    // ✅ Показываем ошибку только если пользователь **не закрыл окно**, а вход реально провалился
                    if (e.message?.contains("13") == false) {
                        onFailure(parseAuthError(e))
                    }
                }
            }
        }
    }


    private suspend fun authenticateWithFirebase(idToken: String): FirebaseUser {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        return auth.signInWithCredential(credential).await().user
            ?: throw IllegalStateException("Firebase authentication failed")
    }

    private fun persistUserSession(user: FirebaseUser) {
        currentUserID = user.uid
        prefs.edit {
            putBoolean("isLoggedIn", true)
            putString("userId", user.uid)
        }
    }

    /**
     * Signs out the current user
     */
    fun signOut(context: Context, onComplete: () -> Unit) {
        authScope.launch {
            try {
                oneTapClient.signOut().await()
                auth.signOut()
                clearSession()
                withContext(Dispatchers.Main) {
                    onComplete()
                }
            } catch (e: Exception) {
                Log.e("Auth", "Sign-out failed", e)
                forceSignOut(context, onComplete)
            }
        }
    }

    private fun forceSignOut(context: Context, onComplete: () -> Unit) {
        auth.signOut()
        clearSession()
        onComplete()
    }

    private fun clearSession() {
        currentUserID = null
        prefs.edit { clear() }
    }

    // Cloud Data Operations

    /**
     * Loads game data from cloud
     * @param userId User ID to load data for
     * @return Result wrapper with GameData or error
     */
    suspend fun loadGameData(userID: String): Result<GameData> = withContext(Dispatchers.IO) {
        try {
            val snapshot = database.child("users").child(userID).get().await()
            val gameData = snapshot.getValue(GameData::class.java) ?: GameData()
            Result.success(gameData)
        } catch (e: Exception) {
            Log.e("Auth", "Load failed", e)
            Result.failure(e)
        }
    }

    /**
     * Saves game data to cloud
     * @param userId User ID to save data for
     * @param gameData Game data to save
     * @return Result wrapper with success or error
     */

    suspend fun saveGameData(userID: String, gameData: GameData): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            database.child("users").child(userID).setValue(gameData).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("Auth", "Save failed", e)
            Result.failure(e)
        }
    }

    /**
     * Shows sign-in dialog when authentication is required
     */
    fun showSignInDialog(
        context: Context,
        onSignIn: () -> Unit,
        onSkip: () -> Unit
    ) {
        val dialog = AlertDialog.Builder(context)
            .setTitle("Авторизация")
            .setMessage("Войдите в аккаунт, чтобы синхронизировать прогресс")
            .setCancelable(false)
            .setPositiveButton("Войти") { dialogInterface, _ ->
                dialogInterface.dismiss()
                onSignIn()
            }
            .setNegativeButton("Пропустить") { dialogInterface, _ ->
                dialogInterface.dismiss()
                onSkip()
            }
            .create()

        dialog.show()
    }


    // Helper functions
    private fun parseAuthError(e: Exception): String {
        return when (e) {
            is ApiException -> when (e.statusCode) {
                10 -> "Developer error"
                12501 -> "Sign-in cancelled"
                else -> "Authentication failed"
            }
            else -> e.message ?: "Unknown error"
        }
    }

    sealed class ResultWrapper<out T> {
        data class Success<out T>(val data: T) : ResultWrapper<T>()
        data class Error(val exception: Exception) : ResultWrapper<Nothing>()
    }
}