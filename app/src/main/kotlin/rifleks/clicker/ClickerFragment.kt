package rifleks.clicker

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.RelativeLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.io.InputStream
import java.util.Random

class ClickerFragment : Fragment() {
    private lateinit var clickButton: ImageButton
    private var clickEnabled = true
    private lateinit var clickArea: RelativeLayout
    private val floatingTexts = mutableListOf<FloatingTextView>()
    private val maxFloatingTexts = 8
    private val random = Random()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_clicker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        clickButton = view.findViewById(R.id.clickButton)
        clickArea = view.findViewById(R.id.clickArea)

        loadClickButtonImage()

        setupClickButton()
    }

    private fun loadClickButtonImage() {
        try {
            val inputStream: InputStream? = context?.assets?.open("images/click.png")
            val bitmap = BitmapFactory.decodeStream(inputStream)
            clickButton.setImageBitmap(bitmap)
            inputStream?.close()
        } catch (e: Exception) {
            Log.e("ClickerFragment", "Ошибка при загрузке изображения", e)
        }
    }

    private fun setupClickButton() {
        val mainActivity = activity as? MainActivity ?: return

        clickButton.setOnClickListener { v ->
            if (!clickEnabled) return@setOnClickListener

            val currentTime = System.currentTimeMillis()
            if (currentTime - mainActivity.lastClickTime >= (mainActivity.clickCooldown * 1000).toLong()) {
                mainActivity.handleClick(v)
                mainActivity.lastClickTime = currentTime

                if (mainActivity.clickCooldown > 0.02) {
                    clickEnabled = false
                    mainActivity.handler.postDelayed({
                        clickEnabled = true
                    }, (mainActivity.clickCooldown * 1000).toLong())
                }

                showFloatingText(MainActivity.calculateDamage(mainActivity.mangoClickLevel))
            }
        }
    }

    private fun showFloatingText(mangoAmount: Int) {
        if (floatingTexts.size >= maxFloatingTexts) {
            val oldestText = floatingTexts.removeAt(0)
            clickArea.removeView(oldestText)
        }

        val floatingText = FloatingTextView(requireContext())
        // Use string resource with placeholder
        floatingText.text = getString(R.string.floating_text, mangoAmount)

        val textSizeInPixels = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics).toInt()

        floatingText.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizeInPixels.toFloat())
        floatingText.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_light))

        val x = random.nextInt(clickArea.width - floatingText.paddingLeft - floatingText.paddingRight - (textSizeInPixels * floatingText.text.length * 0.5).toInt()).toFloat()
        val y = random.nextInt(clickArea.height - floatingText.paddingTop - floatingText.paddingBottom - (textSizeInPixels * 1.5).toInt()).toFloat()

        floatingText.x = x
        floatingText.y = y

        clickArea.addView(floatingText)
        floatingTexts.add(floatingText)

        floatingText.startAnimation()
    }

    override fun onResume() {
        super.onResume()
        clickEnabled = true
    }

    override fun onDestroyView() {
        // Clear animation and remove views to prevent memory leaks
        floatingTexts.forEach {
            it.clearAnimation()
            clickArea.removeView(it)
        }
        floatingTexts.clear()
        super.onDestroyView()
    }

    companion object {
        fun newInstance() = ClickerFragment()
    }
}