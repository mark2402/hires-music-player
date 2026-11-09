#!/usr/bin/env bash
# Rebuilds libffmpegJNI.so for armeabi-v7a, arm64-v8a, x86, and x86_64.
# Requires the Android NDK (r26 or newer) and CMake. FFmpeg 6.0 is cloned
# next to the JNI sources and is not committed.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
NDK_PATH="${NDK_PATH:-${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/android-sdk}/ndk/28.2.13676358}}"
HOST_PLATFORM="${HOST_PLATFORM:-linux-x86_64}"
ANDROID_API="${ANDROID_API:-26}"
if [[ ! -d "$NDK_PATH" ]]; then
  echo "NDK not found at $NDK_PATH. Set NDK_PATH." >&2
  exit 1
fi
if [[ ! -d "$ROOT/src/main/jni/ffmpeg/.git" ]]; then
  git clone --depth 1 --branch release/6.0 https://github.com/FFmpeg/FFmpeg.git "$ROOT/src/main/jni/ffmpeg"
fi
chmod +x "$ROOT/src/main/jni/build_ffmpeg.sh"
"$ROOT/src/main/jni/build_ffmpeg.sh" "$ROOT/src/main" "$NDK_PATH" "$HOST_PLATFORM" "$ANDROID_API" flac alac
for ABI in armeabi-v7a arm64-v8a x86 x86_64; do
  BUILD="$ROOT/build-native-$ABI"
  cmake -S "$ROOT/src/main/jni" -B "$BUILD" \
    -DCMAKE_BUILD_TYPE=Release \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$ANDROID_API" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_PATH/build/cmake/android.toolchain.cmake" \
    -DANDROID_STL=c++_static
  cmake --build "$BUILD" --parallel
  mkdir -p "$ROOT/src/main/jniLibs/$ABI"
  cp "$BUILD/libffmpegJNI.so" "$ROOT/src/main/jniLibs/$ABI/libffmpegJNI.so"
  "$NDK_PATH/toolchains/llvm/prebuilt/$HOST_PLATFORM/bin/llvm-strip" --strip-unneeded \
    "$ROOT/src/main/jniLibs/$ABI/libffmpegJNI.so"
done
echo "Native libraries updated under src/main/jniLibs."
