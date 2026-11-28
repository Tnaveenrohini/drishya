// Flikk Play AI Subtitles — whisper.cpp JNI bridge
#include <jni.h>
#include <atomic>
#include <string>
#include "whisper.h"

static std::atomic<bool> g_abort(false);

struct ProgressCb {
    JNIEnv *env;
    jobject listener;
    jmethodID method;
};

static void on_progress(struct whisper_context *, struct whisper_state *, int progress, void *user) {
    auto *cb = static_cast<ProgressCb *>(user);
    if (cb && cb->listener && cb->method) cb->env->CallVoidMethod(cb->listener, cb->method, (jint) progress);
}

static bool on_abort(void *) { return g_abort.load(); }

extern "C" JNIEXPORT jlong JNICALL
Java_com_drishya_player_WhisperLib_initContext(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_drishya_player_WhisperLib_freeContext(JNIEnv *, jobject, jlong ctx) {
    if (ctx) whisper_free(reinterpret_cast<whisper_context *>(ctx));
}

extern "C" JNIEXPORT void JNICALL
Java_com_drishya_player_WhisperLib_cancel(JNIEnv *, jobject) { g_abort = true; }

extern "C" JNIEXPORT void JNICALL
Java_com_drishya_player_WhisperLib_resetCancel(JNIEnv *, jobject) { g_abort = false; }

// Returns byte[][]: each "t0|t1|text" (centiseconds, UTF-8). null on error / cancel.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_drishya_player_WhisperLib_transcribe(JNIEnv *env, jobject, jlong ctxp, jfloatArray samples,
                                              jstring lang, jint threads, jboolean translate, jobject listener) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxp);
    if (!ctx) return nullptr;
    const jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    const char *l = env->GetStringUTFChars(lang, nullptr);
    std::string language(l);
    env->ReleaseStringUTFChars(lang, l);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.translate = translate;
    p.language = language.c_str();
    p.detect_language = false;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.print_special = false;
    p.no_context = true;
    p.suppress_blank = true;
    p.token_timestamps = true;
    p.max_len = 60;           // subtitle line chinnaga
    p.split_on_word = true;

    ProgressCb cb{env, listener, nullptr};
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        cb.method = env->GetMethodID(cls, "onProgress", "(I)V");
        p.progress_callback = on_progress;
        p.progress_callback_user_data = &cb;
    }
    p.abort_callback = on_abort;
    p.abort_callback_user_data = nullptr;

    const int rc = whisper_full(ctx, p, data, n);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    if (rc != 0 || g_abort.load()) return nullptr;

    const int ns = whisper_full_n_segments(ctx);
    jclass bytesClass = env->FindClass("[B");
    jobjectArray out = env->NewObjectArray(ns, bytesClass, nullptr);
    for (int i = 0; i < ns; i++) {
        std::string s = std::to_string(whisper_full_get_segment_t0(ctx, i)) + "|" +
                        std::to_string(whisper_full_get_segment_t1(ctx, i)) + "|" +
                        whisper_full_get_segment_text(ctx, i);
        jbyteArray b = env->NewByteArray((jsize) s.size());
        env->SetByteArrayRegion(b, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
        env->SetObjectArrayElement(out, i, b);
        env->DeleteLocalRef(b);
    }
    return out;
}
