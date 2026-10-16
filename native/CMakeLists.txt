# Astratto Engine - native core. Builds on its own: nothing here knows of any game.
#   cmake -S astratto/native -B astratto/native/build && cmake --build astratto/native/build --config Release
# On Windows without CMake, build.bat does the same with the Visual C++ compiler directly.
cmake_minimum_required(VERSION 3.16)
project(astratto VERSION 1.1 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(astratto SHARED
    src/api/c_api.cpp
    src/core/engine.cpp
    src/world/voxel_world.cpp
    src/nav/pathfinder.cpp
    src/perception/sight.cpp
    src/render/chunk_mesher.cpp)
target_include_directories(astratto PUBLIC include PRIVATE src)
target_compile_definitions(astratto PRIVATE ASTRATTO_BUILD)
set_target_properties(astratto PROPERTIES PREFIX "" CXX_VISIBILITY_PRESET hidden
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/../bin" LIBRARY_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/../bin")

if(MSVC)
    # route costs are compared with the reference search for equality of outcome: precise floats, no contraction
    target_compile_options(astratto PRIVATE /W4 /fp:precise)
else()
    target_compile_options(astratto PRIVATE -Wall -Wextra -ffp-contract=off)
    find_package(Threads REQUIRED)
    target_link_libraries(astratto PRIVATE Threads::Threads)
endif()

add_executable(astratto_selftest tests/selftest.cpp)
target_link_libraries(astratto_selftest PRIVATE astratto)
set_target_properties(astratto_selftest PROPERTIES RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/../bin")

enable_testing()
add_test(NAME astratto_selftest COMMAND astratto_selftest)
