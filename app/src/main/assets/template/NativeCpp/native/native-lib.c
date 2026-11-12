#include <jni.h>

JNIEXPORT jstring JNICALL
Java___JNI_PACKAGE___MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject thiz) {
    return (*env)->NewStringUTF(env, "Hello from C");
}
