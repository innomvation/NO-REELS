package com.reelblock.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    /** Switches that turn blocking off; disabled while the manager lock is on. */
    private lateinit var blockSwitches: List<Switch>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        installLockHashFrom(intent)

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

        blockSwitches = listOf(blockInstagramSwitch, blockYoutubeSwitch, igExploreHideSwitch, ytHomeHideSwitch)

        findViewById<Button>(R.id.activateAdminButton).setOnClickListener {
            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent())
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "활성화하면 관리자 비밀번호 없이는 NO REELS를 지울 수 없어요.")
            })
        }
        val passwordInput = findViewById<EditText>(R.id.unlockPasswordInput)
        findViewById<Button>(R.id.unlockButton).setOnClickListener {
            val ok = Lock.unlock(this, passwordInput.text.toString())
            passwordInput.text.clear()
            Toast.makeText(this, if (ok) "해제됐어요. 이제 끄거나 지울 수 있어요." else "비밀번호가 틀렸어요.", Toast.LENGTH_SHORT).show()
            refreshLockUi()
        }
        findViewById<Button>(R.id.relockButton).setOnClickListener {
            Lock.relock(this)
            refreshLockUi()
        }

        statusText.text = if (isAccessibilityServiceEnabled()) {
            "접근성 서비스가 켜져 있습니다. 아래에서 켠 항목만 차단됩니다."
        } else {
            "접근성 서비스가 꺼져 있습니다. 아래 버튼을 눌러 'NO REELS'를 켜주세요."
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLockUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        installLockHashFrom(intent)
        refreshLockUi()
    }

    /**
     * First-time setup from the PC: `am start -n com.reelblock.app/.MainActivity
     * --es lock_salt <b64> --es lock_hash <b64>`. Ignored while locked.
     */
    private fun installLockHashFrom(intent: Intent?) {
        val salt = intent?.getStringExtra(EXTRA_LOCK_SALT) ?: return
        val hash = intent.getStringExtra(EXTRA_LOCK_HASH) ?: return
        val ok = Lock.setPasswordHash(this, salt, hash)
        Toast.makeText(this, if (ok) "관리자 비밀번호가 설정되고 잠겼어요." else "잠겨 있어서 비밀번호를 바꾸지 않았어요.", Toast.LENGTH_SHORT).show()
    }

    private fun adminComponent() = ComponentName(this, AdminReceiver::class.java)

    private fun refreshLockUi() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val adminActive = dpm.isAdminActive(adminComponent())
        val hasPassword = Lock.hasPassword(this)
        val locked = Lock.isLocked(this)
        findViewById<TextView>(R.id.lockStatusText).text = buildString {
            append(if (adminActive) "기기 관리자: 켜짐 (삭제 방지)" else "기기 관리자: 꺼짐")
            appendLine()
            append(when {
                !hasPassword -> "관리자 비밀번호: 아직 없음"
                locked -> "보호 중: 관리자 비밀번호가 있어야 끄거나 지울 수 있어요"
                else -> "보호 해제됨: 지금은 끄거나 지울 수 있어요"
            })
        }
        findViewById<Button>(R.id.activateAdminButton).visibility = if (adminActive) View.GONE else View.VISIBLE
        findViewById<View>(R.id.unlockPasswordInput).visibility = if (locked) View.VISIBLE else View.GONE
        findViewById<View>(R.id.unlockButton).visibility = if (locked) View.VISIBLE else View.GONE
        findViewById<View>(R.id.relockButton).visibility = if (hasPassword && !locked) View.VISIBLE else View.GONE
        blockSwitches.forEach { it.isEnabled = !locked }
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

private const val EXTRA_LOCK_SALT = "lock_salt"
private const val EXTRA_LOCK_HASH = "lock_hash"

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
