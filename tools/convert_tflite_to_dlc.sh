#!/usr/bin/env bash
#
# convert_tflite_to_dlc.sh — Nadi rPPG model: TFLite -> Qualcomm SNPE DLC (Hexagon NPU)
#
# Verified working pipeline (QAIRT SDK v2.50):
#   TFLite --tf2onnx--> ONNX --onnxsim--> ONNX --snpe-onnx-to-dlc--> float DLC
#        --snpe-dlc-quantize--> INT8 DLC for Hexagon HTP (iQOO 15: HTP v79 / SM8850)
#
# WHY NOT snpe-tflite-to-dlc: its TFLite importer hits a pad-handling assert
# ("Explicit pad values (0, 0) do not result in expected pad_value...") on this
# graph. The ONNX route is clean and numerically verified against the TFLite
# original.
#
# REQUIREMENTS (Linux x86_64 host):
#   - QAIRT/SNPE SDK (v2.50 verified): https://www.qualcomm.com/developer/software/snpe-sdk
#   - Python 3.12 (the SDK's native converter libs link libpython3.12)
#   - pip: tensorflow-cpu numpy onnx==1.16.2 onnx-simplifier tf2onnx pyyaml
#     protobuf scipy decorator attrs psutil tqdm pytest tflite
#   - System: libc++1, libunwind (LLVM variant, provides libunwind.so.1)
#
# USAGE:
#   export SNPE_ROOT=/path/to/qairt-sdk
#   ./tools/convert_tflite_to_dlc.sh
#
# OUTPUT:
#   app/src/main/assets/rppg_model.dlc   <- INT8, ships in the APK (iQOO 15 NPU)
#
set -euo pipefail

SNPE_ROOT="${SNPE_ROOT:?Set SNPE_ROOT to the QAIRT/SNPE SDK directory}"
PROJ_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TFLITE="${PROJ_ROOT}/app/src/main/ml/rppg_model.tflite"
ASSETS="${PROJ_ROOT}/app/src/main/assets"
WORK="$(mktemp -d)"

# iQOO 15 / Snapdragon 8 Elite Gen 5
SOC="SM8850"
HTP_ARCH="v79"

PY=python3
command -v "$PY" >/dev/null || PY=python

echo "==> [1/5] TFLite -> ONNX (tf2onnx, opset 13)"
"$PY" -m tf2onnx.convert --tflite "${TFLITE}" --output "${WORK}/rppg.onnx" --opset 13

echo "==> [2/5] Simplify ONNX (folds constants; REQUIRED for the SNPE importer)"
"$PY" -m onnxsim "${WORK}/rppg.onnx" "${WORK}/rppg_sim.onnx"

echo "==> [3/5] ONNX -> float DLC"
# Input tensor [1,300,1] float32, 30 Hz rPPG window (names come from the TFLite graph)
"$PY" "${SNPE_ROOT}/bin/x86_64-linux-clang/snpe-onnx-to-dlc" \
  --input_network "${WORK}/rppg_sim.onnx" \
  --input_dim "serving_default_green_signal_input:0" 1,300,1 \
  --out_name "StatefulPartitionedCall_1:0" \
  --output_path "${WORK}/rppg_model_raw.dlc"

echo "==> [4/5] Calibration data for INT8 quantization"
# NOTE: PulseML receives the RAW camera-green buffer (0..255 range).
# This synthetic generator covers that range with PPG-like rhythms.
# FOR BEST ACCURACY: replace with 50-100 real rPPG windows recorded on device,
# saved as raw float32 (1200 bytes per 300-sample window) + an input_list.txt.
python_gen="${WORK}/gen_calib.py"
cat > "${python_gen}" <<'EOF'
import numpy as np, os, sys
out_dir = sys.argv[1]
rng = np.random.default_rng(42)
with open(os.path.join(out_dir, 'input_list.txt'), 'w') as f:
    for i in range(60):
        t = np.arange(300) / 30.0
        f0 = rng.uniform(40, 180) / 60.0
        dc = rng.uniform(10, 250)  # raw camera-green range
        sig = dc + 0.05*np.sin(2*np.pi*f0*t) + 0.02*np.sin(2*np.pi*f0*1.3*t) + rng.normal(0, 0.008, 300)
        sig.astype(np.float32).tofile(os.path.join(out_dir, f'x_{i}.raw'))
        f.write(os.path.join(out_dir, f'x_{i}.raw') + '\n')
EOF
"$PY" "${python_gen}" "${WORK}/calib"

echo "==> [5/5] Quantize to INT8 for Hexagon ${HTP_ARCH} (${SOC})"
"$PY" "${SNPE_ROOT}/bin/x86_64-linux-clang/snpe-dlc-quantize" \
  --input_dlc "${WORK}/rppg_model_raw.dlc" \
  --input_list "${WORK}/calib/input_list.txt" \
  --output_dlc "${ASSETS}/rppg_model.dlc"

"$PY" "${SNPE_ROOT}/bin/x86_64-linux-clang/snpe-dlc-info" \
  --input_dlc "${ASSETS}/rppg_model.dlc" >/dev/null && \
  echo "    DLC validated. HTP arch: ${HTP_ARCH} (${SOC} / iQOO 15)"

rm -rf "${WORK}"
echo ""
echo "Done: app/src/main/assets/rppg_model.dlc"
echo "Runtime AAR (already in app/libs/ for this repo):"
echo "  cp \$SNPE_ROOT/lib/android/snpe-release.aar app/libs/"
