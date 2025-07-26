package rifleks.clicker

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.view.ViewGroup

class FloatingTextView(context: Context) : androidx.appcompat.widget.AppCompatTextView(context) {

    fun startAnimation() {
        val animation = ObjectAnimator.ofFloat(this, "translationY", -150f)
        animation.duration = 1000

        animation.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                (parent as? ViewGroup)?.removeView(this@FloatingTextView)
            }
        })
        animation.start()
    }
}