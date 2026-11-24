cmake_minimum_required(VERSION 3.22.1)
project(strikecam_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(
    strikecam_native
    SHARED
    beatmap_generator.cpp
    arcade_synth.cpp
    live_beat_tracker.cpp
    strikecam_jni.cpp
)

target_include_directories(strikecam_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_compile_options(strikecam_native PRIVATE
    -O3
    -ffast-math
    -Wall
    -Wextra
)

find_library(log-lib log)

target_link_libraries(
    strikecam_native
    ${log-lib}
)
