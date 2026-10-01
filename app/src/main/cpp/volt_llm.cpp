// Volt robotunun internetsiz yapay zeka beyni (llama.cpp ile)
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <algorithm>
#include <cstdio>
#include "llama.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "VoltLLM", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "VoltLLM", __VA_ARGS__)

static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;
static const llama_vocab *g_vocab = nullptr;
static std::vector<uint8_t> g_sys_state;
static volatile bool g_stop = false;

static std::vector<llama_token> tokenize(const std::string &s, bool add_special) {
    int n = -llama_tokenize(g_vocab, s.c_str(), (int32_t) s.size(), nullptr, 0, add_special, true);
    if (n <= 0) return {};
    std::vector<llama_token> t(n);
    int r = llama_tokenize(g_vocab, s.c_str(), (int32_t) s.size(), t.data(), n, add_special, true);
    if (r < 0) return {};
    t.resize(r);
    return t;
}

static bool decode_all(std::vector<llama_token> &toks) {
    int nb = (int) llama_n_batch(g_ctx);
    for (size_t i = 0; i < toks.size(); i += nb) {
        int n = std::min((int) (toks.size() - i), nb);
        if (llama_decode(g_ctx, llama_batch_get_one(toks.data() + i, n)) != 0) return false;
    }
    return true;
}

// Tam olmayan UTF-8 karakterini sona birakir
static size_t utf8_complete_len(const std::string &s) {
    size_t n = s.size();
    if (n == 0) return 0;
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = (unsigned char) s[i - 1];
        if ((c & 0xC0) != 0x80) {
            int need = (c < 0x80) ? 1 : ((c >> 5) == 0x6) ? 2 : ((c >> 4) == 0xE) ? 3 : ((c >> 3) == 0x1E) ? 4 : 1;
            return (n - (i - 1) >= (size_t) need) ? n : i - 1;
        }
        i--; back++;
    }
    return n;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_voltcu_robot_VoltLlm_nativeLoad(JNIEnv *env, jobject, jstring jpath, jstring jsys, jint threads, jint nctx, jstring jcache) {
    const char *cachec = env->GetStringUTFChars(jcache, nullptr);
    std::string cache(cachec);
    env->ReleaseStringUTFChars(jcache, cachec);
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    const char *sysc = env->GetStringUTFChars(jsys, nullptr);
    std::string sys(sysc);
    llama_backend_init();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.use_mlock = true;   // izin verilirse modeli RAM'de tut
    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    env->ReleaseStringUTFChars(jsys, sysc);
    if (!g_model) { LOGE("model yuklenemedi"); return JNI_FALSE; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) nctx;
    cp.n_batch = 256;
    cp.n_ubatch = 256;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) { LOGE("context olusmadi"); return JNI_FALSE; }
    g_vocab = llama_model_get_vocab(g_model);

    // Daha once hesaplanmis kisilik durumu varsa dosyadan oku (acilis cok hizlanir)
    int64_t t0 = llama_time_us();
    if (!cache.empty()) {
        FILE *f = fopen(cache.c_str(), "rb");
        if (f) {
            fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
            if (n > 0) {
                g_sys_state.resize((size_t) n);
                size_t rd = fread(g_sys_state.data(), 1, (size_t) n, f);
                fclose(f);
                if (rd == (size_t) n && llama_state_seq_set_data(g_ctx, g_sys_state.data(), g_sys_state.size(), 0) > 0) {
                    LOGI("hazir (onbellekten): %.1f sn, %ld bayt", (llama_time_us() - t0) / 1e6, n);
                    return JNI_TRUE;
                }
                LOGE("onbellek bozuk, yeniden hesaplaniyor");
                llama_memory_clear(llama_get_memory(g_ctx), true);
            } else fclose(f);
        }
    }
    // Kisilik tarifini bir kez oku ve hafizada sakla
    std::vector<llama_token> st = tokenize(sys, true);
    if (!decode_all(st)) { LOGE("sistem metni islenemedi"); return JNI_FALSE; }
    size_t sz = llama_state_seq_get_size(g_ctx, 0);
    g_sys_state.resize(sz);
    llama_state_seq_get_data(g_ctx, g_sys_state.data(), sz, 0);
    LOGI("hazir: sistem %d token, %.1f sn, durum %zu bayt", (int) st.size(), (llama_time_us() - t0) / 1e6, sz);
    if (!cache.empty()) {
        std::string tmp = cache + ".tmp";
        FILE *f = fopen(tmp.c_str(), "wb");
        if (f) {
            size_t wr = fwrite(g_sys_state.data(), 1, sz, f); fclose(f);
            if (wr == sz) rename(tmp.c_str(), cache.c_str()); else remove(tmp.c_str());
            LOGI("kisilik onbellege yazildi");
        }
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_voltcu_robot_VoltLlm_nativeStop(JNIEnv *, jobject) { g_stop = true; }

extern "C" JNIEXPORT jint JNICALL
Java_com_voltcu_robot_VoltLlm_nativeGenerate(JNIEnv *env, jobject, jstring juser, jint maxTokens, jobject sink, jboolean keep) {
    if (!g_ctx) return -1;
    g_stop = false;
    jclass cls = env->GetObjectClass(sink);
    jmethodID onBytes = env->GetMethodID(cls, "onBytes", "([B)Z");

    const char *uc = env->GetStringUTFChars(juser, nullptr);
    std::string user(uc);
    env->ReleaseStringUTFChars(juser, uc);

    int64_t t0 = llama_time_us();
    std::vector<llama_token> ut = tokenize(user, false);
    llama_memory_t mem = llama_get_memory(g_ctx);
    int used = (int) llama_memory_seq_pos_max(mem, 0) + 1;
    bool fits = used + (int) ut.size() + maxTokens + 8 < (int) llama_n_ctx(g_ctx);
    if (!keep || !fits) {
        llama_memory_clear(mem, true);
        if (llama_state_seq_set_data(g_ctx, g_sys_state.data(), g_sys_state.size(), 0) == 0) {
            LOGE("sistem durumu geri yuklenemedi");
            return -2;
        }
        LOGI("yeni sohbet (keep=%d, dolu=%d)", (int) keep, used);
    } else {
        LOGI("sohbete devam, hafizada %d token", used);
    }
    if (!decode_all(ut)) { LOGE("soru islenemedi"); return -3; }
    int64_t t1 = llama_time_us();

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(g_vocab), 64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.6f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist((uint32_t) (llama_time_us() & 0xffffffff)));

    std::string pending;
    int produced = 0;
    char buf[256];
    for (; produced < maxTokens && !g_stop; produced++) {
        llama_token tok = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(g_vocab, tok)) break;
        int n = llama_token_to_piece(g_vocab, tok, buf, sizeof(buf), 0, false);
        if (n > 0) pending.append(buf, n);
        size_t ok = utf8_complete_len(pending);
        if (ok > 0) {
            jbyteArray arr = env->NewByteArray((jsize) ok);
            env->SetByteArrayRegion(arr, 0, (jsize) ok, (const jbyte *) pending.data());
            jboolean cont = env->CallBooleanMethod(sink, onBytes, arr);
            env->DeleteLocalRef(arr);
            pending.erase(0, ok);
            if (!cont) break;
        }
        if (llama_decode(g_ctx, llama_batch_get_one(&tok, 1)) != 0) break;
    }
    llama_sampler_free(smpl);
    int64_t t2 = llama_time_us();
    LOGI("soru %d token %.1f sn, cevap %d token %.1f sn (%.2f t/s)", (int) ut.size(), (t1 - t0) / 1e6,
         produced, (t2 - t1) / 1e6, produced / ((t2 - t1) / 1e6 + 1e-9));
    return produced;
}
