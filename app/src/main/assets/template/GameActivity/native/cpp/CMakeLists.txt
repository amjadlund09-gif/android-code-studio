# GameActivity (C++) template - copy this file with the other files in native/cpp
# to <app>/src/main/cpp/
cmake_minimum_required(VERSION 3.22.1)

project("game")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Provided by androidx.games:games-activity (needs "buildFeatures { prefab true }").
find_package(game-activity REQUIRED CONFIG)

# The library name must match System.loadLibrary("game") and the
# android.app.lib_name meta-data in AndroidManifest.xml.
add_library(game SHARED
        main.cpp
        AndroidOut.cpp
        Renderer.cpp
        Shader.cpp
        TextureAsset.cpp
        Utility.cpp)

# Export the GameActivity JNI entry point so the linker does not strip it.
set(CMAKE_SHARED_LINKER_FLAGS
        "${CMAKE_SHARED_LINKER_FLAGS} -u Java_com_google_androidgamesdk_GameActivity_initializeNativeCode")

target_link_libraries(game
        game-activity::game-activity_static
        EGL
        GLESv3
        jnigraphics
        android
        log)
