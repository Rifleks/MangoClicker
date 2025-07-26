package rifleks.clicker

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AlertDialog
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.LocalDate

object Auth {
    @SuppressLint("StaticFieldLeak")
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var auth: FirebaseAuth
    private val database = FirebaseDatabase.getInstance().reference

    private var currentUserID: String? = null

    fun getCurrentUserID(): String?{
        return currentUserID
    }

    internal fun setCurrentUserID(value: String?){
        currentUserID = value
    }

    fun initialize(context: Context) {
        // Configure Google Sign In
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(R.string.default_web_client_id))
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(context, gso)
        auth = Firebase.auth
    }

    fun signIn(activity: Activity) {
        val signInIntent = googleSignInClient.signInIntent
        activity.startActivityForResult(signInIntent, MainActivity.RC_SIGN_IN)
    }

    fun handleSignInResult(
        context: Context,
        intent: Intent?,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(intent)
                val account = task.getResult(ApiException::class.java)

                if (account != null)
                    firebaseAuthWithGoogle(account.idToken!!, onSuccess, onFailure, context as Activity) // Передаем activity
                else
                    onFailure("Google Sign-in failed: Account is null")

            } catch (e: Exception) {
                onFailure("Google Sign-in failed: ${e.message}")
            }
        }
    }

    private suspend fun firebaseAuthWithGoogle(
        idToken: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit,
        activity: Activity
    ) {
        try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = auth.signInWithCredential(credential).await()
            setCurrentUserID(authResult.user?.uid)

            withContext(Dispatchers.Main) {
                onSuccess()
                activity.finish() // Закрываем окно авторизации
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onFailure("Firebase authentication failed: ${e.message}")
            }
        }
    }

    suspend fun loadGameData(
        userID: String,
        onSuccess: (GameData) -> Unit,
        onFailure: (String) -> Unit
    ) {
        try {
            val dataSnapshot = database.child("users").child(userID).get().await()
            if (dataSnapshot.exists()) {
                val data = dataSnapshot.value as? Map<*, *> // Получаем данные как Map
                if (data != null) {
                    val gameData = GameData(
                        clicks = (data["clicks"] as? Number)?.toLong() ?: 0L,
                        clickCooldown = (data["clickCooldown"] as? Number)?.toDouble() ?: 0.5,
                        mangoClickLevel = (data["mangoClickLevel"] as? Number)?.toInt() ?: 1,
                        lastClickTime = (data["lastClickTime"] as? Number)?.toLong() ?: 0L,
                        cooldownLevel = (data["cooldownLevel"] as? Number)?.toInt() ?: 0,
                        rebirthCount = (data["rebirthCount"] as? Number)?.toInt() ?: 0,
                        rebirthBonus = (data["rebirthBonus"] as? Number)?.toDouble() ?: 0.0
                    )
                    withContext(Dispatchers.Main) {
                        onSuccess(gameData)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onFailure("Failed to deserialize game data (null map).")
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    onSuccess(GameData()) // Return default values
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onFailure("Load failed: ${e.message}")
            }
        }
    }
    suspend fun saveGameData(userID: String, gameData: GameData, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        try {
            database.child("users").child(userID).setValue(gameData).await()
            withContext(Dispatchers.Main) {
                onSuccess()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onFailure("Save failed: ${e.message}")
            }
        }
    }
    suspend fun getLastSaveDate(userID: String): LocalDate? {
        return try {
            val dataSnapshot = database.child("users").child(userID).child("lastSaveDate").get().await()
            if (dataSnapshot.exists()) {
                LocalDate.parse(dataSnapshot.getValue(String::class.java))
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("Auth", "Error getting last save date: ${e.message}")
            null
        }
    }

    suspend fun setLastSaveDate(userID: String) {
        try {
            val currentDate = LocalDate.now().toString()
            database.child("users").child(userID).child("lastSaveDate").setValue(currentDate).await()
        } catch (e: Exception) {
            Log.e("Auth", "Error setting last save date: ${e.message}")
        }
    }

    fun showSignInDialog(context: Context, onPositiveClick: () -> Unit, onNegativeClick: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle("Google Sign-In Required")
            .setMessage("Please sign in with your Google account to save and load your progress.")
            .setPositiveButton("Sign In") { dialog, which ->
                onPositiveClick()
                dialog.dismiss()
            }
            .setNegativeButton("Skip") { dialog, which ->
                onNegativeClick()
                dialog.dismiss()
            }
            .setCancelable(false) // Prevent dismissing by tapping outside the dialog
            .show()
    }

    fun showConcurrentSessionDialog(context: Context, onDismiss: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle("Concurrent Session Detected")
            .setMessage("Your account is already in use on another device. This game will now exit.")
            .setPositiveButton("OK") { dialog, which ->
                onDismiss()
                dialog.dismiss()
            }
            .setCancelable(false) // Prevent dismissing by tapping outside the dialog
            .show()
    }

    fun signOut() {
        auth.signOut()
        setCurrentUserID(null)
    }
}