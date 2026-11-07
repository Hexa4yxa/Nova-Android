#!/usr/bin/env bash
# Independent Adreno library: preserve original CPU rendering without changing SD1.5 model.
# Link against Khronos libOpenCL.so but do not package the ICD loader. The phone
# provides its own accessible vendor libOpenCL.so, declared in Android manifest.
set -euo pipefail
: "${ANDROID_HOME:?Android SDK is required}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CMAKE="$ANDROID_HOME/cmake/3.31.6/bin/cmake"
NDK="$ANDROID_HOME/ndk/29.0.13113456"
HEADERS="$ROOT/opencl-headers"
LOADER="$ROOT/opencl-loader"
SOURCE="$ROOT/android/nova-image/src/main/cpp"
GPU_OUT="$ROOT/android/gpu-standalone"
STAGING="$ROOT/android/app/src/main/jniLibs/arm64-v8a"

for item in "$CMAKE" "$NDK/build/cmake/android.toolchain.cmake" "$HEADERS/CL/cl.h" \
 "$LOADER/CMakeLists.txt" "$ROOT/android/stable-diffusion.cpp/include/stable-diffusion.h"; do
  if [ ! -e "$item" ]; then echo "Missing pinned OpenCL input: $item" >&2; exit 1; fi
done

"$CMAKE" -S "$LOADER" -B "$LOADER/build" -G Ninja \
 -DCMAKE_BUILD_TYPE=Release \
 -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
 -DOPENCL_ICD_LOADER_HEADERS_DIR="$HEADERS" \
 -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=33 \
 -DOPENCL_ICD_LOADER_BUILD_TESTING=OFF
"$CMAKE" --build "$LOADER/build" --parallel 2

LINK_LIBRARY="$(find "$LOADER/build" -type f -name 'libOpenCL.so' | head -1)"
test -n "$LINK_LIBRARY"
test -s "$LINK_LIBRARY"

"$CMAKE" -S "$SOURCE" -B "$GPU_OUT" -G Ninja \
 -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
 -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=33 \
 -DCMAKE_BUILD_TYPE=Release -DNOVA_EXPERIMENTAL_OPENCL=ON \
 -DOpenCL_INCLUDE_DIR="$HEADERS" -DOpenCL_LIBRARY="$LINK_LIBRARY"
"$CMAKE" --build "$GPU_OUT" --target nova-image-gpu --parallel 2
LIBRARY="$(find "$GPU_OUT" -maxdepth 2 -type f -name 'libnova-image-gpu.so' | head -1)"
test -n "$LIBRARY"
test -s "$LIBRARY"

READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
test -x "$READELF"
"$READELF" -d "$LIBRARY" | tee "$GPU_OUT/dependencies.txt"
grep -q 'libOpenCL.so' "$GPU_OUT/dependencies.txt"

mkdir -p "$STAGING"
cp -f "$LIBRARY" "$STAGING/libnova-image-gpu.so"
test -s "$STAGING/libnova-image-gpu.so"
echo "GPU staged in the SAME NOVA APK; CPU library and model untouched."
