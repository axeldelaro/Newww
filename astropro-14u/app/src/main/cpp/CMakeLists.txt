cmake_minimum_required(VERSION 3.22.1)
project("astropro_native" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(
    astropro_native
    SHARED
    raw_demosaic.cpp
    star_engine.cpp
    stacker_engine.cpp
    astro_lab.cpp
    astro_io.cpp
    astro_jni.cpp
)

target_compile_options(
    astropro_native
    PRIVATE
    -O3
    -ffast-math
    -fvisibility=hidden
    -Wall
    -Wextra
    -Wno-unused-parameter
)

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(
    astropro_native
    PRIVATE
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
)
