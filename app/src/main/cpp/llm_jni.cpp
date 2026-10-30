// 1.9.0 JNI bridge over llama.h (CPU only). Runs inside the broker's isolated ":llm" process.
// Exposes load / applyTemplate / generate (streaming callback, cancellable) / free / systemInfo.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <string>
#include <vector>
#include <cstdio>
#include "llama.h"

#define TAG "BrokerLlmJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::atomic<bool> abort{false};
    int n_ctx = 0;
};

std::atomic<bool> g_backend_inited{false};

void log_cb(ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

bool abort_cb(void *data) {
    return static_cast<Engine *>(data)->abort.load();
}

int64_t now_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

// Length of the longest prefix of s that is complete UTF-8 (so we never hand Java a split code point).
size_t utf8_complete_prefix(const std::string &s) {
    size_t n = s.size();
    if (n == 0) return 0;
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) != 0x80) {  // lead byte or ASCII
            int need = (c < 0x80) ? 1 : ((c >> 5) == 0x6) ? 2 : ((c >> 4) == 0xE) ? 3 : ((c >> 3) == 0x1E) ? 4 : 1;
            return (back + 1 >= need) ? n : i - 1;
        }
        --i; ++back;
    }
    return n;
}

jstring to_jstring(JNIEnv *env, const std::string &s) {
    // Build via byte[] + new String(bytes, UTF_8) so invalid sequences never crash NewStringUTF.
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    jclass strCls = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(strCls, "<init>", "([BLjava/lang/String;)V");
    jstring enc = env->NewStringUTF("UTF-8");
    auto out = static_cast<jstring>(env->NewObject(strCls, ctor, arr, enc));
    env->DeleteLocalRef(arr);
    env->DeleteLocalRef(enc);
    env->DeleteLocalRef(strCls);
    return out;
}

std::string from_jstring(JNIEnv *env, jstring js) {
    if (!js) return {};
    const char *c = env->GetStringUTFChars(js, nullptr);
    std::string s(c ? c : "");
    if (c) env->ReleaseStringUTFChars(js, c);
    return s;
}

std::string json_escape(const std::string &s) {
    std::string o;
    for (char ch : s) {
        switch (ch) {
            case '"': o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\n': o += "\\n"; break;
            case '\r': o += "\\r"; break;
            case '\t': o += "\\t"; break;
            default:
                if (static_cast<unsigned char>(ch) < 0x20) { char b[8]; snprintf(b, sizeof b, "\\u%04x", ch); o += b; }
                else o += ch;
        }
    }
    return o;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_rjkyker_shizukuai_broker_llm_LlamaNative_nativeSystemInfo(JNIEnv *env, jclass) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_com_rjkyker_shizukuai_broker_llm_LlamaNative_nativeLoad(JNIEnv *env, jclass, jstring jpath, jint nCtx,
                                                             jint nThreads, jint nThreadsBatch) {
    if (!g_backend_inited.exchange(true)) {
        llama_log_set(log_cb, nullptr);
        llama_backend_init();
    }
    std::string path = from_jstring(env, jpath);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;  // CPU only in 1.9.0 (Vulkan/OpenCL evaluated later)
    llama_model *model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) { LOGW("model load failed: %s", path.c_str()); return 0; }

    auto *e = new Engine();
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(nCtx);
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreadsBatch;
    cp.no_perf = false;
    cp.abort_callback = abort_cb;
    cp.abort_callback_data = e;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) { LOGW("context init failed"); llama_model_free(model); delete e; return 0; }
    e->model = model;
    e->ctx = ctx;
    e->vocab = llama_model_get_vocab(model);
    e->n_ctx = static_cast<int>(llama_n_ctx(ctx));
    LOGI("loaded %s n_ctx=%d threads=%d/%d params=%llu", path.c_str(), e->n_ctx, nThreads, nThreadsBatch,
         (unsigned long long) llama_model_n_params(model));
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT jstring JNICALL
Java_com_rjkyker_shizukuai_broker_llm_LlamaNative_nativeApplyTemplate(JNIEnv *env, jclass, jlong h,
                                                                      jstring jsys, jstring juser) {
    auto *e = reinterpret_cast<Engine *>(h);
    std::string sys = from_jstring(env, jsys), user = from_jstring(env, juser);
    std::vector<llama_chat_message> msgs;
    if (!sys.empty()) msgs.push_back({"system", sys.c_str()});
    msgs.push_back({"user", user.c_str()});
    const char *tmpl = e ? llama_model_chat_template(e->model, nullptr) : nullptr;
    std::vector<char> buf(8192 + sys.size() * 2 + user.size() * 2);
    int32_t n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
    if (n > (int32_t) buf.size()) {
        buf.resize(n + 1);
        n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
    }
    if (n < 0) {  // unknown template -> plain fallback
        std::string f = (sys.empty() ? "" : sys + "\n\n") + "User: " + user + "\nAssistant:";
        return to_jstring(env, f);
    }
    return to_jstring(env, std::string(buf.data(), n));
}

JNIEXPORT void JNICALL
Java_com_rjkyker_shizukuai_broker_llm_LlamaNative_nativeCancel(JNIEnv *, jclass, jlong h) {
    auto *e = reinterpret_cast<Engine *>(h);
    if (e) e->abort.store(true);
}

// callback: object with method boolean onToken(String piece); return false to stop.
JNIEXPORT jstring JNICALL
Java_com_rjkyker_shizukuai_broker_llm_LlamaNative_nativeGenerate(JNIEnv *env, jclass, jlong h, jstring jprompt,
                                                                 jint maxNew, jfloat temp, jfloat topP, jint seed,
                                                                 jlong deadlineMs, jobject cb) {
    auto *e = reinterpret_cast<Engine *>(h);
    if (!e) return env->NewStringUTF("{\"ok\":false,\"error\":\"not loaded\"}");
    e->abort.store(false);
    jmethodID onToken = nullptr;
    if (cb) {
        jclass cls = env->GetObjectClass(cb);
        onToken = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)Z");
        env->DeleteLocalRef(cls);
    }
    std::string prompt = from_jstring(env, jprompt);
    llama_memory_clear(llama_get_memory(e->ctx), true);

    int n_max = (int) prompt.size() + 16;
    std::vector<llama_token> toks(n_max);
    int n_prompt = llama_tokenize(e->vocab, prompt.c_str(), (int32_t) prompt.size(), toks.data(), n_max, true, true);
    if (n_prompt < 0) {
        toks.resize(-n_prompt);
        n_prompt = llama_tokenize(e->vocab, prompt.c_str(), (int32_t) prompt.size(), toks.data(), -n_prompt, true, true);
    }
    if (n_prompt <= 0) return env->NewStringUTF("{\"ok\":false,\"error\":\"tokenize failed\"}");
    toks.resize(n_prompt);
    if (n_prompt + maxNew > e->n_ctx) {
        return env->NewStringUTF("{\"ok\":false,\"error\":\"prompt too long for n_ctx\"}");
    }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temp <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(e->vocab), 64, 1.1f, 0.0f, 0.0f));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist((uint32_t) seed));
    }

    std::string out, pending;
    std::string stop_reason = "max_tokens";
    int n_gen = 0;
    int64_t t0 = now_us();
    int64_t t_prefill_end = t0;
    bool ok = true;
    std::string err;

    llama_batch batch = llama_batch_get_one(toks.data(), (int32_t) toks.size());
    if (llama_decode(e->ctx, batch) != 0) { ok = false; err = e->abort ? "cancelled" : "prefill decode failed"; }
    t_prefill_end = now_us();
    llama_token cur;
    while (ok && n_gen < maxNew) {
        if (e->abort.load()) { stop_reason = "cancelled"; break; }
        if (deadlineMs > 0 && (now_us() - t0) / 1000 > deadlineMs) { stop_reason = "timeout"; break; }
        cur = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, cur)) { stop_reason = "eog"; break; }
        char buf[256];
        int n = llama_token_to_piece(e->vocab, cur, buf, sizeof buf, 0, false);
        if (n > 0) pending.append(buf, n);
        n_gen++;
        size_t cut = utf8_complete_prefix(pending);
        if (cut > 0) {
            std::string piece = pending.substr(0, cut);
            pending.erase(0, cut);
            out += piece;
            if (cb && onToken) {
                jstring jp = to_jstring(env, piece);
                jboolean cont = env->CallBooleanMethod(cb, onToken, jp);
                env->DeleteLocalRef(jp);
                if (env->ExceptionCheck()) { env->ExceptionClear(); cont = JNI_FALSE; }
                if (!cont) { stop_reason = "cancelled"; break; }
            }
        }
        llama_batch b1 = llama_batch_get_one(&cur, 1);
        if (llama_decode(e->ctx, b1) != 0) {
            if (e->abort.load()) stop_reason = "cancelled"; else { ok = false; err = "decode failed"; }
            break;
        }
    }
    out += pending;
    int64_t t_end = now_us();
    llama_sampler_free(smpl);

    double prefill_ms = (t_prefill_end - t0) / 1000.0;
    double decode_ms = (t_end - t_prefill_end) / 1000.0;
    double tps = decode_ms > 0 ? n_gen * 1000.0 / decode_ms : 0;
    double pps = prefill_ms > 0 ? n_prompt * 1000.0 / prefill_ms : 0;
    char meta[512];
    snprintf(meta, sizeof meta,
             "\"prompt_tokens\":%d,\"gen_tokens\":%d,\"prefill_ms\":%.1f,\"decode_ms\":%.1f,"
             "\"prefill_tps\":%.2f,\"decode_tps\":%.2f,\"stop\":\"%s\"",
             n_prompt, n_gen, prefill_ms, decode_ms, pps, tps, stop_reason.c_str());
    std::string json = std::string("{\"ok\":") + (ok ? "true" : "false") + "," + meta +
                       (ok ? "" : ",\"error\":\"" + json_escape(err) + "\"") +
                       ",\"text\":\"" + json_escape(out) + "\"}";
    return to_jstring(env, json);
}

JNIEXPORT void JNICALL
Java_com_rjkyker_shizukuai_broker_llm_LlamaNative_nativeFree(JNIEnv *, jclass, jlong h) {
    auto *e = reinterpret_cast<Engine *>(h);
    if (!e) return;
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

}  // extern "C"
