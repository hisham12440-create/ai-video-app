// JNI bridge between WhisperLib.kt and whisper.cpp (speech to text, on the phone, no network).
#include <jni.h>

#include <atomic>
#include <cstdint>
#include <cstring>

#include "whisper.h"

#ifndef JNIEXPORT_API
#define JNIEXPORT_API extern "C" JNIEXPORT
#endif

namespace {

std::atomic<bool> g_abort{false};

struct ProgressCtx {
    JNIEnv *env;
    jobject listener;
    jmethodID method;
    int last;
};

void on_progress(struct whisper_context *, struct whisper_state *, int progress, void *user) {
    auto *p = static_cast<ProgressCtx *>(user);
    if (p == nullptr || p->listener == nullptr || p->method == nullptr || progress == p->last) return;
    p->last = progress;
    p->env->CallVoidMethod(p->listener, p->method, static_cast<jint>(progress));
    if (p->env->ExceptionCheck()) p->env->ExceptionClear();
}

bool should_abort(void *) { return g_abort.load(); }

whisper_context *as_ctx(jlong handle) {
    return reinterpret_cast<whisper_context *>(static_cast<intptr_t>(handle));
}

}  // namespace

JNIEXPORT_API jlong JNICALL Java_com_montageai_app_WhisperLib_nativeInit(JNIEnv *env, jobject, jstring path) {
    const char *c = env->GetStringUTFChars(path, nullptr);
    if (c == nullptr) return 0;
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(c, cp);
    env->ReleaseStringUTFChars(path, c);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(ctx));
}

JNIEXPORT_API void JNICALL Java_com_montageai_app_WhisperLib_nativeFree(JNIEnv *, jobject, jlong handle) {
    whisper_context *ctx = as_ctx(handle);
    if (ctx != nullptr) whisper_free(ctx);
}

JNIEXPORT_API void JNICALL Java_com_montageai_app_WhisperLib_nativeAbort(JNIEnv *, jobject) {
    g_abort.store(true);
}

// Returns 0 on success, 1 if aborted, a negative number on failure.
JNIEXPORT_API jint JNICALL Java_com_montageai_app_WhisperLib_nativeTranscribe(
    JNIEnv *env, jobject, jlong handle, jfloatArray samples, jstring lang, jint threads, jobject listener) {
    whisper_context *ctx = as_ctx(handle);
    if (ctx == nullptr) return -1;

    g_abort.store(false);
    const jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    if (data == nullptr) return -2;
    const char *language = env->GetStringUTFChars(lang, nullptr);
    if (language == nullptr) {
        env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
        return -3;
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads > 0 ? threads : 4;
    params.language = language;
    params.detect_language = false;
    params.translate = false;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.no_context = true;
    params.single_segment = false;
    // One segment per word, each with its own start and end time.
    params.token_timestamps = true;
    params.split_on_word = true;
    params.max_len = 1;
    params.abort_callback = should_abort;
    params.abort_callback_user_data = nullptr;

    ProgressCtx progress{env, listener, nullptr, -1};
    if (listener != nullptr) {
        jclass cls = env->GetObjectClass(listener);
        progress.method = env->GetMethodID(cls, "onProgress", "(I)V");
        env->DeleteLocalRef(cls);
        if (progress.method == nullptr) {
            env->ExceptionClear();
        } else {
            params.progress_callback = on_progress;
            params.progress_callback_user_data = &progress;
        }
    }

    const int rc = whisper_full(ctx, params, data, static_cast<int>(n));

    env->ReleaseStringUTFChars(lang, language);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    if (g_abort.load()) return 1;
    return rc == 0 ? 0 : -4;
}

JNIEXPORT_API jint JNICALL Java_com_montageai_app_WhisperLib_nativeSegmentCount(JNIEnv *, jobject, jlong handle) {
    whisper_context *ctx = as_ctx(handle);
    return ctx == nullptr ? 0 : whisper_full_n_segments(ctx);
}

// The text is returned as raw UTF-8 bytes (decoded in Kotlin) because JNI's NewStringUTF
// rejects some valid UTF-8 and aborts the process under CheckJNI.
JNIEXPORT_API jbyteArray JNICALL Java_com_montageai_app_WhisperLib_nativeSegmentText(
    JNIEnv *env, jobject, jlong handle, jint index) {
    whisper_context *ctx = as_ctx(handle);
    const char *text = ctx == nullptr ? nullptr : whisper_full_get_segment_text(ctx, index);
    const jsize len = text == nullptr ? 0 : static_cast<jsize>(std::strlen(text));
    jbyteArray out = env->NewByteArray(len);
    if (out != nullptr && len > 0) env->SetByteArrayRegion(out, 0, len, reinterpret_cast<const jbyte *>(text));
    return out;
}

// Times are in units of 10 ms.
JNIEXPORT_API jlong JNICALL Java_com_montageai_app_WhisperLib_nativeSegmentStart(
    JNIEnv *, jobject, jlong handle, jint index) {
    whisper_context *ctx = as_ctx(handle);
    return ctx == nullptr ? 0 : static_cast<jlong>(whisper_full_get_segment_t0(ctx, index));
}

JNIEXPORT_API jlong JNICALL Java_com_montageai_app_WhisperLib_nativeSegmentEnd(
    JNIEnv *, jobject, jlong handle, jint index) {
    whisper_context *ctx = as_ctx(handle);
    return ctx == nullptr ? 0 : static_cast<jlong>(whisper_full_get_segment_t1(ctx, index));
}

JNIEXPORT_API jstring JNICALL Java_com_montageai_app_WhisperLib_nativeSystemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}
