# SJBStudio EQ32 - 32-Band Parametric/Graphic EQ & MDRC (Sin Root)

High-fidelity 32-band audio equalizer with 3-band macro tone control and 4-band Multi-Band Dynamic Range Compression (MDRC), designed for Android API 28+ without requiring root privileges.

## Architecture Highlights
- **Zero Root Operation**: Leverages Android API 28+ `android.media.audiofx.DynamicsProcessing` attached to session `0` (global mixed audio) or dynamically discovered third-party media sessions via `AudioSessionReceiver`.
- **35 Total Cascaded Bi-quadratic Filters**:
  - **Tone Bass**: Low-Shelf @ 100 Hz
  - **Tone Mid**: Peaking @ 1000 Hz, Q=1.4142
  - **Tone Treble**: High-Shelf @ 8000 Hz
  - **32 ISO Bands**: Peaking biquads from 20.0 Hz to 20,000 Hz (Q=1.4142)
- **4-Band MDRC Dynamics Processing**:
  - Band 1: Sub Bass (<120 Hz)
  - Band 2: Low-Mid (120 Hz - 1000 Hz)
  - Band 3: High-Mid (1000 Hz - 6000 Hz)
  - Band 4: Air & Brilliance (6000 Hz - 20000 Hz)
  - Configurable Threshold (-40 to 0 dB), Ratio (1:1 to 20:1), Attack (1-100 ms), Release (10-500 ms), and Post-Makeup gain.
- **Master Peak Limiter**: Brickwall limiter with 1ms attack and 50ms release at -0.5 dB to prevent clipping distortions.
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
3. Once finished, download the compiled `app-debug.apk` from the **Actions -> Artifacts** tab.
