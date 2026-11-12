package com.reelblock.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
            hideOverlayOf(packageName, "app switch off")
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
            val exploreRegion = if (prefs.getBoolean(Prefs.IG_EXPLORE_HIDE_ENABLED, true)) {
                try { findExploreFeedRegion(root, debugLog) } catch (e: Exception) { null }
            } else null
            if (exploreRegion != null) {
                showOverlay(OverlayKind.IG_EXPLORE, "explore grid on screen", exploreRegion)
            } else {
                hideOverlay(OverlayKind.IG_EXPLORE, "explore grid not on screen")
            }
        } else {
            if (!prefs.getBoolean(Prefs.YT_HOME_HIDE_ENABLED, true)) {
                hideOverlay(OverlayKind.YT_HOME, "yt home hide off")
                return
            }
            val home = try { readYoutubeHomeChips(root, debugLog) } catch (e: Exception) { null }
            if (home == null) {
                hideOverlay(OverlayKind.YT_HOME, "yt home chip not selected")
                return
            }
            showOverlay(OverlayKind.YT_HOME, "yt home chip selected", home.feedRegion)
            redirectToSubscriptions(home.subscriptionsChip, now)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        hideOverlay(null, "service destroyed")
        super.onDestroy()
    }

    // ---- YouTube Home: never show the Home feed, send the user to the 구독 chip ----

    private var lastYtChipClickAtMs = 0L

    private class YtHomeState(val subscriptionsChip: AccessibilityNodeInfo?, val feedRegion: Rect)

    /**
     * Non-null when the browse screen's top chip row is on screen with its "홈" chip selected.
     * Chips are recognised by view id (tabs_bar_text_tab_view); if ids aren't reported, by
     * being a Button in the top part of the screen. The chip row is not in the tree inside the
     * Shorts player or the watch page, so those never match. The returned region spans from the
     * chip row's bottom to the bottom nav's top, so chips and nav stay visible and tappable.
     */
    private fun readYoutubeHomeChips(root: AccessibilityNodeInfo, debugLog: Boolean): YtHomeState? {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        var homeChipSelected: Boolean? = null
        var homeChipVisible = false
        var selectedChip: String? = null
        var subscriptionsChip: AccessibilityNodeInfo? = null
        var chipRowBottom = 0
        var navTop = screenHeight
        val bounds = Rect()

        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++
            if (safe { node.className?.toString() } == "android.widget.Button") {
                val desc = safe { node.contentDescription?.toString() }?.trim()
                if (desc != null) {
                    val resId = safe { node.viewIdResourceName }
                    safe { node.getBoundsInScreen(bounds) }
                    val isChip = if (resId != null) {
                        resId.endsWith(YT_CHIP_ID_SUFFIX)
                    } else {
                        bounds.centerY() in 0 until (screenHeight * YT_CHIP_MAX_CENTER_RATIO).toInt() &&
                            (isHomeTabLabel(desc) || isYoutubeChipLabel(desc))
                    }
                    val selected = safe { node.isSelected } == true
                    if (isChip) {
                        val onScreen = safe { node.isVisibleToUser } == true && bounds.height() > 0 && bounds.bottom > 0
                        if (onScreen) chipRowBottom = maxOf(chipRowBottom, bounds.bottom)
                        if (selected) selectedChip = desc
                        if (isHomeTabLabel(desc)) {
                            homeChipSelected = selected
                            homeChipVisible = onScreen
                        } else if (isSubscriptionsLabel(desc)) {
                            subscriptionsChip = node
                        }
                    } else if (isHomeTabLabel(desc) && bounds.top > screenHeight / 2 && bounds.height() > 0) {
                        navTop = minOf(navTop, bounds.top)
                    }
                }
            }
            if (depth < YOUTUBE_TAB_BAR_MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    safe { node.getChild(i) }?.let { queue.add(it to depth + 1) }
                }
            }
        }

        val isHome = homeChipSelected == true && homeChipVisible
        if (debugLog) {
            Log.d(TAG, "YT chips: home=$homeChipSelected visible=$homeChipVisible selected=$selectedChip " +
                "subsFound=${subscriptionsChip != null} rowBottom=$chipRowBottom navTop=$navTop visited=$visited -> home=$isHome")
        }
        if (!isHome || navTop <= chipRowBottom) return null
        return YtHomeState(subscriptionsChip, Rect(0, chipRowBottom, screenWidth, navTop))
    }

    /** Clicks the 구독 chip, at most once per [YT_CHIP_CLICK_INTERVAL_MS] so a failing click can't spin. */
    private fun redirectToSubscriptions(chip: AccessibilityNodeInfo?, now: Long) {
        if (now - lastYtChipClickAtMs < YT_CHIP_CLICK_INTERVAL_MS) return
        lastYtChipClickAtMs = now
        if (chip == null) {
            Log.d(TAG, "YT 홈 chip selected but 구독 chip not found")
            return
        }
        // The chip Button should be clickable itself; otherwise try its nearest clickable ancestor.
        var target: AccessibilityNodeInfo? = chip
        var hops = 0
        while (target != null && safe { target!!.isClickable } != true && hops < 3) {
            target = safe { target!!.parent }
            hops++
        }
        val clicked = target?.let { safe { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } } == true
        Log.d(TAG, "YT 홈 chip -> click 구독 chip (hops=$hops) result=$clicked")
    }

    // ---- Instagram search tab: hide the Explore grid, keep the search box ----

    /**
     * Non-null (the region between the search box and the bottom tab bar) when the search tab
     * is selected, the search-hint Button is on screen and at least one grid card is on screen.
     * Once the user taps the search box, the real search UI has neither, so it stays usable.
     */
    private fun findExploreFeedRegion(root: AccessibilityNodeInfo, debugLog: Boolean): Rect? {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val bounds = Rect()

        // Cheap first pass over the shallow tab bar only; most events aren't on the search tab.
        val tabQueue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        tabQueue.add(root to 0)
        var tabBarTop = -1
        while (tabQueue.isNotEmpty()) {
            val (node, depth) = tabQueue.removeFirst()
            val resId = safe { node.viewIdResourceName }
            val desc = safe { node.contentDescription?.toString() }?.trim()
            val isSearchTab = resId?.endsWith(IG_SEARCH_TAB_ID_SUFFIX) == true ||
                (desc != null && IG_SEARCH_TAB_LABELS.any { desc == it })
            if (isSearchTab && safe { node.isSelected } == true) {
                safe { node.getBoundsInScreen(bounds) }
                if (bounds.top > screenHeight / 2) {
                    tabBarTop = bounds.top
                    break
                }
            }
            if (depth < TAB_BAR_MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    safe { node.getChild(i) }?.let { tabQueue.add(it to depth + 1) }
                }
            }
        }
        if (tabBarTop < 0) return null

        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        var searchBoxBottom = -1
        var actionBarBottom = -1
        var gridCards = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++
            val resId = safe { node.viewIdResourceName }
            val desc = safe { node.contentDescription?.toString() }?.trim()
            val onScreen = {
                safe { node.isVisibleToUser } == true && safe { node.getBoundsInScreen(bounds) } != null &&
                    bounds.width() > 0 && bounds.height() > 0 && bounds.top >= 0 && bounds.top < screenHeight
            }
            if (resId?.endsWith(IG_EXPLORE_ACTION_BAR_ID_SUFFIX) == true && onScreen()) {
                actionBarBottom = bounds.bottom
            }
            if (safe { node.className?.toString() } == "android.widget.Button") {
                val text = safe { node.text?.toString() }?.trim()
                val isSearchBox = resId?.endsWith(IG_SEARCH_BOX_ID_SUFFIX) == true ||
                    IG_SEARCH_HINT_LABELS.any { it == desc || it == text }
                if (isSearchBox && onScreen() && bounds.top < screenHeight / 3) searchBoxBottom = bounds.bottom
            }
            if (desc != null && IG_GRID_POSITION_REGEX.containsMatchIn(desc) && onScreen()) gridCards++
            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    safe { node.getChild(i) }?.let { queue.add(it to depth + 1) }
                }
            }
        }
        val top = maxOf(searchBoxBottom, actionBarBottom)
        val ok = searchBoxBottom >= 0 && gridCards > 0 && tabBarTop > top
        if (debugLog) {
            Log.d(TAG, "IG explore: searchBoxBottom=$searchBoxBottom actionBarBottom=$actionBarBottom " +
                "gridCards=$gridCards tabBarTop=$tabBarTop visited=$visited -> cover=$ok")
        }
        return if (ok) Rect(0, top, screenWidth, tabBarTop) else null
    }

    // ---- Partial overlay (Instagram Explore grid / YouTube Home feed) ----

    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())

    private var overlayMessage: TextView? = null

    /**
     * What the single overlay view is currently covering (null = hidden). Each branch only
     * hides its own kind, so e.g. an Instagram event never removes a YouTube cover and vice
     * versa; showing a different kind replaces the view; the watchdog drops it once the
     * owning app leaves the foreground.
     */
    private var overlayKind: OverlayKind? = null

    /** Covered screen region for partial kinds (null = full screen). */
    private var overlayRegion: Rect? = null

    private enum class OverlayKind(val owner: String) {
        /** Instagram search tab landing: the Explore grid area only. */
        IG_EXPLORE(INSTAGRAM_PACKAGE),
        /** YouTube browse screen with the 홈 chip selected: the feed area only. */
        YT_HOME(YOUTUBE_PACKAGE),
    }

    private fun overlayMessageFor(kind: OverlayKind): String = when (kind) {
        OverlayKind.IG_EXPLORE -> "탐색 피드는 가려 두었어요.\n위 검색창으로 찾아보세요."
        OverlayKind.YT_HOME -> "유튜브 홈 피드는 가려 두었어요.\n구독 화면으로 이동할게요."
    }

    /**
     * Shows [kind] over [region] (screen coordinates; null = full screen). A different kind
     * replaces the current view (contents differ); the same kind just refreshes its text and,
     * if the measured region moved, its window position/size.
     */
    private fun showOverlay(kind: OverlayKind, reason: String, region: Rect? = null) {
        if (overlayView != null && overlayKind != kind) removeOverlayView("replaced by $kind")
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val current = overlayView
        if (current != null) {
            overlayMessage?.text = overlayMessageFor(kind)
            if (region != overlayRegion) {
                try {
                    wm.updateViewLayout(current, overlayParams(region))
                    overlayRegion = region?.let { Rect(it) }
                } catch (e: Exception) {
                    Log.d(TAG, "overlay update failed: ${e.message}")
                }
            }
            return
        }

        val dp = resources.displayMetrics.density
        val message = TextView(this).apply {
            text = overlayMessageFor(kind)
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, (12 * dp).toInt(), 0, (24 * dp).toInt())
        }
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(18, 18, 18))
            addView(message)
        }

        try {
            wm.addView(view, overlayParams(region))
            overlayView = view
            overlayMessage = message
            overlayKind = kind
            overlayRegion = region?.let { Rect(it) }
            Log.d(TAG, "OVERLAY show $kind region=$region: $reason")
            handler.postDelayed(overlayWatchdog, OVERLAY_WATCHDOG_MS)
        } catch (e: Exception) {
            Log.d(TAG, "overlay add failed: ${e.message}")
        }
    }

    private fun overlayParams(region: Rect?): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            region?.height() ?: WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }
        if (region != null) {
            // Partial covers are placed with node bounds, which are absolute screen coordinates,
            // so don't let the window be shifted by system-bar insets or the display cutout.
            params.y = region.top
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) params.fitInsetsTypes = 0
        }
        return params
    }

    /** Hides the overlay if it is of [kind]; null hides whatever is shown. */
    private fun hideOverlay(kind: OverlayKind?, reason: String) {
        if (kind != null && kind != overlayKind) return
        removeOverlayView(reason)
    }

    /** Hides the overlay if it covers [owner]'s screen, whatever its kind. */
    private fun hideOverlayOf(owner: String, reason: String) {
        if (overlayKind?.owner == owner) removeOverlayView(reason)
    }

    private fun removeOverlayView(reason: String) {
        val view = overlayView ?: return
        Log.d(TAG, "OVERLAY hide $overlayKind: $reason")
        handler.removeCallbacks(overlayWatchdog)
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) } catch (_: Exception) {}
        overlayView = null
        overlayMessage = null
        overlayKind = null
        overlayRegion = null
    }

    /** Events only arrive for Instagram/YouTube, so poll to drop the overlay once the user leaves the app it covers. */
    private val overlayWatchdog = object : Runnable {
        override fun run() {
            if (overlayView == null) return
            val pkg = safe { rootInActiveWindow?.packageName?.toString() }
            if (pkg != overlayKind?.owner) {
                removeOverlayView("foreground is $pkg")
            } else {
                handler.postDelayed(this, OVERLAY_WATCHDOG_MS)
            }
        }
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
        var shortsPlayer: MatchInfo? = null
        var subscribeButtonDesc: String? = null
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
                // flagReportViewIds was added for the YouTube home limit. Before that every resId
                // was null, so these two id rules never fired on device; keep them dormant until
                // verified so Instagram behaviour doesn't change (e.g. a DM-opened reel may also
                // live in clips_viewer, which would bypass the DM exception below).
                if (INSTAGRAM_ID_RULES_ENABLED && isReelsPlayerResourceId(resId)) {
                    return MatchInfo(resId, desc, className, "player-id")
                }
                if (INSTAGRAM_ID_RULES_ENABLED && selected && isReelsTabResourceId(resId)) {
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
                if (shortsPlayer == null && isShortsPlayerMarker(desc)) {
                    shortsPlayer = MatchInfo(resId, desc, className, "yt-player-marker")
                }
                if (desc?.trim() == "리믹스") sawRemix = true
                if (desc?.trim() == "동영상 공유") sawShortsShare = true
                if (shortsPlayer == null && sawRemix && sawShortsShare) {
                    shortsPlayer = MatchInfo(resId, desc, className, "yt-remix+share-rail")
                }
                // Shorts from subscribed channels are allowed: only an unsubscribed channel's
                // Short shows a "@handle을(를) 구독합니다." subscribe button next to the avatar.
                // It must be really on screen, so a preloaded neighbouring page can't count.
                if (subscribeButtonDesc == null && isShortsSubscribeButton(desc)) {
                    val bounds = Rect()
                    val onScreen = safe { node.isVisibleToUser } == true && safe { node.getBoundsInScreen(bounds) } != null &&
                        bounds.width() > 0 && bounds.height() > 0 && bounds.left >= 0 && bounds.top >= 0 &&
                        bounds.right <= screenWidth && bounds.bottom <= screenHeight
                    if (debugLog) Log.d(TAG, "  ^ shorts subscribe button bounds=$bounds onScreen=$onScreen")
                    if (onScreen) subscribeButtonDesc = desc
                }
                val player = shortsPlayer
                if (player != null && subscribeButtonDesc != null) {
                    return player.copy(reason = "${player.reason}+unsubscribed")
                }
            }

            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    safe { node.getChild(i) }?.let { queue.add(it to depth + 1) }
                }
            }
        }
        if (!isInstagram) {
            // A Short whose subscribe button hasn't loaded yet is let through for now; the
            // events that keep coming while it plays re-check it, so an unsubscribed one is
            // blocked as soon as the button appears and a subscribed one is never blocked.
            if (debugLog && shortsPlayer != null) {
                Log.d(TAG, "Shorts player (${shortsPlayer.reason}) without on-screen subscribe button -> allowed (visited=$visited)")
            }
            return null
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

    private fun isYoutubeChipLabel(desc: String): Boolean = YT_CHIP_LABELS.any { desc.equals(it, ignoreCase = true) }

    private fun isSubscriptionsLabel(desc: String): Boolean = YT_SUBSCRIPTIONS_LABELS.any { desc.equals(it, ignoreCase = true) }

    private fun isShortsSubscribeButton(desc: String?): Boolean {
        if (desc == null) return false
        val trimmed = desc.trim()
        return SHORTS_SUBSCRIBE_SUFFIXES.any { trimmed.endsWith(it) }
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

        private const val OVERLAY_WATCHDOG_MS = 500L

        private const val INSTAGRAM_ID_RULES_ENABLED = false

        // YouTube Home feed hiding (redirect the 홈 chip to the 구독 chip)
        private const val YT_CHIP_ID_SUFFIX = ":id/tabs_bar_text_tab_view"
        // Fallback when view ids are missing: chips sit in the app bar at the top of the screen.
        private const val YT_CHIP_MAX_CENTER_RATIO = 0.25f
        private val YT_SUBSCRIPTIONS_LABELS = listOf("구독")
        // Non-home chips in the top row (홈 itself is matched via HOME_TAB_LABELS); only needed
        // to recognise chips when view ids aren't reported.
        private val YT_CHIP_LABELS = YT_SUBSCRIPTIONS_LABELS + listOf("음악", "라이브", "더보기")
        private const val YT_CHIP_CLICK_INTERVAL_MS = 700L

        // Subscribe button on a Short from an unsubscribed channel: "@majubom을(를) 구독합니다."
        private val SHORTS_SUBSCRIBE_SUFFIXES = listOf("구독합니다.")

        // Instagram search tab: Explore grid cover
        private const val IG_SEARCH_TAB_ID_SUFFIX = ":id/search_tab"
        private val IG_SEARCH_TAB_LABELS = listOf("검색 및 탐색하기")
        private const val IG_EXPLORE_ACTION_BAR_ID_SUFFIX = ":id/explore_action_bar"
        private const val IG_SEARCH_BOX_ID_SUFFIX = ":id/action_bar_search_edit_text"
        private val IG_SEARCH_HINT_LABELS = listOf("검색")
        // Grid cards end with their position, e.g. "…님의 릴스(2행, 3열)".
        private val IG_GRID_POSITION_REGEX = Regex("""\(\d+행, \d+열\)$""")

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
