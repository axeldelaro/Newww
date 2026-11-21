#!/usr/bin/env bash
set -euo pipefail

# Fetches all 100% offline AI models & native dependencies to bundle directly inside:
#  - stepquest-companion (Gemma-3 1B Q4_K_M + llama.cpp)
#  - aura-companion (Gemma-3 1B Q4_K_M + Sherpa-ONNX Whisper FR + Piper French TTS + MediaPipe Face Landmarker & Selfie Segmenter)
# After building the APKs, ZERO post-install downloads are needed on Android.

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CACHE_DIR="${AI_CACHE_DIR:-/tmp/offline-ai-cache}"
mkdir -p "$CACHE_DIR/extracted"

if [ ! -d "$CACHE_DIR/llama.cpp" ]; then
  git clone --depth 1 https://github.com/ggml-org/llama.cpp.git "$CACHE_DIR/llama.cpp"
fi

if [ ! -f "$CACHE_DIR/gemma-3-1b-it-Q4_K_M.gguf" ]; then
  curl -fSL -o "$CACHE_DIR/gemma-3-1b-it-Q4_K_M.gguf" \
    "https://huggingface.co/unsloth/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf"
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

# 1. StepQuest Companion assets
mkdir -p "$ROOT_DIR/stepquest-companion/app/src/main/assets/models"
cp -f "$CACHE_DIR/gemma-3-1b-it-Q4_K_M.gguf" \
  "$ROOT_DIR/stepquest-companion/app/src/main/assets/models/gemma-3-1b-it-Q4_K_M.gguf"

# 2. Aura Live Companion assets + AAR
AURA_APP="$ROOT_DIR/aura-companion/app"
mkdir -p "$AURA_APP/libs" \
         "$AURA_APP/src/main/assets/models" \
         "$AURA_APP/src/main/assets/vision" \
         "$AURA_APP/src/main/assets/voice/tts" \
         "$AURA_APP/src/main/assets/voice/whisper"

cp -f "$CACHE_DIR/sherpa-onnx-2.0.2.aar" "$AURA_APP/libs/sherpa-onnx-2.0.2.aar"
cp -f "$CACHE_DIR/gemma-3-1b-it-Q4_K_M.gguf" "$AURA_APP/src/main/assets/models/gemma-3-1b-it-Q4_K_M.gguf"
cp -f "$CACHE_DIR/face_landmarker.task" "$AURA_APP/src/main/assets/vision/face_landmarker.task"
cp -f "$CACHE_DIR/selfie_segmenter.tflite" "$AURA_APP/src/main/assets/vision/selfie_segmenter.tflite"

rm -rf "$AURA_APP/src/main/assets/voice/espeak-ng-data"
cp -r "$CACHE_DIR/extracted/vits-piper-fr_FR-siwis-medium-int8/espeak-ng-data" \
      "$AURA_APP/src/main/assets/voice/espeak-ng-data"

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

echo "All offline AI models ready for StepQuest & Aura Companion!"
