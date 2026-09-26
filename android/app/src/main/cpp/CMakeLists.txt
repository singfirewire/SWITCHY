# SWITCHY — native audio layer (เฟส 2)
#
# ไฟล์ในโฟลเดอร์นี้จะถูกคอมไพล์ "เฉพาะ" เมื่อสั่งบิลด์ด้วย -PwithNativeAudio=true
# และเครื่องที่บิลด์ต้องมี Android NDK ติดตั้งไว้:
#     sdkmanager "ndk;27.0.12077973"
#
# ทำไมแยกออก: Oboe + libopus ทำให้ APK ใหญ่ขึ้นและบิลด์ต้องใช้ NDK (~1 GB)
# เฟส 1 จึงใช้ AudioRecord/AudioTrack ของ Android (AAudio path) ซึ่ง latency ดีอยู่แล้ว
cmake_minimum_required(VERSION 3.22.1)
project(switchy_audio CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# AGP บิลด์แบบ debug ไม่ใส่ออปติไมซ์ให้ -> libopus จะช้ามาก (และเสียงอาจกระตุก)
# ต้องใช้ add_compile_options (ไม่ใช่ CMAKE_C_FLAGS_DEBUG เพราะ AGP toolchain เขียนทับทีหลัง)
# วางไว้ "ก่อน" FetchContent/add_subdirectory เพื่อให้ Oboe/Opus ได้ flag นี้ด้วย
add_compile_options($<$<CONFIG:Debug>:-O2>)

include(FetchContent)

# ---------------------------------------------------------------- Oboe (AAudio)
# ถ้ามี third_party/oboe อยู่ในเครื่องแล้ว ให้ใช้ตัวนั้น (บิลด์แบบไม่ต้องต่อเน็ต)
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/third_party/oboe/CMakeLists.txt")
    add_subdirectory(third_party/oboe)
else()
    FetchContent_Declare(oboe
        GIT_REPOSITORY https://github.com/google/oboe.git
        GIT_TAG 1.9.3
        GIT_SHALLOW TRUE)
    FetchContent_MakeAvailable(oboe)
endif()

# ---------------------------------------------------------------- libopus
# ลำดับการหา libopus:
#   1) third_party/opus  (ก็อปมาไว้ในเครื่อง = บิลด์ไม่ต้องต่อเน็ต)
#   2) ดึงจาก GitHub ด้วย FetchContent (ค่าเริ่มต้น)
#   3) บอก path เองด้วย -DSWITCHY_OPUS_DIR=<ที่เก็บ lib+include>
#   4) ไม่มีเลย -> คอมไพล์โดยไม่มี Opus (SWITCHY_NO_OPUS) แล้วฝั่ง Kotlin ใช้ PCM แทน
set(SWITCHY_OPUS_AVAILABLE OFF)

if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/third_party/opus/CMakeLists.txt")
    set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
    set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
    set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
    add_subdirectory(third_party/opus)
    set(SWITCHY_OPUS_AVAILABLE ON)
    message(STATUS "SWITCHY: ใช้ libopus จาก third_party/opus")
elseif(DEFINED SWITCHY_OPUS_DIR)
    find_library(OPUS_LIB opus PATHS ${SWITCHY_OPUS_DIR} REQUIRED)
    include_directories(${SWITCHY_OPUS_DIR}/include)
    set(SWITCHY_OPUS_AVAILABLE ON)
    message(STATUS "SWITCHY: ใช้ libopus จาก ${SWITCHY_OPUS_DIR}")
else()
    set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
    set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
    FetchContent_Declare(opus
        GIT_REPOSITORY https://github.com/xiph/opus.git
        GIT_TAG v1.6.1
        GIT_SHALLOW TRUE)
    FetchContent_MakeAvailable(opus)
    set(SWITCHY_OPUS_AVAILABLE ON)
    message(STATUS "SWITCHY: ดึง libopus v1.6.1 ด้วย FetchContent")
endif()

# ---------------------------------------------------------------- target
add_library(switchy_audio SHARED
    opus_jni.cpp
    oboe_engine.cpp)

target_compile_options(switchy_audio PRIVATE -Wall -Wextra)

if(NOT SWITCHY_OPUS_AVAILABLE)
    target_compile_definitions(switchy_audio PRIVATE SWITCHY_NO_OPUS=1)
else()
    target_link_libraries(switchy_audio PRIVATE opus)
endif()

target_link_libraries(switchy_audio PRIVATE
    oboe
    log
    android)
