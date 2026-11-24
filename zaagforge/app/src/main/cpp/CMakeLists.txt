cmake_minimum_required(VERSION 3.22.1)
project(zaagforge_dsp LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(zaagforge_dsp SHARED
    music_analyzer.cpp
    hardcore_kicks.cpp
    remix_engine.cpp
    zaagforge_jni.cpp
)

target_include_directories(zaagforge_dsp PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_compile_options(zaagforge_dsp PRIVATE
    -O3
    -Wall
    -Wextra
    -Wno-unused-parameter
)

find_library(log-lib log)
target_link_libraries(zaagforge_dsp ${log-lib})
