# SPDX-License-Identifier: Apache-2.0
# Modified for vpn0wn.
#
# Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.

cmake_minimum_required(VERSION 3.4.1)
project("vpn0wn")
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
add_link_options(LINKER:--build-id=none)
add_compile_options(-Wall -Werror)

set(WG_QUICK_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/wireguard-tools/src/wg-quick/android.c")
set(WG_QUICK_PATCH "${CMAKE_CURRENT_SOURCE_DIR}/patches/wg-quick-literal-cleanup.patch")
set(WG_QUICK_GENERATED "${CMAKE_CURRENT_BINARY_DIR}/wg-quick-android.c")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${WG_QUICK_SOURCE}" "${WG_QUICK_PATCH}")
execute_process(COMMAND patch --batch --fuzz=0 --output "${WG_QUICK_GENERATED}" "${WG_QUICK_SOURCE}" "${WG_QUICK_PATCH}"
    RESULT_VARIABLE WG_QUICK_PATCH_RESULT OUTPUT_VARIABLE WG_QUICK_PATCH_OUTPUT ERROR_VARIABLE WG_QUICK_PATCH_ERROR)
if(NOT WG_QUICK_PATCH_RESULT EQUAL 0)
    message(FATAL_ERROR "Cannot apply wg-quick cleanup patch: ${WG_QUICK_PATCH_OUTPUT}${WG_QUICK_PATCH_ERROR}")
endif()
add_executable(libwg-quick.so "${WG_QUICK_GENERATED}" ndk-compat/compat.c)
target_compile_options(libwg-quick.so PUBLIC -std=gnu11 -include ${CMAKE_CURRENT_SOURCE_DIR}/ndk-compat/compat.h -DWG_PACKAGE_NAME=\"${ANDROID_PACKAGE_NAME}\")
target_link_libraries(libwg-quick.so -ldl)

file(GLOB WG_SOURCES wireguard-tools/src/*.c ndk-compat/compat.c)
add_executable(libwg.so ${WG_SOURCES})
target_include_directories(libwg.so PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/wireguard-tools/src/uapi/linux/" "${CMAKE_CURRENT_SOURCE_DIR}/wireguard-tools/src/")
target_compile_options(libwg.so PUBLIC -std=gnu11 -include ${CMAKE_CURRENT_SOURCE_DIR}/ndk-compat/compat.h -DRUNSTATEDIR=\"/data/data/${ANDROID_PACKAGE_NAME}/cache\")

add_custom_target(libwg-go.so WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/libwg-go" COMMENT "Building wireguard-go" VERBATIM COMMAND "${ANDROID_HOST_PREBUILTS}/bin/make"
    ANDROID_ARCH_NAME=${ANDROID_ARCH_NAME}
    ANDROID_PACKAGE_NAME=${ANDROID_PACKAGE_NAME}
    GRADLE_USER_HOME=${GRADLE_USER_HOME}
    CC=${CMAKE_C_COMPILER}
    CFLAGS=${CMAKE_C_FLAGS}
    LDFLAGS=${CMAKE_SHARED_LINKER_FLAGS}
    SYSROOT=${CMAKE_SYSROOT}
    TARGET=${CMAKE_C_COMPILER_TARGET}
    DESTDIR=${CMAKE_LIBRARY_OUTPUT_DIRECTORY}
    BUILDDIR=${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/../generated-src
)

# Strip unwanted ELF sections to prevent DT_FLAGS_1 warnings on old Android versions
file(GLOB ELF_CLEANER_SOURCES elf-cleaner/*.c elf-cleaner/*.cpp)
add_custom_target(elf-cleaner COMMENT "Building elf-cleaner" VERBATIM COMMAND cc
        -O2 -DPACKAGE_NAME="elf-cleaner" -DPACKAGE_VERSION="" -DCOPYRIGHT=""
        -o "${CMAKE_CURRENT_BINARY_DIR}/elf-cleaner" ${ELF_CLEANER_SOURCES}
)
add_custom_command(TARGET libwg.so POST_BUILD VERBATIM COMMAND "${CMAKE_CURRENT_BINARY_DIR}/elf-cleaner"
        --api-level "${ANDROID_NATIVE_API_LEVEL}" "$<TARGET_FILE:libwg.so>")
add_dependencies(libwg.so elf-cleaner)
add_custom_command(TARGET libwg-quick.so POST_BUILD VERBATIM COMMAND "${CMAKE_CURRENT_BINARY_DIR}/elf-cleaner"
        --api-level "${ANDROID_NATIVE_API_LEVEL}" "$<TARGET_FILE:libwg-quick.so>")
add_dependencies(libwg-quick.so elf-cleaner)
