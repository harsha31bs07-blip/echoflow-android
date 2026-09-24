# Validating SafetyGuard on real apps

SafetyGuard (`core/src/main/kotlin/com/echoflow/core/safety/`) decides locally when automation must stop and hand control to the user. Its rules are heuristics, so each target app has to be checked against them before any flow is taught on it. This matters most for T11, the test that costs −10 if it fails.

## 0. Control check: can EchoFlow read the screen at all?

Open **Settings** with the monitor on. The strip should be green, and its diagnostics line should look like `184 nodes · 62 readable · 0 withheld`.

For every app, read that line first:

| Diagnostics line | Meaning |
|---|---|
| `readable` > 0 | EchoFlow can see the app, so the colour means something. Go on to step 1. |
| `0 readable`, `0 withheld`, few nodes | The screen is still loading, or the app draws its UI without accessibility nodes. Wait 2 s; the monitor re-checks by itself. |
| `0 readable`, `withheld` > 0 | The app hides its UI from EchoFlow. Dump it and report it: that app can't be a target app (LIMITATIONS L15). |
| `0 readable` in Settings too | Capture is broken. Dump it and report it. |

## 1. Walk every sensitive screen with the monitor on

Install the app, enable the accessibility service, and turn on **Safety monitor overlay**. Then, for **each target app**, work through this checklist:

| Screen | Expected overlay |
|---|---|
| Home, search results, product/restaurant page, menu with bank offers | green SAFE |
| Cart / bag (before payment) | green SAFE, or red PAYMENT if the cart has a "Pay using" selector (see LIMITATIONS L2) |
| Address selection | green SAFE |
| Payment options page (UPI / cards / net banking / wallets) | red PAYMENT |
| Card entry form, UPI ID entry | red PAYMENT |
| UPI PIN / bank password | red PASSWORD |
| OTP entry | red OTP |
| Logged-out login screen, phone number + "Get OTP" | red LOGIN |
| Google account picker, Truecaller sheet | red LOGIN |
| Web checkout that shows almost nothing | orange OPAQUE_UNKNOWN |

⚠️ Don't complete any payment. Back out once you've captured the screen.

## 2. Dump each screen as a fixture

Tap **Dump** on every screen in the checklist, and especially on any screen where the overlay was wrong. Dump captures the screen fresh, trying up to 3 times over about 1 s and keeping the most readable result. Each dump goes to `Download/EchoFlow/snap_<time>_<app>_<verdict>.json` and includes the capture diagnostics.

Before a dump is written, it is **redacted**:
- anything typed into a field is dropped;
- runs of 4 or more digits (phone numbers, card numbers, OTPs) become `#`;
- email addresses become `[email]`.

Open the file and check it before committing it.

Copy the files to your computer:

```bash
adb pull /sdcard/Download/EchoFlow/ ./dumps/
```

## 3. File them under the expected verdict

```
core/src/test/resources/fixtures/
  sensitive/payment/<name>.json    must classify as PAYMENT
  sensitive/otp/<name>.json        must classify as OTP
  sensitive/password/<name>.json   must classify as PASSWORD
  sensitive/login/<name>.json      must classify as LOGIN
  sensitive/opaque_unknown/<name>.json
  safe/<name>.json                 must classify as SAFE
```

Each folder name is the expected kind (the name of a `SensitiveKind`, in lowercase). Then run:

```bash
./gradlew :core:test --tests '*FixtureHarnessTest*'
```

## 4. Fix misses in the lexicon, never in the test

- **A sensitive screen classified as SAFE is a T11 bug.** Add the missing phrase or field label to `SafetyLexicon.kt`, preferably as a STRONG signal, or add a rule to `ScreenSafetyClassifier.kt`. Commit the fixture along with the fix.
- **A safe screen classified as sensitive** means a replay would stop early. That's safe, but it hurts T2–T9. Tighten the rule without weakening any `sensitive/` fixture.
- **If an app's payment screen can't be made to trip reliably, remove that app from the declared target list.** Don't loosen the guard to make it pass.
