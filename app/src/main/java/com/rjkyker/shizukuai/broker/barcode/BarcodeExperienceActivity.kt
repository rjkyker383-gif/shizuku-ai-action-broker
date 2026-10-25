package com.rjkyker.shizukuai.broker.barcode

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.random.Random

class BarcodeExperienceActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var heading: TextView
    private var backgroundAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val type = intent.getStringExtra(EXTRA_TYPE).orEmpty()
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val version = intent.getIntExtra(EXTRA_VERSION, 1)
        if (version != 1 || id.isBlank()) {
            finish()
            return
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.rgb(12, 15, 30))
        }
        heading = TextView(this).apply {
            text = "⟁ BARCODE GHOST ENGINE"
            textSize = 26f
            setTextColor(Color.rgb(120, 240, 255))
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        root.addView(heading, matchWrap())
        val subtitle = TextView(this).apply {
            text = "BGX COMMAND: " + type + " / " + id
            textSize = 13f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(24))
        }
        root.addView(subtitle, matchWrap())

        when (type) {
            "EASTER_EGG" -> showEasterEgg(id)
            "GAME" -> showGame(id)
            else -> finish()
        }
        setContentView(root)
    }

    private fun showEasterEgg(id: String) {
        val message = when (id) {
            "GHOST_MODE" -> "GHOST MODE ONLINE\nThe ordinary world has a hidden layer."
            "SPIN_LOGO" -> "SPIN CYCLE ACTIVATED\nTap below to rotate the signal."
            "SECRET_MESSAGE" -> "You found the hidden channel.\nKeep scanning. Keep wondering."
            "TINY_FIREWORKS" -> "MICRO-FIREWORKS READY\nTap to ignite the tiny sky."
            else -> "Unknown Easter egg."
        }
        root.addView(TextView(this).apply {
            text = message
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
        }, matchWrap())

        val button = Button(this).apply { text = "ACTIVATE EASTER EGG" }
        root.addView(button, matchWrap())
        button.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            when (id) {
                "GHOST_MODE", "TINY_FIREWORKS" -> animateBackground()
                "SPIN_LOGO" -> ObjectAnimator.ofFloat(heading, View.ROTATION, 0f, 360f).apply {
                    duration = 1600L
                    interpolator = AccelerateDecelerateInterpolator()
                    start()
                }
                "SECRET_MESSAGE" -> {
                    heading.text = "ꙮ SECRET FOUND ꙮ"
                    heading.animate().scaleX(1.15f).scaleY(1.15f).setDuration(350L).withEndAction {
                        heading.animate().scaleX(1f).scaleY(1f).setDuration(350L).start()
                    }.start()
                }
            }
        }
    }

    private fun showGame(id: String) {
        val title = when (id) {
            "NEON_MAZE" -> "NEON MAZE"
            "BARCODE_MEMORY" -> "BARCODE MEMORY"
            "GHOST_CIPHER" -> "GHOST CIPHER"
            "SCAN_SEQUENCE" -> "SCAN SEQUENCE"
            "TREASURE_HUNT" -> "HIDDEN TREASURE"
            "DAILY_PUZZLE" -> "DAILY PUZZLE"
            "COLLECTIBLE_DISCOVERY" -> "COLLECTIBLE DISCOVERY"
            "REACTION_CHALLENGE" -> "REACTION CHALLENGE"
            else -> "BGX MINI-GAME"
        }
        heading.text = "⚡ " + title + " ⚡"
        val instructions = when (id) {
            "GHOST_CIPHER" -> "Solve the quick math cipher to earn a point."
            "BARCODE_MEMORY" -> "Build a streak by completing five scan actions."
            "NEON_MAZE" -> "Pass five neon checkpoints to escape the maze."
            "SCAN_SEQUENCE" -> "Complete the sequence challenge with five taps."
            "TREASURE_HUNT" -> "Search five locations for hidden treasure."
            "DAILY_PUZZLE" -> "Complete five puzzle moves to reveal the reward."
            "COLLECTIBLE_DISCOVERY" -> "Collect five fragments to assemble a token."
            else -> "Beat the timer with five successful actions."
        }
        root.addView(TextView(this).apply {
            text = instructions
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        }, matchWrap())

        if (id == "GHOST_CIPHER") {
            val a = Random.nextInt(11, 70)
            val b = Random.nextInt(11, 70)
            val answer = a + b
            root.addView(TextView(this).apply {
                text = "$a + $b = ?"
                textSize = 28f
                setTextColor(Color.rgb(120, 240, 255))
                gravity = Gravity.CENTER
            }, matchWrap())
            val input = EditText(this).apply {
                hint = "Enter the answer"
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                gravity = Gravity.CENTER
            }
            root.addView(input, matchWrap())
            val result = TextView(this).apply {
                text = ""
                textSize = 17f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }
            root.addView(result, matchWrap())
            val submit = Button(this).apply { text = "DECODE" }
            root.addView(submit, matchWrap())
            submit.setOnClickListener {
                if (input.text.toString().toIntOrNull() == answer) {
                    result.text = "CIPHER CRACKED. REWARD UNLOCKED."
                    result.setTextColor(Color.rgb(100, 255, 160))
                    it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                } else {
                    result.text = "Not quite. Try again."
                    result.setTextColor(Color.rgb(255, 150, 150))
                    it.performHapticFeedback(HapticFeedbackConstants.REJECT)
                }
            }
            return
        }

        val progress = TextView(this).apply {
            text = "PROGRESS: 0 / 5"
            textSize = 21f
            setTextColor(Color.rgb(120, 240, 255))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(22))
        }
        root.addView(progress, matchWrap())
        val button = Button(this).apply { text = "MAKE A MOVE" }
        root.addView(button, matchWrap())
        var count = 0
        button.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            count++
            if (count >= 5) {
                progress.text = "COMPLETE. HIDDEN REWARD UNLOCKED."
                progress.setTextColor(Color.rgb(100, 255, 160))
                button.text = "PLAY AGAIN"
                button.setOnClickListener {
                    count = 0
                    progress.text = "PROGRESS: 0 / 5"
                    progress.setTextColor(Color.rgb(120, 240, 255))
                    button.text = "MAKE A MOVE"
                }
            } else {
                progress.text = "PROGRESS: " + count + " / 5"
            }
        }
    }

    private fun animateBackground() {
        backgroundAnimator?.cancel()
        backgroundAnimator = ValueAnimator.ofArgb(
            Color.rgb(12, 15, 30),
            Color.rgb(42, 14, 68),
            Color.rgb(8, 74, 88),
            Color.rgb(12, 15, 30)
        ).apply {
            duration = 2200L
            repeatCount = 2
            addUpdateListener { root.setBackgroundColor(it.animatedValue as Int) }
            start()
        }
    }

    override fun onDestroy() {
        backgroundAnimator?.cancel()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    companion object {
        const val EXTRA_TYPE = "bgx.type"
        const val EXTRA_ID = "bgx.id"
        const val EXTRA_VERSION = "bgx.version"
    }
}
