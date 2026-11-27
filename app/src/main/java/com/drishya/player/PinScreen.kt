package com.drishya.player

import android.app.Dialog
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

/**
 * Private folder kosam full-screen PIN keypad.
 * SET: kotha PIN rendu sarlu. UNLOCK: PIN leda fingerprint.
 */
class PinScreen(
    private val activity: AppCompatActivity,
    private val setMode: Boolean,
    private val onSuccess: () -> Unit,
) {
    private val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    private val view = activity.layoutInflater.inflate(R.layout.screen_pin, null)
    private val title: TextView = view.findViewById(R.id.pin_title)
    private val sub: TextView = view.findViewById(R.id.pin_sub)
    private val dots: LinearLayout = view.findViewById(R.id.pin_dots)
    private val keys: GridLayout = view.findViewById(R.id.pin_keys)
    private val useFp: TextView = view.findViewById(R.id.pin_use_fp)
    private val entered = StringBuilder()
    private var first: String? = null
    private val d = activity.resources.displayMetrics.density
    private val bioAuth = BiometricManager.Authenticators.BIOMETRIC_WEAK
    private val canBio: Boolean = !setMode && try {
        BiometricManager.from(activity).canAuthenticate(bioAuth) == BiometricManager.BIOMETRIC_SUCCESS
    } catch (_: Exception) {
        false
    }

    companion object {
        private var wrong = 0
        private var lockedUntil = 0L
    }

    private fun dp(v: Int) = (v * d).toInt()

    fun show() {
        title.setText(if (setMode) R.string.private_set_pin else R.string.private_enter_pin)
        repeat(4) {
            dots.addView(View(activity), LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                marginStart = dp(9)
                marginEnd = dp(9)
            })
        }
        buildKeys()
        updateDots()
        useFp.visibility = if (canBio) View.VISIBLE else View.GONE
        useFp.setOnClickListener { biometric() }
        view.findViewById<View>(R.id.pin_back).setOnClickListener { dialog.dismiss() }
        UiKit.fitSystemBars(view)
        dialog.setContentView(view)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.show()
        if (canBio && !lockedOut()) view.post { biometric() }
    }

    private fun buildKeys() {
        val size = dp(76)
        val labels = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "fp", "0", "del")
        labels.forEach { k ->
            val lp = GridLayout.LayoutParams().apply {
                width = size
                height = size
                setMargins(dp(14), dp(8), dp(14), dp(8))
            }
            val v: View = when (k) {
                "fp" -> ImageButton(activity).apply {
                    setImageResource(R.drawable.ic_fingerprint)
                    background = null
                    contentDescription = activity.getString(R.string.unlock_fingerprint)
                    visibility = if (canBio) View.VISIBLE else View.INVISIBLE
                    setOnClickListener { biometric() }
                }
                "del" -> ImageButton(activity).apply {
                    setImageResource(R.drawable.ic_backspace)
                    background = null
                    contentDescription = activity.getString(R.string.back)
                    setOnClickListener {
                        if (entered.isNotEmpty()) entered.deleteCharAt(entered.length - 1)
                        updateDots()
                    }
                }
                else -> TextView(activity).apply {
                    text = k
                    textSize = 28f
                    gravity = Gravity.CENTER
                    setTextColor(0xFFFFFFFF.toInt())
                    background = ContextCompat.getDrawable(activity, R.drawable.bg_key)
                    isClickable = true
                    setOnClickListener { press(k) }
                }
            }
            keys.addView(v, lp)
        }
    }

    private fun updateDots() {
        for (i in 0 until dots.childCount) {
            dots.getChildAt(i).setBackgroundResource(
                if (i < entered.length) R.drawable.bg_pin_dot_on else R.drawable.bg_pin_dot_off
            )
        }
    }

    private fun lockedOut(): Boolean {
        val left = lockedUntil - SystemClock.elapsedRealtime()
        if (left <= 0) return false
        sub.text = activity.getString(R.string.private_locked, (left / 1000 + 1).toInt())
        return true
    }

    private fun press(k: String) {
        if (!setMode && lockedOut()) return
        if (entered.length >= 4) return
        entered.append(k)
        updateDots()
        if (entered.length == 4) view.postDelayed({ complete() }, 120)
    }

    private fun complete() {
        val pin = entered.toString()
        entered.clear()
        if (setMode) {
            val f = first
            if (f == null) {
                first = pin
                title.setText(R.string.private_confirm_pin)
                updateDots()
            } else if (f == pin) {
                Privacy.setPin(activity, pin)
                done()
            } else {
                first = null
                title.setText(R.string.private_set_pin)
                fail(activity.getString(R.string.private_pin_mismatch))
            }
            return
        }
        if (Privacy.checkPin(activity, pin)) {
            wrong = 0
            done()
        } else {
            wrong++
            if (wrong >= 5) {
                wrong = 0
                lockedUntil = SystemClock.elapsedRealtime() + 30_000L
                updateDots()
                lockedOut()
                shake()
            } else {
                fail(activity.getString(R.string.private_wrong_pin))
            }
        }
    }

    private fun fail(msg: String) {
        sub.text = msg
        updateDots()
        shake()
    }

    private fun shake() {
        dots.animate().translationX(dp(14).toFloat()).setDuration(50).withEndAction {
            dots.animate().translationX(-dp(14).toFloat()).setDuration(70).withEndAction {
                dots.animate().translationX(dp(8).toFloat()).setDuration(60).withEndAction {
                    dots.animate().translationX(0f).setDuration(50).start()
                }.start()
            }.start()
        }.start()
    }

    private fun done() {
        dialog.dismiss()
        onSuccess()
    }

    private fun biometric() {
        if (!canBio) return
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    wrong = 0
                    done()
                }
            })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.private_folder))
            .setSubtitle(activity.getString(R.string.unlock_fingerprint))
            .setNegativeButtonText(activity.getString(R.string.use_pin))
            .setAllowedAuthenticators(bioAuth)
            .build()
        try {
            prompt.authenticate(info)
        } catch (_: Exception) {
        }
    }
}
