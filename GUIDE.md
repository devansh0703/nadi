# GUIDE — Running Qwen3-4B on the iQOO 15 Hexagon NPU

**Audience:** any coding agent (or human) who needs to get an LLM running on the
Hexagon NPU of this device class, or who has to debug it when it stops working.

**Status:** ✅ **VERIFIED WORKING** on 2026-09-27 on iQOO 15 (I2501, SM8850,
Android 16), generating text on the CDSP/HTP with the QAIRT 2.50 runtime.

Read this whole file before changing anything in `app/src/main/jniLibs`,
`QwenEngine`, `QwenModelManager`, or the JSON configs. Most of the failure modes
below cost hours because the error message is a lie.

---

## 0. TL;DR — the two things that actually matter

1. **`ADSP_LIBRARY_PATH` must be exactly ONE directory.**
   The DSP-side fastRPC loader does **not** split it on `:`. If you write
   `/path/a:/path/b` it tries to open the literal path
   `/path/a:/path/b/cdsp/./libQnnHtpV81Skel.so` and fails with `errno 2`, which
   surfaces upstream as `Failed to load skel, error: 1002` →
   `Transport layer setup failed: 14001` → `QNN_DEVICE_ERROR_INVALID_CONFIG`.
   **`14001` is almost always this.** Use one dir; put everything in it.
2. **The Hexagon skeleton (`libQnnHtpV81Skel.so`) must match the host QNN
   library version.** Host `libQnnHtp.so` 2.50 + vendor/device Skel 2.34 fails.
   Extract the matched Skel from the same SDK zip as the host libs.

Everything else (SoC model number, PD session, `use-mmap`, etc.) is secondary.

---

## 1. Hardware / version facts (do not guess these)

| Thing | Value on iQOO 15 (I2501) |
|---|---|
| SoC model (`ro.soc.model`) | `SM8850` — Snapdragon 8 Elite Gen 5 |
| Hexagon arch | **v81** (`dsp_arch: "v81"`) |
| QNN SoC enum | `QNN_SOC_MODEL_SM8850 = 87` (`include/QNN/QnnTypes.h`) |
| DLC target id | `HTP_V81_SM8850_8MB` (**not** v79, not SM8750) |
| Device firmware QNN | `v2.34.3.250704181830_119508` (`/vendor/lib64/hw/libQnnHtp.so`) |
| Android | 16 / SDK 36, abi `arm64-v8a` only |
| `ro.board.platform` | `canoe` |

> ⚠️ SM8750 (Snapdragon 8 Elite **Gen 4**) bundles do **not** load here. Use
> `SM8850` everywhere — bundle, ctx-bins, `htp_backend_ext_config.json`.

### The model bundle used here
`qwen3_4b_instruct_2507-geniex_qairt-w4a16-qualcomm_snapdragon_8_elite_gen5_for_galaxy/`

```
genie_config.json              # Genie dialog/engine config (see §4)
htp_backend_ext_config.json    # HTP device + memory config (see §5)
tokenizer.json  merges.txt  vocab.json  added_tokens.json
tokenizer_config.json  special_tokens_map.json
config.json                    # HF config, Qwen3ForCausalLM
metadata.json                  # tool_versions.qairt = 2.45.0.260326154327
sample_prompt.txt
part1_of_4.bin  778,174,464   # context binaries, concatenated at load
part2_of_4.bin  666,509,312
part3_of_4.bin  666,562,560
part4_of_4.bin  1,067,687,936
```

**This bundle is TEXT-ONLY.** `metadata.json` says
`"use_case": "Text Generation"`, `config.json` says `Qwen3ForCausalLM`, and there
is no vision encoder. It **cannot** do image input (no food-photo recognition,
no lab-report OCR, no frame-based lighting coaching). Those need a real
Qwen3-VL bundle — keep the vision code paths but gate them.

### The SDK
`/home/devansh/Downloads/v2.50.0.260828.zip` → top-level `qairt/2.50.0.260828/`.

> The **extracted** copy at `/home/devansh/Downloads/qairt/2.50.0.260828/` is
> missing `lib/hexagon-*/unsigned/` — always extract the Skels from the **zip**.

Host binaries live in `bin/aarch64-android/` (`genie-t2t-run`, `genie-app`,
`qnn-net-run`, `qnn-platform-validator`, …) and host libs in
`lib/aarch64-android/` (`libGenie.so` 1.20.0, `libQnnHtp.so`, `libQnnSystem.so`,
`libQnnHtpV81Stub.so`, `libQnnHtpPrepare.so`, `libQnnHtpNetRunExtensions.so`, …).

DSP-side skeletons live in `lib/hexagon-v81/unsigned/`:

```
libQnnHtpV81Skel.so            13,546,372   ← the one that matters
libQairtHtpV81Skel.so          13,546,380
libSnpeHtpV81Skel.so           14,016,192
libQnnHtpV81.so                13,947,992
libQairtHtpV81.so              14,484,640
libQnnSystem.so                   822,408
libQnnNetRunDirectV81Skel.so    3,058,416
libQnnHexagonSkel_dspApp.so       260,952
libQnnHtpNetRunExtensions.so    1,387,812
libCalculator_skel.so                5,744
libqnnhtpv81.cat / libqairthtpv81.cat / libsnpehtpv81.cat   ← signatures
```

---

## 2. Minimal working repro (host CLI, no app)

Everything goes in **one** directory on the device:

```bash
D=/data/local/tmp/qwen3
Z=/home/devansh/Downloads/v2.50.0.260828.zip
S=/home/devansh/Downloads/qairt/2.50.0.260828

adb shell mkdir -p $D

# host tools + host QNN libs
adb push $S/bin/aarch64-android/genie-t2t-run $D/
adb push $S/lib/aarch64-android/libGenie.so \
         $S/lib/aarch64-android/libQnnHtp.so \
         $S/lib/aarch64-android/libQnnSystem.so \
         $S/lib/aarch64-android/libQnnHtpV81Stub.so \
         $S/lib/aarch64-android/libQnnHtpPrepare.so \
         $S/lib/aarch64-android/libQnnHtpNetRunExtensions.so $D/

# the MATCHED DSP skeleton set (must come from the zip)
rm -rf /tmp/v81skel && mkdir -p /tmp/v81skel
unzip -o -j $Z 'qairt/2.50.0.260828/lib/hexagon-v81/unsigned/*' -d /tmp/v81skel
adb push /tmp/v81skel/libQnnHtpV81Skel.so /tmp/v81skel/libQairtHtpV81Skel.so \
         /tmp/v81skel/libQnnHtpV81.so /tmp/v81skel/libQnnHtpNetRunExtensions.so \
         /tmp/v81skel/libqnnhtpv81.cat $D/

# the model bundle
adb push "<bundle>"/. $D/
```

Run (**note the single-dir `ADSP_LIBRARY_PATH`**):

```bash
adb shell "cd $D && \
  LD_LIBRARY_PATH=$D \
  ADSP_LIBRARY_PATH=$D \
  ./genie-t2t-run -c genie_config.json -p '<|im_start|>system
You are a helpful assistant.<|im_end|>
<|im_start|>user
What is HRV?<|im_end|>
<|im_start|>assistant
'"
```

Expected:

```
Using libGenie.so version 1.20.0
[INFO]  "Using create From Binary"
[INFO]  "Allocated total size = 343933440 across 8 buffers"
[PROMPT]: ...
[BEGIN]: HRV stands for Heart Rate Variability, ... [END]
```

`Allocated total size = 343933440 across 8 buffers` is the tell that the binary
context was loaded; `[END]` marks EOS.

### ChatML is mandatory

`genie-t2t-run -p` sends the string raw. Feeding `What is 2+2?` gives a
repetition loop. Always send the Qwen3 ChatML template, ending **without** a
closing token so the model continues as the assistant:

```
<|im_start|>system
{system}<|im_end|>
<|im_start|>user
{user}<|im_end|>
<|im_start|>assistant
```
(`bos/eos` = 151643 / 151645; also set in the dialog context block.)

---

## 3. Understanding the failures (so you don't chase ghosts)

| Symptom | Real cause |
|---|---|
| `Failed to create device: 14001` / `Device Creation failure` | **Host libs and Skel from different versions**, or **`ADSP_LIBRARY_PATH` contains `:`**. |
| `Failed to load skel, error: 1002`, `remote_handle_open ... _dom=cdsp (errno 2)` | Skel not found by the DSP loader → check the single-dir rule first. |
| `Unable to find a valid system interface` / `Resource manager not able to create QnnSystemInterface` | Host `libGenie.so`/QNN stubs are newer than `libQnnSystem.so` on `LD_LIBRARY_PATH`. Use the **SDK's own** `libQnnSystem.so`, not the vendor one. |
| SNPE/`snpe-platform-validator` says `Runtime DSP Prerequisites: Absent` while READY is Present | Same path bug — fix `ADSP_LIBRARY_PATH` to one dir and it flips to Present. |
| `Expected pdSession (0) does not match actual pdSession (1)` (in-app, SNPE) | Unsigned PD needed: `UNSIGNEDPD_CHECK` + `setUnsignedPD(true)`. HTP defaults to unsigned PD for Genie/QNN. |
| Repeated garbage output | Missing ChatML template, or bad sampler (see §4). |
| Gatsby/validator "calculator util test" failures | **Expected** on stock retail devices; not a real failure (RULES.md §2). |

### How to actually see the error (the 14001 message is not enough)

```bash
adb logcat -c
adb shell "cd $D && LD_LIBRARY_PATH=$D ADSP_LIBRARY_PATH=$D ./genie-t2t-run -c genie_config.json -p 'hi'"
adb logcat -d | grep -iE 'adsprpc|fastrpc|remote_handle|skel|dsp|qnn'
```
The fastRPC lines tell you the literal path it tried to open — that is how the
`:`-in-`ADSP_LIBRARY_PATH` bug was found.

---

## 4. `genie_config.json` (dialog + engine)

```json
{
  "dialog": {
    "version": 1, "type": "basic",
    "context": { "version": 1, "size": 4096, "n-vocab": 151936,
                 "bos-token": 151643, "eos-token": 151645 },
    "sampler": { "version": 1, "seed": 42, "temp": 0.8, "top-k": 40, "top-p": 0.95 },
    "tokenizer": { "version": 1, "path": "tokenizer.json" },
    "engine": {
      "version": 1, "n-threads": 3,
      "backend": {
        "version": 1, "type": "QnnHtp",
        "QnnHtp": { "version": 1, "use-mmap": true, "spill-fill-bufsize": 0,
                    "mmap-budget": 0, "poll": true, "cpu-mask": "0xe0",
                    "kv-dim": 128, "allow-async-init": false,
                    "pos-id-dim": 64, "rope-theta": 5000000 },
        "extensions": "htp_backend_ext_config.json"
      },
      "model": { "version": 1, "type": "binary",
                 "binary": { "version": 1, "ctx-bins": [
                   "part1_of_4.bin","part2_of_4.bin",
                   "part3_of_4.bin","part4_of_4.bin" ] } }
    }
  }
}
```

Notes:
- `cpu-mask: "0xe0"` = cores 5–7 (the performance cluster).
- `n-threads: 3` matches the 3 unmasked cores.
- `kv-dim: 128` = `head_dim`. `pos-id-dim: 64`. `rope-theta: 5000000` — all from
  `config.json`; **do not change** or the ctx-bins mismatch.
- `size: 4096` is the KV context; the bundle was built for 4096.
- **Sampler is the knob for output quality.** `temp 0.8 / top-k 40 / top-p 0.95`
  is creative; for grounded/templated wellness answers in the app we write a
  per-request config with `temp ≈ 0.6`, `top-k ≈ 20`, `top-p ≈ 0.85` and a fixed
  `seed`, which is what fixed the repetition.
- The `ctx-bins` are concatenated *in listed order* — the order matters.

## 5. `htp_backend_ext_config.json` (HTP device + memory)

The bundle's version (this is what we run with):

```json
{"devices":[{"soc_model":87,"dsp_arch":"v81",
             "cores":[{"core_id":0,"perf_profile":"burst","rpc_control_latency":100}]}],
 "memory":{"mem_type":"shared_buffer"},
 "context":{"weight_sharing_enabled":true}}
```

- `soc_model: 87` = `QNN_SOC_MODEL_SM8850`. Getting this wrong is an easy 14001.
- `dsp_arch: "v81"` — v79/v73 will not load on this SoC.
- `perf_profile: "burst"` for lowest latency; `"sustained_high_performance"` for
  long generations. `rpc_control_latency: 100` (µs).
- `mem_type: "shared_buffer"` is required for a 4B model on a 12/16 GB phone —
  `dma_buf`/`ion` will not fit.
- The SDK's own example (`examples/Genie/configs/htp_backend_ext_config.json`)
  is a **minimal** variant (`"dsp_arch": "v81", "htp_arch": ..., "soc_id": 87`).
  Both work once §0.1 is fixed; the bundle's full form is preferred.
- `htp_arch` / `soc_id` (SDK example naming) and `dsp_arch` / `soc_model`
  (bundle naming) are the same knobs with different spellings.
- You can also select the PD session here (`"pd_session": "unsigned"`) and
  restrict HTP to specific cores with `cores` / `htp_cores`. On this device the
  default (unsigned PD) already works.

---

## 6. Bundling into the Android app (what `Nadi` does)

An app on `targetSdk ≥ 29` may **not** `exec()` a binary from its writable data
dir (W^X / noexec). It **may** exec from `nativeLibraryDir`. So:

1. **Executables and libs go in `app/src/main/jniLibs/arm64-v8a/` named
   `lib*.so`** so AGP extracts them with `jniLibs { useLegacyPackaging = true }`
   (already set in `app/build.gradle.kts`):
   - `libgenie_t2t_run.so` = `bin/aarch64-android/genie-t2t-run`
   - `libGenie.so`, `libQnnHtp.so`, `libQnnSystem.so`, `libQnnHtpV81Stub.so`,
     `libQnnHtpPrepare.so`, `libQnnHtpNetRunExtensions.so`
   - `libQnnHtpV81Skel.so` (**from the SDK zip**, matched to the host libs)
   They land in `applicationInfo.nativeLibraryDir`, which becomes **both**
   `LD_LIBRARY_PATH` and `ADSP_LIBRARY_PATH` — one directory, satisfying §0.1
   by construction. (This is exactly why the app path works.)
2. **Model weights go in `filesDir`**, not assets, because a 3.1 GB asset
   cannot be loaded from the APK: `QwenModelManager` imports them once from
   `assets/qwen3-4b-2507/` if bundled, otherwise from an external import dir
   (documented adb push). See §7.
3. `AndroidManifest.xml` must already declare:
   ```xml
   <uses-native-library android:name="libcdsprpc.so" android:required="true" />
   <uses-native-library android:name="libadsprpc.so" android:required="false" />
   ```
   Without these the app's linker namespace cannot resolve the CDSP RPC shim.
4. Manifest also needs `android:extractNativeLibs="true"`-equivalent (legacy
   packaging) — the Skel must be a real file on disk, the DSP loader cannot read
   it out of the APK zip.

### Runtime environment the app must set
```kotlin
val libDir = context.applicationInfo.nativeLibraryDir
val workDir = File(context.filesDir, "qwen3-4b-2507")   // holds the JSONs + bins
pb.directory(workDir)
pb.environment()["LD_LIBRARY_PATH"]   = libDir           // single dir
pb.environment()["ADSP_LIBRARY_PATH"] = libDir           // single dir  ← §0.1
pb.command(File(libDir, "libgenie_t2t_run.so").absolutePath,
           "-c", "genie_config.json",
           "-p", chatMlPrompt,
           "--log", "warn")
```
Because `genie-t2t-run` resolves `tokenizer.json`, `*.bin`, and the extension
config **relative to cwd**, the process working directory must be the model dir.

### One-time model import (adb, for a device without the assets)
```bash
adb shell run-as com.nadi.health mkdir -p files/qwen3-4b-2507
# (only works if the app is debuggable) — or push to the external import dir:
adb shell mkdir -p /sdcard/Nadi/models/qwen3-4b-2507
adb push "<bundle>"/. /sdcard/Nadi/models/qwen3-4b-2507/
```
Then in-app: **AI → Settings → Import model from /sdcard/Nadi/models**. The
manager copies the ~3.1 GB into `filesDir` once; after that it is fully offline.

---

## 7. Verifying it is on the NPU, not the CPU

- `Allocated total size = ...` and a real `[BEGIN] … [END]` are the primary proof.
- Cross-check with the platform validator, **from a single dir**:
  ```bash
  cd $D && LD_LIBRARY_PATH=$D ADSP_LIBRARY_PATH=$D ./qnn-platform-validator --runtime dsp
  # Runtime DSP Prerequisites: Present
  ```
- During a generation, logcat shows an unsigned-PD CDSP session being opened:
  ```bash
  adb logcat | grep -iE 'unsigned PD|cdsp|adsprpc'
  # remote_session_control Unsigned PD enable 1 request for domain 100000
  ```
- Timing sanity: a 4B w4a16 model on this HTP is a few tokens/sec, not tens.
  If it is instant, something fell back.

---

## 8. Checklist for a future agent

- [ ] `adb shell getprop ro.soc.model` == `SM8850`.
- [ ] Every JSON says `soc_model: 87` / `dsp_arch: "v81"` / `SM8850`.
- [ ] Host QNN libs, `libGenie.so`, and `libQnnHtpV81Skel.so` are **all the same
      SDK version** (check `metadata.json` `tool_versions.qairt`).
- [ ] `ADSP_LIBRARY_PATH` and `LD_LIBRARY_PATH` are each **one** directory.
- [ ] `htp_backend_ext_config.json` uses `"mem_type": "shared_buffer"`.
- [ ] Prompts are ChatML-templated (see §2).
- [ ] `useLegacyPackaging = true`, `libcdsprpc.so` in `uses-native-library`.
- [ ] No CPU/GPU fallback anywhere — `QwenNpuUnavailableException` on failure,
      same philosophy as `PulseML` (RULES.md §8).
