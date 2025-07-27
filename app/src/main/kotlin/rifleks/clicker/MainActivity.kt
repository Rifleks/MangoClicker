package rifleks.clicker

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.text.DecimalFormat
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.pow
import rifleks.clicker.BuildConfig

class MainActivity : AppCompatActivity() {

    // Game state variables
    internal var clicks: Long = 0
    internal var clickCooldown: Double = 0.5
    internal var mangoClickLevel: Int = 1
    internal var lastClickTime: Long = 0
    internal var cooldownLevel: Int = 0
    internal var rebirthCount: Int = 0
    internal var rebirthBonus: Double = 0.0

    // UI components
    private lateinit var mainLayout: View
    private lateinit var mangoCounter: TextView
    private lateinit var clickerTab: AppCompatTextView
    private lateinit var shopTab: AppCompatTextView

    val handler = Handler(Looper.getMainLooper())

    // Preferences and state
    private lateinit var prefs: SharedPreferences
    private var isClickerTabActive: Boolean = true
    private var isShopTabActive: Boolean = false
    private var gameData: GameData = GameData()
    private var signInSkipped: Boolean = false
    private var isFirstRun: Boolean = true
    private var isUserLoggedIn: Boolean = false

    // Update-related variables
    private var updateService: UpdateService? = null
    private var updateDialog: AlertDialog? = null
    private var installDialog: AlertDialog? = null

    @Suppress("UNUSED_VARIABLE")
    private var currentVersionName: String = ""
    private var latestVersionName: String = ""
    private var latestVersionCode: Long = 0
    private var latestApkSize: Long = 0

    private var latestDownloadedFile: File? = null

    @SuppressLint("ObsoleteSdkInt")
    private var versionCode: Long = 0

    private val formatter: DecimalFormat = DecimalFormat("#.##")

    // Auth and database
    private val authScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (updateDialog?.isShowing == true || installDialog?.isShowing == true) {
                Toast.makeText(this@MainActivity, "Обновление в процессе...", Toast.LENGTH_SHORT)
                    .show()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    companion object {
        const val RC_SIGN_IN = 9001  // Or any unique integer code
        private const val TAG = "MainActivity"

        fun calculateDamageCost(level: Int): Long {
            val cost = 60.0 * 1.5.pow(level - 1)
            return cost.toLong()
        }

        fun calculateDamage(level: Int): Long {
            return level.toLong()
        }

        fun calculateCooldownCost(level: Int): Long {
            val cost = 130.0 * 1.5.pow(level)
            return cost.toLong()
        }

        fun calculateCooldown(level: Int): Double {
            val newCd = (0.5 - level * 0.03).coerceAtLeast(0.2)
            return newCd
        }

        fun formatNumber(count: Long): String {
            if (count < 1000) return count.toString()

            val suffixes = arrayOf("", "K", "M", "B", "T")
            var value = count.toDouble()
            var index = 0

            while (value >= 1000 && index < suffixes.size - 1) {
                value /= 1000
                index++
            }

            return if (value % 1 == 0.0) {
                "${value.toInt()}${suffixes[index]}"
            } else {
                String.format(Locale.US, "%.1f%s", value, suffixes[index])
            }
        }
    }

    @SuppressLint("ObsoleteSdkInt", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Инициализация Auth
        Auth.initialize(applicationContext)

        // Настройка статусбара
        setupStatusBarAppearance()

        // Обработка "Назад"
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        // SharedPreferences
        prefs = getSharedPreferences("ClickerPrefs", MODE_PRIVATE)
        isFirstRun = prefs.getBoolean("isFirstRun", true)
        isUserLoggedIn = prefs.getBoolean("isUserLoggedIn", false)

        // Привязка layout
        mainLayout = findViewById(R.id.mainLayout)

        // Переводы и UI
        Translation.loadTranslations(this)
        initViews()
        setupTabs()
        showClickerFragment()

        // Первая установка
        if (isFirstRun) {
            prefs.edit {
                putBoolean("isFirstRun", false)
                apply()
            }
            Log.d("MainActivity", "First app launch.")
            loadGame()
        } else {
            @Suppress("DEPRECATION")
            val account = GoogleSignIn.getLastSignedInAccount(this)

            if (account != null) {
                Auth.setCurrentUserID(account.id)
                checkAuthState() // Загружает данные из Firebase
            } else {
                loadGameFromPrefs()

                val loggedIn = prefs.getBoolean("isUserLoggedIn", false)
                if (!loggedIn && !signInSkipped) {
                    Auth.showSignInDialog(
                        this,
                        onSignIn = {
                            Auth.startSignIn(this, RC_SIGN_IN)
                        },
                        onSkip = {
                            signInSkipped = true
                        }
                    )
                }
            }

        }

        // Получение версии приложения
        try {
            currentVersionName = BuildConfig.VERSION_NAME
            versionCode = BuildConfig.VERSION_CODE.toLong()
        } catch (e: Exception) {
            currentVersionName = "Failed to determine"
            versionCode = 0
            Log.e("MainActivity", "Failed to get version info: ${e.message}")
        }

        // Проверка обновлений
        updateService =
            UpdateService(applicationContext, object : UpdateService.UpdateInstallListener {
                override fun onInstallCompleted() {
                    Log.d("Update", "Install completed")
                    // ничего — установка будет отдельно
                }

                override fun onInstallFailed() {
                    Log.e("Update", "Install failed")
                }

                override fun onProgressUpdate(percent: Int, downloaded: Long, total: Long) {
                    updateInstallDialogProgress(percent, downloaded, total)
                }

                override fun onDownloadComplete(file: File) {
                    latestDownloadedFile = file
                }
            })


        if (isConnectedToInternet()) {
            updateService?.checkForUpdateAndInstall { shouldUpdate, apkSize, downloadUrl, versionName, versionCode ->
                if (shouldUpdate) {
                    latestApkSize = apkSize
                    latestVersionName = versionName
                    latestVersionCode = versionCode
                    showUpdateDialog(downloadUrl, versionName, versionCode)
                }
            }
        }
    }

    private fun isConnectedToInternet(): Boolean {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            networkInfo != null && networkInfo.isConnected
        }
    }


    @Suppress("DEPRECATION")
    private fun setupStatusBarAppearance() {
        window.apply {
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            statusBarColor = Color.TRANSPARENT
            decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }

        val wic = WindowInsetsControllerCompat(window, window.decorView)
        wic.isAppearanceLightStatusBars = false // Белый текст
    }

    private fun checkAuthState() {
        val userID = Auth.getCurrentUserID()

        if (userID == null) {
            showAuthDialog()
        } else {
            lifecycleScope.launch {
                val result = Auth.loadGameData(userID)
                if (result.isSuccess) {
                    val gameData = result.getOrNull()
                } else {
                    val error = result.exceptionOrNull()?.message
                    Log.e("Auth", "Ошибка загрузки: $error")
                    showToast("Ошибка входа")
                }
            }
        }
    }

    private fun showAuthDialog() {
        Auth.showSignInDialog(
            this,
            onSignIn = { Auth.startSignIn(this, RC_SIGN_IN) },
            onSkip = { loadGameFromPrefs() }
        )
    }


    override fun onStart() {
        super.onStart()
        isUserLoggedIn = prefs.getBoolean("isUserLoggedIn", false)
        if (!isUserLoggedIn) {
            @Suppress("DEPRECATION")
            val account = GoogleSignIn.getLastSignedInAccount(this)
            if (account == null && !signInSkipped) {
                Auth.showSignInDialog(
                    this,
                    onSignIn = { Auth.startSignIn(this, RC_SIGN_IN) },
                    onSkip = { signInSkipped = true }
                )
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RC_SIGN_IN) {
            Auth.handleSignInResult(
                data,
                onSuccess = { userId ->
                    // ✅ 1. Сохраняем логин
                    prefs.edit {
                        putBoolean("isUserLoggedIn", true)
                        apply()
                    }
                    isUserLoggedIn = true
                    signInSkipped = false

                    // ✅ 2. Сохраняем userId
                    Auth.setCurrentUserID(userId)

                    // ✅ 3. Загружаем данные из облака
                    loadGameFromCloud()

                    Log.d(TAG, "Sign-in success. userId: $userId")
                },
                onFailure = { error ->
                    Log.e(TAG, "Sign-in failed: $error")
                    signInSkipped = true
                    showToast("Ошибка входа: $error")
                }
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
            .setTransition(androidx.fragment.app.FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            .replace(R.id.contentFrame, ClickerFragment.newInstance())
            .commit()

        clickerTab.background = ContextCompat.getDrawable(this, R.drawable.rounded_tab_background)
        shopTab.background = ContextCompat.getDrawable(this, android.R.color.transparent)
    }

    private fun showShopFragment() {
        if (supportFragmentManager.findFragmentById(R.id.contentFrame) is ShopFragment) return
        isShopTabActive = true
        isClickerTabActive = false

        supportFragmentManager.beginTransaction()
            .setTransition(androidx.fragment.app.FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            .replace(R.id.contentFrame, ShopFragment.newInstance())
            .commit()

        clickerTab.background = ContextCompat.getDrawable(this, android.R.color.transparent)
        shopTab.background = ContextCompat.getDrawable(this, R.drawable.rounded_tab_background)
    }

    @SuppressLint("SetTextI18n")
    internal fun updateMangoCounter() {
        mangoCounter.text = String.format(
            Locale.getDefault(),
            getString(R.string.mango_count),
            Companion.formatNumber(clicks)
        ) + " 🥭"
        mangoCounter.contentDescription = String.format("%s 🥭", clicks.toString())
    }

    internal fun buyClickUpgrade(): Boolean {
        if (cooldownLevel >= 16) return false

        val price: Long = Companion.calculateCooldownCost(cooldownLevel)

        if (clicks >= price) {
            clicks -= price
            cooldownLevel++
            clickCooldown = Companion.calculateCooldown(cooldownLevel)
            updateMangoCounter()
            val shopFragment =
                supportFragmentManager.findFragmentById(R.id.contentFrame) as? ShopFragment
            shopFragment?.updateButtonState()
            saveGame()
            return true
        }
        return false
    }

    @RequiresApi(Build.VERSION_CODES.O_MR1)
    internal fun handleClick(view: View) {
        val damage: Long = calculateDamageWithRebirthBonus(mangoClickLevel)
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

        val price: Long = Companion.calculateDamageCost(mangoClickLevel + 1)
        if (clicks >= price) {
            clicks -= price
            mangoClickLevel++
            updateMangoCounter()
            val shopFragment =
                supportFragmentManager.findFragmentById(R.id.contentFrame) as? ShopFragment
            shopFragment?.updateButtonState()
            saveGame()
            return true
        }
        return false
    }

    private fun loadGame() {
        loadGameFromPrefs()
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
            putBoolean("isUserLoggedIn", isUserLoggedIn)
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
        val userID = Auth.getCurrentUserID() ?: run {
            loadGameFromPrefs()
            return
        }

        authScope.launch {
            val result = Auth.loadGameData(userID)
            if (result.isSuccess) {
                val data = result.getOrNull()
                if (data != null) {
                    updateGameData(data)
                    showToast("Данные успешно загружены")
                } else {
                    handleLoadError("Пустой ответ с сервера")
                }
            } else {
                handleLoadError(result.exceptionOrNull()?.message ?: "Неизвестная ошибка")
            }
        }
    }

    private fun updateGameData(data: GameData) {
        // Обновляем данные
        clicks = data.clicks
        clickCooldown = data.clickCooldown
        mangoClickLevel = data.mangoClickLevel
        lastClickTime = data.lastClickTime
        cooldownLevel = data.cooldownLevel
        rebirthCount = data.rebirthCount
        rebirthBonus = data.rebirthBonus

        // Обновляем UI
        updateMangoCounter()
        updateShopButtons()
    }

    private fun updateShopButtons() {
        (supportFragmentManager.findFragmentById(R.id.contentFrame) as? ShopFragment)?.updateButtonState()
    }

    private fun handleLoadError(error: String) {
        Log.e("CloudLoad", error)
        loadGameFromPrefs()
        showToast("Ошибка загрузки: $error")
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        authScope.cancel()
    }

    private fun saveGameToCloud() {
        val userID = Auth.getCurrentUserID() ?: return

        authScope.launch {
            val gameData = GameData(
                clicks = clicks,
                clickCooldown = clickCooldown,
                mangoClickLevel = mangoClickLevel,
                lastClickTime = lastClickTime,
                cooldownLevel = cooldownLevel,
                rebirthCount = rebirthCount,
                rebirthBonus = rebirthBonus
            )

            val result = Auth.saveGameData(userID, gameData)
            if (result.isSuccess) {
                showToast("Данные успешно сохранены")
            } else {
                showToast("Ошибка сохранения: ${result.exceptionOrNull()?.message}")
            }

        }
    }

    internal fun calculateDamageWithRebirthBonus(level: Int): Long {
        val baseDamage: Long = Companion.calculateDamage(level)
        val bonusMultiplier: Double = 1 + rebirthBonus
        val totalDamage: Double = baseDamage * bonusMultiplier

        return roundDamage(totalDamage)
    }

    private fun roundDamage(damage: Double): Long {
        val decimalPart: Double = damage - damage.toInt()

        return if (decimalPart <= 0.4) {
            damage.toInt().toLong()
        } else {
            ceil(damage).toLong()
        }
    }

    @SuppressLint("MissingInflatedId", "SetTextI18n")
    private fun showInstallDialog(apkSize: Long) {
        val builder = AlertDialog.Builder(this)
        val dialogView = LayoutInflater.from(this).inflate(R.layout.install_dialog, null)
        builder.setView(dialogView)
        builder.setCancelable(false)

        installDialog = builder.create()
        installDialog?.show()
        installDialog?.window?.setBackgroundDrawable(Color.BLACK.toDrawable())

        val titleTextView = dialogView.findViewById<TextView>(R.id.titleTextView)
        val cancelButton = dialogView.findViewById<Button>(R.id.cancelButton)
        val installButton = dialogView.findViewById<Button>(R.id.confirmInstallButton)
        val progressTextView = dialogView.findViewById<TextView>(R.id.progressTextView)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.updateProgressBar)

        titleTextView.text = Translation.translate("update_downloading_title")
        titleTextView.setTextColor(Color.WHITE)

        cancelButton.text = Translation.translate("cancel")
        installButton.text = Translation.translate("update_install")
        installButton.isEnabled = false
        installButton.setTextColor(Color.WHITE)
        cancelButton.setTextColor(Color.WHITE)

        val totalMB = apkSize / (1024 * 1024).toDouble()
        val updateSizeText = Translation.translate("update_size")
            .replace("{size}", formatter.format(totalMB))
        progressTextView.text = updateSizeText
        progressBar.progress = 0

        installButton.setOnClickListener {
            latestDownloadedFile?.let { file ->
                updateService?.installApk(file)
            }
        }

        cancelButton.setOnClickListener {
            updateService?.cancelDownload()
            installDialog?.dismiss()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateInstallDialogProgress(progress: Int, downloaded: Long, total: Long) {
        installDialog?.let { dialog ->
            val progressTextView = dialog.findViewById<TextView>(R.id.progressTextView)
            val progressBar = dialog.findViewById<ProgressBar>(R.id.updateProgressBar)
            val installButton = dialog.findViewById<Button>(R.id.confirmInstallButton)

            val downloadedMB = downloaded.toDouble() / (1024 * 1024).toDouble()
            val totalMB = total / (1024 * 1024).toDouble()

            val downloadedText = Translation.translate("update_downloaded")
                .replace("{downloaded}", formatter.format(downloadedMB))
                .replace("{total}", formatter.format(totalMB))

            val progressText = "$downloadedText ($progress%)"
            progressTextView?.text = progressText
            progressBar?.progress = progress

            if (progress >= 100) {
                installButton?.isEnabled = true
            }
        }
    }

    @SuppressLint("MissingInflatedId", "SetTextI18n")
    private fun showUpdateDialog(downloadUrl: String, versionName: String, versionCode: Long) {
        val builder = AlertDialog.Builder(this)
        val dialogView = LayoutInflater.from(this).inflate(R.layout.update_dialog, null)
        builder.setView(dialogView)
        builder.setCancelable(false)

        val dialog = builder.create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(Color.BLACK.toDrawable())

        val titleTextView = dialogView.findViewById<TextView>(R.id.titleTextView)
        val currentTextView = dialogView.findViewById<TextView>(R.id.currentVersionTextView)
        val newTextView = dialogView.findViewById<TextView>(R.id.newVersionTextView)
        val installButton = dialogView.findViewById<Button>(R.id.installButton)
        val laterButton = dialogView.findViewById<Button>(R.id.laterButton)

        titleTextView.text = Translation.translate("update_available")

        currentTextView.text = Translation.translate("update_current_version")
            .replace("{version}", "$currentVersionName ($versionCode)")
        newTextView.text = Translation.translate("update_new_version")
            .replace("{version}", "$latestVersionName ($latestVersionCode)")

        installButton.text = Translation.translate("update_install")
        laterButton.text = Translation.translate("update_later")

        installButton.setTextColor(Color.WHITE)
        laterButton.setTextColor(Color.WHITE)

        installButton.setOnClickListener {
            dialog.dismiss()
            showInstallDialog(latestApkSize)
            updateService?.downloadAndInstallUpdate(downloadUrl, latestVersionName, versionCode)
        }

        laterButton.setOnClickListener {
            dialog.dismiss()
        }
    }

}