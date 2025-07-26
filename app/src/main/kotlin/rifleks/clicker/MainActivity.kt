package rifleks.clicker

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentTransaction
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow

class MainActivity : AppCompatActivity() {
    internal var clicks = 0L
    internal var clickCooldown = 0.5
    internal var mangoClickLevel = 1
    internal var lastClickTime = 0L
    internal var cooldownLevel = 0
    internal var rebirthCount = 0
    internal var rebirthBonus = 0.0
    private lateinit var mainLayout: View
    private var isClickerTabActive: Boolean = true
    private var isShopTabActive: Boolean = false
    private lateinit var mangoCounter: TextView
    private lateinit var prefs: SharedPreferences
    internal val handler = android.os.Handler(Looper.getMainLooper())
    private lateinit var clickerTab: AppCompatTextView
    private lateinit var shopTab: AppCompatTextView
    private var gameData = GameData()
    private var signInSkipped = false

    companion object {
        const val RC_SIGN_IN = 9001
        fun calculateDamage(level: Int): Int = level

        fun calculateDamageCost(level: Int): Long = 15L * level * level

        fun calculateCooldown(level: Int): Double = max(0.5 - (level * 0.03), 0.20)

        fun calculateCooldownCost(level: Int, currentCooldown: Double): Long {
            val baseCost = 135.0 * 1.5.pow(level.toDouble())
            val priceMultiplier = if (level <= 5) 2.0 else 1.0
            return (baseCost * priceMultiplier).toLong()
        }

        fun formatNumber(number: Long): String {
            val formatter = DecimalFormat().apply {
                maximumFractionDigits = 2
                minimumFractionDigits = 0
            }

            return when {
                number >= 1_000_000_000_000_000_000L -> "${formatter.format(number / 1_000_000_000_000_000_000.0)}Qi"
                number >= 1_000_000_000_000_000L -> "${formatter.format(number / 1_000_000_000_000_000.0)}Qa"
                number >= 1_000_000_000_000L -> "${formatter.format(number / 1_000_000_000_000.0)}T"
                number >= 1_000_000_000L -> "${formatter.format(number / 1_000_000_000.0)}B"
                number >= 1_000_000L -> "${formatter.format(number / 1_000_000.0)}M"
                number >= 1_000L -> "${formatter.format(number / 1_000.0)}K"
                else -> number.toString()
            }
        }

        fun calculateRebirthCost(rebirthCount: Int): Long {
            val baseCost = 500000.0
            val multiplier = 1.5.pow(rebirthCount.toDouble())
            return (baseCost * multiplier).toLong()
        }

        fun calculateRebirthBonus(rebirthCount: Int): Double = rebirthCount * 20.0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mainLayout = findViewById(R.id.mainLayout)
        window.apply {
            WindowCompat.setDecorFitsSystemWindows(this, false)
            val wic = WindowInsetsControllerCompat(this, decorView)
            wic.isAppearanceLightStatusBars = true
            statusBarColor = ContextCompat.getColor(this@MainActivity, android.R.color.transparent)
        }
        prefs = getSharedPreferences("ClickerPrefs", MODE_PRIVATE)
        Translation.loadTranslations(this)
        Auth.initialize(this)
        initViews()
        setupTabs()
        showClickerFragment()
        loadGame()
    }

    override fun onStart() {
        super.onStart()

        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account == null && !signInSkipped) {
            Auth.showSignInDialog(this,
                onPositiveClick = { Auth.signIn(this) },
                onNegativeClick = { signInSkipped = true }
            )
        } else if (account != null) {
            val userID = Auth.getCurrentUserID()
            userID?.let{
                loadGameFromCloud()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RC_SIGN_IN) {
            Auth.handleSignInResult(this, data,
                onSuccess = {
                    val account = GoogleSignIn.getLastSignedInAccount(this)
                    Auth.setCurrentUserID(account?.id)
                    loadGameFromCloud()
                    signInSkipped = false
                    Log.d("TAG", "Авторизация Успешна")
                },
                onFailure = { message ->
                    Log.e("SignIn", "Sign-in failed: $message")
                    signInSkipped = true
                },
            )
        }
    }

    private fun initViews() {
        mangoCounter = findViewById(R.id.mangoCounter)
        clickerTab = findViewById(R.id.clickerTab)
        shopTab = findViewById(R.id.shopTab)
        updateMangoCounter()

        clickerTab.text = Translation.translate("main_tab_text")
        shopTab.text = Translation.translate("shop_tab_text")
    }

    private fun setupTabs() {

        clickerTab.setOnClickListener {
            showClickerFragment()
        }

        shopTab.setOnClickListener {
            showShopFragment()
        }
    }

    private fun showClickerFragment() {
        if (supportFragmentManager.findFragmentById(R.id.contentFrame) is ClickerFragment) return
        isClickerTabActive = true
        isShopTabActive = false

        supportFragmentManager.beginTransaction()
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            .replace(R.id.contentFrame, ClickerFragment.newInstance())
            .commit()

        clickerTab.background = ContextCompat.getDrawable(this, R.drawable.rounded_tab_background)
        shopTab.setBackgroundColor(ContextCompat.getColor(this, android.R.color.transparent))
    }

    private fun showShopFragment() {
        if (supportFragmentManager.findFragmentById(R.id.contentFrame) is ShopFragment) return
        isShopTabActive = true
        isClickerTabActive = false

        supportFragmentManager.beginTransaction()
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            .replace(R.id.contentFrame, ShopFragment.newInstance())
            .commit()

        clickerTab.background = ContextCompat.getDrawable(this, android.R.color.transparent)
        shopTab.background = ContextCompat.getDrawable(this, R.drawable.rounded_tab_background)
    }

    @SuppressLint("SetTextI18n")
    internal fun updateMangoCounter() {
        mangoCounter.text = String.format(getString(R.string.mango_count), formatNumber(clicks)) + " 🥭"
        mangoCounter.contentDescription = String.format("%s 🥭", clicks.toString())
    }

    internal fun buyClickUpgrade(): Boolean {
        if (cooldownLevel >= 16) return false

        val price = calculateCooldownCost(cooldownLevel, clickCooldown)

        if (clicks >= price) {
            clicks -= price
            cooldownLevel++
            clickCooldown = calculateCooldown(cooldownLevel)
            updateMangoCounter()
            saveGame()
            return true
        }
        return false
    }

    internal fun handleClick(view: View) {
        val damage = calculateDamageWithRebirthBonus(mangoClickLevel)
        clicks += damage
        updateMangoCounter()
        saveGame()

        view.isHapticFeedbackEnabled = true
        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_PRESS)

        view.animate()
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(75)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(75)
            }
    }

    internal fun buyMangoClickUpgrade(): Boolean {
        if (mangoClickLevel >= 100) return false

        val price = calculateDamageCost(mangoClickLevel + 1)
        if (clicks >= price) {
            clicks -= price
            mangoClickLevel++
            updateMangoCounter()
            saveGame()
            return true
        }
        return false
    }

    private fun loadGame() {
        loadGameFromPrefs()
        //loadGameFromCloud()
    }

    private fun loadGameFromPrefs() {
        clicks = prefs.getLong("clicks", clicks)
        clickCooldown = prefs.getFloat("clickCooldown", clickCooldown.toFloat()).toDouble()
        mangoClickLevel = prefs.getInt("mangoClickLevel", mangoClickLevel)
        lastClickTime = prefs.getLong("lastClickTime", lastClickTime)
        cooldownLevel = prefs.getInt("cooldownLevel", cooldownLevel)
        rebirthCount = prefs.getInt("rebirthCount", rebirthCount)
        rebirthBonus = prefs.getFloat("rebirthBonus", rebirthBonus.toFloat()).toDouble()

        updateMangoCounter()
    }

    private fun saveGameToPrefs() {
        prefs.edit {
            putLong("clicks", clicks)
            putFloat("clickCooldown", clickCooldown.toFloat())
            putInt("mangoClickLevel", mangoClickLevel)
            putLong("lastClickTime", lastClickTime)
            putInt("cooldownLevel", cooldownLevel)
            putInt("rebirthCount", rebirthCount)
            putFloat("rebirthBonus", rebirthBonus.toFloat())
            apply()
        }
    }

    internal fun saveGame() {
        gameData.clicks = clicks
        gameData.clickCooldown = clickCooldown
        gameData.mangoClickLevel = mangoClickLevel
        gameData.lastClickTime = lastClickTime
        gameData.cooldownLevel = cooldownLevel
        gameData.rebirthCount = rebirthCount
        gameData.rebirthBonus = rebirthBonus
        saveGameToCloud()
        saveGameToPrefs()
    }

    private fun loadGameFromCloud() {
        val userID = Auth.getCurrentUserID() ?: return
        CoroutineScope(Dispatchers.IO).launch { // CoroutineScope 1
            Auth.loadGameData(userID,
                onSuccess = { data ->
                    CoroutineScope(Dispatchers.Main).launch { //CoroutineScope 2
                        clicks = data.clicks
                        clickCooldown = data.clickCooldown
                        mangoClickLevel = data.mangoClickLevel
                        lastClickTime = data.lastClickTime
                        cooldownLevel = data.cooldownLevel
                        rebirthCount = data.rebirthCount
                        rebirthBonus = data.rebirthBonus
                        updateMangoCounter()
                        gameData = data
                    }
                    checkConcurrentSession()
                },
                onFailure = { message ->
                    Log.e("CloudLoad", "Failed to load game data: $message")
                })
        }
    }

    private fun saveGameToCloud() {
        val userID = Auth.getCurrentUserID() ?: return
        gameData.clicks = clicks
        gameData.clickCooldown = clickCooldown
        gameData.mangoClickLevel = mangoClickLevel
        gameData.lastClickTime = lastClickTime
        gameData.cooldownLevel = cooldownLevel
        gameData.rebirthCount = rebirthCount
        gameData.rebirthBonus = rebirthBonus

        CoroutineScope(Dispatchers.IO).launch { // Coroutine Scope 1
            val lastSaveDate = Auth.getLastSaveDate(userID)
            val today = LocalDate.now()

            if (lastSaveDate == null || ChronoUnit.DAYS.between(lastSaveDate, today) >= 1) {
                Auth.saveGameData(userID, gameData, // Сохраняем данные
                    onSuccess = {
                        Log.d("CloudSave", "Game data saved to cloud successfully.")
                        CoroutineScope(Dispatchers.IO).launch{
                            Auth.setLastSaveDate(userID) // Обновляем дату
                        }
                    },
                    onFailure = { message ->
                        Log.e("CloudSave", "Failed to save game data: $message")
                    })
            }
        }
    }

    private fun checkConcurrentSession() {
        val userID = Auth.getCurrentUserID() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            //TODO: Implement logic to check for concurrent session
            // If concurrent session is detected, show a dialog and close the game
            withContext(Dispatchers.Main) {
                //Auth.showConcurrentSessionDialog(this@MainActivity) { finish() }
            }
        }
    }

    internal fun calculateDamageWithRebirthBonus(level: Int): Long {
        val baseDamage = calculateDamage(level)

        val bonusMultiplier = 1 + (rebirthBonus / 100)
        val totalDamage = baseDamage * bonusMultiplier

        return roundDamage(totalDamage)
    }

    private fun roundDamage(damage: Double): Long {
        val decimalPart = damage - damage.toInt()

        return if (decimalPart <= 0.4) {
            damage.toInt().toLong()
        } else {
            ceil(damage).toLong()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}