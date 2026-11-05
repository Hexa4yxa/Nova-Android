cmake_minimum_required(VERSION 3.31.6)
project(nova-image LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "NOVA Image supports only arm64-v8a in the first Android test")
endif()

# Pinned upstream checkout is done by CI. Never download code at CMake configure time.
set(SD_SRC "${CMAKE_CURRENT_LIST_DIR}/../../../../stable-diffusion.cpp")
if(NOT EXISTS "${SD_SRC}/include/stable-diffusion.h")
    message(FATAL_ERROR "Missing pinned stable-diffusion.cpp source. Run CI checkout; do not use a fake engine.")
endif()

set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_WEBP OFF CACHE BOOL "" FORCE)
set(SD_WEBM OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_GGML_LIB OFF CACHE BOOL "" FORCE)
set(SD_OPENCL OFF CACHE BOOL "" FORCE)
set(SD_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory("${SD_SRC}" "${CMAKE_CURRENT_BINARY_DIR}/sd" EXCLUDE_FROM_ALL)

add_library(nova-image SHARED nova_image_jni.cpp)
target_include_directories(nova-image PRIVATE "${SD_SRC}/include")
target_link_libraries(nova-image PRIVATE stable-diffusion android log)
target_compile_options(nova-image PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
target_link_options(nova-image PRIVATE "LINKER:--exclude-libs,ALL")
