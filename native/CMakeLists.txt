# Astratto Engine - the native core, its self-test and the example C++ gameplay module. Builds on its own: nothing
# here knows of any game. From the engine directory:
#   cmake -S native -B native/build-<platform> -DCMAKE_BUILD_TYPE=Release -DASTRATTO_PLATFORM=<platform>
#   cmake --build native/build-<platform> --config Release
#   native/bin/<platform>/astratto_selftest
# (./gradlew buildNative does exactly this off Windows; on Windows build.bat does the same with Visual C++ directly.)
cmake_minimum_required(VERSION 3.16)
project(astratto VERSION 2.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT ASTRATTO_PLATFORM)
    if(WIN32)
        set(_os windows)
    elseif(APPLE)
        set(_os macos)
    else()
        set(_os linux)
    endif()
    if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64|ARM64")
        set(_arch arm64)
    else()
        set(_arch x64)
    endif()
    set(ASTRATTO_PLATFORM "${_os}-${_arch}")
endif()
set(ASTRATTO_OUT "${CMAKE_CURRENT_SOURCE_DIR}/bin/${ASTRATTO_PLATFORM}")

# every .cpp under src/ is part of the library
file(GLOB_RECURSE ASTRATTO_SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/src/*.cpp")
add_library(astratto SHARED ${ASTRATTO_SOURCES})
target_include_directories(astratto PUBLIC include PRIVATE src)
target_compile_definitions(astratto PRIVATE ASTRATTO_BUILD)
set_target_properties(astratto PROPERTIES PREFIX "" CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
if(NOT WIN32)
    # libastratto.so / libastratto.dylib, the names the Java bindings look for
    set_target_properties(astratto PROPERTIES PREFIX "lib")
endif()

find_package(Threads REQUIRED)
target_link_libraries(astratto PRIVATE Threads::Threads ${CMAKE_DL_LIBS})
if(WIN32)
    target_link_libraries(astratto PRIVATE user32 winmm)
endif()

if(MSVC)
    # route costs are compared with a reference search for equality of outcome: precise floats, no contraction
    target_compile_options(astratto PRIVATE /W4 /wd4324 /fp:precise /utf-8)
else()
    target_compile_options(astratto PRIVATE -Wall -Wextra -ffp-contract=off)
endif()

add_executable(astratto_selftest tests/selftest.cpp tests/services.cpp)
target_link_libraries(astratto_selftest PRIVATE astratto)

# the example gameplay module a game can copy: a library the engine loads (include/astratto/astratto_module.h)
add_library(astratto_example_module SHARED modules/example/example_module.cpp)
target_include_directories(astratto_example_module PRIVATE include)
if(WIN32)
    set_target_properties(astratto_example_module PROPERTIES PREFIX "")
else()
    set_target_properties(astratto_example_module PROPERTIES PREFIX "lib")
endif()

foreach(t astratto astratto_selftest astratto_example_module)
    set_target_properties(${t} PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY "${ASTRATTO_OUT}" LIBRARY_OUTPUT_DIRECTORY "${ASTRATTO_OUT}"
        RUNTIME_OUTPUT_DIRECTORY_RELEASE "${ASTRATTO_OUT}" LIBRARY_OUTPUT_DIRECTORY_RELEASE "${ASTRATTO_OUT}"
        RUNTIME_OUTPUT_DIRECTORY_DEBUG "${ASTRATTO_OUT}" LIBRARY_OUTPUT_DIRECTORY_DEBUG "${ASTRATTO_OUT}")
endforeach()
if(NOT WIN32)
    # the self-test finds its library beside itself
    set_target_properties(astratto_selftest PROPERTIES BUILD_RPATH "$ORIGIN" INSTALL_RPATH "$ORIGIN")
    if(APPLE)
        set_target_properties(astratto_selftest PROPERTIES BUILD_RPATH "@loader_path")
    endif()
endif()

enable_testing()
add_test(NAME astratto_selftest COMMAND astratto_selftest WORKING_DIRECTORY "${ASTRATTO_OUT}")
