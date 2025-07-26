package rifleks.clicker

import android.annotation.SuppressLint
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
import java.text.DecimalFormat
import java.util.Locale

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
                val price = MainActivity.calculateCooldownCost(mainActivity.cooldownLevel, mainActivity.clickCooldown)
                if (mainActivity.clicks < price) {
                    showToast(Translation.translate("not_enough_mangoes").replace("{cost}", MainActivity.formatNumber(price)))
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
                    showToast(Translation.translate("not_enough_mangoes").replace("{cost}", MainActivity.formatNumber(price)))
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

    private fun updateButtonState() {
        val isCooldownMaxed = mainActivity.cooldownLevel >= 16
        val isMangoClickMaxed = mainActivity.mangoClickLevel >= 100
        val canRebirth = mainActivity.clicks >= MainActivity.calculateRebirthCost(mainActivity.rebirthCount)

        val cooldownCost = MainActivity.calculateCooldownCost(mainActivity.cooldownLevel, mainActivity.clickCooldown)
        val canBuyCooldown = mainActivity.clicks >= cooldownCost
        updateButtonAppearance(upgradeButton, canBuyCooldown, isCooldownMaxed, cooldownCost,
            ContextCompat.getColor(requireContext(), android.R.color.holo_green_light))

        val mangoClickCost = MainActivity.calculateDamageCost(mainActivity.mangoClickLevel + 1)
        val canBuyMangoClick = mainActivity.clicks >= mangoClickCost
        updateButtonAppearance(mangoClickUpgradeButton, canBuyMangoClick, isMangoClickMaxed, mangoClickCost,
            ContextCompat.getColor(requireContext(), android.R.color.holo_green_light))

        updateButtonAppearance(rebirthButton, canRebirth, false, MainActivity.calculateRebirthCost(mainActivity.rebirthCount),
            ContextCompat.getColor(requireContext(), android.R.color.holo_green_light))
    }

    @SuppressLint("SetTextI18n")
    private fun updateButtonAppearance(button: Button, canBuy: Boolean, isMaxLevel: Boolean, cost: Long, normalColor: Int) {
        button.isEnabled = !isMaxLevel && canBuy
        val alphaValue = if (!canBuy && !isMaxLevel) 0.3f else 1.0f
        button.alpha = alphaValue

        val context = context ?: return

        val buttonColor = when {
            isMaxLevel -> Color.DKGRAY
            canBuy -> normalColor
            else -> Color.GRAY
        }

        button.setBackgroundColor(buttonColor)

        val buttonText = if (isMaxLevel) {
            Translation.translate("max_level")
        } else {
            val formattedCost = MainActivity.formatNumber(cost)
            when (button) {
                upgradeButton -> Translation.translate("upgrade_cooldown").replace("{cost}", formattedCost)
                mangoClickUpgradeButton -> Translation.translate("upgrade_damage").replace("{cost}", formattedCost)
                else -> Translation.translate("rebirth").replace("{cost}", formattedCost)
            }
        }
        if (button == rebirthButton) {
            button.text = "${Translation.translate("rebirth")} (${MainActivity.formatNumber(cost)} 🥭)"
        } else {
            button.text = buttonText
        }
    }

    private fun showRebirthConfirmationDialog() {
        val rebirthCost = MainActivity.calculateRebirthCost(mainActivity.rebirthCount)
        val bonus = MainActivity.calculateRebirthBonus(mainActivity.rebirthCount)

        val df = DecimalFormat("#.##", java.text.DecimalFormatSymbols(Locale.US))

        val message = Translation.translate("rebirth_confirmation_message")
            .replace("{bonus}", df.format(bonus))
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
        val rebirthCost = MainActivity.calculateRebirthCost(mainActivity.rebirthCount)

        if (mainActivity.clicks >= rebirthCost) {
            mainActivity.clicks -= rebirthCost
            mainActivity.rebirthCount++
            mainActivity.rebirthBonus = MainActivity.calculateRebirthBonus(mainActivity.rebirthCount)
            resetGame()
            updateViews()
            Toast.makeText(requireContext(), Translation.translate("rebirth_successful"), Toast.LENGTH_SHORT).show()
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
        val rebirthBonus = String.format(Locale.US, "%.0f", mainActivity.rebirthBonus)
        rebirthBonusText.text = "${Translation.translate("rebirth_bonus")}: $rebirthBonus%"
    }

    @SuppressLint("SetTextI18n")
    private fun updateRebirthButtonText() {
        val rebirthCost = MainActivity.calculateRebirthCost(mainActivity.rebirthCount)
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
    }
}