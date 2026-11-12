# Minimum CMake version (the version installed by SDK Manager is picked in app/build.gradle).
cmake_minimum_required(VERSION 3.22.1)

project("native-lib" LANGUAGES __LANG__)

# The library name must match System.loadLibrary("native-lib") in MainActivity.
add_library(native-lib SHARED __SRC_FILE__)

find_library(log-lib log)

target_link_libraries(native-lib
        android
        ${log-lib})
