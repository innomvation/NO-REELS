package com.reelblock.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

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
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in TARGET_PACKAGES) return
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

        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        val enabledKey = if (packageName == INSTAGRAM_PACKAGE) Prefs.BLOCK_INSTAGRAM else Prefs.BLOCK_YOUTUBE
        if (!prefs.getBoolean(enabledKey, true)) {
            if (packageName == INSTAGRAM_PACKAGE) hideFeedOverlay()
            return
        }

        val debugLog = prefs.getBoolean(Prefs.DEBUG_LOG, false)
        val root = rootInActiveWindow ?: return

        val match = try {
            findReelsIndicator(root, packageName, debugLog)
        } catch (e: Exception) {
            Log.d(TAG, "traversal error: ${e.message}")
            null
        }

        if (match != null) {
            Log.d(TAG, "MATCH reason=${match.reason} id=${match.resId} desc=${match.desc} class=${match.className} -> back")
            lastBlockAtMs = now
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }

        if (packageName == INSTAGRAM_PACKAGE) {
            val suggested = try { findSuggestedFeed(root, debugLog) } catch (e: Exception) { false }
            if (suggested) showFeedOverlay() else hideFeedOverlay()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        hideFeedOverlay()
        super.onDestroy()
    }

    // ---- Home feed: cover suggested/ad posts ("추천"/"광고"/unfollowed accounts) ----

    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())

    /**
     * True when the Instagram Home tab is selected and a post from an account
     * the user doesn't follow is meaningfully on screen. Suggested/ad posts carry a
     * "추천 …"/"광고 …" description; some unfollowed posts skip that prefix but still show
     * an "OOO님 팔로우" button in their header.
     */
    private fun findSuggestedFeed(root: AccessibilityNodeInfo, debugLog: Boolean): Boolean {
        val screenHeight = resources.displayMetrics.heightPixels
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        var homeSelected = false
        var navTop = screenHeight
        var suggested = false
        val bounds = Rect()

        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++
            val desc = safe { node.contentDescription?.toString() }?.trim()
            val className = safe { node.className?.toString() }

            if (desc != null && depth <= TAB_BAR_MAX_DEPTH && className == "android.widget.FrameLayout" &&
                (isHomeTabLabel(desc) || isExactReelsLabel(desc))
            ) {
                safe { node.getBoundsInScreen(bounds) }
                if (bounds.top in 1 until navTop) navTop = bounds.top
                if (isHomeTabLabel(desc) && safe { node.isSelected } == true) homeSelected = true
            }

            if (!suggested && desc != null) {
                if (SUGGESTED_POST_PREFIXES.any { desc.startsWith(it) }) {
                    safe { node.getBoundsInScreen(bounds) }
                    val visible = minOf(bounds.bottom, navTop) - maxOf(bounds.top, 0)
                    if (bounds.width() > 0 && visible >= screenHeight * SUGGESTED_VISIBLE_RATIO) suggested = true
                } else if (desc.endsWith("님 팔로우") && depth <= FOLLOW_BUTTON_MAX_DEPTH && className == "android.widget.TextView") {
                    // Post-header follow button; the "회원님을 위한 추천" account carousel sits deeper.
                    safe { node.getBoundsInScreen(bounds) }
                    if (bounds.width() > 0 && bounds.top in 0..(screenHeight * FOLLOW_BUTTON_MAX_TOP_RATIO).toInt()) suggested = true
                }
                if (suggested && debugLog) Log.d(TAG, "FEED suggested: depth=$depth desc=$desc bounds=$bounds")
            }

            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    safe { node.getChild(i) }?.let { queue.add(it to depth + 1) }
                }
            }
        }
        return homeSelected && suggested
    }

    private fun showFeedOverlay() {
        if (overlayView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dp = resources.displayMetrics.density

        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(18, 18, 18))
            addView(TextView(context).apply {
                text = "✓\n다 봤어요"
                textSize = 26f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = "팔로우한 계정 글은 여기까지예요.\n추천 게시물은 가려둘게요."
                textSize = 15f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
                setPadding(0, (12 * dp).toInt(), 0, (24 * dp).toInt())
            })
            addView(Button(context).apply {
                text = "맨 위로"
                setOnClickListener { scrollFeedToTop() }
            })
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }

        try {
            wm.addView(view, params)
            overlayView = view
            handler.postDelayed(overlayWatchdog, OVERLAY_WATCHDOG_MS)
        } catch (e: Exception) {
            Log.d(TAG, "overlay add failed: ${e.message}")
        }
    }

    private fun hideFeedOverlay() {
        val view = overlayView ?: return
        handler.removeCallbacks(overlayWatchdog)
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) } catch (_: Exception) {}
        overlayView = null
    }

    /** Events only arrive for Instagram/YouTube, so poll to drop the overlay once the user leaves Instagram. */
    private val overlayWatchdog = object : Runnable {
        override fun run() {
            if (overlayView == null) return
            val pkg = safe { rootInActiveWindow?.packageName?.toString() }
            if (pkg != INSTAGRAM_PACKAGE) {
                hideFeedOverlay()
            } else {
                handler.postDelayed(this, OVERLAY_WATCHDOG_MS)
            }
        }
    }

    /** Re-tapping the Home tab makes Instagram scroll the feed back to the top. */
    private fun scrollFeedToTop() {
        hideFeedOverlay()
        val root = rootInActiveWindow ?: return
        val homeTabs = safe { root.findAccessibilityNodeInfosByText("홈") } ?: return
        homeTabs.firstOrNull { safe { it.contentDescription?.toString()?.trim() } == "홈" }
            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private data class MatchInfo(val resId: String?, val desc: String?, val className: String?, val reason: String)

    /** BFS over the active window, capped so this stays cheap on every content-changed event. */
    private fun findReelsIndicator(root: AccessibilityNodeInfo, packageName: String, debugLog: Boolean): MatchInfo? {
        val isInstagram = packageName == INSTAGRAM_PACKAGE
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        // The bottom nav (홈/릴스/메시지/검색/프로필 row) only exists while browsing normal
        // screens; Instagram hides it in the immersive fullscreen Reels player. BFS visits
        // this shallow row before any deeper "player-description" node, so by the time we'd
        // consider that match this flag already reflects whether the row was present.
        var bottomNavVisible = false
        var dmChatOverlay = false
        var sawRemix = false
        var sawShortsShare = false
        var bestFullscreenRatio = 0f
        var playerDescMatch: MatchInfo? = null
        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels

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

            if (isInstagram) {
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
                    // A node that hasn't actually been laid out/rendered can report a tall but
                    // zero-width rect (left == right) — height alone made that look "fullscreen".
                    // Require the width to also be there before trusting the height ratio.
                    val widthRatio = if (gotBounds && screenWidth > 0) bounds.width().toFloat() / screenWidth else 0f
                    val heightRatio = if (gotBounds && screenHeight > 0) bounds.height().toFloat() / screenHeight else 0f
                    if (debugLog) {
                        Log.d(TAG, "  ^ reel-desc bounds=$bounds widthRatio=$widthRatio heightRatio=$heightRatio")
                    }
                    if (widthRatio >= MIN_WIDTH_RATIO && heightRatio > bestFullscreenRatio) {
                        bestFullscreenRatio = heightRatio
                        playerDescMatch = MatchInfo(resId, desc, className, "player-description ratio=$heightRatio")
                    }
                }
            }
            if (!isInstagram) {
                // YouTube's bottom nav (홈/Shorts/만들기/구독/내 페이지) stays visible even
                // inside the fullscreen Shorts player, unlike Instagram, so tab-selection
                // alone isn't enough to tell "just switched tabs" apart from "still watching".
                // But the tab click itself is still worth catching immediately.
                if (selected && depth <= YOUTUBE_TAB_BAR_MAX_DEPTH && className == "android.widget.Button" && isExactShortsLabel(desc)) {
                    return MatchInfo(resId, desc, className, "yt-tab-label+selected")
                }
                // "리믹스" alone is NOT Shorts-only: the share sheet of a regular video also
                // lists it (링크 복사 / Quick Share / 게시물 작성 / 리믹스). The Shorts side rail
                // has it together with "동영상 공유", so require both; the sound button is
                // Shorts-only by itself.
                if (isShortsPlayerMarker(desc)) {
                    return MatchInfo(resId, desc, className, "yt-player-marker")
                }
                if (desc?.trim() == "리믹스") sawRemix = true
                if (desc?.trim() == "동영상 공유") sawShortsShare = true
                if (sawRemix && sawShortsShare) {
                    return MatchInfo(resId, desc, className, "yt-remix+share-rail")
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

    private fun isExactShortsLabel(desc: String?): Boolean {
        if (desc == null) return false
        return desc.trim().equals("Shorts", ignoreCase = true)
    }

    private fun isShortsPlayerMarker(desc: String?): Boolean {
        if (desc == null) return false
        return SHORTS_PLAYER_MARKERS.any { desc.contains(it) }
    }

    companion object {
        private const val TAG = "ReelBlock"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private val TARGET_PACKAGES = setOf(INSTAGRAM_PACKAGE, YOUTUBE_PACKAGE)
        private const val BLOCK_COOLDOWN_MS = 800L
        private const val PROCESS_THROTTLE_MS = 200L
        private const val MAX_NODES = 600
        private const val MAX_DEPTH = 20
        private const val TAB_BAR_MAX_DEPTH = 3
        private const val YOUTUBE_TAB_BAR_MAX_DEPTH = 10
        private const val FULLSCREEN_RATIO_THRESHOLD = 0.5f
        private const val MIN_WIDTH_RATIO = 0.8f

        // Home feed suggestion cover
        private val SUGGESTED_POST_PREFIXES = listOf("추천 ", "광고 ")
        private const val SUGGESTED_VISIBLE_RATIO = 0.35f
        private const val FOLLOW_BUTTON_MAX_DEPTH = 7
        private const val FOLLOW_BUTTON_MAX_TOP_RATIO = 0.6f
        private const val OVERLAY_WATCHDOG_MS = 500L

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

        // Shorts-exclusive actions on the fullscreen player; regular YouTube videos don't
        // have these, so no bounds/ratio check is needed like Instagram required.
        private val SHORTS_PLAYER_MARKERS = listOf("이 사운드를 사용하는 동영상 더보기")
    }
}
