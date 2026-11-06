cmake_minimum_required(VERSION 3.22.1)
project(spektradroid CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(spektra SHARED
        spektra_engine.cpp
        filters.cpp
        cam16.cpp
        jni_bridge.cpp)

target_link_libraries(spektra
        android
        jnigraphics
        log)

# match spektrafilm's numerics: no fast-math reassociation of the colour math
target_compile_options(spektra PRIVATE -O2 -fno-fast-math)
