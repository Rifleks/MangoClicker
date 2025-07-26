package rifleks.clicker

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.FragmentTransaction
import java.text.DecimalFormat
import kotlin.math.ceil
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    internal var clicks = 0L
    internal var clickCooldown = 0.5
    internal var mangoClickLevel = 1

    internal var lastClickTime = 0L

    internal var cooldownLevel: Int = 0
    internal var rebirthCount = 0
    internal var rebirthBonus = 0.0

    private lateinit var mainLayout: View //  Ссылка на главный лайаут
    private var isClickerTabActive: Boolean = true // Флаг для отслеживания активной вкладки
    private var isShopTabActive: Boolean = false

    companion object {
        fun calculateDamage(level: Int): Int {
            return level
        }

        fun calculateDamageCost(level: Int): Long {
            return 15L * level * level
        }

        fun calculateCooldown(level: Int): Double {
            return max(0.5 - (level * 0.03), 0.20) // Минимальное КД = 0.20
        }

        fun calculateCooldownCost(level: Int, currentCooldown: Double): Long {
            val baseCost = 135.0 * Math.pow(1.5, level.toDouble())
            val priceMultiplier = if (currentCooldown <= 0.35) 2.0 else 1.0
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
            val baseCost = 500000.0 // Базовая стоимость
            val multiplier = Math.pow(1.5, rebirthCount.toDouble()) // Умножитель, зависящий от количества перерождений
            return (baseCost * multiplier).toLong()
        }

        fun calculateRebirthBonus(rebirthCount: Int): Double {
            return rebirthCount * 20.0
        }
    }

    private lateinit var mangoCounter: TextView
    private lateinit var prefs: SharedPreferences
    internal val handler = Handler(Looper.getMainLooper())
    private lateinit var clickerTab: AppCompatTextView
    private lateinit var shopTab: AppCompatTextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mainLayout = findViewById(R.id.mainLayout)
        window.apply {
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            statusBarColor = Color.TRANSPARENT
        }

        prefs = getSharedPreferences("ClickerPrefs", MODE_PRIVATE)
        loadGame()
        Translation.loadTranslations(this) // Загружаем переводы
        initViews()
        setupTabs()
        showClickerFragment()
    }

    private fun initViews() {
        mangoCounter = findViewById(R.id.mangoCounter)
        clickerTab = findViewById(R.id.clickerTab) as AppCompatTextView
        shopTab = findViewById(R.id.shopTab) as AppCompatTextView
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

        // Фиолетовый фон выделенной вкладки
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

        // Фиолетовый фон выделенной вкладки
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
            saveGame()
            updateMangoCounter()

            return true
        }
        return false
    }

    internal fun handleClick(view: View) {
        val damage = calculateDamageWithRebirthBonus(mangoClickLevel)
        clicks += damage
        updateMangoCounter()
        saveGame()

        view.animate()
            .scaleX(0.99f)  // Уменьшение "дерганности" при клике
            .scaleY(0.99f)
            .setDuration(30)  // Сокращение времени анимации
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(30)
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
        clicks = prefs.getLong("clicks", clicks)
        cooldownLevel = prefs.getInt("cooldownLevel", 0)
        clickCooldown = calculateCooldown(cooldownLevel)
        mangoClickLevel = prefs.getInt("mangoClickLevel", 1)
        rebirthCount = prefs.getInt("rebirthCount", 0)
        rebirthBonus = prefs.getFloat("rebirthBonus", 0.0f).toDouble()

    }

    internal fun saveGame() {
        prefs.edit {
            putLong("clicks", clicks)
            putInt("cooldownLevel", cooldownLevel)
            putInt("mangoClickLevel", mangoClickLevel)
            putInt("rebirthCount", rebirthCount)
            putFloat("rebirthBonus", rebirthBonus.toFloat())
        } // Использовать apply для сохранения в фоне
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