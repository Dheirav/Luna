# CycleTracker — Android

Kotlin implementation of [`docs/CYCLE_RULES.md`](../docs/CYCLE_RULES.md), built against
[`spec/cycle_fixtures.json`](../spec/cycle_fixtures.json).

## Modules

| Module | What it is | Why |
|---|---|---|
| `:core` | Plain Kotlin/JVM. The cycle engine. | No Android APIs, so its tests run on the JVM in under a second — no emulator, no device. That is what makes running the golden fixture cheap enough to do on every change. |
| `:app` | Android app: Room, Compose, Material 3. | Depends on `:core`. Persists daily logs only. |

The persisted schema is **daily logs only**. Periods, cycles and spotting events are a projection
recomputed from those rows (`CYCLE_RULES.md` §1.1) — storing them incrementally was the root cause
of the defect that produced one-day cycles.

## Targets

```
minSdk 31        java.time with no desugaring, Material You, modern widget APIs
targetSdk 35
compileSdk 35
abiFilters arm64-v8a     one known phone (Galaxy A35); matters once SQLCipher lands
R8 + resource shrinking on release
```

No `INTERNET` permission, deliberately and permanently. Cloud backup and device transfer are
excluded in `data_extraction_rules.xml`.

## Building in WSL

The full toolchain is installed locally — no Android Studio required:

```bash
./gradlew :core:test          # 34 golden fixture cases, ~2s once warm
./gradlew :app:assembleDebug  # debug APK
./gradlew :app:assembleRelease
```

| | Location | Notes |
|---|---|---|
| SDK | `~/Android/Sdk` | platform 35, build-tools 35.0.0, platform-tools |
| Gradle | wrapper, 8.11.1 | `~/tools/gradle-8.11.1` bootstrapped it |
| JDK | `/usr/lib/jvm/java-21-openjdk-amd64` | pinned in `~/.gradle/gradle.properties` |

The JDK is pinned in the **user-level** `~/.gradle/gradle.properties`, not the repo's, because the
system default here is JDK 24 and Gradle 8.11 does not support it. Keeping it out of the repo means
the project still builds on Windows with Studio's bundled JDK.

`local.properties` (pointing at the SDK) is machine-local and gitignored — Studio regenerates it.

### Deploying to the phone

Wireless debugging avoids USB passthrough (`usbipd-win`) entirely. On the A35:
**Settings → Developer options → Wireless debugging → Pair device with pairing code**, then

```bash
~/Android/Sdk/platform-tools/adb pair <ip>:<pairing-port>
~/Android/Sdk/platform-tools/adb connect <ip>:<port>
./gradlew :app:installDebug
```

WSL2 reaches the LAN, so this works with the phone on the same Wi-Fi.

### If you'd rather use Android Studio

WSLg is available here (`/mnt/wslg`, `DISPLAY=:0`), so Studio can run inside WSL — but with 7 GB of
RAM, Studio plus a Gradle daemon plus indexing will thrash. Editing from Windows Studio against
`\\wsl.localhost\Ubuntu\home\dheirav\Code\MensturalTracker` works, but build in WSL: Gradle over the
9P bridge is slow.

**Transfer the whole repository, not just `android/`.** The spec files live at the repo root so
they stay language-neutral. Copies are vendored into `core/src/test/resources` and
`app/src/main/assets` so a standalone `android/` still builds, but `SpecDriftTest` can only verify
those copies are current when `../spec` is reachable.

After editing anything in `spec/`:

```bash
./gradlew syncSpec          # refresh the vendored copies
```

Forgetting this fails `SpecDriftTest` in WSL rather than silently testing stale rules. On Windows,
where `../spec` is absent, the check skips.

`.gitattributes` normalises line endings to LF and pins `*.bat` to CRLF, so the round trip does not
corrupt the wrapper scripts.

### First open in Android Studio

The Gradle wrapper JAR is not committed. Studio will offer to generate the wrapper on first sync —
accept it, or run `gradle wrapper` once if you have Gradle on the PATH. The version is already
pinned in `gradle/wrapper/gradle-wrapper.properties`.

Studio supplies its own JDK. Don't build with a system JDK 21+ — AGP 8.7 has not been tested
against those and will complain.

There is no launcher icon yet. Add one with **Studio → New → Image Asset**; the manifest currently
omits `android:icon` so the build does not depend on it.

## Current state

Builds clean. All 34 fixture cases pass.

| APK | Size |
|---|---|
| release (R8 + resource shrinking, arm64-v8a) | **1.4 MB** |
| debug (unminified) | 25 MB |

1.4 MB is a skeleton, not the finished app — the ~12 MB budget in the plan assumed charts, widget,
Health Connect and the logging UI are all present. Plenty of headroom.

`TodayScreen` shows the corrected engine's answer for today, from the backfilled seed. It is
deliberately plain — design effort belongs in the Phase 2 logging screen, because adherence is the
constraint everything else depends on.
