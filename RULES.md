# Nadi — Project Rules

Hard-won constraints from building this app.
**Read this before touching sensors, SNPE, the measurement pipeline, or the workout counters.**

---

## 1. Platform facts (iQOO 15, verified on-device)

- SoC SM8850, board `canoe`, Android 16, **Hexagon HTP arch v81** — the DLC
  target identifier is `HTP_V81_SM8850_8MB` (NOT v79). Page size 4096
  (the 16KB-page hypothesis was eliminated for this unit, but the 16KB link
  flag is kept because Play policy requires it for native code).
- The 3D ultrasonic fingerprint scanner has **no public API**: raw data stays in
  the TEE (NDSS 2018), the Biometric HAL is auth-only, and no heart-rate/PPG
  sensor types exist in `dumpsys sensorservice`. Realme Lab's fingerprint HR
  (realme 9 Pro+/GT6T/14 Pro) is an OEM-internal feature — no SDK, and vivo
  ships nothing equivalent. **Camera rPPG is the only no-contact physiological
  signal on this phone. Do not chase fingerprint HR again.**
- Sensor inventory lives in `dumpsys sensorservice`. Public sensors used here:
  accelerometer/gyro (lsm6dsvx + vsen_gyro, up to ~480 Hz effective on this
  unit), light, proximity, magnetometer (qmc6309h), significant motion,
  stationary/motion detect (one-shot triggers), step counter/detector (vivo
  `pedometer`), and lsm6dsvx `sensor_temperature` (die temp, NOT body temp).
  Everything with type ≥ 66537 is vivo-private and off-limits.

---

## 2. SNPE / Hexagon NPU (error 73 = three stacked causes)

1. **Manifest**: `<uses-native-library>` for `libcdsprpc.so` (required=true),
   `libadsprpc.so`, `libsdsprpc.so` (required=false) — must be **inside
   `<application>`**, not `<manifest>` (AAPT rejects it there).
2. **Packaging**: `useLegacyPackaging = true` in `app/build.gradle.kts`.
   Hexagon Skel libs must be extracted to disk; the DSP-side loader cannot read
   inside the APK zip.
3. **PD session**: `.setRuntimeCheckOption(UNSIGNEDPD_CHECK)` +
   `.setUnsignedPD(true)` — fixes
   `Expected pdSession (0) does not match actual pdSession (1)`.
4. **Do NOT call `setDebugEnabled(true)`** — triggers the same PD bug AND
   disables accelerated HTP init. Never enable for release.

- The working builder chain lives in `PulseML.initSnpe` — copy it, don't
  reinvent it.
- Success line: `PulseML online on iQOO 15 Hexagon NPU: backend=SNPE-DLC/HTP …`
- **There is no fallback backend.** `setCpuFallbackEnabled(false)`, no TFLite,
  no NNAPI, no Adreno GPU delegate. If the DLC or the NPU is missing, `PulseML`
  throws `Iqoo15NpuUnavailableException` and the UI shows a hard error. Do not
  "fix" this by adding a slower path — the whole point is NPU-or-nothing.
- On-device validator toolkit at `/data/local/tmp/snpe/`. The validator
  "calculator test" always fails on stock (no testsig, needs root) — that
  failure is EXPECTED; `Runtime DSP Prerequisites: Present` is the pass signal.

---

## 3. Sensors — API rules

- **`SENSOR_DELAY_FASTEST` requires `HIGH_SAMPLING_RATE_SENSORS`** (normal
  permission) since Android 12 — otherwise `SecurityException` on register.
  Always declare it AND wrap registration in try/catch with a fallback rate.
- **`SecurityException` can fire even for "normal" permissions** in restricted
  modes. Every `registerListener` call path needs try/catch.
- **Step counts: use the public pedometer API only**
  (`TYPE_STEP_COUNTER` / `TYPE_STEP_DETECTOR`, gated by runtime
  `ACTIVITY_RECOGNITION`). Never compute steps/gait from raw IMU — the vendor
  pedometer exists and is better. The custom autocorrelation gait analyzer was
  removed for exactly this reason.
- **One-shot trigger sensors** (SMD / stationary / motion detect) latch and fire
  spuriously — they reported motion while the phone lay flat on a table. Never
  use them for UX tips; measured RMS from the recorder is honest.
- **The front ambient-light sensor lies about room brightness** when the phone
  faces the user (the face shadows it: 51 lx in a bright room). Lux-based "dim
  lighting" tips are false positives — camera lighting quality is judged from
  the ROI signal itself (`SignalQualityIndicator`).
- `sensor_temperature` = chip die temperature. Never present it as body
  temperature.
- vivo-private sensor types (66537+) are undocumented and unavailable to apps —
  do not attempt to read them.

---

## 4. Workout dashboard — sensor-only rep counting

- **The workout dashboard uses no camera.** Rep counting is accelerometer-only
  and must stay that way; do not "improve" it by adding a vision path.
- **Two detectors, deliberately different:**
  - `WorkoutKind.REP_SENSOR` → FitTrack hysteresis peak detector. Constants
    (`axis`, `threshold`, `cooldownMs`) are copied per exercise from
    `LuckyTheCookie/FitTrack` and are **tuning, not magic** — changing them
    silently changes accuracy. Push-ups/sit-ups use Z; squats/jumping jacks
    use Y.
  - `WorkoutKind.BARBELL` → RepCapture low-pass/high-pass lift detector
    (`alpha = 0.8`). RepCapture originally shipped bench press only; the
    deadlift and overhead-press entries are the same math applied to a
    different axis and threshold.
- **Calibrate before counting.** A short baseline pass (15 samples) is required;
  reps are not accepted until the engine reports calibrated. Removing
  calibration makes the first few reps phantom counts.
- **Carry matters more than the algorithm.** Every exercise carries a `hint`
  describing where the phone goes. Do not drop the hints — the counters are
  honest only when the phone is held the documented way.
- **Reps must be cooldown-gated.** Without the per-exercise cooldown a single
  bounce registers two reps on high-tempo exercises.
- **`RunTracker` must not fake data.** Reject location fixes worse than 35 m
  accuracy. If no provider yields a usable fix, call
  `onLocationUnavailable(message)` and keep recording the rest of the workout —
  never invent distance or pace.
- **Pause means paused.** In AI running and manual timed mode, elapsed time must
  not accumulate while the session is inactive.
- **Only these exercise kinds exist:** `REP_SENSOR`, `BARBELL`, `TIMED`, `RUN`.
  Elliptical is `TIMED` (elapsed time only) because a rep count for it would be
  fabricated. Running is `RUN` and is explicitly *not* a rep counter.

---

## 5. Signal-processing rules (learned the hard way)

- **Never pre-filter a low-frequency band with a first-difference.** A
  differentiator attenuates 0.3 Hz by ~16× and starves amplitude gates — this
  silently killed respiration for a full iteration. Mean-subtract for DC
  removal instead.
- **Never take |magnitude| of a zero-mean oscillation** for band analysis:
  rectification doubles the perceived frequency. Project onto the gravity unit
  vector (per-sample EMA gravity direction) instead — this also makes the
  measurement slant/tilt-invariant.
- **6 s windows cannot resolve respiration** (~0.17 Hz resolution against a
  0.15–0.50 Hz band). IMU breathing needs ≥10 s windows. The camera respiration
  tile was removed because it could never meet the 10 s goal.
- **Fingertip PPG calibrations don't transfer to facial reflectance.** Kanva's
  `110 − 25·R` pins face-derived R at the 90 clamp floor. Face R sits ~0.75–1.1;
  calibration must be rescaled and anchored to logged ratios. Log raw R every
  window (`adb logcat -s Vitals`).
- The HeartPy (van Gent 2019) pipeline is the reference for beat detection:
  zero-phase band-pass → square → 0.6 s MA threshold → contiguous-run argmax →
  60–125% median rejection. Zero-phase matters (no group delay).
- SCG: 15–45 Hz on the best-variance axis. GCG (gyro, 1–40 Hz) is immune to
  linear-motion artifacts — cross-check both and fuse IBIs when they agree.
- **DSP must run on `Dispatchers.Default`**, never the main thread
  (FFT/Welch over 15 s @ 500 Hz is real work). Sensor callbacks stay lean.

---

## 6. UX rules from user feedback

- **First readings ≤ 10 s** — gate extended vitals on 180 samples (6 s), not
  300. Values are sticky: never blank back to "--" on one bad window.
- **Don't show features that don't work.** Camera respiration was removed after
  it refused to lock; a "--" tile is worse than no tile.
- **No nagging tips.** "Very dim lighting" in bright light and "motion detected"
  on a table both destroyed trust. Tips must come from measurements proven to
  correlate with the failure (ROI quality), not from proxies that can lie.
- **Don't auto-analyze things the user didn't ask for** (the walking "analysis"
  chip was removed; steps stay a readout, not a measurement mode).
- Keep UI tiles wider than tall. Use "--" only before the first confident value.
- **Camera permission denial must not brick the app.** The workout dashboard has
  to stay reachable; `MainScreen` shows a permission notice, not a dead screen.

---

## 7. Device / build workflow

- Build (RAM-constrained):
  ```bash
  ./gradlew :app:assembleDebug --no-daemon --no-parallel -x lint \
    -Dorg.gradle.jvmargs=-Xmx1536m --console=plain
  ```
- `adb uninstall` only on a signature change; otherwise:
  ```bash
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  adb shell pm grant com.nadi.health android.permission.CAMERA
  adb shell am start -n com.nadi.health/.MainActivity
  ```
- CAMERA is requested at launch. ACTIVITY_RECOGNITION is requested on first
  Steps interaction. Location is requested when a run starts, never at launch.
- `app/.cxx`, `app/build`, `build` and `.gradle` are **not** gitignored — clear
  them before archiving so stale `.so` files don't ship.
- Verify every change on-device via logcat: `PulseML online` (NPU OK), absence
  of `FATAL EXCEPTION`, and the log tag of the feature you touched.

---

## 8. Hard boundaries (do not reopen)

- **iQOO 15-only for the heart dashboard**: `DeviceCapability.isSnapdragon8850`
  gate, no CPU/GPU SNPE fallback, `Iqoo15NpuUnavailableException` path. The
  workout dashboard is sensor-only and stays available on other devices.
- Camera rPPG is the only no-contact vitals path. IMU modes (chest respiration,
  sternum SCG/GCG HR+HRV, tremor test) are contact modes.
- Fingerprint biometrics: authentication only, no physiological access.
- Estimated BP is a wellness heuristic (Chandrasekaran TBME 2013 anchor), not a
  medical measurement. Tremor bands are screening information, not diagnosis.
- Nothing measured here is a medical device output. Don't word UI or docs as if
  it were.
