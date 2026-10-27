cmake_minimum_required(VERSION 3.21)
project(Shunshoupai VERSION 1.2.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(Qt6 6.8 REQUIRED COMPONENTS Widgets Network)
qt_standard_project_setup()

qt_add_executable(Shunshoupai WIN32
    src/main.cpp
    src/api.h
    src/backend.h
    src/window.h
    src/window.cpp
    vendor/qrcodegen.cpp
    vendor/qrcodegen.hpp
    ../shared/core/connection.cpp
    ../shared/core/connection.h
)
target_include_directories(Shunshoupai PRIVATE vendor ../shared/core)
target_link_libraries(Shunshoupai PRIVATE Qt6::Widgets Qt6::Network)
target_compile_definitions(Shunshoupai PRIVATE
    SHUNSHOUPAI_VERSION="${PROJECT_VERSION}"
)
if(MSVC)
    target_compile_options(Shunshoupai PRIVATE /utf-8 /W4)
else()
    target_compile_options(Shunshoupai PRIVATE -Wall -Wextra)
endif()

install(TARGETS Shunshoupai RUNTIME DESTINATION .)

include(CTest)
if(BUILD_TESTING)
    add_executable(ConnectionCoreTest
        ../shared/core/connection.cpp
        ../shared/core/connection_test.cpp
    )
    target_include_directories(ConnectionCoreTest PRIVATE ../shared/core)
    if(MSVC)
        target_compile_options(ConnectionCoreTest PRIVATE /utf-8 /W4)
    endif()
    add_test(NAME ConnectionCore COMMAND ConnectionCoreTest)
endif()
