# SJBStudio EQ32 - 32-Band Parametric/Graphic EQ & MDRC (Sin Root) Beta experiment

High-fidelity 32-band audio equalizer with 3-band macro tone control and 4-band Multi-Band Dynamic Range Compression (MDRC), designed for Android API 28+ without requiring root privileges.

## Architecture Highlights
- **Zero Root Operation**: Leverages Android API 28+ `android.media.audiofx.DynamicsProcessing`. It first tries session `0` (global mixed audio); if the system rejects it, it falls back to per-app sessions (YouTube, Spotify, AIMP) discovered via `AudioSessionReceiver`.
- **One biquad response, folded into 32 physical bands**: the analytic response is the sum of
  - 32 log-spaced peaking bands from 20 Hz to 20 kHz (Q=4.318; not an ISO table),
  - Tone Bass (low-shelf @ 100 Hz), Tone Mid (peaking @ 1 kHz, Q=0.707), Tone Treble (high-shelf @ 8 kHz),
  - Bass Boost (low-shelf @ 60 Hz, 0 to +12 dB).
  This response is converted to exactly 32 `DynamicsProcessing` pre-EQ bands; tone and boost do not use extra bands.
- **4-Band MDRC Dynamics Processing**:
  - Band 1: Sub Bass (<120 Hz)
  - Band 2: Low-Mid (120 Hz - 1000 Hz)
  - Band 3: High-Mid (1000 Hz - 6000 Hz)
  - Band 4: Air & Brilliance (6000 Hz - 20000 Hz)
  - Configurable Threshold (-40 to 0 dB), Ratio (1:1 to 20:1), Attack (1-100 ms), Release (10-500 ms), and Post-Makeup gain.
- **Master Peak Limiter**: Limiter (1 ms attack, 50 ms release, ratio 20:1) with a -0.5 dB threshold to prevent clipping distortions.
- **Hardware-Accelerated UI**: Custom `EqGraphView` renders logarithmic Bode magnitude plot with Choreographer 16ms frame-rate limiter.
- **AMOLED Pitch-Black Interface**: Saves battery during extended high-resolution audio sessions.

## How to Build in Android Studio
1. Unzip the project folder.
2. Open **Android Studio Hedgehog / Iguana / Jellyfish (2023.2+)**.
3. Select **File -> Open...** and select the root directory containing `settings.gradle.kts`.
4. Allow Gradle Sync to finish with JDK 17. The launcher bootstraps the Gradle 8.4 distribution when needed, so an internet connection is required on first use.
5. Click **Run 'app'** or execute:
   ```bash
   ./gradlew assembleDebug
   ```

## How to Build on GitHub Actions
1. Push this repository to GitHub.
2. The workflow file `.github/workflows/build.yml` will automatically trigger and installs Gradle 8.4 explicitly (it does not depend on the missing binary wrapper JAR).
3. Once finished, download the release APK from the **Actions -> Artifacts** tab. It is signed when the `EQ32_KEYSTORE_*` secrets exist; otherwise an unsigned APK is uploaded.
