# Scroll Helper

Barra de rolagem estilo iOS na borda direita e toque no topo da tela para voltar ao início no Android e na One UI.

![Demonstração do Scroll Helper](assets/demo.gif)

## O que faz

- Desenha uma barra lateral discreta a 120Hz em aplicativos que escondem o indicador nativo, como o feed do X (Twitter).
- Permite segurar e arrastar a barra lateral para rolar a página com resposta tátil e velocidade contínua.
- Volta ao topo da lista ao tocar na área central da barra de status (câmera frontal), mantendo o gesto de deslizar para baixo para abrir as notificações.
- Alterna entre tema escuro, claro ou automático e pausa sozinho em aplicativos de banco.

## Instalação

### Pelo celular (APK direto ou Obtainium)

1. Baixe o `s25-scroll-helper.apk` na [última release](https://github.com/pedrosatin/android-ios-scroll/releases/latest) ou adicione `https://github.com/pedrosatin/android-ios-scroll` no Obtainium.
2. Abra o **Scroll Helper** e ative a **Sobreposição de tela**.
3. No Android 13+, toque em **Configurações restritas**, abra o menu `⋮` no canto superior direito e marque **Permitir configurações restritas**.
4. Toque em **Serviço de acessibilidade** e ative o **Scroll Helper**.

### Via ADB (recomendado para apps de banco)

Instalar com `-i com.android.vending` registra a Play Store como origem do pacote e evita alertas de segurança em bancos como Nubank e Inter:

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

## Como funciona no X (Twitter) e Jetpack Compose

Listas em Jetpack Compose emitem eventos `TYPE_VIEW_SCROLLED` com `fromIndex = -1`, `itemCount = -1` e `maxScrollY = scrollY + 100`. Dividir `scrollY / maxScrollY` trava qualquer barra comum em 99%.

O [`ScrollAccessibilityService.java`](src/dev/satin/scrollhelper/ScrollAccessibilityService.java) identifica essa margem sintética fixa, acompanha o maior `scrollY` da sessão e calcula a posição com interpolação exponencial no `Choreographer.FrameCallback`. No arrasto manual da barra, a velocidade do dedo é convertida em gestos contínuos (`GestureDescription`) em vez de saltos de página via `ACTION_SCROLL_FORWARD`.

## Build

```bash
./build.sh
```

---

## English

Right-edge iOS-style scrollbar (120Hz, hold-and-drag thumb scrubbing) and status-bar tap-to-scroll-to-top for Android and Samsung One UI.

- Works on Jetpack Compose feeds like X (Twitter) where `maxScrollY` is fixed at `scrollY + 100`.
- Supports system, dark, and light themes (`~26 KB` APK, zero dependencies, Android 10+).
- Pauses automatically inside banking apps. Download from [Releases](https://github.com/pedrosatin/android-ios-scroll/releases/latest) or install with the ADB command above.

## License

[MIT](LICENSE)
