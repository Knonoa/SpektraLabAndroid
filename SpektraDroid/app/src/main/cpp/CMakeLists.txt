cmake_minimum_required(VERSION 3.22.1)
project(spektradroid CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
get_filename_component(SPK_ENGINE "${CMAKE_CURRENT_LIST_DIR}/../../../../../engine" ABSOLUTE)

# The Android dependency is an immutable snapshot: A7 V / A7R VI and Nikon HE/HE*.
# Pass the path explicitly so changing the environment invalidates CMake's cache.
if(DEFINED ENV{SPEKTRALAB_LIBRAW_SOURCE} AND NOT "$ENV{SPEKTRALAB_LIBRAW_SOURCE}" STREQUAL "")
    set(SPEKTRALAB_LIBRAW_SOURCE "$ENV{SPEKTRALAB_LIBRAW_SOURCE}" CACHE PATH "Pinned LibRaw snapshot" FORCE)
endif()
if(NOT SPEKTRALAB_LIBRAW_SOURCE)
    get_filename_component(SPEKTRALAB_LIBRAW_SOURCE
        "${CMAKE_CURRENT_LIST_DIR}/../../../../build/deps/LibRaw-snapshot202611" ABSOLUTE)
endif()
if(NOT EXISTS "${SPEKTRALAB_LIBRAW_SOURCE}/libraw/libraw.h")
    message(FATAL_ERROR "RAW import needs the pinned LibRaw snapshot. Run bash tools/setup_libraw.sh.")
endif()
find_package(Git REQUIRED)
execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${SPEKTRALAB_LIBRAW_SOURCE}" rev-parse HEAD
    OUTPUT_VARIABLE _libraw_commit OUTPUT_STRIP_TRAILING_WHITESPACE)
execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${SPEKTRALAB_LIBRAW_SOURCE}" rev-parse "HEAD^{tree}"
    OUTPUT_VARIABLE _libraw_tree OUTPUT_STRIP_TRAILING_WHITESPACE)
execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${SPEKTRALAB_LIBRAW_SOURCE}" status --porcelain --ignored
    OUTPUT_VARIABLE _libraw_changes OUTPUT_STRIP_TRAILING_WHITESPACE)
if(NOT _libraw_commit STREQUAL "0b87f5c907d7ee0e7641dd1b6a6c9140b9451922"
   OR NOT _libraw_tree STREQUAL "fb93233a46ec9a96fb17f2dbab160f5fa27a0ab6"
   OR NOT _libraw_changes STREQUAL "")
    message(FATAL_ERROR "LibRaw source differs from the validated snapshot; run tools/setup_libraw.sh with a clean dependency path.")
endif()
file(GLOB_RECURSE _libraw_sources CONFIGURE_DEPENDS
        "${SPEKTRALAB_LIBRAW_SOURCE}/src/*.cpp")
list(FILTER _libraw_sources EXCLUDE REGEX "/[^/]*_ph\\.cpp$")
add_library(spk_libraw STATIC ${_libraw_sources})
target_include_directories(spk_libraw PUBLIC "${SPEKTRALAB_LIBRAW_SOURCE}")
target_compile_definitions(spk_libraw PUBLIC LIBRAW_NODLL PRIVATE LIBRAW_NOTHREADS USE_ZLIB)
target_link_libraries(spk_libraw PUBLIC z)
set_target_properties(spk_libraw PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_compile_options(spk_libraw PRIVATE -w)

add_library(spektra SHARED
        spektra_engine.cpp
        filters.cpp
        cam16.cpp
        overscan.cpp
        android_image_decoder.cpp
        "${SPK_ENGINE}/host/image_io.cpp"
        "${SPK_ENGINE}/host/tiff_ifd.cpp"
        "${SPK_ENGINE}/host/white_balance.cpp"
        "${SPK_ENGINE}/host/platform.cpp"
        "${SPK_ENGINE}/host/stb_impl.cpp"
        "${SPK_ENGINE}/src/io/raw_decoder.cpp"
        "${SPK_ENGINE}/src/io/raw_headroom.cpp"
        jni_bridge.cpp)

target_include_directories(spektra PRIVATE "${SPK_ENGINE}/host"
        "${SPK_ENGINE}/src" "${SPK_ENGINE}/third_party/stb")

target_link_libraries(spektra
        android
        jnigraphics
        z
        log
        spk_libraw)


# match spektrafilm's numerics: no fast-math reassociation of the colour math
target_compile_options(spektra PRIVATE -O2 -fno-fast-math)
