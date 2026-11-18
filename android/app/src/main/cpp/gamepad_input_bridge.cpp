#include "gamepad_input_bridge.h"

#include <android/log.h>
#include <dlfcn.h>
#include <cmath>
#include <mutex>
#include <atomic>
#include <chrono>

#define LOG_TAG "NFSU2-InputBridge"

extern "C" {
    // Rust game_core exports
    int32_t nfs_controller_camera_process_axes(
        float raw_rx, float raw_ry, float raw_z, float raw_rz,
        float raw_lt, float raw_rt, float raw_brake, float raw_gas,
        float* out_x, float* out_y
    ) __attribute__((weak));

    void nfs_controller_camera_feed(float rx, float ry) __attribute__((weak));
    float nfs_controller_camera_get_yaw(void) __attribute__((weak));
    void nfs_controller_camera_register_roam_stick(void (*cb)(float, float)) __attribute__((weak));
}

static JavaVM* s_jvm = nullptr;
static jclass s_input_log_class = nullptr;
static jmethodID s_log_axis_mid = nullptr;
static jmethodID s_log_key_mid = nullptr;
static jmethodID s_log_cam_mid = nullptr;
static std::mutex s_jni_mutex;

static void (*s_roam_stick_fn)(float, float) = nullptr;
static void (*s_core_cam_fn)(float, float) = nullptr;
static void (*s_garage_fn)(float, float) = nullptr;

static std::atomic<float> s_last_stick_x{0.0f};
static std::atomic<float> s_last_stick_y{0.0f};
static std::chrono::steady_clock::time_point s_last_axis_log_time{};

extern "C" __attribute__((visibility("default")))
void nfs_register_roam_stick_handler(void (*handler)(float, float)) {
    s_roam_stick_fn = handler;
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Roam stick handler registered: %p", (void*)handler);
    if (nfs_controller_camera_register_roam_stick) {
        nfs_controller_camera_register_roam_stick(handler);
    }
}

static void resolve_native_handlers() {
    if (!s_roam_stick_fn) {
        s_roam_stick_fn = reinterpret_cast<void(*)(float, float)>(dlsym(RTLD_DEFAULT, "roam_stick_input"));
        if (!s_roam_stick_fn) {
            s_roam_stick_fn = reinterpret_cast<void(*)(float, float)>(dlsym(RTLD_DEFAULT, "nfs_roam_stick_input"));
        }
        if (!s_roam_stick_fn) {
            s_roam_stick_fn = reinterpret_cast<void(*)(float, float)>(dlsym(RTLD_DEFAULT, "Java_io_mininxd_nfsu2_AdhocRoamClient_nativeRoamStick"));
        }
    }

    if (!s_core_cam_fn) {
        s_core_cam_fn = reinterpret_cast<void(*)(float, float)>(dlsym(RTLD_DEFAULT, "nfs_camera_stick_input"));
    }
    if (!s_garage_fn) {
        s_garage_fn = reinterpret_cast<void(*)(float, float)>(dlsym(RTLD_DEFAULT, "nfs_garage_stick_input"));
    }
}

void nfs_gamepad_bridge_init(JavaVM* vm) {
    if (vm) {
        s_jvm = vm;
    }
    resolve_native_handlers();
}

static JNIEnv* get_jni_env(bool* attached) {
    *attached = false;
    if (!s_jvm) return nullptr;

    JNIEnv* env = nullptr;
    jint res = s_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_OK) return env;

    if (res == JNI_EDETACHED) {
        if (s_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            *attached = true;
            return env;
        }
    }
    return nullptr;
}

static void ensure_jni_cached(JNIEnv* env) {
    if (!env || s_input_log_class) return;
    std::lock_guard<std::mutex> lock(s_jni_mutex);
    if (s_input_log_class) return;

    jclass localCls = env->FindClass("io/mininxd/nfsu2/InputLog");
    if (localCls) {
        s_input_log_class = reinterpret_cast<jclass>(env->NewGlobalRef(localCls));
        env->DeleteLocalRef(localCls);

        s_log_axis_mid = env->GetStaticMethodID(s_input_log_class, "logNativeGamepadAxis", "(IFFFFFFFF)V");
        s_log_key_mid = env->GetStaticMethodID(s_input_log_class, "logNativeGamepadKey", "(IIIII)V");
        s_log_cam_mid = env->GetStaticMethodID(s_input_log_class, "cameraLook", "(Ljava/lang/String;FFFF)V");
    } else {
        env->ExceptionClear();
    }
}

void nfs_gamepad_bridge_feed_camera(float rx, float ry) {
    resolve_native_handlers();

    // 1. Deliver to roam camera orbit (roam_android.c)
    if (s_roam_stick_fn) {
        s_roam_stick_fn(rx, ry);
    }

    // 2. Deliver to Rust controller camera engine
    if (nfs_controller_camera_feed) {
        nfs_controller_camera_feed(rx, ry);
    }

    // 3. Deliver to Rust camera stick engine
    if (s_core_cam_fn) {
        s_core_cam_fn(rx, ry);
    }

    // 4. Deliver to garage car viewer
    if (s_garage_fn) {
        s_garage_fn(rx, ry);
    }
}

void nfs_gamepad_bridge_on_motion(const AInputEvent* event) {
    if (!event || AInputEvent_getType(event) != AINPUT_EVENT_TYPE_MOTION) return;

    int32_t src = AInputEvent_getSource(event);
    if ((src & (AINPUT_SOURCE_JOYSTICK | AINPUT_SOURCE_GAMEPAD | AINPUT_SOURCE_CLASS_JOYSTICK)) == 0) {
        return;
    }

    int32_t dev_id = AInputEvent_getDeviceId(event);

    float lx = AMotionEvent_getAxisValue(event, AMOTION_EVENT_AXIS_X, 0);
    float ly = AMotionEvent_getAxisValue(event, AMOTION_EVENT_AXIS_Y, 0);
    float raw_rx = AMotionEvent_getAxisValue(event, AMOTION_EVENT_AXIS_RX, 0);
    float raw_ry = AMotionEvent_getAxisValue(event, AMOTION_EVENT_AXIS_RY, 0);
    float raw_z = AMotionEvent_getAxisValue(event, AMOTION_EVENT_AXIS_Z, 0);
    float raw_rz = AMotionEvent_getAxisValue(event, AMOTION_EVENT_AXIS_RZ, 0);
    float hat_x = AMotionEvent_getAxisValue(event, AMOTION_EVENT_AXIS_HAT_X, 0);
    float hat_y = AMotionEvent_getAxisValue(event, AMOTION_EVENT_AXIS_HAT_Y, 0);
    float raw_lt = AMotionEvent_getAxisValue(event, AMOTION_EVENT_AXIS_LTRIGGER, 0);
    float raw_rt = AMotionEvent_getAxisValue(event, AMOTION_EVENT_AXIS_RTRIGGER, 0);
    float raw_brake = AMotionEvent_getAxisValue(event, AMOTION_EVENT_AXIS_BRAKE, 0);
    float raw_gas = AMotionEvent_getAxisValue(event, AMOTION_EVENT_AXIS_GAS, 0);

    float chosen_rx = 0.0f;
    float chosen_ry = 0.0f;

    // Evaluate axes through Rust controller engine
    if (nfs_controller_camera_process_axes) {
        nfs_controller_camera_process_axes(
            raw_rx, raw_ry, raw_z, raw_rz,
            raw_lt, raw_rt, raw_brake, raw_gas,
            &chosen_rx, &chosen_ry
        );
    } else {
        // Fallback axis disambiguation
        if (std::hypot(raw_rx, raw_ry) > 0.05f) {
            chosen_rx = raw_rx;
            chosen_ry = raw_ry;
        } else if (!(raw_z < -0.85f && raw_rz < -0.85f)) {
            chosen_rx = raw_z;
            chosen_ry = raw_rz;
        }

        float mag = std::hypot(chosen_rx, chosen_ry);
        if (mag < 0.15f) {
            chosen_rx = 0.0f;
            chosen_ry = 0.0f;
        } else {
            chosen_rx = std::max(-1.0f, std::min(1.0f, chosen_rx));
            chosen_ry = std::max(-1.0f, std::min(1.0f, chosen_ry));
        }
    }

    // Feed camera handlers
    nfs_gamepad_bridge_feed_camera(chosen_rx, chosen_ry);

    float prev_x = s_last_stick_x.exchange(chosen_rx);
    float prev_y = s_last_stick_y.exchange(chosen_ry);

    // Rate-limited telemetry to InputLog
    auto now = std::chrono::steady_clock::now();
    bool stick_state_changed = (chosen_rx != 0.0f || chosen_ry != 0.0f) != (prev_x != 0.0f || prev_y != 0.0f);
    bool should_log = stick_state_changed || (std::chrono::duration_cast<std::chrono::milliseconds>(now - s_last_axis_log_time).count() >= 60);

    if (should_log) {
        s_last_axis_log_time = now;
        bool attached = false;
        JNIEnv* env = get_jni_env(&attached);
        if (env) {
            ensure_jni_cached(env);
            if (s_input_log_class && s_log_axis_mid) {
                float lt = std::fmax(raw_lt, raw_brake);
                float rt = std::fmax(raw_rt, raw_gas);
                env->CallStaticVoidMethod(s_input_log_class, s_log_axis_mid,
                    static_cast<jint>(dev_id),
                    static_cast<jfloat>(lx), static_cast<jfloat>(ly),
                    static_cast<jfloat>(chosen_rx), static_cast<jfloat>(chosen_ry),
                    static_cast<jfloat>(hat_x), static_cast<jfloat>(hat_y),
                    static_cast<jfloat>(lt), static_cast<jfloat>(rt));
            }
            if (s_input_log_class && s_log_cam_mid && (chosen_rx != 0.0f || chosen_ry != 0.0f)) {
                float yaw = nfs_controller_camera_get_yaw ? nfs_controller_camera_get_yaw() : std::atan2(chosen_rx, -chosen_ry);
                jstring srcStr = env->NewStringUTF("RightStick");
                env->CallStaticVoidMethod(s_input_log_class, s_log_cam_mid,
                    srcStr,
                    static_cast<jfloat>(chosen_rx), static_cast<jfloat>(chosen_ry),
                    static_cast<jfloat>(yaw), 0.0f);
                env->DeleteLocalRef(srcStr);
            }
            if (attached) {
                s_jvm->DetachCurrentThread();
            }
        }
    }
}

void nfs_gamepad_bridge_on_key(const AInputEvent* event) {
    if (!event || AInputEvent_getType(event) != AINPUT_EVENT_TYPE_KEY) return;

    int32_t dev_id = AInputEvent_getDeviceId(event);
    int32_t action = AKeyEvent_getAction(event);
    int32_t key_code = AKeyEvent_getKeyCode(event);
    int32_t scan_code = AKeyEvent_getScanCode(event);
    int32_t repeat = AKeyEvent_getRepeatCount(event);

    bool attached = false;
    JNIEnv* env = get_jni_env(&attached);
    if (env) {
        ensure_jni_cached(env);
        if (s_input_log_class && s_log_key_mid) {
            env->CallStaticVoidMethod(s_input_log_class, s_log_key_mid,
                static_cast<jint>(dev_id),
                static_cast<jint>(action),
                static_cast<jint>(key_code),
                static_cast<jint>(scan_code),
                static_cast<jint>(repeat));
        }
        if (attached) {
            s_jvm->DetachCurrentThread();
        }
    }
}
