# halba POS app

Native Android app (Kotlin) that runs on the payment terminals of halba's PSPs. The merchant's till asks halba for a
payment (`POST /v1/terminal_payments`); halba reaches this app through a long-poll and the app takes the card with the
PSP's SDK, or shows a QR code for Alipay, WeChat Pay, Bizum, BLIK and TWINT.

## Flavors

One app per PSP, because SDKs, signing and stores differ:

| Flavor | Card | Distribution |
| --- | --- | --- |
| `stripe` | Stripe Terminal SDK, built-in reader in handoff mode (Apps on Devices); simulated reader in debug builds | Stripe Dashboard after app review |
| `adyen` | coming: Adyen payment app on the terminal | Adyen Customer Area |
| `mypos` | coming: myPOS Smart SDK | myPOS AppMarket |

```bash
./gradlew :app:assembleStripeDebug
```

## What it does

- **Pairing**: shows an 8-character code that the merchant enters in the halba dashboard; the device key is encrypted
  with the Android Keystore.
- **Setup on the terminal**: store and terminal name chosen on the device, card reader check, screen download.
- **Screen at rest**: the merchant's image or video in a loop (uploaded in the dashboard), or the halba screen; the
  screen stays on, and a banner asks to connect power when the terminal is not charging.
- **Payments**: from the till, straight to the method the till chose (`payment_method`) or with a method choice.
- **Staff menu**: hold the status badge for 3 seconds, then the store's staff PIN (checked by halba).

The screens follow the **Terminal** group of the halba design system (artifact "Halba Design System", `project/terminal.md`):
tokens, measures, texts in English, Italian and German (`res/values*/strings.xml`, keys `terminal_*`), timings and
accessibility. States A–H: guided setup (language, pairing, store and name, reader, idle screen, test payment, done),
idle with the merchant's image or video, payment from the till, card, QR, result, hidden staff menu, system states.

- Built with Android Views (`Ui.kt`, `Halba.kt`), not Compose: same measures, smaller app.
- Fonts: system sans and monospace until the Inter and DM Mono TTF files are added (Android cannot use the woff2 files).
- Official payment marks: placeholders (QR icon and name), as in the design system previews.
- The accent follows the merchant's checkout colour with the contrast rules of section 3.3.
The API is in `halba-orchestrator` (`src/terminal/devices.ts`).
