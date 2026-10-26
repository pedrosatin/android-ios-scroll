# Scroll Helper

iOS-style right-edge scrollbar and status-bar tap-to-scroll-to-top for Android 10+ (`minSdkVersion 29`, `~33 KB`, zero dependencies).

Works on any Android phone (Samsung One UI, Pixel, Xiaomi, Motorola, Nothing). Tested at 120Hz on a Galaxy S25 running Android 17 / One UI 9.

![Scroll Helper demo](assets/demo.gif)

## Features

- Renders a 120Hz right-edge scrollbar in apps that hide the native indicator, including X (Twitter) and Jetpack Compose feeds.
- Skips `WebView`, standard `ScrollView`, and browsers that already draw their own scrollbar, plus provides a 1-tap toggle to disable the scrollbar for the most recently used app.
- Expands the scrollbar pill on long-press with haptic feedback so you can drag it vertically to scrub through long lists.
- Scrolls back to the top of any feed or browser when you tap the center of the status bar (camera cutout), while keeping swipe-down for notifications.
- Supports Auto, Dark, and Light themes and pauses automatically inside banking apps.

## Installation

### On device (direct APK or Obtainium)

1. Download `scroll-helper.apk` from [Releases](https://github.com/pedrosatin/android-ios-scroll/releases/latest) or add `https://github.com/pedrosatin/android-ios-scroll` to Obtainium.
2. Open **Scroll Helper** and grant **Display over other apps**.
3. On Android 13+, tap **Allow restricted settings**, open the top-right `⋮` menu on the App Info screen, and select **Allow restricted settings**.
4. Tap **Accessibility service** and enable **Scroll Helper**.

### Via ADB (recommended if you use banking apps)

Installing with `-i com.android.vending` sets Google Play Store as the installer source so banking apps do not flag the accessibility service as sideloaded:

```bash
curl -sL https://github.com/pedrosatin/android-ios-scroll/releases/latest/download/scroll-helper.apk -o scroll-helper.apk && \
adb install -r -g -i com.android.vending scroll-helper.apk && \
adb shell appops set dev.satin.scrollhelper SYSTEM_ALERT_WINDOW allow && \
EXISTING_A11Y=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r') && \
if [ "$EXISTING_A11Y" = "null" ] || [ -z "$EXISTING_A11Y" ]; then \
  adb shell settings put secure enabled_accessibility_services "dev.satin.scrollhelper/dev.satin.scrollhelper.ScrollAccessibilityService"; \
else \
  adb shell settings put secure enabled_accessibility_services "${EXISTING_A11Y}:dev.satin.scrollhelper/dev.satin.scrollhelper.ScrollAccessibilityService"; \
fi && \
adb shell settings put secure accessibility_enabled 1
```

## How it fixes Jetpack Compose scrollbars

Jetpack Compose lazy lists (such as the X/Twitter timeline) emit `TYPE_VIEW_SCROLLED` with `fromIndex = -1`, `itemCount = -1`, and a synthetic `maxScrollY` fixed at `scrollY + 100`. Dividing `scrollY / maxScrollY` pins standard scrollbar overlays at 99%.

[`ScrollAccessibilityService.java`](src/dev/satin/scrollhelper/ScrollAccessibilityService.java) detects that constant `50..250 px` gap. Compose derives `scrollY` from the first visible item, so it jumps by hundreds of pixels for a single event whenever an item leaves the screen. The service advances by the median of the last three deltas, which drops those spikes, and drifts back toward the reported value only in the direction of motion. The unknown feed length is mapped with `pos / (pos + 10 screens)`: the thumb moves about a tenth of the track per screen at the top and slows gradually deeper in the feed. The mapping depends only on the position, so the thumb never jumps.

Accessibility scroll events arrive about every 100 ms. The thumb is drawn 120 ms behind real time and interpolated between the two surrounding events inside `Choreographer.FrameCallback`, so it moves on every frame at 120 Hz without overshooting. Because that interpolation is linear between events, its velocity steps at each event; a centred Gaussian average over ±64 ms of the trajectory (already known thanks to the render delay) smooths those steps at the end of a fling. A lone scroll event (an app re-measuring after loading content) does not show the bar; a real scroll sends a second event within 250 ms. Thickness (4 dp), color (36% white or black), and fade (250 ms linear, after the bar has been still for 1 s) follow the native Android scrollbar. Only a small window around the thumb accepts touches, so taps next to it still reach the app. Dragging the thumb maps its travel to pages of the list (at most 15 across the whole track in infinite feeds) and sends them as `ACTION_SCROLL_FORWARD`/`ACTION_SCROLL_BACKWARD`, one at a time once the previous page stops moving. An injected gesture would cancel the finger holding the thumb. Pages that have not been sent when the finger lifts are dropped, so the list stops with the finger.

## Battery and performance notes

The service only does work when something scrolls or the thumb is being dragged. The frame loop stops as soon as the thumb settles and fades out; there is no polling, no logging, and no work while the screen is idle. Display metrics, theme, and the per-app exclusion list are cached and refreshed only when preferences or the device configuration change, so the per-event and per-frame paths avoid re-querying resources and preferences.

## Build from source

```bash
./build.sh
```

## License

[MIT](LICENSE)
