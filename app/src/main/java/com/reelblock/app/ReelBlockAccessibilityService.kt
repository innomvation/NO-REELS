package com.reelblock.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Phase 1: when Instagram shows the Reels tab (bottom nav) or the fullscreen
 * Reels player, immediately press back. Everything else (Home, DMs, profile,
 * search) is left untouched.
 *
 * The bottom nav's Reels tab button is present in the tree on every screen
 * (Home, Search, Profile...), so matching MUST require isSelected == true for
 * tab-based signals. Only the fullscreen Reels player resource-id is treated
 * as an unconditional match, since that view only exists while actually
 * viewing Reels. Turn on "디버그 로그 모드" in the app to dump candidate node
 * info to logcat (filter: ReelBlock) and update the id/label lists below.
 */
class ReelBlockAccessibilityService : AccessibilityService() {

    private var lastBlockAtMs = 0L
    private var lastProcessedAtMs = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName != INSTAGRAM_PACKAGE) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val now = System.currentTimeMillis()
        if (now - lastBlockAtMs < BLOCK_COOLDOWN_MS) return
        // Fast scrolling fires content-changed events dozens of times per second; a full
        // tree walk on every single one burns CPU for no benefit, so only process at most
        // a few times per second. Still fast enough to catch a Reels tap almost instantly.
        if (now - lastProcessedAtMs < PROCESS_THROTTLE_MS) return
        lastProcessedAtMs = now

        val debugLog = getSharedPreferences(Prefs.NAME, MODE_PRIVATE).getBoolean(Prefs.DEBUG_LOG, false)
        val root = rootInActiveWindow ?: return

        val match = try {
            findReelsIndicator(root, debugLog)
        } catch (e: Exception) {
            Log.d(TAG, "traversal error: ${e.message}")
            null
        }

        if (match != null) {
            Log.d(TAG, "MATCH reason=${match.reason} id=${match.resId} desc=${match.desc} class=${match.className} -> back")
            lastBlockAtMs = now
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    override fun onInterrupt() {}

    private data class MatchInfo(val resId: String?, val desc: String?, val className: String?, val reason: String)

    /** BFS over the active window, capped so this stays cheap on every content-changed event. */
    private fun findReelsIndicator(root: AccessibilityNodeInfo, debugLog: Boolean): MatchInfo? {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        // The bottom nav (홈/릴스/메시지/검색/프로필 row) only exists while browsing normal
        // screens; Instagram hides it in the immersive fullscreen Reels player. BFS visits
        // this shallow row before any deeper "player-description" node, so by the time we'd
        // consider that match this flag already reflects whether the row was present.
        var bottomNavVisible = false
        var dmChatOverlay = false
        var bestFullscreenRatio = 0f
        var playerDescMatch: MatchInfo? = null
        val screenHeight = resources.displayMetrics.heightPixels

        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++

            val resId = safe { node.viewIdResourceName }
            val desc = safe { node.contentDescription?.toString() }
            val selected = safe { node.isSelected } ?: false
            val className = safe { node.className?.toString() }

            if (debugLog && (resId != null || desc != null)) {
                Log.d(TAG, "depth=$depth id=$resId desc=$desc selected=$selected class=$className")
            }

            if (isReelsPlayerResourceId(resId)) {
                return MatchInfo(resId, desc, className, "player-id")
            }
            if (selected && isReelsTabResourceId(resId)) {
                return MatchInfo(resId, desc, className, "tab-id+selected")
            }
            // Instagram also sets isSelected=true on unrelated in-feed "릴스" badges
            // deep inside the Home feed (e.g. the Reels tray). Only the actual bottom-nav
            // tab button sits this shallow and is a FrameLayout, so require both to avoid
            // matching those badges (which caused false triggers even on the DM screen).
            if (selected && depth <= TAB_BAR_MAX_DEPTH && className == "android.widget.FrameLayout" && isExactReelsLabel(desc)) {
                return MatchInfo(resId, desc, className, "tab-label+selected")
            }
            if (depth <= TAB_BAR_MAX_DEPTH && className == "android.widget.FrameLayout" && isHomeTabLabel(desc)) {
                bottomNavVisible = true
            }
            // A reel opened from inside a DM thread plays with the chat's emoji-reaction
            // bar still overlaid on top ("이모티콘 공감 시트 열기" etc) — that overlay only
            // exists in a chat context, so its presence means "let them watch this one".
            if (isDmChatMarker(desc)) {
                dmChatOverlay = true
            }
            // Fullscreen Reels video surfaces (opened from the tab, Home feed, Search grid,
            // or a profile) all carry this exact instruction text on the video node itself.
            // Regular photo/video posts and people-search results use different phrasing.
            // But the SAME text also shows up on reel cards embedded inline in a scrollable
            // list (Home feed, a profile's grid/feed) — those are much smaller than the
            // screen, while the real immersive player fills it, so measure actual bounds
            // instead of trusting the text alone.
            if (isReelsPlayerDescription(desc)) {
                val bounds = Rect()
                val gotBounds = safe { node.getBoundsInScreen(bounds) } != null
                val ratio = if (gotBounds && screenHeight > 0) bounds.height().toFloat() / screenHeight else 0f
                if (debugLog) {
                    Log.d(TAG, "  ^ reel-desc bounds=$bounds ratio=$ratio")
                }
                if (ratio > bestFullscreenRatio) {
                    bestFullscreenRatio = ratio
                    playerDescMatch = MatchInfo(resId, desc, className, "player-description ratio=$ratio")
                }
            }

            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    safe { node.getChild(i) }?.let { queue.add(it to depth + 1) }
                }
            }
        }
        return if (playerDescMatch != null && !bottomNavVisible && !dmChatOverlay && bestFullscreenRatio >= FULLSCREEN_RATIO_THRESHOLD) {
            playerDescMatch
        } else {
            null
        }
    }

    private inline fun <T> safe(block: () -> T): T? = try { block() } catch (e: Exception) { null }

    private fun isExactReelsLabel(desc: String?): Boolean {
        if (desc == null) return false
        val trimmed = desc.trim()
        return REELS_LABELS.any { trimmed.equals(it, ignoreCase = true) }
    }

    private fun isHomeTabLabel(desc: String?): Boolean {
        if (desc == null) return false
        val trimmed = desc.trim()
        return HOME_TAB_LABELS.any { trimmed.equals(it, ignoreCase = true) }
    }

    private fun isDmChatMarker(desc: String?): Boolean {
        if (desc == null) return false
        return DM_CHAT_MARKERS.any { desc.contains(it) }
    }

    private fun isReelsPlayerResourceId(resId: String?): Boolean {
        if (resId == null) return false
        return REELS_PLAYER_IDS.any { resId.contains(it, ignoreCase = true) }
    }

    private fun isReelsTabResourceId(resId: String?): Boolean {
        if (resId == null) return false
        return REELS_TAB_IDS.any { resId.contains(it, ignoreCase = true) }
    }

    private fun isReelsPlayerDescription(desc: String?): Boolean {
        if (desc == null) return false
        return REELS_PLAYER_DESC_MARKERS.any { desc.contains(it) }
    }

    companion object {
        private const val TAG = "ReelBlock"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"
        private const val BLOCK_COOLDOWN_MS = 800L
        private const val PROCESS_THROTTLE_MS = 200L
        private const val MAX_NODES = 600
        private const val MAX_DEPTH = 20
        private const val TAB_BAR_MAX_DEPTH = 3
        private const val FULLSCREEN_RATIO_THRESHOLD = 0.5f

        private val REELS_LABELS = listOf("Reels", "릴스")
        private val HOME_TAB_LABELS = listOf("홈", "Home")

        // Only the fullscreen player id is safe to match unconditionally.
        private val REELS_PLAYER_IDS = listOf("clips_viewer", "reels_viewer")

        // Tab-bar ids only count as a match when isSelected == true (see above).
        private val REELS_TAB_IDS = listOf("clips_tab", "reels_tab")

        // Content-description Instagram puts on an actual Reels video surface, e.g.
        // "OOO님이 만든 릴스입니다. 재생하거나 일시 중지하려면 두 번 누르세요."
        private val REELS_PLAYER_DESC_MARKERS = listOf("만든 릴스입니다", "Reel by")

        // Only present when a reel is opened from inside a DM thread (the chat's
        // emoji-reaction bar stays overlaid on the video).
        private val DM_CHAT_MARKERS = listOf("공감 시트", "이모티콘 공감")
    }
}
