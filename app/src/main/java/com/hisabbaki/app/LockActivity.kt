package com.hisabbaki.app

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.util.concurrent.Executor

/**
 * PIN / biometric gate. Shown by MainActivity whenever the app returns to the
 * foreground after the re-lock timeout and an app lock is configured.
 */
class LockActivity : AppCompatActivity() {

    companion object {
        @Volatile
        var unlockedAtRealtimeMs: Long = 0L

        const val RELOCK_MS: Long = 3L * 60L * 1000L

        fun shouldLock(ctx: android.content.Context): Boolean =
            Prefs.isLockEnabled(ctx) && Prefs.hasPin(ctx) &&
                SystemClock.elapsedRealtime() - unlockedAtRealtimeMs > RELOCK_MS
    }

    private val entered = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock)

        for (id in intArrayOf(
            R.id.btnK1, R.id.btnK2, R.id.btnK3, R.id.btnK4, R.id.btnK5,
            R.id.btnK6, R.id.btnK7, R.id.btnK8, R.id.btnK9, R.id.btnK0
        )) {
            val b = findViewById<MaterialButton>(id)
            b.setOnClickListener { press(b.text.toString()) }
        }
        findViewById<MaterialButton>(R.id.btnKBack).setOnClickListener {
            if (entered.isNotEmpty()) entered.deleteCharAt(entered.length - 1)
            updateDots()
        }

        val manual = findViewById<MaterialButton>(R.id.btnBiometric)
        val canBio = Prefs.isBioUnlock(this) && canAuthenticate()
        manual.visibility = if (canBio) View.VISIBLE else View.GONE
        if (canBio) {
            manual.setOnClickListener { showBiometric() }
            showBiometric()
        }
        updateDots()
    }

    private fun canAuthenticate(): Boolean {
        val bm = BiometricManager.from(this)
        return bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun showBiometric() {
        val executor: Executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlock()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.app_lock))
            .setSubtitle(getString(R.string.lock_subtitle))
            .setNegativeButtonText(getString(R.string.cancel))
            .build()
        prompt.authenticate(info)
    }

    private fun press(digit: String) {
        if (entered.length >= 8) return
        entered.append(digit)
        updateDots()
        val target = Prefs.pinLength(this)
        if (entered.length == target) {
            if (Prefs.checkPin(this, entered.toString())) {
                unlock()
            } else {
                findViewById<TextView>(R.id.tvLockError).setText(R.string.lock_wrong_pin)
                entered.setLength(0)
                updateDots()
            }
        }
    }

    private fun unlock() {
        unlockedAtRealtimeMs = SystemClock.elapsedRealtime()
        finish()
    }

    private fun updateDots() {
        val len = Prefs.pinLength(this).coerceIn(4, 8)
        val sb = StringBuilder()
        for (i in 0 until len) sb.append(if (i < entered.length) "●" else "○")
        findViewById<TextView>(R.id.tvDots).text = sb.toString()
    }
}
