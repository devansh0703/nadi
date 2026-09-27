cmake_minimum_required(VERSION 3.22.1)



project(nadi LANGUAGES C CXX)

# C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)


# Main native library
add_library(nadi SHARED

        native-lib.cpp
        signal_processor.cpp
        kiss_fft.c
)

# Base optimization flags (for this target only)
target_compile_options(nadi PRIVATE
        -O3
        -ffast-math
)

# Enable NEON / architecture-specific flags safely
#
# iQOO 15: Snapdragon 8 Elite Gen 5 with Oryon cores (Armv8.2-A compatible,
# +fp16 and +dotprod mandatory for the NEON kernel paths). NNAPI/NPU is not
# reachable from native code here - SNPE/HTP handles that in PulseML.kt.
if(ANDROID)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        # 64-bit ARM: no -mfloat-abi / -mfpu!
        target_compile_options(nadi PRIVATE -march=armv8.2-a+fp16+dotprod)
        target_compile_definitions(nadi PRIVATE USE_NEON)
        # 16KB page alignment - required on Android 15/16 devices (iQOO 15)
        target_link_options(nadi PRIVATE "-Wl,-z,max-page-size=16384")
    elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
        # 32-bit ARM: these flags are valid
        target_compile_options(nadi PRIVATE
                -mfpu=neon
                -mfloat-abi=softfp
        )
        target_compile_definitions(nadi PRIVATE USE_NEON)
    endif()
endif()

# Note: the armeabi-v7a branch is kept for completeness but the project only
# builds arm64-v8a (see abiFilters in app/build.gradle.kts).

# Include directories
target_include_directories(nadi PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
)

# Link libraries
find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(nadi
        ${log-lib}
        ${android-lib}
)
