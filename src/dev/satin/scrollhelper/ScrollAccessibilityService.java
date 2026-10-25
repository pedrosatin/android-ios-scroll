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
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

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
    private float targetWidthDp = 3.2f;
    private float displayedWidthDp = 3.2f;

    // Continuous smooth-scroll physics state while dragging thumb
    private boolean isDragging = false;
    private float dragVelocityPxPerSec = 0f;
    private boolean gestureInFlight = false;
    private long postDragCooldownUntil = 0L;

    private boolean barTouchable = false;
    private boolean animRunning = false;
    private long lastFrameNanos = 0L;
    private String currentPackage = "";

    private static class ScrollTracker {
        int maxItemCountSeen = 0;
        int maxScrollYSeen = 0;
        int lastScrollY = 0;
        int resetCandidateCount = 0;
        float cumulativePixels = 0f;
        float maxCumulativePixels = 1f;
        float smoothVirtualY = 0f;
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
            targetWidthDp = 3.2f;
            ensureAnimating();
        }
    };

    private final Runnable continuousDragSwipeLoop = new Runnable() {
        @Override
        public void run() {
            if (!isDragging) return;

            DisplayMetrics dm = getResources().getDisplayMetrics();
            if (!gestureInFlight && Math.abs(dragVelocityPxPerSec) > 40f * dm.density) {
                dispatchSmoothDragStroke(dragVelocityPxPerSec);
                dragVelocityPxPerSec *= 0.45f;
            }
            if (isDragging) {
                handler.postDelayed(this, 65);
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
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
        if (prefs == null || pkg == null || pkg.isEmpty()) return false;
        String csv = prefs.getString(MainActivity.KEY_EXCLUDED_PKGS, "");
        return !csv.isEmpty() && ("," + csv + ",").contains("," + pkg + ",");
    }

    private boolean isDarkThemeActive() {
        if (prefs != null) {
            int mode = prefs.getInt(MainActivity.KEY_THEME_MODE, 0);
            if (mode == 1) return true;
            if (mode == 2) return false;
        }
        int uiMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return uiMode == Configuration.UI_MODE_NIGHT_YES;
    }

    private void setBarTouchable(boolean touchable) {
        if (wm == null || scrollBarView == null || barParams == null || !overlaysAdded) return;
        if (this.barTouchable == touchable) return;
        this.barTouchable = touchable;
        if (touchable) {
            barParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            barParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        try {
            wm.updateViewLayout(scrollBarView, barParams);
        } catch (Exception ignored) {
        }
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
        final int screenH = dm.heightPixels;

        int barW = (int) (30 * density);
        int barH = (int) (screenH * 0.74f);
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
        barParams.gravity = Gravity.END | Gravity.CENTER_VERTICAL;

        scrollBarView.setOnTouchListener(new View.OnTouchListener() {
            private float lastY = 0f;
            private long lastMoveTime = 0L;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (!isScrollbarEnabled() || !isDragEnabled()) return false;
                int h = v.getHeight();
                if (h <= 0) return false;

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        float touchY = event.getY();
                        float minThumbH = 38f * density;
                        float thumbH = Math.max(minThumbH, h * displayedRatio);
                        float availH = Math.max(1f, (h - 8 * density) - thumbH);
                        float thumbTop = 4 * density + (availH * clamp(displayedFraction, 0f, 1f));
                        float thumbBottom = thumbTop + thumbH;

                        float slop = 52f * density;
                        if (touchY < thumbTop - slop || touchY > thumbBottom + slop) {
                            return false;
                        }

                        isDragging = true;
                        lastY = touchY;
                        lastMoveTime = System.currentTimeMillis();
                        dragVelocityPxPerSec = 0f;
                        targetAlpha = 1.0f;
                        targetWidthDp = 7.5f;
                        handler.removeCallbacks(beginFadeOutRunnable);
                        handler.removeCallbacks(continuousDragSwipeLoop);
                        handler.post(continuousDragSwipeLoop);
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        ensureAnimating();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        if (!isDragging) return false;
                        float y = clamp(event.getY(), 0f, (float) h);
                        long now = System.currentTimeMillis();
                        long dtMs = Math.max(4L, now - lastMoveTime);
                        float dy = y - lastY;
                        lastY = y;
                        lastMoveTime = now;

                        float minTH = 38f * density;
                        float tH = Math.max(minTH, h * displayedRatio);
                        float avH = Math.max(1f, (h - 8 * density) - tH);
                        targetFraction = clamp((y - 4 * density - tH * 0.5f) / avH, 0f, 1f);
                        displayedFraction = targetFraction;
                        ensureAnimating();

                        float instVel = (dy / dtMs) * 1000f;
                        dragVelocityPxPerSec = dragVelocityPxPerSec * 0.35f + instVel * 0.65f;

                        if (!gestureInFlight && Math.abs(dy) > 2.5f * density) {
                            dispatchSmoothDragStroke(dragVelocityPxPerSec);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!isDragging) return false;
                        isDragging = false;
                        dragVelocityPxPerSec = 0f;
                        handler.removeCallbacks(continuousDragSwipeLoop);
                        postDragCooldownUntil = System.currentTimeMillis() + 450L;
                        targetWidthDp = 3.2f;
                        handler.removeCallbacks(beginFadeOutRunnable);
                        handler.postDelayed(beginFadeOutRunnable, 1600);
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
            wm.addView(topTapView, topParams);
            overlaysAdded = true;
        } catch (Exception ignored) {
            overlaysAdded = false;
        }
    }

    private void dispatchSmoothDragStroke(float thumbVelPxPerSec) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        boolean scrollDown = thumbVelPxPerSec > 0;
        float speedMag = Math.abs(thumbVelPxPerSec);

        float dist = clamp(speedMag * 0.48f, dm.heightPixels * 0.14f, dm.heightPixels * 0.58f);
        float cx = dm.widthPixels * 0.46f;
        float midY = dm.heightPixels * 0.50f;
        float startY = scrollDown ? (midY + dist * 0.5f) : (midY - dist * 0.5f);
        float endY = scrollDown ? (midY - dist * 0.5f) : (midY + dist * 0.5f);

        Path path = new Path();
        path.moveTo(cx, startY);
        path.lineTo(cx, endY);

        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(path, 0, 85));

        gestureInFlight = true;
        boolean sent = dispatchGesture(b.build(), new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                gestureInFlight = false;
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                gestureInFlight = false;
            }
        }, null);

        if (!sent) {
            gestureInFlight = false;
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

        float posLerp = isDragging ? 0.92f : (1f - (float) Math.exp(-9.5f * dt));
        float ratioLerp = 1f - (float) Math.exp(-4.0f * dt);
        float widthLerp = 1f - (float) Math.exp(-20f * dt);
        float alphaRate = (targetAlpha > displayedAlpha) ? 20f : 4.2f;
        float alphaLerp = 1f - (float) Math.exp(-alphaRate * dt);

        displayedFraction += (targetFraction - displayedFraction) * posLerp;
        displayedRatio += (targetRatio - displayedRatio) * ratioLerp;
        displayedWidthDp += (targetWidthDp - displayedWidthDp) * widthLerp;
        displayedAlpha += (targetAlpha - displayedAlpha) * alphaLerp;

        boolean stillMoving = Math.abs(targetFraction - displayedFraction) > 0.0008f
                || Math.abs(targetRatio - displayedRatio) > 0.0008f
                || Math.abs(targetWidthDp - displayedWidthDp) > 0.02f
                || Math.abs(targetAlpha - displayedAlpha) > 0.005f;

        if (!stillMoving) {
            displayedFraction = targetFraction;
            displayedRatio = targetRatio;
            displayedWidthDp = targetWidthDp;
            displayedAlpha = targetAlpha;
        }

        if (displayedAlpha <= 0.01f && targetAlpha == 0f && !isDragging && barTouchable) {
            setBarTouchable(false);
        }

        scrollBarView.invalidate();

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
                if (blockedPkg) {
                    hideScrollbarImmediate();
                }
            }
            return;
        }

        if (event.getEventType() != AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            return;
        }
        if (blockedPkg || !isScrollbarEnabled()) return;

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

        DisplayMetrics dm = getResources().getDisplayMetrics();
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
                        tracker.maxScrollYSeen = Math.max(scrollY, dm.heightPixels);
                        tracker.resetCandidateCount = 0;
                    }
                }
            } else {
                tracker.resetCandidateCount = 0;
            }

            if (!ignorePositionUpdate && Math.abs(scrollY - tracker.lastScrollY) < 12 && displayedAlpha > 0.1f) {
                return;
            }
            tracker.lastScrollY = scrollY;

            if (scrollY > tracker.maxScrollYSeen) {
                tracker.maxScrollYSeen = scrollY;
            }
            if (!isComposeSyntheticMax && maxScrollY > tracker.maxScrollYSeen) {
                tracker.maxScrollYSeen = maxScrollY;
            }

            float effectiveMax;
            if (isComposeSyntheticMax) {
                float horizon = dm.heightPixels * 3.0f;
                effectiveMax = Math.max(dm.heightPixels * 4.0f, tracker.maxScrollYSeen + horizon);
            } else {
                effectiveMax = Math.max(1f, (float) maxScrollY);
            }

            if (!ignorePositionUpdate) {
                targetFraction = clamp(scrollY / effectiveMax, 0f, 1f);
            }
            float totalContentH = Math.max(dm.heightPixels * 2.5f, tracker.maxScrollYSeen + dm.heightPixels);
            targetRatio = clamp(dm.heightPixels / totalContentH, 0.055f, 0.18f);
            updated = true;
        } else if (itemCount > 1 && fromIndex >= 0) {
            int visibleCount = (toIndex >= fromIndex) ? Math.max(1, toIndex - fromIndex + 1) : 4;

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
                targetFraction = clamp(tracker.smoothVirtualY / denom, 0f, 1f);
            }
            targetRatio = clamp((float) visibleCount / (float) Math.max(visibleCount + 2, effectiveTotal), 0.055f, 0.18f);
            updated = true;
        } else if (dy != 0) {
            tracker.cumulativePixels = Math.max(0f, tracker.cumulativePixels + dy);
            if (tracker.cumulativePixels > tracker.maxCumulativePixels) {
                tracker.maxCumulativePixels = tracker.cumulativePixels;
            }
            float effectiveMax = Math.max(dm.heightPixels * 3.0f, tracker.maxCumulativePixels + dm.heightPixels * 2.0f);
            if (!ignorePositionUpdate) {
                targetFraction = clamp(tracker.cumulativePixels / effectiveMax, 0f, 1f);
            }
            targetRatio = clamp(dm.heightPixels / (effectiveMax + dm.heightPixels), 0.055f, 0.18f);
            updated = true;
        }

        if (updated && scrollBarView != null && !isDragging) {
            if (displayedAlpha <= 0.01f) {
                displayedFraction = targetFraction;
                displayedRatio = targetRatio;
                setBarTouchable(isDragEnabled());
            }
            targetAlpha = 1.0f;
            handler.removeCallbacks(beginFadeOutRunnable);
            handler.postDelayed(beginFadeOutRunnable, 1600);
            ensureAnimating();
        }
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
            handler.postDelayed(beginFadeOutRunnable, 1600);
            ensureAnimating();
        }

        DisplayMetrics dm = getResources().getDisplayMetrics();
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

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (wm != null && overlaysAdded) {
            try {
                if (scrollBarView != null) wm.removeView(scrollBarView);
                if (topTapView != null) wm.removeView(topTapView);
            } catch (Exception ignored) {
            }
            overlaysAdded = false;
        }
    }

    private class ScrollBarView extends View {
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float density;

        ScrollBarView(AccessibilityService ctx, float density) {
            super(ctx);
            this.density = density;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (displayedAlpha <= 0.01f) return;

            int w = getWidth();
            int h = getHeight();

            float barThickness = displayedWidthDp * density;
            float rightMargin = 2.0f * density;
            float left = w - rightMargin - barThickness;
            float right = w - rightMargin;
            float radius = barThickness / 2f;

            float minThumbH = 38f * density;
            float thumbH = Math.max(minThumbH, h * displayedRatio);
            float availH = Math.max(1f, (h - 8 * density) - thumbH);
            float top = 4 * density + (availH * clamp(displayedFraction, 0f, 1f));
            float bottom = top + thumbH;

            boolean dark = isDarkThemeActive();
            int baseAlpha = isDragging ? 225 : (dark ? 155 : 140);
            int channel = dark ? 205 : 75;
            thumbPaint.setColor(Color.argb((int) (displayedAlpha * baseAlpha), channel, channel, channel));
            rect.set(left, top, right, bottom);
            canvas.drawRoundRect(rect, radius, radius, thumbPaint);
        }
    }
}
