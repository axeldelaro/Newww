#!/usr/bin/env bash
set -euo pipefail

# Usage: bash scripts/fetch_companions_ai.sh [all|stepquest|aura]
TARGET="${1:-all}"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CACHE_DIR="${AI_CACHE_DIR:-/tmp/offline-ai-cache}"
mkdir -p "$CACHE_DIR/extracted"

if [ ! -d "$CACHE_DIR/llama.cpp" ]; then
  git clone --depth 1 https://github.com/ggml-org/llama.cpp.git "$CACHE_DIR/llama.cpp"
fi

if [ "$TARGET" = "all" ] || [ "$TARGET" = "stepquest" ]; then
  if [ ! -f "$CACHE_DIR/gemma-3-1b-it-Q4_K_M.gguf" ]; then
    curl -fSL -o "$CACHE_DIR/gemma-3-1b-it-Q4_K_M.gguf" \
      "https://huggingface.co/unsloth/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf"
  fi
  mkdir -p "$ROOT_DIR/stepquest-companion/app/src/main/assets/models"
  rm -f "$ROOT_DIR/stepquest-companion/app/src/main/assets/models/"*.gguf
  cp -fl "$CACHE_DIR/gemma-3-1b-it-Q4_K_M.gguf" \
    "$ROOT_DIR/stepquest-companion/app/src/main/assets/models/gemma-3-1b-it-Q4_K_M.gguf" 2>/dev/null || \
  cp -f "$CACHE_DIR/gemma-3-1b-it-Q4_K_M.gguf" \
    "$ROOT_DIR/stepquest-companion/app/src/main/assets/models/gemma-3-1b-it-Q4_K_M.gguf"
fi

if [ "$TARGET" = "all" ] || [ "$TARGET" = "aura" ]; then
  if [ ! -f "$CACHE_DIR/Llama-3.2-3B-Instruct-IQ3_M.gguf" ]; then
    curl -fSL -o "$CACHE_DIR/Llama-3.2-3B-Instruct-IQ3_M.gguf" \
      "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-IQ3_M.gguf"
  fi

  if [ ! -f "$CACHE_DIR/face_landmarker.task" ]; then
    curl -fSL -o "$CACHE_DIR/face_landmarker.task" \
      "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task"
  fi

  if [ ! -f "$CACHE_DIR/selfie_segmenter.tflite" ]; then
    curl -fSL -o "$CACHE_DIR/selfie_segmenter.tflite" \
      "https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_segmenter/float16/latest/selfie_segmenter.tflite"
  fi

  if [ ! -f "$CACHE_DIR/sherpa-onnx-2.0.2.aar" ]; then
    curl -fSL -o "$CACHE_DIR/sherpa-onnx-2.0.2.aar" \
      "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.2/sherpa-onnx-2.0.2.aar"
  fi

  if [ ! -d "$CACHE_DIR/extracted/vits-piper-fr_FR-siwis-medium-int8" ]; then
    curl -fSL -o "$CACHE_DIR/vits-piper-fr_FR-siwis-medium-int8.tar.bz2" \
      "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-fr_FR-siwis-medium-int8.tar.bz2"
    tar -xjf "$CACHE_DIR/vits-piper-fr_FR-siwis-medium-int8.tar.bz2" -C "$CACHE_DIR/extracted"
  fi

  if [ ! -d "$CACHE_DIR/extracted/vits-piper-fr_FR-upmc-medium-int8" ]; then
    curl -fSL -o "$CACHE_DIR/vits-piper-fr_FR-upmc-medium-int8.tar.bz2" \
      "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-fr_FR-upmc-medium-int8.tar.bz2"
    tar -xjf "$CACHE_DIR/vits-piper-fr_FR-upmc-medium-int8.tar.bz2" -C "$CACHE_DIR/extracted"
  fi

  if [ ! -d "$CACHE_DIR/extracted/sherpa-onnx-whisper-tiny" ]; then
    curl -fSL -o "$CACHE_DIR/sherpa-onnx-whisper-tiny.tar.bz2" \
      "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2"
    tar -xjf "$CACHE_DIR/sherpa-onnx-whisper-tiny.tar.bz2" -C "$CACHE_DIR/extracted"
  fi

  AURA_APP="$ROOT_DIR/aura-companion/app"
  mkdir -p "$AURA_APP/libs" \
           "$AURA_APP/src/main/assets/models" \
           "$AURA_APP/src/main/assets/vision" \
           "$AURA_APP/src/main/assets/voice/tts" \
           "$AURA_APP/src/main/assets/voice/whisper"

  # Remove old v1 Gemma model & old unpacked espeak folder
  rm -f "$AURA_APP/src/main/assets/models/"*.gguf
  rm -rf "$AURA_APP/src/main/assets/voice/espeak-ng-data"

  cp -f "$CACHE_DIR/sherpa-onnx-2.0.2.aar" "$AURA_APP/libs/sherpa-onnx-2.0.2.aar"
  cp -fl "$CACHE_DIR/Llama-3.2-3B-Instruct-IQ3_M.gguf" "$AURA_APP/src/main/assets/models/Llama-3.2-3B-Instruct-IQ3_M.gguf" 2>/dev/null || \
  cp -f "$CACHE_DIR/Llama-3.2-3B-Instruct-IQ3_M.gguf" "$AURA_APP/src/main/assets/models/Llama-3.2-3B-Instruct-IQ3_M.gguf"

  cp -f "$CACHE_DIR/face_landmarker.task" "$AURA_APP/src/main/assets/vision/face_landmarker.task"
  cp -f "$CACHE_DIR/selfie_segmenter.tflite" "$AURA_APP/src/main/assets/vision/selfie_segmenter.tflite"

  # Package espeak-ng-data as ONE single fast zip (< 5 MB, unzips in ~0.25s on Android!)
  python3 - "$CACHE_DIR/extracted/vits-piper-fr_FR-siwis-medium-int8/espeak-ng-data" "$AURA_APP/src/main/assets/voice/espeak-ng-data.zip" <<'PY'
import os, sys, zipfile
src_dir, out_zip = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out_zip, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
    for root, _, files in os.walk(src_dir):
        for fn in files:
            full = os.path.join(root, fn)
            rel = os.path.relpath(full, src_dir)
            zf.write(full, rel)
print(f"Packed espeak-ng-data.zip: {os.path.getsize(out_zip)/1024/1024:.2f} MB")
PY

  cp -f "$CACHE_DIR/extracted/vits-piper-fr_FR-siwis-medium-int8/fr_FR-siwis-medium.onnx" \
        "$AURA_APP/src/main/assets/voice/tts/fr_FR-siwis-medium.onnx"
  cp -f "$CACHE_DIR/extracted/vits-piper-fr_FR-siwis-medium-int8/tokens.txt" \
        "$AURA_APP/src/main/assets/voice/tts/siwis-tokens.txt"

  cp -f "$CACHE_DIR/extracted/vits-piper-fr_FR-upmc-medium-int8/fr_FR-upmc-medium.onnx" \
        "$AURA_APP/src/main/assets/voice/tts/fr_FR-upmc-medium.onnx"
  cp -f "$CACHE_DIR/extracted/vits-piper-fr_FR-upmc-medium-int8/tokens.txt" \
        "$AURA_APP/src/main/assets/voice/tts/upmc-tokens.txt"

  cp -f "$CACHE_DIR/extracted/sherpa-onnx-whisper-tiny/tiny-encoder.int8.onnx" \
        "$AURA_APP/src/main/assets/voice/whisper/tiny-encoder.int8.onnx"
  cp -f "$CACHE_DIR/extracted/sherpa-onnx-whisper-tiny/tiny-decoder.int8.onnx" \
        "$AURA_APP/src/main/assets/voice/whisper/tiny-decoder.int8.onnx"
  cp -f "$CACHE_DIR/extracted/sherpa-onnx-whisper-tiny/tiny-tokens.txt" \
        "$AURA_APP/src/main/assets/voice/whisper/tiny-tokens.txt"
fi

echo "Offline AI models v2 ready for target: $TARGET"
