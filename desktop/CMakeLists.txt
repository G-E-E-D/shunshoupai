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

find_package(Qt6 6.8 REQUIRED COMPONENTS Widgets Network)
qt_standard_project_setup()

qt_add_executable(Shunshoupai WIN32
    src/main.cpp
    src/api.h
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
    vendor/qrcodegen.cpp
    vendor/qrcodegen.hpp
    ../shared/core/connection.cpp
    ../shared/core/connection.h
)
target_include_directories(Shunshoupai PRIVATE vendor ../shared/core)
target_link_libraries(Shunshoupai PRIVATE Qt6::Widgets Qt6::Network)
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
endif()
