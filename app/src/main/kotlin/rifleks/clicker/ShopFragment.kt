package rifleks.clicker

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.util.Locale
import kotlin.math.pow

class ShopFragment : Fragment() {

    private lateinit var upgradeButton: Button
    private lateinit var cooldownText: TextView
    private lateinit var mangoClickUpgradeButton: Button
    private lateinit var mangoClickInfoText: TextView
    private lateinit var rebirthBonusText: TextView
    private lateinit var rebirthButton: Button
    private lateinit var mainActivity: MainActivity

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_shop, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        upgradeButton = view.findViewById(R.id.upgradeButton)
        cooldownText = view.findViewById(R.id.cooldownText)
        mangoClickUpgradeButton = view.findViewById(R.id.mangoClickUpgradeButton)
        mangoClickInfoText = view.findViewById(R.id.mangoClickInfoText)
        rebirthBonusText = view.findViewById(R.id.rebirthBonusText)
        rebirthButton = view.findViewById(R.id.rebirthButton)

        mainActivity = activity as? MainActivity ?: return
        updateViews()

        upgradeButton.setOnClickListener {
            animateButton(it)
            if (mainActivity.buyClickUpgrade()) {
                updateViews()
            } else {
                val price = MainActivity.calculateCooldownCost(mainActivity.cooldownLevel)
                if (mainActivity.clicks < price) {
                    showToast(
                        Translation.translate("not_enough_mangoes").replace("{cost}", MainActivity.formatNumber(price))
                    )
                } else {
                    showToast(Translation.translate("max_level"))
                }
            }
        }

        mangoClickUpgradeButton.setOnClickListener {
            animateButton(it)
            if (mainActivity.buyMangoClickUpgrade()) {
                updateViews()
            } else {
                val price = MainActivity.calculateDamageCost(mainActivity.mangoClickLevel + 1)
                if (mainActivity.clicks < price) {
                    showToast(
                        Translation.translate("not_enough_mangoes").replace("{cost}", MainActivity.formatNumber(price))
                    )
                } else {
                    showToast(Translation.translate("max_level"))
                }
            }
        }

        rebirthButton.setOnClickListener {
            animateButton(it)
            showRebirthConfirmationDialog()
        }
    }

    private fun animateButton(view: View) {
        view.animate()
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(50)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(50)
            }
    }

    fun updateButtonState() {
        val isCooldownMaxed = mainActivity.cooldownLevel >= 16
        val isMangoClickMaxed = mainActivity.mangoClickLevel >= 100

        val rebirthCost = calculateRebirthCost(mainActivity.rebirthCount)
        val canRebirth = mainActivity.clicks >= rebirthCost

        val cooldownCost = MainActivity.calculateCooldownCost(mainActivity.cooldownLevel)
        val canBuyCooldown = mainActivity.clicks >= cooldownCost

        val mangoClickCost = MainActivity.calculateDamageCost(mainActivity.mangoClickLevel + 1)
        val canBuyMangoClick = mainActivity.clicks >= mangoClickCost

        updateButtonAppearance(upgradeButton, canBuyCooldown, isCooldownMaxed, cooldownCost,
            ContextCompat.getColor(requireContext(), android.R.color.holo_green_light))

        updateButtonAppearance(mangoClickUpgradeButton, canBuyMangoClick, isMangoClickMaxed, mangoClickCost,
            ContextCompat.getColor(requireContext(), android.R.color.holo_green_light))

        updateButtonAppearance(rebirthButton, canRebirth, false, rebirthCost,
            ContextCompat.getColor(requireContext(), android.R.color.holo_green_light))
    }

    @SuppressLint("SetTextI18n")
    private fun updateButtonAppearance(button: Button, canBuy: Boolean, isMaxLevel: Boolean, cost: Long, normalColor: Int) {
        button.isEnabled = !isMaxLevel && canBuy
        val alphaValue = if (!canBuy && !isMaxLevel) 0.3f else 1.0f
        button.alpha = alphaValue

        context ?: return

        val buttonColor = when {
            isMaxLevel -> Color.DKGRAY
            canBuy -> normalColor
            else -> Color.GRAY
        }

        button.setBackgroundColor(buttonColor)

        val buttonText = when (button) {
            upgradeButton -> {
                val cooldown = mainActivity.clickCooldown
                if (cooldown <= 0.2) {
                    button.isEnabled = false
                    Translation.translate("max_level")
                } else {
                    val formattedCost = MainActivity.formatNumber(cost)
                    Translation.translate("upgrade_cooldown").replace("{cost}", formattedCost)
                }
            }
            mangoClickUpgradeButton -> {
                val formattedCost = MainActivity.formatNumber(cost)
                Translation.translate("upgrade_damage").replace("{cost}", formattedCost)
            }
            rebirthButton -> {
                val prefs = requireContext().getSharedPreferences("ClickerPrefs", Context.MODE_PRIVATE)
                val rebirths = prefs.getInt("rebirths", 0)
                val bonus = Math.round(rebirths * 0.2)
                val formattedCost = MainActivity.formatNumber(cost)
                "${Translation.translate("rebirth")} (+$bonus) ($formattedCost 🥭)"
            }
            else -> ""
        }

        if (button != rebirthButton) {
            button.text = buttonText
        }
    }

    private fun showRebirthConfirmationDialog() {
        val rebirthCost = calculateRebirthCost(mainActivity.rebirthCount)
        val bonus = Math.round(calculateRebirthBonus(mainActivity.rebirthCount) * 100).toString()

        val message = Translation.translate("rebirth_confirmation_message")
            .replace("{bonus}", bonus)
            .replace("{cost}", MainActivity.formatNumber(rebirthCost))

        val builder = AlertDialog.Builder(requireContext())
            .setTitle(Translation.translate("rebirth_confirmation_title"))
            .setMessage(message)
            .setPositiveButton(Translation.translate("rebirth_positive_button")) { _, _ ->
                performRebirth()
            }
            .setNegativeButton(Translation.translate("rebirth_negative_button"), null)
        builder.show()
    }

    private fun performRebirth() {
        val rebirthCost = calculateRebirthCost(mainActivity.rebirthCount)

        if (mainActivity.clicks >= rebirthCost) {
            mainActivity.clicks -= rebirthCost
            mainActivity.rebirthCount++
            mainActivity.rebirthBonus = calculateRebirthBonus(mainActivity.rebirthCount)
            resetGame()
            updateViews()
            Toast.makeText(requireContext(), Translation.translate("rebirth_successful"), Toast.LENGTH_SHORT).show()

            mainActivity.saveGame()
        } else {
            showToast(Translation.translate("not_enough_mango_rebirth"))
        }
    }

    private fun resetGame() {
        mainActivity.clicks = 0L
        mainActivity.cooldownLevel = 0
        mainActivity.clickCooldown = 0.5
        mainActivity.mangoClickLevel = 1

        mainActivity.saveGame()
    }

    @SuppressLint("SetTextI18n")
    private fun updateCooldownText() {
        val cooldownSec = mainActivity.clickCooldown
        val formattedCooldown = String.format(Locale.US, "%.2f", cooldownSec)
        cooldownText.text = "${Translation.translate("click_delay")}: $formattedCooldown ${Translation.translate("seconds")}"
    }

    @SuppressLint("SetTextI18n")
    private fun updateClickInfoText() {
        val damagePerClick = MainActivity.calculateDamage(mainActivity.mangoClickLevel)
        mangoClickInfoText.text = "${Translation.translate("click_mango_info")}: $damagePerClick 🥭"
    }

    @SuppressLint("SetTextI18n")
    private fun updateRebirthBonusText() {
        val rebirthBonus = Math.round(mainActivity.rebirthBonus * 100).toString()
        rebirthBonusText.text = "${Translation.translate("rebirth_bonus")}: +${rebirthBonus}%"
    }

    @SuppressLint("SetTextI18n")
    private fun updateRebirthButtonText() {
        val rebirthCost = calculateRebirthCost(mainActivity.rebirthCount)
        rebirthButton.text = "${Translation.translate("rebirth")} (${MainActivity.formatNumber(rebirthCost)} 🥭)"
        rebirthButton.isEnabled = mainActivity.clicks >= rebirthCost
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun updateViews() {
        updateCooldownText()
        updateClickInfoText()
        updateRebirthBonusText()
        updateRebirthButtonText()
        updateButtonState()
    }

    companion object {
        fun newInstance() = ShopFragment()

        fun calculateRebirthCost(rebirthCount: Int): Long {
            val baseCost = 500_000.0
            return Math.round(baseCost * 1.3.pow(rebirthCount.toDouble()))
        }

        fun calculateRebirthBonus(rebirthCount: Int): Double {
            return rebirthCount * 0.2
        }
    }
}
