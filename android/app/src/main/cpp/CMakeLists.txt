cmake_minimum_required(VERSION 3.22.1)
project(shunshoupai_android_core LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
get_filename_component(SHARED_CORE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../shared/core" ABSOLUTE)

add_library(shunshoupai_core SHARED native_core.cpp "${SHARED_CORE}/connection.cpp")
target_include_directories(shunshoupai_core PRIVATE "${SHARED_CORE}")
target_compile_options(shunshoupai_core PRIVATE -Wall -Wextra -Werror)
