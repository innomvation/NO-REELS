package com.reelblock.app

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Device admin registration. While active, Android refuses to uninstall the app (from Settings,
 * the launcher or `adb uninstall`) until the admin is deactivated in Settings → 기기 관리자 앱 —
 * and that screen is guarded by the accessibility service while [Lock] is locked.
 */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "NO REELS 보호가 걸려 있어요. 관리자 비밀번호로 먼저 해제하세요."
}
