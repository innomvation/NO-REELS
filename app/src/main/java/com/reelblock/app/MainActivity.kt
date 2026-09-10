package com.reelblock.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        val openSettingsButton = findViewById<Button>(R.id.openAccessibilitySettingsButton)
        val debugLogSwitch = findViewById<Switch>(R.id.debugLogSwitch)

        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        debugLogSwitch.isChecked = prefs.getBoolean(Prefs.DEBUG_LOG, false)
        debugLogSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(Prefs.DEBUG_LOG, isChecked).apply()
        }

        openSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        statusText.text = if (isAccessibilityServiceEnabled()) {
            "접근성 서비스가 켜져 있습니다. 인스타그램 릴스가 차단됩니다."
        } else {
            "접근성 서비스가 꺼져 있습니다. 아래 버튼을 눌러 'ReelBlock'을 켜주세요."
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${ReelBlockAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
            .asSequence().contains(expected)
    }

    private fun TextUtils.SimpleStringSplitter.asSequence(): Sequence<String> = sequence {
        while (hasNext()) yield(next())
    }
}

object Prefs {
    const val NAME = "reelblock_prefs"
    const val DEBUG_LOG = "debug_log"
}
