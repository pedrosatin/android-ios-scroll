# Android iOS-style scrollbar & tap-to-top

Lightweight Android accessibility overlay (`~25 KB`, zero external dependencies) that brings two iOS system interactions to Android and Samsung One UI:

1. Interactive right-edge scrollbar with 120Hz exponential smoothing, haptic long-press expansion, and continuous velocity-based thumb scrubbing.
2. Status-bar / camera-cutout tap-to-scroll-to-top across any vertical feed or web view, while preserving downward swipe to open the notification shade.

Built and tested on a Samsung Galaxy S25 running Android 17 / One UI 9, compatible with Android 10+ (`minSdkVersion 29`).

---

## Why native Android scrollbars fail in modern apps

Apps built with Jetpack Compose lazy lists, including the X (Twitter) timeline, emit `AccessibilityEvent.TYPE_VIEW_SCROLLED` with `fromIndex = -1`, `itemCount = -1`, and a synthetic `maxScrollY` that is always fixed at `scrollY + 100` (for example, `scrollY = 16334` and `maxScrollY = 16434`). Any overlay that computes progress as `scrollY / maxScrollY` pins the indicator at 99% and jumps erratically whenever inline carousels or video players emit nested scroll events.

Standard accessibility scroll actions (`ACTION_SCROLL_FORWARD` and `ACTION_SCROLL_BACKWARD`) also step in discrete viewport pages without momentum, which causes visible chunking and flicker when dragging a custom scrollbar thumb.

### How this project fixes both problems

- [`ScrollAccessibilityService.java`](src/dev/satin/scrollhelper/ScrollAccessibilityService.java) detects when `maxScrollY - scrollY` stays within a constant `50..250 px` window with negative item indices, tracks the session peak `maxScrollYSeen`, and projects a continuous lookahead horizon (`maxScrollYSeen + 3 * screenHeight`). Sudden low-offset events from inline media players are filtered out unless the user genuinely scrolls back to the top.
- Instead of snapping on 10Hz accessibility events, thumb position, height ratio, pill thickness (`3.2dp` idle, `7.5dp` when grabbed), and alpha are interpolated inside a `Choreographer.FrameCallback` using frame-rate-independent exponential decay (`1 - exp(-9.5 * dt)`).
- Dragging the scrollbar thumb measures finger velocity (`px/s`) and dispatches overlapping `GestureDescription` touch strokes scaled to the user's speed (`14%` to `58%` of screen height per stroke), preserving native touch physics and fling inertia. A `450 ms` post-drag cooldown prevents late accessibility events from bouncing the thumb after release.
- The service uses `TYPE_APPLICATION_OVERLAY` (`SYSTEM_ALERT_WINDOW`), declares `android:isAccessibilityTool="true"`, keeps `FLAG_NOT_TOUCHABLE` set whenever the pill is invisible, and automatically hides itself inside banking and brokerage apps (`Nubank`, `Itaú`, `Inter`, `Mercado Pago`, `Nomad`, `Avenue`, `Binance`, `Caixa`).

---

## Installation

### Option 1: Direct APK or Obtainium (no PC required)

1. Download `s25-scroll-helper.apk` from the [latest GitHub Release](https://github.com/pedrosatin/android-ios-scroll/releases/latest), or add `https://github.com/pedrosatin/android-ios-scroll` to [Obtainium](https://github.com/ImranR98/Obtainium).
2. Open **S25 Scroll Helper** and follow the three onboarding cards:
   - Grant **Display over other apps** (`SYSTEM_ALERT_WINDOW`).
   - On Android 13+, tap **Open app info**, open the top-right `⋮` menu, and select **Allow restricted settings**.
   - Tap **Open Accessibility** and enable **S25 Scroll Helper**.

### Option 2: One-command ADB install (recommended for banking apps)

Installing via ADB with `-i com.android.vending` registers the Google Play Store as the installer package, preventing banking apps from flagging the sideloaded service:

```bash
curl -sL https://github.com/pedrosatin/android-ios-scroll/releases/latest/download/s25-scroll-helper.apk -o s25-scroll-helper.apk && \
adb install -r -g -i com.android.vending s25-scroll-helper.apk && \
adb shell appops set dev.satin.scrollhelper SYSTEM_ALERT_WINDOW allow && \
EXISTING_A11Y=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r') && \
if [ "$EXISTING_A11Y" = "null" ] || [ -z "$EXISTING_A11Y" ]; then \
  adb shell settings put secure enabled_accessibility_services "dev.satin.scrollhelper/dev.satin.scrollhelper.ScrollAccessibilityService"; \
else \
  adb shell settings put secure enabled_accessibility_services "${EXISTING_A11Y}:dev.satin.scrollhelper/dev.satin.scrollhelper.ScrollAccessibilityService"; \
fi && \
adb shell settings put secure accessibility_enabled 1
```

---

## Building from source

No Android Studio or multi-gigabyte Gradle distribution is required. The [`build.sh`](build.sh) script downloads the standalone Android SDK `build-tools` (`r35`) and `android-34` platform jar into `.sdk/` on first run and compiles the signed APK in under two seconds:

```bash
./build.sh
```

---

## Português (PT-BR)

Utilitário leve para Android (`~25 KB`, sem dependências externas) que adiciona duas interações do iOS ao Android e à One UI da Samsung:

1. Barra de rolagem interativa na borda direita a 120Hz com expansão tátil ao segurar e rolagem contínua ao arrastar a pílula.
2. Toque no topo da tela (região da câmera frontal / barra de status) para voltar rapidamente ao início de qualquer feed ou página, mantendo o gesto de deslizar para baixo para abrir a central de notificações.

### Por que a barra nativa some ou pula no X (Twitter)

Feeds em Jetpack Compose como o do X emitem eventos `TYPE_VIEW_SCROLLED` com `fromIndex = -1`, `itemCount = -1` e um `maxScrollY` sintético sempre igual a `scrollY + 100`. Qualquer barra que divida `scrollY / maxScrollY` fica travada no fim da tela e pisca quando vídeos ou carrosséis internos emitem eventos de rolagem. Além disso, usar `ACTION_SCROLL_FORWARD` faz a tela pular em blocos secos em vez de deslizar.

O [`ScrollAccessibilityService.java`](src/dev/satin/scrollhelper/ScrollAccessibilityService.java) resolve os dois problemas:

- Identifica quando `maxScrollY - scrollY` é uma margem sintética fixa (`50..250 px`), guarda o pico real da sessão (`maxScrollYSeen`) e projeta um horizonte contínuo de rolagem, ignorando saltos falsos de carrosséis internos.
- Interpola posição, tamanho, largura da pílula (`3.2dp` normal, `7.5dp` ao segurar) e opacidade dentro de um `Choreographer.FrameCallback` a 120Hz com decaimento exponencial.
- Converte a velocidade do dedo sobre a barra lateral em gestos contínuos (`GestureDescription`) proporcionais à velocidade do arrasto, mantendo a física e a inércia nativas do Android.
- Pausa automaticamente a sobreposição ao abrir aplicativos de banco (`Nubank`, `Itaú`, `Inter`, `Mercado Pago`, `Nomad`, `Avenue`, `Binance`, `Caixa`) e pode ser instalado com origem `com.android.vending` via ADB para evitar falsos alertas de segurança.

## License

[MIT](LICENSE)
