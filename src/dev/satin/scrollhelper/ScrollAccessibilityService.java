package dev.satin.scrollhelper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ScrollAccessibilityService extends AccessibilityService implements Choreographer.FrameCallback {
    private WindowManager wm;
    private ScrollBarView scrollBarView;
    private WindowManager.LayoutParams barParams;
    private View topTapView;
    private boolean overlaysAdded = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private float targetFraction = 0f;
    private float displayedFraction = 0f;
    private float targetRatio = 0.10f;
    private float displayedRatio = 0.10f;

    private float targetAlpha = 0f;
    private float displayedAlpha = 0f;

    // Metrics measured from T3 Code's native scrollbar on a Galaxy S25
    // (1080x2340 @ 480dpi, 120Hz screen recording): 12px == 4dp square
    // thumb flush to the right edge, fade starts ~300ms after the
    // content stops and runs ~250ms linearly.
    private static final float NATIVE_WIDTH_DP = 4f;
    private static final float DRAG_WIDTH_DP = 6f;
    private static final float MIN_THUMB_DP = 36f;
    // Infinite feeds have no real length; give them a steady thumb about
    // the size of T3 Code's native one instead of a shrinking sliver.
    private static final float FEED_THUMB_RATIO = 0.075f;
    // Native waits ~300ms, but with a real finger every short pause
    // between swipes then blinks the bar off and on. Hold it for 1s.
    private static final long FADE_DELAY_MS = 1000L;
    private static final float FADE_OUT_SEC = 0.25f;
    private static final float FADE_IN_SEC = 0.05f;

    // Accessibility scroll events arrive about every 100ms. The thumb
    // is drawn RENDER_DELAY behind real time and interpolated between
    // the two events around that moment, so it moves every vsync and
    // never overshoots and waits (which extrapolation did on flings).
    private static final long RENDER_DELAY_NANOS = 120_000_000L;
    // The interpolated trajectory is linear between events ~100ms
    // apart, so its velocity steps at every event. On the recorded
    // flings a centred Gaussian average over +-64ms of that trajectory
    // cut velocity re-accelerations from 193 to 52 and jerk by ~60%
    // (spring smoothers lagged 4-15x more). The render delay above
    // makes the "future" half of the window already known.
    private static final long[] SMOOTH_OFFSETS_NANOS = {-64_000_000L, -32_000_000L, 0L, 32_000_000L, 64_000_000L};
    private static final float[] SMOOTH_WEIGHTS = {0.28f, 0.73f, 1f, 0.73f, 0.28f};
    private final long[] sampleTime = new long[4];
    private final float[] sampleFraction = new float[4];
    private int sampleCount = 0;
    private long pendingShowAtMs = 0L;

    private float targetWidthDp = NATIVE_WIDTH_DP;
    private float displayedWidthDp = NATIVE_WIDTH_DP;

    // Track geometry of the foreground scrollable view, in screen px.
    // -1 means "full screen", used until a scroll event reports bounds.
    private int trackTopPx = -1;
    private int trackHeightPx = -1;

    // Continuous smooth-scroll physics state while dragging thumb
    private boolean isDragging = false;
    private long postDragCooldownUntil = 0L;

    private boolean barTouchable = false;
    private View grabView;
    private WindowManager.LayoutParams grabParams;
    private int lastGrabY = Integer.MIN_VALUE;
    private int barScreenTop = 0;
    private boolean animRunning = false;
    private long lastFrameNanos = 0L;
    private String currentPackage = "";

    private static class ScrollTracker {
        int maxItemCountSeen = 0;
        int lastScrollY = 0;
        int resetCandidateCount = 0;
        float cumulativePixels = 0f;
        float smoothVirtualY = 0f;
        int lastFromIndex = 0;
        boolean hasVirtual = false;
        float virtualPos = 0f;
        final float[] deltas = new float[3];
        int deltaCount = 0;
    }

    // Infinite feeds never report their length. Map position to the
    // track with f = pos / (pos + 10 screens): about 1/10 of the track
    // per screen at the top, slowing gradually as the feed gets deeper.
    // It depends only on the position, so the thumb never jumps, and the
    // top of the feed is still the top.
    private static final float FEED_HALF_SCREENS = 10f;

    private static float feedFraction(float pos, float screenH) {
        pos = Math.max(0f, pos);
        return clamp(pos / (pos + screenH * FEED_HALF_SCREENS), 0f, 0.995f);
    }

    private final Map<String, ScrollTracker> trackers = new HashMap<>();

    private static final Set<String> IGNORED_PACKAGES = new HashSet<>();
    private static final Set<String> SENSITIVE_PACKAGES = new HashSet<>();
    private static final Set<String> NATIVE_SCROLLBAR_PACKAGES = new HashSet<>();
    static {
        IGNORED_PACKAGES.add("com.sec.android.app.launcher");
        IGNORED_PACKAGES.add("com.android.systemui");
        IGNORED_PACKAGES.add("com.samsung.android.honeyboard");

        SENSITIVE_PACKAGES.add("com.nu.production");
        SENSITIVE_PACKAGES.add("br.com.intermedium");
        SENSITIVE_PACKAGES.add("com.itau");
        SENSITIVE_PACKAGES.add("br.gov.caixa.tem");
        SENSITIVE_PACKAGES.add("com.mercadopago.wallet");
        SENSITIVE_PACKAGES.add("com.nomadglobal.app");
        SENSITIVE_PACKAGES.add("br.com.avenue.mobile");
        SENSITIVE_PACKAGES.add("com.binance.dev");

        // Apps and browsers that already render their own right-edge scrollbar
        NATIVE_SCROLLBAR_PACKAGES.add("com.t3tools.t3code");
        NATIVE_SCROLLBAR_PACKAGES.add("com.brave.browser");
        NATIVE_SCROLLBAR_PACKAGES.add("com.android.chrome");
        NATIVE_SCROLLBAR_PACKAGES.add("com.sec.android.app.sbrowser");
        NATIVE_SCROLLBAR_PACKAGES.add("org.mozilla.firefox");
        NATIVE_SCROLLBAR_PACKAGES.add("com.android.settings");
    }

    private final Runnable beginFadeOutRunnable = new Runnable() {
        @Override
        public void run() {
            if (isDragging) return;
            targetAlpha = 0f;
            targetWidthDp = NATIVE_WIDTH_DP;
            ensureAnimating();
        }
    };

    // Dragging the thumb scrolls with the list's own accessibility page
    // actions. Injecting swipes (dispatchGesture) cancels every touch in
    // progress, including the finger holding the thumb, so the content
    // barely moved. Each stretch of thumb travel maps to whole pages of
    // the list, sent one at a time so the app's page animation finishes.
    private static final long DRAG_PAGE_INTERVAL_MS = 220L;
    private static final float MAX_FEED_PAGES = 15f;
    private AccessibilityNodeInfo scrollNode;
    private float pagesPerTrack = 10f;
    private float dragStartFraction = 0f;
    private int dragPagesDone = 0;
    // A page is sent only once the previous one finished animating (no
    // scroll event for PAGE_SETTLE_MS), so slow apps never queue pages.
    private static final long PAGE_SETTLE_MS = 150L;
    private static final long PAGE_MAX_WAIT_MS = 700L;
    private static final long PAGE_POLL_MS = 40L;
    private long lastScrollEventMs = 0L;
    private long lastPageActionMs = 0L;

    private int pendingDragPages() {
        int desired = Math.round((targetFraction - dragStartFraction) * pagesPerTrack);
        return desired - dragPagesDone;
    }

    private final Runnable dragPageStepper = new Runnable() {
        @Override
        public void run() {
            int pending = pendingDragPages();
            long now = SystemClock.uptimeMillis();
            boolean settled = now - lastPageActionMs >= DRAG_PAGE_INTERVAL_MS
                    && (now - lastScrollEventMs >= PAGE_SETTLE_MS
                        || now - lastPageActionMs >= PAGE_MAX_WAIT_MS);
            if (pending != 0 && scrollNode != null && settled) {
                lastPageActionMs = now;
                boolean ok = false;
                try {
                    scrollNode.refresh();
                    ok = scrollNode.performAction(pending > 0
                            ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                            : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                } catch (Exception ignored) {
                }
                // At either end the action fails: stop asking for more.
                dragPagesDone += ok ? Integer.signum(pending) : pending;
            }
            if (isDragging || pendingDragPages() != 0) {
                handler.postDelayed(this, PAGE_POLL_MS);
            }
        }
    };

    // Hot paths (every scroll event, every drawn frame at 120Hz) read
    // these caches instead of re-querying resources and preferences.
    // They refresh on connect, on preference changes, and on
    // configuration changes (rotation, theme, density).
    private final DisplayMetrics cachedMetrics = new DisplayMetrics();
    private float cachedDensity = 3f;
    private boolean cachedDark = true;
    private final Set<String> cachedExcluded = new HashSet<>();
    private boolean prefsListenerRegistered = false;
    private final Rect cachedBounds = new Rect();

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                    refreshCaches();
                }
            };

    private void refreshCaches() {
        try {
            cachedMetrics.setTo(getResources().getDisplayMetrics());
            cachedDensity = cachedMetrics.density;
        } catch (Exception ignored) {
        }
        boolean dark;
        int mode = prefs != null ? prefs.getInt(MainActivity.KEY_THEME_MODE, 0) : 0;
        if (mode == 1) {
            dark = true;
        } else if (mode == 2) {
            dark = false;
        } else {
            int uiMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            dark = uiMode == Configuration.UI_MODE_NIGHT_YES;
        }
        cachedDark = dark;
        cachedExcluded.clear();
        if (prefs != null) {
            String csv = prefs.getString(MainActivity.KEY_EXCLUDED_PKGS, "");
            if (csv != null && !csv.isEmpty()) {
                for (String part : csv.split(",")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty()) cachedExcluded.add(trimmed);
                }
            }
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        refreshCaches();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        refreshCaches();
        if (!prefsListenerRegistered) {
            prefs.registerOnSharedPreferenceChangeListener(prefsListener);
            prefsListenerRegistered = true;
        }
        ensureOverlaysAdded();
    }

    private boolean isScrollbarEnabled() {
        return prefs == null || prefs.getBoolean(MainActivity.KEY_ENABLE_SCROLLBAR, true);
    }

    private boolean shouldSkipNativeScrollbars() {
        return prefs == null || prefs.getBoolean(MainActivity.KEY_ONLY_MISSING_SCROLLBAR, true);
    }

    private boolean isDragEnabled() {
        return prefs == null || prefs.getBoolean(MainActivity.KEY_ENABLE_DRAG, true);
    }

    private boolean isTopTapEnabled() {
        return prefs == null || prefs.getBoolean(MainActivity.KEY_ENABLE_TOP_TAP, true);
    }

    private boolean shouldHideInBanks() {
        return prefs == null || prefs.getBoolean(MainActivity.KEY_HIDE_IN_BANKS, true);
    }

    private boolean isUserExcludedPackage(String pkg) {
        return pkg != null && !pkg.isEmpty() && cachedExcluded.contains(pkg);
    }

    private boolean isDarkThemeActive() {
        return cachedDark;
    }

    private float minThumbPx(float trackH) {
        return Math.min(trackH, MIN_THUMB_DP * cachedDensity);
    }

    private float thumbHeightPx(float trackH, float ratio) {
        return Math.max(minThumbPx(trackH), trackH * ratio);
    }

    private float minRatioFor(float trackH) {
        if (trackH <= 0f) return 0f;
        return minThumbPx(trackH) / trackH;
    }

    // Native scrollbars span the scrollable view itself, not a fixed
    // fraction of the screen, so mirror the source view's bounds.
    // The overlay window stays full height and the track is drawn
    // inside it: relayouting the window mid-scroll makes it flicker.
    // Bounds are only adopted while the bar is hidden so the track
    // never jumps under a visible thumb.
    private void updateTrackBounds(Rect bounds) {
        if (bounds == null || displayedAlpha > 0.05f || isDragging) return;
        int h = bounds.height();
        int w = bounds.width();
        if (h < 200 * cachedDensity || w < cachedMetrics.widthPixels * 0.5f) return;
        trackTopPx = Math.max(0, bounds.top);
        trackHeightPx = Math.min(h, cachedMetrics.heightPixels - trackTopPx);
    }

    private void resetTrackBounds() {
        trackTopPx = -1;
        trackHeightPx = -1;
    }

    private float trackTop(int viewH) {
        return trackHeightPx > 0 ? Math.min(trackTopPx, viewH) : 0f;
    }

    private float trackHeight(int viewH) {
        return trackHeightPx > 0 ? Math.max(1f, Math.min(trackHeightPx, viewH - trackTop(viewH))) : viewH;
    }

    private void updateTrackFromEvent(AccessibilityEvent event) {
        // Bounds are only adopted while hidden, so skip the (relatively
        // expensive) source lookup for most events of a visible scroll.
        if (displayedAlpha > 0.05f || isDragging) return;
        AccessibilityNodeInfo source = null;
        try {
            source = event.getSource();
            if (source == null) return;
            source.getBoundsInScreen(cachedBounds);
            updateTrackBounds(cachedBounds);
        } catch (Exception ignored) {
        } finally {
            if (source != null) {
                try {
                    source.recycle();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // Only the small grab window takes touches; the drawing window never
    // does, so taps next to the thumb (menus, buttons) reach the app.
    private void setBarTouchable(boolean touchable) {
        if (wm == null || grabView == null || grabParams == null || !overlaysAdded) return;
        if (this.barTouchable == touchable) return;
        this.barTouchable = touchable;
        if (touchable) {
            grabParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            grabParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        lastGrabY = Integer.MIN_VALUE;
        positionGrabWindow();
        try {
            wm.updateViewLayout(grabView, grabParams);
        } catch (Exception ignored) {
        }
        updateGrabExclusion();
    }

    // Thumb geometry in drawing-window coordinates.
    private float thumbTopLocal() {
        int viewH = scrollBarView != null ? scrollBarView.getHeight() : 0;
        float h = trackHeight(viewH);
        float thumbH = thumbHeightPx(h, displayedRatio);
        return trackTop(viewH) + Math.max(1f, h - thumbH) * clamp(displayedFraction, 0f, 1f);
    }

    private void positionGrabWindow() {
        if (wm == null || grabView == null || grabParams == null || !overlaysAdded || isDragging) return;
        if (scrollBarView == null || scrollBarView.getHeight() <= 0) return;
        float density = cachedDensity;
        int viewH = scrollBarView.getHeight();
        float thumbH = thumbHeightPx(trackHeight(viewH), displayedRatio);
        float slop = 52f * density;
        int y = (int) (thumbTopLocal() - slop);
        int h = (int) (thumbH + 2 * slop);
        // The grab window is thumb + 52dp slop on each side, so its
        // position only needs ~16dp accuracy. Coarser steps mean far
        // fewer WindowManager relayouts while scrolling at 120Hz.
        float step = 16f * density;
        if (Math.abs(y - lastGrabY) < step && Math.abs(h - grabParams.height) < step) return;
        lastGrabY = y;
        grabParams.y = y;
        grabParams.height = h;
        try {
            wm.updateViewLayout(grabView, grabParams);
        } catch (Exception ignored) {
        }
    }

    // With gesture navigation the right edge belongs to the back
    // gesture, which steals drags on the thumb. Claim the grab area
    // while it can be dragged.
    private void updateGrabExclusion() {
        if (grabView == null) return;
        java.util.List<Rect> rects = new java.util.ArrayList<>();
        if (barTouchable) rects.add(new Rect(0, 0, grabParams.width, grabParams.height));
        grabView.setSystemGestureExclusionRects(rects);
    }

    private void ensureOverlaysAdded() {
        if (overlaysAdded || wm == null || !Settings.canDrawOverlays(this)) {
            return;
        }
        setupOverlays();
    }

    @SuppressWarnings("deprecation")
    private void setupOverlays() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        final float density = dm.density;

        int barW = (int) (30 * density);
        // MATCH_PARENT follows rotation; the track is drawn inside.
        int barH = WindowManager.LayoutParams.MATCH_PARENT;
        scrollBarView = new ScrollBarView(this, density);
        scrollBarView.setHapticFeedbackEnabled(true);

        barParams = new WindowManager.LayoutParams(
                barW,
                barH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
        );
        barParams.gravity = Gravity.END | Gravity.TOP;
        barParams.y = 0;

        grabView = new View(this);
        grabView.setBackgroundColor(Color.TRANSPARENT);
        grabView.setHapticFeedbackEnabled(true);
        grabParams = new WindowManager.LayoutParams(
                barW,
                (int) (120 * density),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
        );
        grabParams.gravity = Gravity.END | Gravity.TOP;
        grabParams.y = 0;

        grabView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (!isScrollbarEnabled() || !isDragEnabled() || scrollBarView == null) return false;
                int viewH = scrollBarView.getHeight();
                if (viewH <= 0) return false;
                // Raw coordinates: the grab window itself may sit anywhere.
                float trackY = barScreenTop + trackTop(viewH);
                float h = trackHeight(viewH);

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        isDragging = true;
                        clearSamples();
                        targetAlpha = 1.0f;
                        targetWidthDp = DRAG_WIDTH_DP;
                        handler.removeCallbacks(beginFadeOutRunnable);
                        dragStartFraction = displayedFraction;
                        dragPagesDone = 0;
                        lastPageActionMs = 0L;
                        handler.removeCallbacks(dragPageStepper);
                        scrubActive = scrubEnabled();
                        if (scrubActive) {
                            scrubInjected = 0f;
                            scrubDone = 0f;
                            scrubWanted = 0f;
                            vInFlight = false;
                            vListBounds = null;
                            try {
                                if (scrollNode != null && scrollNode.refresh()) {
                                    vListBounds = new Rect();
                                    scrollNode.getBoundsInScreen(vListBounds);
                                }
                            } catch (Exception ignored) {
                            }
                            handler.removeCallbacks(scrubPump);
                            handler.post(scrubPump);
                        } else {
                            handler.post(dragPageStepper);
                        }
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        ensureAnimating();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        if (!isDragging) return false;
                        float y = clamp(event.getRawY() - trackY, 0f, h);

                        float tH = thumbHeightPx(h, displayedRatio);
                        float avH = Math.max(1f, h - tH);
                        float fingerFraction = clamp((y - tH * 0.5f) / avH, 0f, 1f);
                        if (scrubActive) {
                            // The thumb follows the content the injected
                            // finger has really moved, never the real finger.
                            scrubWanted = (fingerFraction - dragStartFraction) * scrubPxPerTrack();
                        } else {
                            targetFraction = fingerFraction;
                            displayedFraction = targetFraction;
                        }
                        ensureAnimating();
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!isDragging) return false;
                        isDragging = false;
                        if (scrubActive) {
                            scrubActive = false;
                            // Stop where the content is; the thumb already is.
                            scrubWanted = scrubInjected;
                            liftVirtualFinger(true);
                        }
                        // Content stops with the finger: drop pages that did
                        // not fire yet instead of scrolling on for seconds.
                        dragPagesDone += pendingDragPages();
                        postDragCooldownUntil = System.currentTimeMillis() + 450L;
                        targetWidthDp = NATIVE_WIDTH_DP;
                        handler.removeCallbacks(beginFadeOutRunnable);
                        handler.postDelayed(beginFadeOutRunnable, FADE_DELAY_MS);
                        positionGrabWindow();
                        ensureAnimating();
                        return true;
                }
                return false;
            }
        });

        // Top status bar / camera cutout tap target for iOS-style Scroll-to-Top
        topTapView = new View(this);
        topTapView.setBackgroundColor(Color.TRANSPARENT);

        WindowManager.LayoutParams topParams = new WindowManager.LayoutParams(
                (int) (90 * density),
                (int) (36 * density),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
        );
        topParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        topParams.y = 0;

        topTapView.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private long downTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        downTime = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_UP:
                        float dx = Math.abs(event.getRawX() - downX);
                        float dy = event.getRawY() - downY;
                        long dt = System.currentTimeMillis() - downTime;
                        if (dy > 25 * density) {
                            performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
                        } else if (isTopTapEnabled() && dx < 20 * density && dt < 500) {
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                            scrollToTopBurst();
                        }
                        return true;
                }
                return false;
            }
        });

        try {
            wm.addView(scrollBarView, barParams);
            wm.addView(grabView, grabParams);
            wm.addView(topTapView, topParams);
            overlaysAdded = true;
        } catch (Exception ignored) {
            overlaysAdded = false;
        }
    }

    private void ensureAnimating() {
        if (!animRunning) {
            animRunning = true;
            lastFrameNanos = System.nanoTime();
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (scrollBarView == null) {
            animRunning = false;
            return;
        }

        float dt = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = frameTimeNanos;
        if (dt <= 0f || dt > 0.1f) dt = 0.0083f;

        long renderTime = frameTimeNanos - RENDER_DELAY_NANOS;
        boolean interpolating = !isDragging && sampleCount > 0
                && renderTime < sampleTime[sampleCount - 1];
        float predicted;
        if (isDragging) {
            predicted = targetFraction;
        } else if (sampleCount == 0) {
            predicted = sampleAt(renderTime);
        } else {
            float acc = 0f;
            float wSum = 0f;
            for (int i = 0; i < SMOOTH_OFFSETS_NANOS.length; i++) {
                acc += SMOOTH_WEIGHTS[i] * sampleAt(renderTime + SMOOTH_OFFSETS_NANOS[i]);
                wSum += SMOOTH_WEIGHTS[i];
            }
            predicted = acc / wSum;
        }

        float ratioLerp = 1f - (float) Math.exp(-8f * dt);
        float widthLerp = 1f - (float) Math.exp(-20f * dt);

        if (displayedAlpha > 0.01f)        // The smoothed trajectory is already continuous; following it
        // with another exponential lerp only added lag and jerk.
        displayedFraction = isDragging
                ? displayedFraction + (predicted - displayedFraction) * 0.92f
                : predicted;
        displayedRatio += (targetRatio - displayedRatio) * ratioLerp;
        displayedWidthDp += (targetWidthDp - displayedWidthDp) * widthLerp;
        // Native scrollbars fade linearly, not exponentially.
        if (targetAlpha > displayedAlpha) {
            displayedAlpha = Math.min(targetAlpha, displayedAlpha + dt / FADE_IN_SEC);
        } else if (targetAlpha < displayedAlpha) {
            displayedAlpha = Math.max(targetAlpha, displayedAlpha - dt / FADE_OUT_SEC);
        }

        boolean stillMoving = interpolating
                || Math.abs(predicted - displayedFraction) > 0.0005f
                || Math.abs(targetRatio - displayedRatio) > 0.0005f
                || Math.abs(targetWidthDp - displayedWidthDp) > 0.02f
                || Math.abs(targetAlpha - displayedAlpha) > 0.005f;

        if (!stillMoving) {
            displayedFraction = predicted;
            displayedRatio = targetRatio;
            displayedWidthDp = targetWidthDp;
            displayedAlpha = targetAlpha;
        }

        if (displayedAlpha <= 0.01f && targetAlpha == 0f && !isDragging && barTouchable) {
            setBarTouchable(false);
        }

        scrollBarView.invalidate();
        if (barTouchable) positionGrabWindow();

        if (stillMoving) {
            Choreographer.getInstance().postFrameCallback(this);
        } else {
            animRunning = false;
        }
    }

    private void hideScrollbarImmediate() {
        targetAlpha = 0f;
        displayedAlpha = 0f;
        setBarTouchable(false);
        if (scrollBarView != null) scrollBarView.invalidate();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (!overlaysAdded) {
            ensureOverlaysAdded();
            if (!overlaysAdded) return;
        }

        CharSequence pkgCs = event.getPackageName();
        String pkg = pkgCs != null ? pkgCs.toString() : "";

        boolean isSelfApp = pkg.equals(getPackageName());
        boolean blockedPkg = IGNORED_PACKAGES.contains(pkg)
                || (shouldHideInBanks() && SENSITIVE_PACKAGES.contains(pkg))
                || isUserExcludedPackage(pkg)
                || (!isSelfApp && shouldSkipNativeScrollbars() && NATIVE_SCROLLBAR_PACKAGES.contains(pkg));

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (!pkg.isEmpty() && !pkg.equals(currentPackage)) {
                currentPackage = pkg;
                if (!isSelfApp && !IGNORED_PACKAGES.contains(pkg) && !SENSITIVE_PACKAGES.contains(pkg) && prefs != null) {
                    prefs.edit().putString(MainActivity.KEY_LAST_FOREGROUND_PKG, pkg).apply();
                }
                resetTrackBounds();
                if (blockedPkg) {
                    hideScrollbarImmediate();
                }
            }
            return;
        }

        if (event.getEventType() != AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            return;
        }
        lastScrollEventMs = SystemClock.uptimeMillis();
        if (blockedPkg || !isScrollbarEnabled()) return;

        updateTrackFromEvent(event);

        CharSequence clsCs = event.getClassName();
        String cls = clsCs != null ? clsCs.toString() : "";
        if (cls.contains("ViewPager") || cls.contains("HorizontalScrollView") || cls.contains("TabLayout")) {
            return;
        }

        // WebViews and standard ScrollViews in third-party apps already draw their own native scrollbar
        if (!isSelfApp && shouldSkipNativeScrollbars()) {
            if (cls.contains("WebView") || cls.contains("ScrollView")) {
                return;
            }
        }

        int dx = event.getScrollDeltaX();
        int dy = event.getScrollDeltaY();
        if (Math.abs(dx) > Math.abs(dy) && dy == 0) {
            return;
        }

        int fromIndex = event.getFromIndex();
        int toIndex = event.getToIndex();
        int itemCount = event.getItemCount();
        int scrollY = event.getScrollY();
        int maxScrollY = event.getMaxScrollY();

        if (itemCount > 0 && itemCount <= 5 && maxScrollY <= 0 && dy == 0) {
            return;
        }
        // Compose views that do not scroll (X sends a burst of these when
        // a screen opens) report dy=-1 with no position. Treating them as
        // a scroll showed the thumb at the top before the real position.
        if (dy == -1 && scrollY <= 0 && maxScrollY <= 0 && itemCount < 0) {
            return;
        }

        DisplayMetrics dm = cachedMetrics;
        ScrollTracker tracker = trackers.get(pkg);
        if (tracker == null) {
            tracker = new ScrollTracker();
            trackers.put(pkg, tracker);
        }

        boolean inPostDragCooldown = System.currentTimeMillis() < postDragCooldownUntil;
        boolean ignorePositionUpdate = isDragging || inPostDragCooldown;
        boolean updated = false;

        if (maxScrollY > 0 && scrollY >= 0) {
            int gap = maxScrollY - scrollY;
            boolean isComposeSyntheticMax = (gap >= 50 && gap <= 250 && fromIndex < 0 && itemCount < 0);

            // Standard pixel-scrolled containers with a real maxScrollY (not Compose synthetic max)
            // in third-party apps already render their own scrollbar
            if (!isSelfApp && shouldSkipNativeScrollbars() && !isComposeSyntheticMax) {
                return;
            }

            if (isComposeSyntheticMax && tracker.lastScrollY > dm.heightPixels * 1.2f && scrollY < dm.heightPixels * 0.45f) {
                if (tracker.lastScrollY - scrollY > dm.heightPixels * 0.9f) {
                    tracker.resetCandidateCount++;
                    if (tracker.resetCandidateCount <= 2) {
                        return;
                    } else {
                        tracker.resetCandidateCount = 0;
                    }
                }
            } else {
                tracker.resetCandidateCount = 0;
            }

            int rawDelta = scrollY - tracker.lastScrollY;
            tracker.lastScrollY = scrollY;

            // Compose derives scrollY from the first visible item, so a
            // fast fling reports deltas like 1151, 389, 352, 255, 1359,
            // -182, 706 while the content moves smoothly. Advance by the
            // median of the last three deltas, which drops any single
            // spike and still follows a real reversal within one event,
            // and drift back toward the raw value only in the direction
            // of motion so the thumb never steps backwards.
            float position = scrollY;
            int pixelDir = Integer.signum(rawDelta);
            boolean trustedDir = false;
            if (isComposeSyntheticMax) {
                boolean visible = displayedAlpha > 0.01f;
                if (!tracker.hasVirtual || scrollY <= 0 || Math.abs(rawDelta) > dm.heightPixels * 2) {
                    tracker.virtualPos = scrollY;
                    tracker.deltaCount = 0;
                    tracker.hasVirtual = true;
                } else {
                    if (!visible) {
                        // Hidden: re-sync to the reported position. Drift
                        // from earlier gestures goes away unseen.
                        tracker.deltaCount = 0;
                        tracker.virtualPos = tracker.lastScrollY - rawDelta;
                    }
                    tracker.deltas[tracker.deltaCount % 3] = rawDelta;
                    tracker.deltaCount++;
                    // Until a median is possible, follow the raw delta
                    // capped at one screen, then the mean of two.
                    float d;
                    float cap = dm.heightPixels;
                    if (tracker.deltaCount >= 3) {
                        d = median3(tracker.deltas[0], tracker.deltas[1], tracker.deltas[2]);
                        // A delta against the motion is either a spike or
                        // the start of a reversal. Do not keep moving the
                        // old way; barely move until the next event says
                        // which one it was.
                        if (rawDelta != 0 && d != 0f && Math.signum(rawDelta) != Math.signum(d)) {
                            d *= 0.25f;
                        }
                        // Decelerating: the median still remembers the
                        // faster events before it. Lean on the newest
                        // delta when it is smaller, so the thumb slows
                        // down together with the content.
                        else if (d > 0f && rawDelta < d) {
                            d = 0.5f * (d + Math.max(rawDelta, 0f));
                        } else if (d < 0f && rawDelta > d) {
                            d = 0.5f * (d + Math.min(rawDelta, 0f));
                        }
                    } else if (tracker.deltaCount == 2) {
                        float a0 = tracker.deltas[0], a1 = tracker.deltas[1];
                        d = Math.signum(a0) != Math.signum(a1) ? 0f : clamp((a0 + a1) * 0.5f, -cap, cap);
                    } else {
                        d = clamp(rawDelta, -cap, cap);
                    }
                    float err = scrollY - (tracker.virtualPos + d);
                    if (d != 0f && Math.signum(err) == Math.signum(d)) {
                        d += err * 0.15f;
                    }
                    tracker.virtualPos = Math.max(0f, tracker.virtualPos + d);
                    // The reported scrollY is noisy but never drifts: keep
                    // the estimate close to it, and especially never far
                    // ahead of it in the direction of motion.
                    if (d != 0f) {
                        float sign = Math.signum(d);
                        float lead = (tracker.virtualPos - scrollY) * sign;
                        float maxLead = dm.heightPixels * 0.11f;
                        float maxLag = dm.heightPixels * 0.26f;
                        if (lead > maxLead) {
                            tracker.virtualPos = scrollY + sign * maxLead;
                        } else if (lead < -maxLag) {
                            tracker.virtualPos = scrollY - sign * maxLag;
                        }
                    }
                    pixelDir = (int) Math.signum(d);
                    trustedDir = tracker.deltaCount >= 3;
                }
                position = tracker.virtualPos;
            }


            float effectiveMax;
            float fraction;
            if (isComposeSyntheticMax) {
                fraction = feedFraction(position, dm.heightPixels);
                effectiveMax = dm.heightPixels * FEED_HALF_SCREENS * 2f;
            } else {
                effectiveMax = Math.max(1f, (float) maxScrollY);
                fraction = clamp(position / effectiveMax, 0f, 1f);
            }

            if (!ignorePositionUpdate) {
                setTargetFraction(fraction, pixelDir, trustedDir);
            }
            // Native: thumb = extent / (extent + range), extent is the
            // scrollable view's height, range is maxScrollY.
            float extent = trackHeightPx > 0 ? trackHeightPx : dm.heightPixels;
            // Infinite feeds would map the track to dozens of pages; cap
            // them so a thumb drag stays proportionate.
            pagesPerTrack = isComposeSyntheticMax
                    ? clamp(effectiveMax / extent, 1f, MAX_FEED_PAGES)
                    : Math.max(1f, effectiveMax / extent);
            scrubMode = isComposeSyntheticMax ? 1 : 0;
            scrubRangePx = effectiveMax;
            if (isComposeSyntheticMax) {
                targetRatio = Math.max(FEED_THUMB_RATIO, minRatioFor(extent));
            } else {
                targetRatio = clamp(extent / (extent + effectiveMax), minRatioFor(extent), 1f);
            }
            updated = true;
        } else if (itemCount > 1 && fromIndex >= 0) {
            int visibleCount = (toIndex >= fromIndex) ? Math.max(1, toIndex - fromIndex + 1) : 4;
            int itemDir = dy != 0 ? Integer.signum(dy) : Integer.signum(fromIndex - tracker.lastFromIndex);
            tracker.lastFromIndex = fromIndex;

            if (fromIndex == 0 && itemCount < tracker.maxItemCountSeen * 0.6f) {
                tracker.maxItemCountSeen = itemCount;
                tracker.smoothVirtualY = 0f;
            }
            if (itemCount > tracker.maxItemCountSeen) {
                tracker.maxItemCountSeen = itemCount;
            }

            if (dy != 0) {
                float itemStep = dy / (dm.heightPixels * 0.28f);
                tracker.smoothVirtualY = Math.max(0f, tracker.smoothVirtualY + itemStep);
                tracker.smoothVirtualY = tracker.smoothVirtualY * 0.75f + fromIndex * 0.25f;
            } else {
                tracker.smoothVirtualY = fromIndex;
            }

            if (fromIndex == 0 && dy <= 0) {
                tracker.smoothVirtualY = 0f;
            }

            int effectiveTotal = Math.max(itemCount, tracker.maxItemCountSeen);
            float denom = Math.max(1f, effectiveTotal - visibleCount);
            if (tracker.smoothVirtualY > denom) {
                denom = tracker.smoothVirtualY;
            }

            if (!ignorePositionUpdate) {
                setTargetFraction(clamp(tracker.smoothVirtualY / denom, 0f, 1f), itemDir, false);
            }
            float extentItems = trackHeightPx > 0 ? trackHeightPx : dm.heightPixels;
            targetRatio = clamp((float) visibleCount / (float) Math.max(1, effectiveTotal), minRatioFor(extentItems), 1f);
            pagesPerTrack = Math.max(1f, (float) (effectiveTotal - visibleCount) / visibleCount);
            scrubMode = 0;
            scrubRangePx = Math.max(1f, (effectiveTotal - visibleCount) * (extentItems / visibleCount));
            updated = true;
        } else if (dy != 0) {
            tracker.cumulativePixels = Math.max(0f, tracker.cumulativePixels + dy);
            float fractionDy = feedFraction(tracker.cumulativePixels, dm.heightPixels);
            float effectiveMax = dm.heightPixels * FEED_HALF_SCREENS * 2f;
            if (!ignorePositionUpdate) {
                setTargetFraction(fractionDy, Integer.signum(dy), false);
            }
            float extentDy = trackHeightPx > 0 ? trackHeightPx : dm.heightPixels;
            targetRatio = Math.max(FEED_THUMB_RATIO, minRatioFor(extentDy));
            pagesPerTrack = clamp(effectiveMax / extentDy, 1f, MAX_FEED_PAGES);
            scrubMode = 1;
            updated = true;
        }

        if (updated && !isDragging) {
            // Remember the list so dragging the thumb can page it.
            AccessibilityNodeInfo src = event.getSource();
            if (src != null) {
                if (scrollNode != null) {
                    try {
                        scrollNode.recycle();
                    } catch (Exception ignored) {
                    }
                }
                scrollNode = src;
            }
        }

        if (updated && scrollBarView != null && !isDragging) {
            if (displayedAlpha <= 0.01f && targetAlpha == 0f) {
                // Apps fire lone scroll events when they re-measure or
                // load content (X recomputes scrollY after a page loads).
                // A real scroll sends another event within ~100ms, so
                // wait for it instead of flashing the bar.
                long nowMs = System.currentTimeMillis();
                if (nowMs - pendingShowAtMs > 250L) {
                    pendingShowAtMs = nowMs;
                    return;
                }
            }
            if (displayedAlpha <= 0.01f) {
                displayedFraction = targetFraction;
                displayedRatio = targetRatio;
                setBarTouchable(isDragEnabled());
            }
            targetAlpha = 1.0f;
            handler.removeCallbacks(beginFadeOutRunnable);
            handler.postDelayed(beginFadeOutRunnable, FADE_DELAY_MS);
            ensureAnimating();
        }
    }

    private int lastScrollDir = 0;
    private int reverseCount = 0;

    // dir is the content direction derived per event source (scrollY or
    // index change); Compose reports dy=-1 for every event, so raw dy
    // cannot be trusted there. Compose's scrollY is itself an estimate
    // that wobbles a few hundred px against the real motion, so a
    // reversal only counts once two events in a row agree.
    private static float median3(float a, float b, float c) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }

    // trusted: dir is already de-noised by the caller, flip immediately.
    private void setTargetFraction(float next, int dir, boolean trusted) {
        boolean visible = displayedAlpha > 0.01f;
        long now = System.nanoTime();
        if (!visible) {
            lastScrollDir = dir;
            reverseCount = 0;
            clearSamples();
            targetFraction = next;
            pushSample(now, next);
            return;
        }
        if (dir != 0 && lastScrollDir != 0 && dir != lastScrollDir) {
            if (!trusted && ++reverseCount < 2) return;
            lastScrollDir = dir;
        } else if (dir != 0) {
            if (lastScrollDir == 0) lastScrollDir = dir;
            reverseCount = 0;
        }
        if ((lastScrollDir > 0 && next < targetFraction) || (lastScrollDir < 0 && next > targetFraction)) {
            return;
        }
        targetFraction = next;
        pushSample(now, next);
    }

    private void clearSamples() {
        sampleCount = 0;
    }

    private void pushSample(long time, float fraction) {
        if (sampleCount == sampleTime.length) {
            System.arraycopy(sampleTime, 1, sampleTime, 0, sampleCount - 1);
            System.arraycopy(sampleFraction, 1, sampleFraction, 0, sampleCount - 1);
            sampleCount--;
        }
        sampleTime[sampleCount] = time;
        sampleFraction[sampleCount] = fraction;
        sampleCount++;
    }

    private float sampleAt(long time) {
        if (sampleCount == 0) return targetFraction;
        if (time <= sampleTime[0]) return sampleFraction[0];
        for (int i = 1; i < sampleCount; i++) {
            if (time <= sampleTime[i]) {
                float span = Math.max(1f, sampleTime[i] - sampleTime[i - 1]);
                float k = (time - sampleTime[i - 1]) / span;
                return sampleFraction[i - 1] + (sampleFraction[i] - sampleFraction[i - 1]) * k;
            }
        }
        return sampleFraction[sampleCount - 1];
    }

    private void scrollToTopBurst() {
        ScrollTracker tracker = trackers.get(currentPackage);
        if (tracker != null) {
            tracker.cumulativePixels = 0f;
            tracker.smoothVirtualY = 0f;
            tracker.lastScrollY = 0;
        }
        targetFraction = 0f;
        boolean isSelfApp = currentPackage.equals(getPackageName());
        boolean blockedScrollbar = IGNORED_PACKAGES.contains(currentPackage)
                || (shouldHideInBanks() && SENSITIVE_PACKAGES.contains(currentPackage))
                || isUserExcludedPackage(currentPackage)
                || (!isSelfApp && shouldSkipNativeScrollbars() && NATIVE_SCROLLBAR_PACKAGES.contains(currentPackage));

        if (isScrollbarEnabled() && !blockedScrollbar) {
            targetAlpha = 1.0f;
            setBarTouchable(isDragEnabled());
            handler.removeCallbacks(beginFadeOutRunnable);
            handler.postDelayed(beginFadeOutRunnable, FADE_DELAY_MS);
            ensureAnimating();
        }

        DisplayMetrics dm = cachedMetrics;
        for (int i = 0; i < 4; i++) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Path p = new Path();
                    p.moveTo(dm.widthPixels * 0.46f, dm.heightPixels * 0.20f);
                    p.lineTo(dm.widthPixels * 0.46f, dm.heightPixels * 0.85f);
                    GestureDescription.Builder b = new GestureDescription.Builder();
                    b.addStroke(new GestureDescription.StrokeDescription(p, 0, 75));
                    dispatchGesture(b.build(), null, null);
                }
            }, i * 110L);
        }
    }

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }

    // ---- Scrub by injected finger (opt-in: settings put global scrollhelper_scrub 1) ----
    private boolean scrubActive = false;
    private int scrubMode = 1; // 1 = infinite feed mapping, 0 = linear
    private float scrubRangePx = 1f;
    private float scrubInjected = 0f;   // distance commanded
    private float scrubDone = 0f;       // distance the injected finger has completed
    private boolean vInFlight = false;
    private float vPendingStep = 0f;
    private static final long SEG_MS = 40L;
    private float scrubWanted = 0f;
    private GestureDescription.StrokeDescription vStroke;
    private float vY = 0f;
    private boolean vLifting = false;
    private float vSlopPending = 0f;
    private float vLastStep = 0f;
    private Rect vListBounds;

    private boolean scrubEnabled() {
        return Settings.Global.getInt(getContentResolver(), "scrollhelper_scrub", 0) == 1;
    }

    // Content px for the whole track. Infinite feeds have no length, so
    // the track spans MAX_FEED_PAGES screens, like the page fallback.
    private float scrubPxPerTrack() {
        float screenH = cachedMetrics.heightPixels;
        return scrubMode == 1 ? screenH * MAX_FEED_PAGES : Math.max(screenH, scrubRangePx);
    }

    private final Runnable scrubPump = new Runnable() {
        @Override
        public void run() {
            if (!scrubActive) return;
            stepVirtualFinger();
            handler.postDelayed(this, 16L);
        }
    };

    private final AccessibilityService.GestureResultCallback strokeCb = new AccessibilityService.GestureResultCallback() {
        @Override
        public void onCompleted(GestureDescription g) {
            vInFlight = false;
            scrubDone += vPendingStep;
            vPendingStep = 0f;
            if (scrubActive) {
                targetFraction = clamp(dragStartFraction + scrubDone / scrubPxPerTrack(), 0f, 1f);
                displayedFraction = targetFraction;
                ensureAnimating();
            }
        }

        @Override
        public void onCancelled(GestureDescription g) {
            vInFlight = false;
            vPendingStep = 0f;
            vStroke = null;
            vLifting = false;
        }
    };

    private final AccessibilityService.GestureResultCallback liftDone = new AccessibilityService.GestureResultCallback() {
        @Override
        public void onCompleted(GestureDescription g) {
            vStroke = null;
            vLifting = false;
        }

        @Override
        public void onCancelled(GestureDescription g) {
            vStroke = null;
            vLifting = false;
        }
    };

    private void dispatchStroke(GestureDescription.StrokeDescription st, GestureResultCallback cb) {
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(st);
        dispatchGesture(b.build(), cb, handler);
    }

    private void stepVirtualFinger() {
        if (vLifting || vInFlight) return;
        float need = scrubWanted - scrubInjected;
        if (Math.abs(need) < 2f) return;
        float screenW = cachedMetrics.widthPixels;
        float screenH = cachedMetrics.heightPixels;
        float x = screenW * 0.4f;
        // Drag inside the list itself (tab bars and toolbars do not
        // scroll it), clear of the status bar and the home gesture area.
        float top = screenH * 0.15f, bottom = screenH * 0.88f;
        if (vListBounds != null && vListBounds.height() > screenH * 0.3f) {
            top = Math.max(top, vListBounds.top + vListBounds.height() * 0.1f);
            bottom = Math.min(bottom, vListBounds.bottom - vListBounds.height() * 0.1f);
        }
        // One segment per round trip: the next one goes out only after the
        // previous finished, so the finger never bursts or stalls. Speed is
        // capped, and eases off as the wanted distance is reached.
        float maxStep = screenH * 0.13f;
        float step = clamp(need * 0.6f, -maxStep, maxStep);
        if (Math.abs(need) < 8f) step = need;
        if (vStroke == null) {
            // Content forward = finger moving up, so start low; and back.
            vY = step > 0 ? bottom : top;
            Path p = new Path();
            p.moveTo(x, vY);
            vStroke = new GestureDescription.StrokeDescription(p, 0, 1, true);
            vPendingStep = 0f;
            vInFlight = true;
            dispatchStroke(vStroke, strokeCb);
            // Lists ignore the first touch-slop px of a drag: add them to
            // the first move so they do not eat the content distance.
            vSlopPending = android.view.ViewConfiguration.get(this).getScaledTouchSlop() + 2f;
            return;
        }
        float slop = Math.signum(step) * vSlopPending;
        vSlopPending = 0f;
        float target = vY - step - slop;
        if (target < top || target > bottom) {
            // Out of room: lift while still moving, so the list keeps
            // gliding for the few frames the next touch takes to land.
            liftVirtualFinger(false);
            return;
        }
        Path p = new Path();
        p.moveTo(x, vY);
        p.lineTo(x, target);
        vStroke = vStroke.continueStroke(p, 0, SEG_MS, true);
        vPendingStep = step;
        vLastStep = step;
        vInFlight = true;
        dispatchStroke(vStroke, strokeCb);
        vY = target;
        scrubInjected += step;
    }

    // Hold still before lifting so the list sees no velocity and does
    // not fling.
    private void liftVirtualFinger(final boolean still) {
        if (vStroke == null || vLifting) return;
        if (vInFlight) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    liftVirtualFinger(still);
                }
            }, 10L);
            return;
        }
        vLifting = true;
        float screenH = cachedMetrics.heightPixels;
        float x = cachedMetrics.widthPixels * 0.4f;
        Path p = new Path();
        p.moveTo(x, vY);
        if (still) {
            vStroke = vStroke.continueStroke(p, 0, 90, false);
        } else {
            // Keep the finger's speed through the lift.
            float end = clamp(vY - vLastStep * 0.5f, screenH * 0.07f, screenH * 0.93f);
            p.lineTo(x, end);
            vStroke = vStroke.continueStroke(p, 0, 16, false);
        }
        dispatchStroke(vStroke, liftDone);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (wm != null && overlaysAdded) {
            try {
                if (scrollBarView != null) wm.removeView(scrollBarView);
                if (grabView != null) wm.removeView(grabView);
                if (topTapView != null) wm.removeView(topTapView);
            } catch (Exception ignored) {
            }
            overlaysAdded = false;
        }
    }

    private class ScrollBarView extends View {
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final int[] loc = new int[2];
        private final float density;

        ScrollBarView(AccessibilityService ctx, float density) {
            super(ctx);
            this.density = density;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            getLocationOnScreen(loc);
            barScreenTop = loc[1];
            if (displayedAlpha <= 0.01f) return;

            int w = getWidth();
            float trackY = trackTop(getHeight());
            float h = trackHeight(getHeight());

            // Flush to the right edge like a native View scrollbar.
            float barThickness = displayedWidthDp * density;
            float left = w - barThickness;
            float right = w;
            float radius = 0f;

            float thumbH = thumbHeightPx(h, displayedRatio);
            float availH = Math.max(1f, h - thumbH);
            float top = trackY + availH * clamp(displayedFraction, 0f, 1f);
            float bottom = top + thumbH;

            // Measured native thumb on S25 (dark): RGB 98 over a 10
            // background, i.e. white at ~36% alpha; light mode uses
            // black at the same alpha. The system caps untouchable
            // overlay windows at 0.8 alpha, so compensate for it.
            boolean dark = isDarkThemeActive();
            float effective = isDragging ? 0.55f : 0.36f;
            int baseAlpha = (int) (255f * effective / 0.8f);
            int channel = dark ? 255 : 0;
            thumbPaint.setColor(Color.argb((int) (displayedAlpha * baseAlpha), channel, channel, channel));
            rect.set(left, top, right, bottom);
            canvas.drawRoundRect(rect, radius, radius, thumbPaint);
        }
    }
}
