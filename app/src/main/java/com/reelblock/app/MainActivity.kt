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
        val blockInstagramSwitch = findViewById<Switch>(R.id.blockInstagramSwitch)
        val blockYoutubeSwitch = findViewById<Switch>(R.id.blockYoutubeSwitch)
        val debugLogSwitch = findViewById<Switch>(R.id.debugLogSwitch)

        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        // Drop data left by the removed Instagram home suggested-post limit.
        prefs.edit().remove("feed_seen_authors").remove("feed_window_start").remove("feed_limit_enabled")
            .remove("feed_limit_count").remove("feed_limit_window_min").apply()

        blockInstagramSwitch.isChecked = prefs.getBoolean(Prefs.BLOCK_INSTAGRAM, true)
        blockInstagramSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(Prefs.BLOCK_INSTAGRAM, isChecked).apply()
        }

        blockYoutubeSwitch.isChecked = prefs.getBoolean(Prefs.BLOCK_YOUTUBE, true)
        blockYoutubeSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(Prefs.BLOCK_YOUTUBE, isChecked).apply()
        }

        val igExploreHideSwitch = findViewById<Switch>(R.id.igExploreHideSwitch)
        igExploreHideSwitch.isChecked = prefs.getBoolean(Prefs.IG_EXPLORE_HIDE_ENABLED, true)
        igExploreHideSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(Prefs.IG_EXPLORE_HIDE_ENABLED, isChecked).apply()
        }

        val ytHomeHideSwitch = findViewById<Switch>(R.id.ytHomeHideSwitch)
        ytHomeHideSwitch.isChecked = prefs.getBoolean(Prefs.YT_HOME_HIDE_ENABLED, true)
        ytHomeHideSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(Prefs.YT_HOME_HIDE_ENABLED, isChecked).apply()
        }

        debugLogSwitch.isChecked = prefs.getBoolean(Prefs.DEBUG_LOG, false)
        debugLogSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(Prefs.DEBUG_LOG, isChecked).apply()
        }

        openSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        statusText.text = if (isAccessibilityServiceEnabled()) {
            "접근성 서비스가 켜져 있습니다. 아래에서 켠 항목만 차단됩니다."
        } else {
            "접근성 서비스가 꺼져 있습니다. 아래 버튼을 눌러 'NO REELS'를 켜주세요."
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
    const val BLOCK_INSTAGRAM = "block_instagram"
    const val BLOCK_YOUTUBE = "block_youtube"

    // Instagram search tab: cover the Explore grid, keep the search box (default on).
    const val IG_EXPLORE_HIDE_ENABLED = "ig_explore_hide_enabled"

    // YouTube: never show the Home feed; redirect the 홈 chip to 구독 (default on).
    const val YT_HOME_HIDE_ENABLED = "yt_home_hide_enabled"
}
