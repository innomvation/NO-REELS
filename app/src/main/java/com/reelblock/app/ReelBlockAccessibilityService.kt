package com.reelblock.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
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

    // YouTube: true while the user is on the search screen or in a Shorts player opened from it.
    // A Short tapped in search results may play; swiping on to the next one is blocked.
    private var ytFromSearch = false
    private var ytLeftSearchSinceMs = 0L
    // "channel|length" of the Short opened from search; a different one means the user swiped on.
    private var ytSearchShortKey: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName in GUARD_PACKAGES) {
            guardSelf(packageName)
            return
        }
        if (packageName !in TARGET_PACKAGES) return
        // Scrolling the home feed: move the covers along right away, outside the normal throttle.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (packageName == INSTAGRAM_PACKAGE && onHomeFeed) {
                val now = System.currentTimeMillis()
                if (now - lastCoverUpdateAtMs >= FEED_COVER_SCROLL_INTERVAL_MS) {
                    lastCoverUpdateAtMs = now
                    rootInActiveWindow?.let { updateFeedCovers(it, false) }
                }
            }
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val now = System.currentTimeMillis()
        if (now - lastBlockAtMs < BLOCK_COOLDOWN_MS) return
        // Fast scrolling fires content-changed events dozens of times per second; a full
        // tree walk on every single one burns CPU for no benefit, so only process at most
        // a few times per second. Still fast enough to catch a Reels tap almost instantly.
        if (now - lastProcessedAtMs < PROCESS_THROTTLE_MS) {
            // The last event of a screen load can land here and be dropped, leaving e.g. a
            // freshly loaded search results grid uncovered. Re-check once the window passes.
            if (!recheckPending) {
                recheckPending = true
                val delay = PROCESS_THROTTLE_MS - (now - lastProcessedAtMs) + 20
                handler.postDelayed({
                    recheckPending = false
                    process(packageName, System.currentTimeMillis())
                }, delay)
            }
            return
        }
        process(packageName, now)
    }

    private var recheckPending = false

    private fun process(packageName: String, now: Long) {
        if (now - lastBlockAtMs < BLOCK_COOLDOWN_MS) return
        if (now - lastProcessedAtMs < PROCESS_THROTTLE_MS) return
        lastProcessedAtMs = now

        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        val enabledKey = if (packageName == INSTAGRAM_PACKAGE) Prefs.BLOCK_INSTAGRAM else Prefs.BLOCK_YOUTUBE
        if (!prefs.getBoolean(enabledKey, true)) {
            hideOverlayOf(packageName, "app switch off")
            if (packageName == INSTAGRAM_PACKAGE) {
                onHomeFeed = false
                followingFeedOnScreen = false
                hideFeedCovers()
                hideFeedNavButton()
            }
            return
        }

        val debugLog = prefs.getBoolean(Prefs.DEBUG_LOG, false)
        val root = rootInActiveWindow ?: return
        // A delayed re-check may run after the user has already switched apps.
        if (root.packageName?.toString() != packageName) return

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
            val hideOn = prefs.getBoolean(Prefs.IG_EXPLORE_HIDE_ENABLED, true)
            val exploreRegion = if (hideOn) {
                try { findExploreFeedRegion(root, debugLog) } catch (e: Exception) { null }
            } else null
            val resultsRegion = if (hideOn && exploreRegion == null) {
                try { findSearchResultsGridRegion(root, debugLog) } catch (e: Exception) { null }
            } else null
            when {
                exploreRegion != null -> showOverlay(OverlayKind.IG_EXPLORE, "explore grid on screen", exploreRegion)
                resultsRegion != null -> showOverlay(OverlayKind.IG_SEARCH_RESULTS, "search results grid on screen", resultsRegion)
                else -> {
                    hideOverlay(OverlayKind.IG_EXPLORE, "explore grid not on screen")
                    hideOverlay(OverlayKind.IG_SEARCH_RESULTS, "search results grid not on screen")
                }
            }
            updateFeedCovers(root, debugLog)
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

    // ---- Self-protection: keep NO REELS from being switched off or uninstalled while locked ----
    //
    // Watches the system screens that could remove it — the app info page (삭제 / 강제 중지), the
    // accessibility page that turns the service off, the device-admin page that deactivates it,
    // and the uninstall confirmation — and backs out of them while the manager lock is on.

    private var lastGuardAtMs = 0L

    private fun guardSelf(packageName: String) {
        if (!Lock.isLocked(this)) return
        val now = System.currentTimeMillis()
        if (now - lastGuardAtMs < GUARD_INTERVAL_MS) return
        val root = rootInActiveWindow ?: return
        val texts = try { collectTexts(root) } catch (e: Exception) { return }
        if (getSharedPreferences(Prefs.NAME, MODE_PRIVATE).getBoolean(Prefs.DEBUG_LOG, false)) {
            Log.d(TAG, "GUARD scan $packageName root=${root.packageName} texts=${texts.take(25)}")
        }
        if (texts.none { it.contains(APP_LABEL) }) return
        val danger = when (packageName) {
            ACCESSIBILITY_SETTINGS_PACKAGE -> GUARD_ACCESSIBILITY_WORDS
            PACKAGE_INSTALLER_PACKAGE -> GUARD_UNINSTALL_WORDS
            else -> GUARD_SETTINGS_WORDS
        }
        val hit = texts.firstOrNull { t -> danger.any { t.contains(it) } } ?: return
        lastGuardAtMs = now
        Log.d(TAG, "GUARD $packageName: \"$hit\" with $APP_LABEL on screen -> back")
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /** All visible text and content descriptions in the active window (capped). */
    private fun collectTexts(root: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++
            safe { node.text?.toString() }?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            safe { node.contentDescription?.toString() }?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    safe { node.getChild(i) }?.let { queue.add(it to depth + 1) }
                }
            }
        }
        return out
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        hideOverlay(null, "service destroyed")
        hideFeedCovers()
        hideFeedNavButton()
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

        // A channel page has the same tab row (홈 / 동영상 / Shorts …) with its 홈 tab selected on entry,
        // but no 구독 chip — only the browse screen's chip row has one.
        val isHome = homeChipSelected == true && homeChipVisible && subscriptionsChip != null
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

    // ---- Instagram home feed: cover posts from accounts the user doesn't follow ----
    //
    // The feed stays scrollable (the cover window doesn't take touches); the logo bar, the stories
    // tray and posts from followed accounts stay visible. Every post starts with a header row
    // (row_feed_profile_header); a post is "unfollowed" when its header shows the follow button
    // (inline_follow_button, "OOO님 팔로우") or the "회원님을 위한 추천" label, or its media is
    // described as "추천 …"/"광고 …". Each such post is covered from its header to the next header.

    private var feedCoverView: FeedCoverView? = null
    /** True while the last check found the home feed, so scroll events refresh the covers quickly. */
    private var onHomeFeed = false
    private var lastCoverUpdateAtMs = 0L
    /** Status of the first visible post last time; it becomes the top post once its header scrolls off. */
    private var lastFirstPostUnfollowed = false
    /** author -> unfollowed, learned from headers, to judge a post whose header is off screen by its media. */
    private val authorUnfollowed = HashMap<String, Boolean>()

    private fun updateFeedCovers(root: AccessibilityNodeInfo, debugLog: Boolean) {
        val covers = try { findFeedCovers(root, debugLog) } catch (e: Exception) { null }
        onHomeFeed = covers != null
        setFeedCovers(covers ?: emptyList())
        updateFeedNavButton()
    }

    // ---- Instagram: floating button to hop between the home feed (stories) and the 팔로잉 feed ----
    //
    // The 팔로잉 feed (logo -> 팔로잉) lists every followed account's posts in time order but has no
    // stories tray, so this button links the two: "팔로잉 피드 ▶" on home, "◀ 스토리" on 팔로잉.

    private enum class FeedNavMode { HOME, FOLLOWING }

    private var feedNavButton: TextView? = null
    private var feedNavMode: FeedNavMode? = null
    /** Set by [findFeedCovers]: the 팔로잉 feed's title bar is on screen. */
    private var followingFeedOnScreen = false
    /** Set by [findFeedCovers]: top of the bottom tab bar, where the floating button sits above. */
    private var lastTabBarTop = 0

    private fun updateFeedNavButton() {
        val mode = when {
            followingFeedOnScreen -> FeedNavMode.FOLLOWING
            onHomeFeed -> FeedNavMode.HOME
            else -> null
        }
        if (mode == null) { hideFeedNavButton(); return }
        val existing = feedNavButton
        if (existing != null && feedNavMode == mode) return
        hideFeedNavButton()
        val dp = resources.displayMetrics.density
        val button = TextView(this).apply {
            text = if (mode == FeedNavMode.HOME) "팔로잉 피드 ▶" else "◀ 스토리"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding((18 * dp).toInt(), 0, (18 * dp).toInt(), 0)
            background = GradientDrawable().apply {
                cornerRadius = 24 * dp
                setColor(Color.argb(230, 0, 149, 246))
            }
            setOnClickListener { if (mode == FeedNavMode.HOME) openFollowingFeed() else backToStories() }
        }
        val height = (FEED_NAV_BUTTON_HEIGHT_DP * dp).toInt()
        val tabTop = if (lastTabBarTop > 0) lastTabBarTop else resources.displayMetrics.heightPixels
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (16 * dp).toInt()
            y = tabTop - height - (16 * dp).toInt()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) params.fitInsetsTypes = 0
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(button, params)
            feedNavButton = button
            feedNavMode = mode
            handler.removeCallbacks(feedCoverWatchdog)
            handler.postDelayed(feedCoverWatchdog, OVERLAY_WATCHDOG_MS)
        } catch (e: Exception) {
            Log.d(TAG, "feed nav button add failed: ${e.message}")
        }
    }

    private fun hideFeedNavButton() {
        val view = feedNavButton ?: return
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) } catch (_: Exception) {}
        feedNavButton = null
        feedNavMode = null
    }

    /** Home -> 팔로잉: tap the logo (scrolling to the top first if it slid away), then the menu's 팔로잉 item. */
    private fun openFollowingFeed() {
        hideFeedNavButton()
        val root = rootInActiveWindow ?: return
        val logo = findNodeByIdSuffix(root, IG_HOME_LOGO_ID_SUFFIX, null)
        val logoShown = logo?.let { safe { it.isVisibleToUser } } == true
        val delay = if (logoShown) 0L else {
            // Re-tapping 홈 scrolls the feed up, which brings the logo back.
            clickSelfOrAncestor(findNodeByIdSuffix(root, IG_FEED_TAB_ID_SUFFIX, null))
            IG_SCROLL_TOP_DELAY_MS
        }
        handler.postDelayed({
            val r = rootInActiveWindow ?: return@postDelayed
            val logoClicked = clickSelfOrAncestor(findNodeByIdSuffix(r, IG_HOME_LOGO_ID_SUFFIX, null))
            Log.d(TAG, "팔로잉 switch: tap logo result=$logoClicked")
            handler.postDelayed({
                val item = rootInActiveWindow?.let { findNodeByIdSuffix(it, IG_CONTEXT_MENU_ITEM_ID_SUFFIX, IG_FOLLOWING_LABEL) }
                Log.d(TAG, "팔로잉 switch: tap menu item found=${item != null} result=${clickSelfOrAncestor(item)}")
            }, IG_FOLLOWING_MENU_DELAY_MS)
        }, delay)
    }

    /** 팔로잉 -> home: go back, then re-tap 홈 so the feed sits at the top with the stories tray. */
    private fun backToStories() {
        hideFeedNavButton()
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({
            val r = rootInActiveWindow ?: return@postDelayed
            val clicked = clickSelfOrAncestor(findNodeByIdSuffix(r, IG_FEED_TAB_ID_SUFFIX, null))
            Log.d(TAG, "스토리: back + tap 홈 result=$clicked")
        }, IG_SCROLL_TOP_DELAY_MS)
    }

    private fun findNodeByIdSuffix(root: AccessibilityNodeInfo, idSuffix: String, desc: String?): AccessibilityNodeInfo? {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++
            if (safe { node.viewIdResourceName }?.endsWith(idSuffix) == true &&
                (desc == null || safe { node.contentDescription?.toString() }?.trim() == desc)
            ) return node
            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    safe { node.getChild(i) }?.let { queue.add(it to depth + 1) }
                }
            }
        }
        return null
    }

    private fun clickSelfOrAncestor(node: AccessibilityNodeInfo?): Boolean {
        var target = node
        var hops = 0
        while (target != null && safe { target!!.isClickable } != true && hops < 3) {
            target = safe { target!!.parent }
            hops++
        }
        return target?.let { safe { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } } == true
    }

    /** Null when not on the home (For You) feed; otherwise the screen rects to cover (may be empty). */
    private fun findFeedCovers(root: AccessibilityNodeInfo, debugLog: Boolean): List<Rect>? {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        var feedTabSelected = false
        var pushedScreen = false
        var followingTitle = false
        var demarcatorBottom = -1
        var feedOnScreen = false
        var logoBottom = 0
        var trayBottom = 0
        var tabBarTop = screenHeight
        val headerTops = mutableListOf<Int>()
        val unfollowedYs = mutableListOf<Int>()
        val authorYs = mutableListOf<Pair<Int, String>>()   // header author names
        val mediaAuthorYs = mutableListOf<Pair<Int, String>>() // "X님의 사진 …" media descriptions
        val bounds = Rect()

        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++
            val resId = safe { node.viewIdResourceName }
            val desc = safe { node.contentDescription?.toString() }?.trim()
            // Instagram keeps nodes for scrolled-away content in the tree with bogus rects.
            val onScreen = safe { node.isVisibleToUser } == true && safe { node.getBoundsInScreen(bounds) } != null &&
                bounds.width() > 0 && bounds.height() > 0
            // The selected state sits on the 홈 tab itself on some builds, on its tab_icon child on others.
            if (safe { node.isSelected } == true && (resId?.endsWith(IG_FEED_TAB_ID_SUFFIX) == true ||
                    (resId?.endsWith(IG_TAB_ICON_ID_SUFFIX) == true &&
                        safe { node.parent?.viewIdResourceName }?.endsWith(IG_FEED_TAB_ID_SUFFIX) == true))
            ) feedTabSelected = true
            if (onScreen) {
                when {
                    resId == null -> {}
                    resId.endsWith(IG_HOME_LOGO_ID_SUFFIX) -> { logoBottom = maxOf(logoBottom, bounds.bottom); feedOnScreen = true }
                    // A titled action bar means a pushed screen (팔로잉 feed, a post opened from a profile…).
                    resId.endsWith(IG_ACTION_BAR_TITLE_ID_SUFFIX) -> {
                        pushedScreen = true
                        if (desc == IG_FOLLOWING_LABEL) followingTitle = true
                    }
                    resId.endsWith(IG_FEED_DEMARCATOR_ID_SUFFIX) -> { demarcatorBottom = bounds.bottom; feedOnScreen = true }
                    resId.endsWith(IG_POST_HEADER_ID_SUFFIX) -> { headerTops.add(bounds.top); feedOnScreen = true }
                    resId.endsWith(IG_INLINE_FOLLOW_ID_SUFFIX) -> unfollowedYs.add(bounds.centerY())
                    resId.endsWith(IG_SUGGESTED_LABEL_ID_SUFFIX) && desc?.contains("추천") == true -> unfollowedYs.add(bounds.centerY())
                    resId.endsWith(IG_POST_AUTHOR_ID_SUFFIX) && desc != null -> authorYs.add(bounds.centerY() to desc)
                    IG_TAB_ID_SUFFIXES.any { resId.endsWith(it) } && bounds.top > screenHeight / 2 ->
                        tabBarTop = minOf(tabBarTop, bounds.top)
                }
                if (desc == IG_STORIES_TRAY_DESC) { trayBottom = maxOf(trayBottom, bounds.bottom); feedOnScreen = true }
                if (desc != null) {
                    if (IG_SUGGESTED_MEDIA_PREFIXES.any { desc.startsWith(it) }) {
                        unfollowedYs.add(bounds.top + 1)
                    } else if (desc.contains("님의 ")) {
                        mediaAuthorYs.add(bounds.top + 1 to desc.substringBefore("님의 ").trim())
                    }
                }
            }
            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    safe { node.getChild(i) }?.let { queue.add(it to depth + 1) }
                }
            }
        }

        // The 홈 tab's selected state isn't always in the accessibility tree, and other tabs' screens
        // stay in the tree off screen, so "feed content actually on screen" identifies the home feed.
        followingFeedOnScreen = followingTitle
        if (tabBarTop < screenHeight) lastTabBarTop = tabBarTop
        // Full-screen views opened from home (story camera/editor, a story) hide the bottom tab bar;
        // the home feed always shows it. Without this the story camera got judged as "home" and,
        // carrying over the last post's status, was covered entirely.
        val tabBarOnScreen = tabBarTop < screenHeight
        if (!(feedTabSelected || feedOnScreen) || pushedScreen || !tabBarOnScreen) {
            if (debugLog && headerTops.isNotEmpty()) Log.d(TAG, "IG feed covers: skip feedTab=$feedTabSelected feed=$feedOnScreen pushed=$pushedScreen tabBar=$tabBarOnScreen")
            return null
        }
        val limitTop = maxOf(logoBottom, trayBottom)
        val limitBottom = tabBarTop
        val tops = headerTops.distinct().sorted()
        val covers = mutableListOf<Rect>()

        // Segment above the first visible header: the tail of a post whose header scrolled off.
        val firstTop = tops.firstOrNull() ?: limitBottom
        if (firstTop > limitTop) {
            val inTop = { y: Int -> y in limitTop until firstTop }
            val topUnfollowed = when {
                // The "모두 확인했습니다" divider sits right above the first suggestion — not a post.
                demarcatorBottom > limitTop && demarcatorBottom >= firstTop - IG_DEMARCATOR_GAP_PX -> false
                unfollowedYs.any(inTop) -> true
                else -> mediaAuthorYs.firstOrNull { inTop(it.first) }?.let { authorUnfollowed[it.second] }
                    ?: lastFirstPostUnfollowed
            }
            if (topUnfollowed) covers.add(Rect(0, limitTop, screenWidth, firstTop))
        }

        // One segment per visible header, down to the next header (or the tab bar).
        tops.forEachIndexed { i, top ->
            val bottom = tops.getOrNull(i + 1) ?: limitBottom
            val inSeg = { y: Int -> y in top until bottom }
            val unfollowed = unfollowedYs.any(inSeg)
            authorYs.firstOrNull { inSeg(it.first) }?.let { authorUnfollowed[it.second] = unfollowed }
            if (i == 0) lastFirstPostUnfollowed = unfollowed
            val coverTop = maxOf(top, limitTop)
            val coverBottom = minOf(bottom, limitBottom)
            if (unfollowed && coverBottom > coverTop) covers.add(Rect(0, coverTop, screenWidth, coverBottom))
        }

        if (debugLog) {
            Log.d(TAG, "IG feed covers: limit=$limitTop..$limitBottom headers=$tops unfollowedYs=$unfollowedYs " +
                "visited=$visited -> $covers")
        }
        return covers
    }

    private fun setFeedCovers(rects: List<Rect>) {
        if (rects.isEmpty()) {
            hideFeedCovers()
            return
        }
        val view = feedCoverView ?: createFeedCoverView() ?: return
        if (view.rects != rects) {
            view.rects = rects
            view.invalidate()
        }
    }

    private fun createFeedCoverView(): FeedCoverView? {
        val view = FeedCoverView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Touches go straight through so the feed scrolls normally under the covers.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) params.fitInsetsTypes = 0
        return try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, params)
            feedCoverView = view
            // Windows added later draw on top: drop the floating button so updateFeedNavButton()
            // re-adds it above the covers.
            hideFeedNavButton()
            handler.postDelayed(feedCoverWatchdog, OVERLAY_WATCHDOG_MS)
            view
        } catch (e: Exception) {
            Log.d(TAG, "feed cover add failed: ${e.message}")
            null
        }
    }

    private fun hideFeedCovers() {
        val view = feedCoverView ?: return
        if (feedNavButton == null) handler.removeCallbacks(feedCoverWatchdog)
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) } catch (_: Exception) {}
        feedCoverView = null
    }

    /** Events only arrive for Instagram/YouTube, so poll to drop the covers once the user leaves Instagram. */
    private val feedCoverWatchdog = object : Runnable {
        override fun run() {
            if (feedCoverView == null && feedNavButton == null) return
            val pkg = safe { rootInActiveWindow?.packageName?.toString() }
            if (pkg != INSTAGRAM_PACKAGE) {
                onHomeFeed = false
                followingFeedOnScreen = false
                hideFeedCovers()
                hideFeedNavButton()
            } else {
                handler.postDelayed(this, OVERLAY_WATCHDOG_MS)
            }
        }
    }

    /** Full-screen, touch-transparent view that paints the given screen rects. */
    private class FeedCoverView(context: Context) : View(context) {
        var rects: List<Rect> = emptyList()
        private val fill = Paint().apply { color = Color.rgb(18, 18, 18) }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            textAlign = Paint.Align.CENTER
            textSize = 15 * context.resources.displayMetrics.density
        }
        private val location = IntArray(2)

        override fun onDraw(canvas: Canvas) {
            // Rects are absolute screen coordinates; shift by wherever the window actually landed.
            getLocationOnScreen(location)
            canvas.translate(-location[0].toFloat(), -location[1].toFloat())
            for (r in rects) {
                canvas.drawRect(r, fill)
                if (r.height() > label.textSize * 3) {
                    canvas.drawText("추천 게시물 숨김", r.exactCenterX(), r.exactCenterY(), label)
                }
            }
        }
    }

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

    /**
     * Search results screen (after typing a query): a tab row 추천/계정/오디오/태그 sits under the
     * search box, and the 추천 tab is a Reels grid. Non-null (tab row bottom to screen bottom)
     * when that tab row and at least one grid card below it are on screen, so the tabs stay
     * usable and 계정/태그 (lists, no grid cards) are left alone.
     */
    private fun findSearchResultsGridRegion(root: AccessibilityNodeInfo, debugLog: Boolean): Rect? {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val bounds = Rect()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        var tabRowBottom = -1
        val cardTops = ArrayList<Int>()
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++
            val resId = safe { node.viewIdResourceName }
            val desc = safe { node.contentDescription?.toString() }?.trim()
            val onScreen = {
                safe { node.isVisibleToUser } == true && safe { node.getBoundsInScreen(bounds) } != null &&
                    bounds.width() > 0 && bounds.height() > 0 && bounds.top >= 0 && bounds.top < screenHeight
            }
            // Tab row: by id, or by the tab's own label ("추천, 1/4.") when ids aren't reported.
            val isTabRow = resId?.endsWith(IG_SEARCH_RESULTS_TABS_ID_SUFFIX) == true ||
                (desc != null && IG_SEARCH_RESULTS_TAB_REGEX.matches(desc))
            if (isTabRow && onScreen() && bounds.top < screenHeight / 3) {
                tabRowBottom = maxOf(tabRowBottom, bounds.bottom)
            }
            // The ViewPager keeps the 추천 page alive while 계정/태그 is shown, shifted off to the
            // side, so only count cards that are horizontally on screen.
            if (desc != null && IG_GRID_POSITION_REGEX.containsMatchIn(desc) && onScreen() &&
                bounds.left >= 0 && bounds.right <= screenWidth
            ) cardTops += bounds.top
            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    safe { node.getChild(i) }?.let { queue.add(it to depth + 1) }
                }
            }
        }
        val ok = tabRowBottom >= 0 && cardTops.any { it >= tabRowBottom - 4 }
        if (debugLog) {
            Log.d(TAG, "IG search results: tabRowBottom=$tabRowBottom cards=${cardTops.size} visited=$visited -> cover=$ok")
        }
        return if (ok) Rect(0, tabRowBottom, screenWidth, screenHeight) else null
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
        /** Instagram search results 추천 tab: the Reels grid under the tab row. */
        IG_SEARCH_RESULTS(INSTAGRAM_PACKAGE),
        /** YouTube browse screen with the 홈 chip selected: the feed area only. */
        YT_HOME(YOUTUBE_PACKAGE),
    }

    private fun overlayMessageFor(kind: OverlayKind): String = when (kind) {
        OverlayKind.IG_EXPLORE -> "탐색 피드는 가려 두었어요.\n위 검색창으로 찾아보세요."
        OverlayKind.IG_SEARCH_RESULTS -> "추천 릴스는 가려 두었어요.\n위 '계정'이나 '태그' 탭을 눌러 보세요."
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
            // Added mid-transition (e.g. keyboard closing on the search results screen), the
            // window can stay undrawn until the app below redraws. Nudge it a few times.
            for (delay in OVERLAY_REDRAW_DELAYS_MS) {
                handler.postDelayed({
                    if (overlayView === view) {
                        view.requestLayout(); view.invalidate()
                        try { wm.updateViewLayout(view, overlayParams(overlayRegion)) } catch (_: Exception) {}
                    }
                }, delay)
            }
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
        var sawSearchQuery = false
        var sawReelPlayer = false
        var shortChannel: String? = null
        var shortLength: String? = null
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
                // A channel page has its own 홈/동영상/Shorts tab row (id tabs_bar_text_tab_view);
                // its "Shorts" tab is the channel's Shorts list, not the bottom-nav Shorts feed.
                if (selected && depth <= YOUTUBE_TAB_BAR_MAX_DEPTH && className == "android.widget.Button" && isExactShortsLabel(desc) &&
                    resId?.contains("tabs_bar_text_tab_view") != true
                ) {
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
                if (resId?.endsWith(YT_SEARCH_QUERY_ID_SUFFIX) == true) sawSearchQuery = true
                if (resId?.endsWith(YT_REEL_PLAYER_ID_SUFFIX) == true) sawReelPlayer = true
                // Which Short is on screen: its channel ("@handle 채널로 이동") and total length
                // (seek bar "0분 26초 중 0분 4초" -> "0분 26초").
                if (shortChannel == null && desc != null && desc.endsWith(YT_CHANNEL_BUTTON_SUFFIX)) shortChannel = desc
                if (shortLength == null && className == "android.widget.SeekBar" && desc != null && desc.contains(" 중 ")) {
                    shortLength = desc.substringBefore(" 중 ")
                }
            }

            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    safe { node.getChild(i) }?.let { queue.add(it to depth + 1) }
                }
            }
        }
        if (!isInstagram) {
            val inPlayer = sawReelPlayer || shortsPlayer != null
            updateYtSearchContext(sawSearchQuery, inPlayer)
            if (debugLog) Log.d(TAG, "yt pass search=$sawSearchQuery player=$inPlayer fromSearch=$ytFromSearch visited=$visited")
            if (inPlayer && ytFromSearch) {
                // Opened from search results: the tapped Short may play (even from an unsubscribed
                // channel), but swiping on to the next one is blocked.
                val key = if (shortChannel != null && shortLength != null) "$shortChannel|$shortLength" else null
                if (ytSearchShortKey == null) ytSearchShortKey = key
                if (debugLog) Log.d(TAG, "Shorts from search key=$key first=$ytSearchShortKey")
                if (key == null || key == ytSearchShortKey) return null
                // Back lands on the results list, whose events fall inside the block cooldown and
                // are skipped, so forget the first Short here to let the next tapped one play.
                ytSearchShortKey = null
                return MatchInfo(null, key, null, "yt-search-short-swiped")
            }
            val player = shortsPlayer
            if (player != null && subscribeButtonDesc != null) {
                return player.copy(reason = "${player.reason}+unsubscribed")
            }
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

    /**
     * Search screen -> remember it; Shorts player -> keep whatever it was; any other screen for a
     * little while -> forget it. The grace period covers the brief transition frames between the
     * results list and the player, which show neither.
     */
    private fun updateYtSearchContext(onSearchScreen: Boolean, inPlayer: Boolean) {
        val now = System.currentTimeMillis()
        when {
            onSearchScreen -> { ytFromSearch = true; ytLeftSearchSinceMs = 0L; ytSearchShortKey = null }
            inPlayer -> ytLeftSearchSinceMs = 0L
            ytFromSearch -> {
                if (ytLeftSearchSinceMs == 0L) ytLeftSearchSinceMs = now
                else if (now - ytLeftSearchSinceMs > YT_SEARCH_CONTEXT_GRACE_MS) {
                    ytFromSearch = false
                    ytSearchShortKey = null
                }
            }
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

        // Self-protection (see guardSelf). Package names and wording are from a Galaxy (One UI).
        private const val APP_LABEL = "NO REELS"
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val ACCESSIBILITY_SETTINGS_PACKAGE = "com.samsung.accessibility"
        private const val PACKAGE_INSTALLER_PACKAGE = "com.google.android.packageinstaller"
        private val GUARD_PACKAGES = setOf(SETTINGS_PACKAGE, ACCESSIBILITY_SETTINGS_PACKAGE, PACKAGE_INSTALLER_PACKAGE)
        // App info page (삭제 / 강제 중지) and the device-admin deactivate dialog. That dialog's
        // 취소 / 해제 buttons aren't readable, but its body ("이 관리자 앱을 사용 중이며…") is, and
        // the activation dialog says "사용하면" instead.
        // The accessibility pages live in com.android.settings too: the service's own detail page
        // shows its description. Not "사용 안 함" — that's on every row of the installed-services
        // list, and the list itself should stay usable for other services.
        private val GUARD_SETTINGS_WORDS = listOf("강제 중지", "삭제", "제거", "관리자 앱을 사용 중", "비활성화", "릴스 탭/화면을 감지")
        // The service's own detail page shows its description; turning it off asks "…중지할까요?".
        private val GUARD_ACCESSIBILITY_WORDS = listOf("릴스 탭/화면을 감지", "중지", "끄기", "사용 안 함")
        private val GUARD_UNINSTALL_WORDS = listOf("삭제", "제거")
        private const val GUARD_INTERVAL_MS = 400L
        private const val BLOCK_COOLDOWN_MS = 800L
        private const val PROCESS_THROTTLE_MS = 200L
        private const val MAX_NODES = 600

        // Instagram home feed cover
        private const val IG_HOME_LOGO_ID_SUFFIX = ":id/title_logo"
        private const val IG_STORIES_TRAY_DESC = "릴스 트레이 컨테이너"
        // Home feed: cover posts from accounts the user doesn't follow
        private const val IG_FEED_TAB_ID_SUFFIX = ":id/feed_tab"
        private const val IG_TAB_ICON_ID_SUFFIX = ":id/tab_icon"
        private const val IG_ACTION_BAR_TITLE_ID_SUFFIX = ":id/action_bar_title"
        private const val IG_FOLLOWING_LABEL = "팔로잉"
        private const val IG_CONTEXT_MENU_ITEM_ID_SUFFIX = ":id/context_menu_item"
        private const val IG_FOLLOWING_MENU_DELAY_MS = 600L
        private const val IG_SCROLL_TOP_DELAY_MS = 600L
        private const val FEED_NAV_BUTTON_HEIGHT_DP = 44
        private const val IG_FEED_DEMARCATOR_ID_SUFFIX = ":id/end_of_feed_demarcator_container"
        private const val IG_DEMARCATOR_GAP_PX = 8
        private const val IG_POST_HEADER_ID_SUFFIX = ":id/row_feed_profile_header"
        private const val IG_POST_AUTHOR_ID_SUFFIX = ":id/row_feed_photo_profile_name"
        private const val IG_INLINE_FOLLOW_ID_SUFFIX = ":id/inline_follow_button"
        private const val IG_SUGGESTED_LABEL_ID_SUFFIX = ":id/secondary_label"
        private val IG_SUGGESTED_MEDIA_PREFIXES = listOf("추천 ", "광고 ")
        private const val FEED_COVER_SCROLL_INTERVAL_MS = 30L
        private val IG_TAB_ID_SUFFIXES = listOf(":id/feed_tab", ":id/clips_tab", ":id/direct_tab", ":id/search_tab", ":id/profile_tab")
        private const val MAX_DEPTH = 20
        private const val TAB_BAR_MAX_DEPTH = 3
        private const val YOUTUBE_TAB_BAR_MAX_DEPTH = 10
        private const val FULLSCREEN_RATIO_THRESHOLD = 0.5f
        private const val MIN_WIDTH_RATIO = 0.8f

        private const val OVERLAY_WATCHDOG_MS = 500L
        private val OVERLAY_REDRAW_DELAYS_MS = longArrayOf(300L, 800L, 1500L)

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

        // Shorts opened from search results: allowed until the user swipes to the next one
        private const val YT_SEARCH_QUERY_ID_SUFFIX = ":id/search_query"
        private const val YT_REEL_PLAYER_ID_SUFFIX = ":id/reel_recycler"
        private const val YT_CHANNEL_BUTTON_SUFFIX = " 채널로 이동"
        private const val YT_SEARCH_CONTEXT_GRACE_MS = 1500L

        // Instagram search tab: Explore grid cover
        private const val IG_SEARCH_TAB_ID_SUFFIX = ":id/search_tab"
        private val IG_SEARCH_TAB_LABELS = listOf("검색 및 탐색하기")
        private const val IG_EXPLORE_ACTION_BAR_ID_SUFFIX = ":id/explore_action_bar"
        private const val IG_SEARCH_BOX_ID_SUFFIX = ":id/action_bar_search_edit_text"
        private val IG_SEARCH_HINT_LABELS = listOf("검색")
        // Grid cards end with their position, e.g. "…님의 릴스(2행, 3열)".
        // Instagram search results: tab row 추천/계정/오디오/태그 above the 추천 Reels grid
        private const val IG_SEARCH_RESULTS_TABS_ID_SUFFIX = ":id/search_tab_bar_layout"
        private val IG_SEARCH_RESULTS_TAB_REGEX = Regex("""^(추천|계정|오디오|태그), \d+/\d+\.?$""")
        private val IG_GRID_POSITION_REGEX =Regex("""\(\d+행, \d+열\)$""")

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
