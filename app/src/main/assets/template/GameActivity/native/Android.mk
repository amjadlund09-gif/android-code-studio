# GameActivity (C++) template - ndk-build version (used when "Use CMake" is off).
# Needs "buildFeatures { prefab true }" in app/build.gradle so game-activity can be imported.
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

# The library name must match System.loadLibrary("game") and the
# android.app.lib_name meta-data in AndroidManifest.xml.
LOCAL_MODULE    := game
LOCAL_SRC_FILES := main.cpp \
                   AndroidOut.cpp \
                   Renderer.cpp \
                   Shader.cpp \
                   TextureAsset.cpp \
                   Utility.cpp
LOCAL_CPPFLAGS  := -std=c++17
LOCAL_LDLIBS    := -lEGL -lGLESv3 -ljnigraphics -landroid -llog
LOCAL_STATIC_LIBRARIES := game-activity_static

# Export the GameActivity JNI entry point so the linker does not strip it.
LOCAL_LDFLAGS   := -u Java_com_google_androidgamesdk_GameActivity_initializeNativeCode

include $(BUILD_SHARED_LIBRARY)

$(call import-module,prefab/game-activity)
