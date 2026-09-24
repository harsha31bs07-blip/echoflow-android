# EchoFlow — Teachable Voice Automation (Android)

You teach EchoFlow a task on your phone by saying a command and then doing the task yourself with taps. Later it replays the task when you say that command, a paraphrase of it, or a version with different values.

It uses only Android Accessibility Service APIs, and it hands control back to you on any payment, OTP, password or login screen.

- Design: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · [docs/TEST_MATRIX.md](docs/TEST_MATRIX.md) · [docs/LIMITATIONS.md](docs/LIMITATIONS.md)
- Safety validation workflow: [docs/SAFETY_FIXTURES.md](docs/SAFETY_FIXTURES.md)

## Status

| Build step | Modules | State |
|---|---|---|
| 1. Safety foundation | M2 AccessibilityBridge, M3 ScreenPerception (snapshot capture), M4 SafetyGuard, M5 ActionGateway, safety monitor + snapshot dump | **Done — needs on-device validation** |
| 2. Teach + inspect (T1) | M6, M7, M8, Inspector UI | Not started |
| 3–8 | Replay, NLU, DecisionLayer, slots, state classifier, run log, bonuses | Not started |

## Project layout

```
core/   Pure Kotlin/JVM (no Android dependency): snapshot model, SafetyGuard, ActionGateway, Redactor.
        All safety logic lives here so it is unit-tested on the JVM.
app/    Android app: AccessibilityService, snapshot capture, action executor, safety monitor overlay,
        onboarding screen. Framework APIs only (no AndroidX).
docs/   Architecture, rubric traceability, limitations, fixture workflow.
```

## Build and install

You need JDK 17 or newer and the Android SDK (compileSdk 35). Android Studio sets both up.

```bash
./gradlew :app:assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:test                  # SafetyGuard + gateway unit tests and fixture harness
```

If the Android SDK or Google Maven isn't available (some CI machines), you can still build and test the safety core on its own:

```bash
./gradlew -Pechoflow.jvmOnly=true :core:test
```

### Enable the accessibility service (judges, read this)

1. Open **EchoFlow** and tap **Open Accessibility settings**.
2. Enable **EchoFlow automation**.
3. **Android 13+ with a sideloaded APK:** the toggle may be greyed out ("restricted setting"). Go to **Settings → Apps → EchoFlow → ⋮ (top right) → Allow restricted settings**, then repeat step 2.

## Safety monitor (build step 1)

When the monitor is on, a strip at the top of the screen shows how SafetyGuard classifies whatever is on screen, and reads each change aloud:

- **Green `SAFE`**: automation may act on this screen.
- **Red `PAYMENT` / `OTP` / `PASSWORD` / `LOGIN`**: automation stops and hands control to the user.
- **Orange `OPAQUE_UNKNOWN`**: the screen can't be read, so automation stops to be safe.

The strip's buttons:

- **Dump** saves a redacted snapshot to `Download/EchoFlow/` for use as a test fixture.
- **Re-arm** resets the guard after a hand-off. It only works once you've left the sensitive screen.
- **Move** switches the strip between the top and bottom of the screen.
- **Hide** turns the monitor off.

The monitor is how the heuristics get validated on the real target apps before anything is built on top of them. The process is in [docs/SAFETY_FIXTURES.md](docs/SAFETY_FIXTURES.md).

## Target apps

| Flow | Apps |
|---|---|
| Food ordering (item, quantity, saved address) | Swiggy / Zomato |
| Shopping search | Amazon / Flipkart |
| Cross-app bonus | Myntra |

This list becomes final only after every app's payment, OTP and login screens pass the safety monitor (see [docs/LIMITATIONS.md](docs/LIMITATIONS.md), L1).
