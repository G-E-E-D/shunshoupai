cmake_minimum_required(VERSION 3.21)
file(READ "${CMAKE_CURRENT_SOURCE_DIR}/../package.json" SHUNSHOUPAI_PACKAGE_JSON)
string(JSON SHUNSHOUPAI_PACKAGE_VERSION GET "${SHUNSHOUPAI_PACKAGE_JSON}" version)
if(NOT SHUNSHOUPAI_PACKAGE_VERSION MATCHES "^[0-9]+\\.[0-9]+\\.[0-9]+$")
    message(FATAL_ERROR "package.json must contain a numeric x.y.z version for Windows resources")
endif()
project(Shunshoupai VERSION ${SHUNSHOUPAI_PACKAGE_VERSION} LANGUAGES CXX)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/../package.json")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Decode selected/clipboard QR images locally, without a camera or WebView.
# Use the complete upstream release archive (GitHub-generated source archives
# omit submodule sources) and verify its published SHA-256 digest.
include(FetchContent)
if(POLICY CMP0135)
    cmake_policy(SET CMP0135 NEW)
endif()
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build application dependencies statically" FORCE)
set(ZXING_READERS ON CACHE BOOL "" FORCE)
set(ZXING_WRITERS OFF CACHE STRING "" FORCE)
set(ZXING_EXAMPLES OFF CACHE BOOL "" FORCE)
set(ZXING_EXAMPLES_QT OFF CACHE BOOL "" FORCE)
set(ZXING_UNIT_TESTS OFF CACHE BOOL "" FORCE)
set(ZXING_BLACKBOX_TESTS OFF CACHE BOOL "" FORCE)
set(ZXING_C_API OFF CACHE BOOL "" FORCE)
foreach(format 1D AZTEC DATAMATRIX MAXICODE PDF417)
    set(ZXING_ENABLE_${format} OFF CACHE BOOL "" FORCE)
endforeach()
set(ZXING_ENABLE_QRCODE ON CACHE BOOL "" FORCE)
FetchContent_Declare(zxing
    URL https://github.com/zxing-cpp/zxing-cpp/releases/download/v3.1.1/zxing-cpp-3.1.1.tar.gz
    URL_HASH SHA256=c3c02c29c0b519de7bd4e25b376e606e87f0761befd1282815642a2246613d14
)
FetchContent_MakeAvailable(zxing)
# ZXing 3.1 uses C++20 internally even though its public CMake feature is 17.
# Keep that requirement inside the decoder library; the application stays 17.
set_target_properties(ZXing PROPERTIES CXX_STANDARD 20 CXX_STANDARD_REQUIRED ON)
file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/third-party/ZXing")
configure_file("${zxing_SOURCE_DIR}/LICENSE" "${CMAKE_CURRENT_BINARY_DIR}/third-party/ZXing/LICENSE" COPYONLY)
configure_file("${CMAKE_CURRENT_SOURCE_DIR}/licenses/ZXing/libzueci-LICENSE.txt" "${CMAKE_CURRENT_BINARY_DIR}/third-party/ZXing/libzueci-LICENSE.txt" COPYONLY)
configure_file("${zxing_SOURCE_DIR}/core/src/libzueci/README.md" "${CMAKE_CURRENT_BINARY_DIR}/third-party/ZXing/libzueci-README.md" COPYONLY)
if(EXISTS "${zxing_SOURCE_DIR}/NOTICE")
    configure_file("${zxing_SOURCE_DIR}/NOTICE" "${CMAKE_CURRENT_BINARY_DIR}/third-party/ZXing/NOTICE" COPYONLY)
endif()

find_package(Qt6 6.8 REQUIRED COMPONENTS Widgets Network)
qt_standard_project_setup()

add_library(PairingQrDecoder STATIC src/qr_decoder.cpp src/qr_decoder.h)
target_include_directories(PairingQrDecoder PUBLIC src PRIVATE ../shared/core)
target_compile_features(PairingQrDecoder PRIVATE cxx_std_20)
target_link_libraries(PairingQrDecoder PUBLIC Qt6::Gui PRIVATE ZXing::ZXing)
if(MSVC)
    target_compile_options(PairingQrDecoder PRIVATE /utf-8 /W4)
endif()

qt_add_executable(Shunshoupai WIN32
    src/main.cpp
    src/api.h
    src/device_bootstrap.h
    src/qr_decoder.h
    src/pairing_input.h
    src/draft_state.h
    src/session_cookies.h
    src/session_cookies.cpp
    src/backend.h
    src/window.h
    src/window.cpp
    src/dialogs.cpp
    src/design.cpp
    src/design.h
    src/modern_calendar.cpp
    src/modern_calendar.h
    src/visual_smoke.cpp
    src/peer_smoke.cpp
    vendor/qrcodegen.cpp
    vendor/qrcodegen.hpp
    ../shared/core/connection.cpp
    ../shared/core/connection.h
)
target_include_directories(Shunshoupai PRIVATE vendor ../shared/core)
target_link_libraries(Shunshoupai PRIVATE Qt6::Widgets Qt6::Network PairingQrDecoder)
if(WIN32)
    enable_language(RC)
    configure_file(src/app-version.rc.in "${CMAKE_CURRENT_BINARY_DIR}/app-version.rc" @ONLY)
    target_sources(Shunshoupai PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/app-version.rc")
    target_link_libraries(Shunshoupai PRIVATE crypt32)
endif()
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
    add_test(NAME AppVersion COMMAND Shunshoupai --version)
    set_tests_properties(AppVersion PROPERTIES PASS_REGULAR_EXPRESSION "Shunshoupai ${PROJECT_VERSION}")
    add_executable(ConnectionCoreTest
        ../shared/core/connection.cpp
        ../shared/core/connection_test.cpp
    )
    target_include_directories(ConnectionCoreTest PRIVATE ../shared/core)
    if(MSVC)
        target_compile_options(ConnectionCoreTest PRIVATE /utf-8 /W4)
    endif()
    add_test(NAME ConnectionCore COMMAND ConnectionCoreTest)
    add_executable(SessionCookiesTest src/session_cookies.cpp tests/session_cookies_test.cpp)
    target_include_directories(SessionCookiesTest PRIVATE src)
    target_link_libraries(SessionCookiesTest PRIVATE Qt6::Network)
    if(WIN32)
        target_link_libraries(SessionCookiesTest PRIVATE crypt32)
    endif()
    if(MSVC)
        target_compile_options(SessionCookiesTest PRIVATE /utf-8 /W4)
    endif()
    add_test(NAME SessionCookies COMMAND SessionCookiesTest)
    add_executable(DraftStateTest tests/draft_state_test.cpp)
    target_include_directories(DraftStateTest PRIVATE src)
    target_link_libraries(DraftStateTest PRIVATE Qt6::Core)
    if(MSVC)
        target_compile_options(DraftStateTest PRIVATE /utf-8 /W4)
    endif()
    add_test(NAME DraftState COMMAND DraftStateTest)
    add_executable(DeviceBootstrapTest src/session_cookies.cpp tests/device_bootstrap_test.cpp)
    target_include_directories(DeviceBootstrapTest PRIVATE src)
    target_link_libraries(DeviceBootstrapTest PRIVATE Qt6::Network)
    target_compile_definitions(DeviceBootstrapTest PRIVATE SHUNSHOUPAI_VERSION="${PROJECT_VERSION}")
    if(WIN32)
        target_link_libraries(DeviceBootstrapTest PRIVATE crypt32)
    endif()
    if(MSVC)
        target_compile_options(DeviceBootstrapTest PRIVATE /utf-8 /W4)
    endif()
    add_test(NAME DeviceBootstrap COMMAND DeviceBootstrapTest)
    add_executable(PairingQrTest tests/qr_decoder_test.cpp vendor/qrcodegen.cpp ../shared/core/connection.cpp)
    target_include_directories(PairingQrTest PRIVATE src vendor ../shared/core)
    target_link_libraries(PairingQrTest PRIVATE PairingQrDecoder)
    if(MSVC)
        target_compile_options(PairingQrTest PRIVATE /utf-8 /W4)
    endif()
    add_test(NAME PairingQr COMMAND PairingQrTest)
endif()
