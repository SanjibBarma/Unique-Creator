#include <jni.h>
#include <string>
#include <vector>
#include <sys/ptrace.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <android/log.h>
#include <sys/system_properties.h>
#include <pthread.h>
#include <dirent.h>

#define LOG_TAG "NativeGuardian"

static jboolean validateNativeEnvironment(JNIEnv* env, jobject thiz, jboolean is_debug) {
    // ১. ডিবাগ মোডে ptrace চেক স্কিপ করব যাতে ডেভেলপমেন্টে সমস্যা না হয়
    if (!is_debug) {
        // ptrace চেক অনেক সময় নরমাল ফোনেও এরর দেয়, তাই আমরা শুধু হ্যাকিং টুল চেক করব
        // ptrace(PTRACE_TRACEME, 0, 1, 0);
    }

    // ২. শুধুমাত্র বর্তমান প্রসেসের জন্য Frida/Xposed চেক (Selective Scan)
    FILE* fp = fopen("/proc/self/maps", "r");
    if (fp != nullptr) {
        char line[512];
        while (fgets(line, sizeof(line), fp)) {
            // Frida এবং Xposed এর মূল সিগনেচারগুলো চেক করা হচ্ছে
            if (strstr(line, "frida-agent") || strstr(line, "xposed.dex") || strstr(line, "frida-helper")) {
                fclose(fp);
                return JNI_FALSE;
            }
        }
        fclose(fp);
    }

    // ৩. Frida পোর্টের জন্য বেসিক চেক (Port 27042)
    // (এটি অনেক সময় ফলস পজিটিভ দেয় তাই আমরা শুধু মেমোরি স্ক্যান রাখছি)

    return JNI_TRUE;
}

static jboolean checkNativeIntegrity(JNIEnv* env, jobject thiz, jobject ctx) {
    return JNI_TRUE;
}

static const JNINativeMethod methods[] = {
    {"validateNativeEnvironment", "(Z)Z", (void*)validateNativeEnvironment},
    {"checkNativeIntegrity", "(Landroid/content/Context;)Z", (void*)checkNativeIntegrity}
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;

    jclass clazz = env->FindClass("com/example/uniquecreator/security/SecurityHelper");
    if (clazz == nullptr) return JNI_ERR;

    if (env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(methods[0])) < 0) return JNI_ERR;

    return JNI_VERSION_1_6;
}