# Nadi

**On-device heart health and workout tracking.**

![Platform](https://img.shields.io/badge/platform-Android-green)
![API](https://img.shields.io/badge/API-26%2B-brightgreen)
![ABI](https://img.shields.io/badge/ABI-arm64--v8a-blue)
![Runtime](https://img.shields.io/badge/SNPE-DLC%2FHTP-orange)

Nadi is an Android app with two independent dashboards:

1. **Heart** — contactless heart rate and stress read from the front camera with
   remote photoplethysmography (rPPG), refined by a quantized 1D CNN running on
   the Hexagon NPU.
2. **Workout** — rep counting and cardio tracking that uses **no camera at all**.
   Push-ups, sit-ups, squats, jumping jacks and three barbell lifts are counted
   from the accelerometer; elliptical runs as manual timed mode; running tracks
   duration, distance, route and pace from GPS plus an accelerometer cadence.

Everything runs on the device. No frames, motion samples or location traces
leave the phone.

> **Reference device: iQOO 15** — Snapdragon 8 Elite Gen 5 (`SM8850`), board
> `canoe`, Android 16, Hexagon HTP arch v81. The AI refinement path is tuned for
> this SoC and the heart dashboard is gated to it (see
> [Device support](#-device-support)).

---

## Features

### Heart dashboard (camera)

- Contactless: no wearable, front camera only
- 30 FPS CameraX analysis pipeline with MediaPipe Face Landmarker ROI tracking
- Native C++ signal chain: Arm Neon SIMD, Hamming window, KissFFT → raw HR
- AI refinement: INT8 1D CNN via **Qualcomm SNPE on the Hexagon NPU**
- Stress estimate from heart-rate variability, plus live waveform and signal
  quality readouts
- Measurement history persisted on device

### Workout dashboard (sensors, no camera)

- **Automatic rep counting** — push-ups, sit-ups/crunches, squats, jumping jacks.
  Ported from [LuckyTheCookie/FitTrack](https://github.com/LuckyTheCookie/FitTrack)
  (hysteresis peak detector).
- **Barbell lifts** — deadlift, overhead press, bench press. Ported from
  [jvangore31/RepCapture](https://github.com/jvangore31/RepCapture)
  (low-pass/high-pass lift detector).
- **Elliptical** — manual timed mode. You start and stop it; it reports elapsed
  time and nothing it cannot measure.
- **Running** — duration, distance, live pace and a route trace.
- **AI running** — the same run tracking, but the session auto-starts and
  auto-pauses from your stride cadence.
- Per-exercise sensor axis, threshold and cooldown constants, with a calibration
  pass before reps are accepted.

---

## Tech stack

| Layer | Choice |
|---|---|
| UI | Jetpack Compose (Material 3), MVVM + `StateFlow` |
| Camera | CameraX `ImageAnalysis` |
| Face / ROI | MediaPipe Face Landmarker (`tasks-vision`) |
| Native DSP | C++17, `arm_neon.h`, KissFFT |
| AI refinement | Qualcomm SNPE, quantized `.dlc`, Hexagon HTP |
| Sensors | `SensorManager` (accelerometer), `LocationManager` |
| Math | Apache Commons Math |

Notable versions from `app/build.gradle.kts`: Compose BOM `2023.10.01`,
CameraX `1.3.0`, MediaPipe `tasks-vision:0.10.0`,
`kotlinx-coroutines-android:1.7.3`, `commons-math3:3.6.1`.

---

## Architecture

```
             HEART DASHBOARD                         WORKOUT DASHBOARD
   ┌──────────────────────────────┐        ┌──────────────────────────────────┐
   │ CameraX  (30 fps, RGBA_8888) │        │ Accelerometer  (SENSOR_DELAY_GAME)│
   └───────────────┬──────────────┘        └────────────────┬─────────────────┘
                   ▼                                        ▼
   ┌──────────────────────────────┐        ┌──────────────────────────────────┐
   │ MediaPipe Face Landmarker    │        │ RepCountEngine                   │
   │ forehead + cheek ROI         │        │  FitTrack hysteresis  /          │
   └───────────────┬──────────────┘        │  RepCapture high-pass            │
                   ▼                       └────────────────┬─────────────────┘
   ┌──────────────────────────────┐                        ▼
   │ Green-channel ROI sampling   │        ┌──────────────────────────────────┐
   │ zero-allocation IntArray     │        │ RunTracker (GPS + cadence)       │
   └───────────────┬──────────────┘        │ distance · pace · route          │
                   ▼                       └────────────────┬─────────────────┘
   ┌──────────────────────────────┐                        ▼
   │ C++ circular buffer          │        ┌──────────────────────────────────┐
   │ Arm Neon DSP + FFT (KissFFT) │        │ Compose UI                       │
   └───────────────┬──────────────┘        │ history · route polyline         │
                   ▼                       └──────────────────────────────────┘
   ┌──────────────────────────────┐
   │ SNPE DLC · INT8              │
   │ Hexagon NPU (HTP)            │
   └───────────────┬──────────────┘
                   ▼
   ┌──────────────────────────────┐
   │ Compose UI                   │
   └──────────────────────────────┘
```

Both dashboards are reachable from a bottom navigation bar in `MainActivity`;
neither depends on the other, and denying camera permission leaves the workout
dashboard fully usable.

---

## Project structure

```
Nadi/
├── app/
│   ├── libs/snpe-release.aar            # SNPE runtime (see Prerequisites)
│   ├── build.gradle.kts                 # namespace/appId: com.nadi.health
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   ├── face_landmarker.task     # MediaPipe face model
│       │   └── rppg_model.dlc           # INT8 model for Hexagon HTP
│       ├── cpp/
│       │   ├── CMakeLists.txt           # target "nadi", Neon + 16KB pages
│       │   ├── native-lib.cpp           # JNI bridge
│       │   ├── signal_processor.cpp/.h  # Neon SIMD, windowing
│       │   └── kiss_fft.c/.h            # FFT
│       └── java/com/nadi/health/
│           ├── MainActivity.kt          # tab nav: Heart / Workout
│           ├── analysis/                # HRVAnalyzer, VitalsAnalyzer, SignalQuality
│           ├── camera/CameraManager.kt
│           ├── core/                    # DeviceCapability, NativeSignalProcessor
│           ├── data/MeasurementHistory.kt
│           ├── imu/                     # CardioImu, RespirationImu, TremorAnalyzer
│           ├── ml/PulseML.kt            # SNPE/DLC HTP inference
│           ├── ui/                      # MainScreen, WorkoutScreen, RunScreen, ImuSection
│           ├── viewmodel/               # HeartRateViewModel, WorkoutViewModel, ImuViewModel
│           ├── vision/FaceTracker.kt
│           └── workout/                 # Exercises, RepCountEngine, RunTracker
├── tools/convert_tflite_to_dlc.sh       # TFLite → SNPE DLC conversion
├── index.html                           # project landing page
└── RULES.md                             # hard-won constraints, read before editing
```

---

## Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 26+ (Oreo); project compiles against SDK 36
- NDK + CMake 3.22.1
- An arm64 device — this project builds `arm64-v8a` only
- **Qualcomm SNPE SDK** for the NPU runtime AAR (not redistributable here):
  ```bash
  cp $SNPE_ROOT/lib/android/snpe-release.aar app/libs/
  ```
  Without the AAR, Gradle prints a warning, the build still succeeds, and
  `PulseML` throws `Iqoo15NpuUnavailableException` at startup — by design, since
  there is no CPU/GPU inference fallback.

## Build & install

```bash
git clone https://github.com/devansh0703/Nadi
cd Nadi

# Build (RAM-constrained flags used during development)
./gradlew :app:assembleDebug --no-daemon --no-parallel -x lint \
  -Dorg.gradle.jvmargs=-Xmx1536m --console=plain

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.nadi.health android.permission.CAMERA
adb shell am start -n com.nadi.health/.MainActivity
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Regenerating the DLC model

`app/src/main/assets/rppg_model.dlc` is already committed. To rebuild it from
the TFLite source model (`app/src/main/ml/rppg_model.tflite`):

```bash
export SNPE_ROOT=/path/to/snpe-sdk
./tools/convert_tflite_to_dlc.sh
# -> app/src/main/assets/rppg_model.dlc  (INT8, Hexagon HTP)
```

---

## Usage

### Heart tab

1. Grant camera permission.
2. Center your face in the preview; a mesh appears once the face is locked.
3. Hold still. The signal-acquisition phase runs for a few seconds.
4. Heart rate and stress populate and then update live.

Good light, a steady face, and an uncovered forehead get you there fastest.

### Workout tab

1. Pick an exercise from the grouped list (*Automatic rep counting*,
   *Barbell lifts*, *Manual & running*).
2. Read the carry hint — sensor placement is what makes the count work.
3. Start the set. The rep counter calibrates on the first samples and then
   counts through each detected peak.
4. Finish to save the set to history (last 20 sessions).

Running asks for location permission when a run starts, never at launch.

---

## How it works

### Camera rPPG

Blood volume changes with every heartbeat, which changes how much green light
the skin absorbs. MediaPipe locates forehead and cheek landmarks; those ROI
pixels are sampled into a pre-allocated `IntArray` (no per-frame allocation, no
GC pauses in the 30 FPS loop) and reduced to a green-channel average. The native
layer keeps a rolling 300-sample buffer and runs mean/variance normalisation,
a Hamming window and an FFT to find the dominant frequency in the 0.75–3.0 Hz
band (45–180 BPM). The quantized DLC model then cleans motion artifacts out of
that estimate on the Hexagon NPU.

### Sensor rep counting (FitTrack port)

The accelerometer is sampled at roughly 33 Hz. The first 15 samples calibrate a
baseline; samples pass through a 3-sample moving average; the detector tracks
`delta = |smoothed − baseline|`. A rep is registered when `delta` crosses above
the exercise threshold, peaks above `threshold × 1.2`, and then falls back under
`threshold × 0.4` — gated by a per-exercise cooldown so a bounce is not counted
twice. Thresholds and axes are per exercise: push-ups/sit-ups on Z, squats and
jumping jacks on Y.

### Barbell lifts (RepCapture port)

A single-pole low-pass filter (`alpha = 0.8`) tracks gravity, and the linear
acceleration is the residual `current − gravity`. The lift phase is detected
from the residual crossing on the exercise's axis, with a longer cooldown to
match the slower tempo of a heavy set.

### Running

Distance accrues by haversine between location fixes on the best available
provider (GPS, falling back to network), rejecting fixes worse than 35 m
accuracy. The route polyline holds up to 5000 points. Cadence comes from the
accelerometer: a step is a threshold crossing above 11.5 m/s² with a minimum
250 ms interval, counted over a 10 s window. In AI running, a sustained stride
starts the session and its absence pauses it — elapsed time does not accumulate
while paused.

---

## Device support

The **workout dashboard is sensor-only and runs anywhere** the app installs.

The **heart dashboard is gated to the iQOO 15** (`DeviceCapability.isSnapdragon8850`).
On any other device `MainScreen` renders an unsupported-device screen instead of
the camera pipeline, and `PulseML` refuses to start rather than silently dropping
to a slower backend. There is intentionally no CPU or GPU SNPE fallback:
`setCpuFallbackEnabled(false)` is set, NNAPI is not used, and inference is
NPU-or-nothing.

---

## Troubleshooting

**Camera shows nothing**
Check the permission: `adb shell pm grant com.nadi.health android.permission.CAMERA`.

**`PulseML` logs an error / app shows the NPU error screen**
The app needs both the DLC asset and the SNPE AAR, on a device that actually
exposes the Hexagon NPU.

```bash
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E "dlc|libnadi"
adb shell getprop ro.soc.model                     # expect SM8850 on iQOO 15
adb shell ls /dev/fastrpc-adsp /sys/class/adsprpc  # NPU device nodes
adb logcat -s PulseML
```

A healthy start logs:
`PulseML online on iQOO 15 Hexagon NPU: backend=SNPE-DLC/HTP, …`

**Reps are not counted**
Rep counting is sensitive to carry. Put the phone where the hint says and keep
it there for the whole set — swapping pockets mid-set changes the baseline.

**Run distance looks wrong**
Location accuracy governs this. Indoors, GPS degrades and the tracker may fall
back to network fixes or report that location is unavailable; the workout itself
still records.

---

## References

- [FitTrack](https://github.com/LuckyTheCookie/FitTrack) — accelerometer rep counting
- [RepCapture](https://github.com/jvangore31/RepCapture) — barbell lift detection
- [MediaPipe Face Landmarker](https://developers.google.com/mediapipe/solutions/vision/face_landmarker)
- [Qualcomm SNPE SDK](https://www.qualcomm.com/developer/software/snpe-sdk)
- [Arm Neon intrinsics](https://developer.arm.com/architectures/instruction-sets/intrinsics/)
- [KissFFT](https://github.com/mborgerding/kissfft)
- [Remote photoplethysmography review (2022)](https://ieeexplore.ieee.org/)
- [PhysNet: Deep learning for rPPG](https://arxiv.org/abs/1905.02419)

---

## Author

**devansh0703**
- GitHub: [@devansh0703](https://github.com/devansh0703)

Originally built for the Arm AI Developer Challenge.

## License

No license file is included yet, so the default all-rights-reserved applies.
Add a `LICENSE` before reusing this code elsewhere.
