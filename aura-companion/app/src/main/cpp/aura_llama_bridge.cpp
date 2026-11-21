#include <jni.h>
#include <android/log.h>
#include "llama.h"

#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#define LOG_TAG "AuraLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
std::mutex g_mutex;
llama_model* g_model = nullptr;
llama_context* g_ctx = nullptr;
std::atomic<bool> g_stop_flag{false};
bool g_backend_initialized = false;

bool is_valid_utf8_prefix(const std::string& s, size_t& valid_len) {
    size_t i = 0;
    const size_t n = s.size();
    while (i < n) {
        unsigned char c = static_cast<unsigned char>(s[i]);
        size_t need = 0;
        if (c < 0x80) need = 1;
        else if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        else { i++; continue; }
        if (i + need > n) break;
        i += need;
    }
    valid_len = i;
    return valid_len > 0;
}

void unload_locked() {
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_newww_auracompanion_ai_AuraLlamaEngine_nativeLoadModel(
        JNIEnv* env, jobject, jstring modelPath, jint nThreads, jint nCtx) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const char* pathStr = env->GetStringUTFChars(modelPath, nullptr);
    if (!pathStr) return JNI_FALSE;
    std::string path(pathStr);
    env->ReleaseStringUTFChars(modelPath, pathStr);

    unload_locked();
    if (!g_backend_initialized) {
        llama_backend_init();
        g_backend_initialized = true;
    }

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    g_model = llama_model_load_from_file(path.c_str(), mparams);
    if (!g_model) {
        LOGE("Failed to load GGUF model from %s", path.c_str());
        return JNI_FALSE;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = nCtx > 256 ? nCtx : 2048;
    cparams.n_batch = 256;
    cparams.n_threads = nThreads > 0 ? nThreads : 4;
    cparams.n_threads_batch = cparams.n_threads;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("Failed to initialize llama context");
        unload_locked();
        return JNI_FALSE;
    }

    LOGI("Aura loaded 3B model %s (ctx=%d, threads=%d)", path.c_str(), cparams.n_ctx, cparams.n_threads);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_newww_auracompanion_ai_AuraLlamaEngine_nativeStop(JNIEnv*, jobject) {
    g_stop_flag.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_newww_auracompanion_ai_AuraLlamaEngine_nativeUnload(JNIEnv*, jobject) {
    g_stop_flag.store(true);
    std::lock_guard<std::mutex> lock(g_mutex);
    unload_locked();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_newww_auracompanion_ai_AuraLlamaEngine_nativeGenerate(
        JNIEnv* env, jobject, jstring jPrompt, jint maxTokens, jfloat temperature, jobject tokenCallback) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_stop_flag.store(false);
    if (!g_model || !g_ctx) return env->NewStringUTF("");

    const char* promptChars = env->GetStringUTFChars(jPrompt, nullptr);
    if (!promptChars) return env->NewStringUTF("");
    std::string prompt(promptChars);
    env->ReleaseStringUTFChars(jPrompt, promptChars);

    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    if (!vocab) return env->NewStringUTF("");

    llama_memory_clear(llama_get_memory(g_ctx), true);

    const int n_ctx = static_cast<int>(llama_n_ctx(g_ctx));
    // parse_special=true pour <|begin_of_text|>, <|start_header_id|>, <|eot_id|>; add_special=false car <|begin_of_text|> est déjà dans le prompt
    int n_prompt_est = -llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, false, true);
    if (n_prompt_est <= 0) return env->NewStringUTF("");

    std::vector<llama_token> tokens(n_prompt_est);
    int n_tokens = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                  tokens.data(), static_cast<int32_t>(tokens.size()), false, true);
    if (n_tokens <= 0) return env->NewStringUTF("");
    tokens.resize(n_tokens);

    const int max_prompt = n_ctx - 96;
    if (static_cast<int>(tokens.size()) > max_prompt && max_prompt > 64) {
        tokens.erase(tokens.begin() + 1, tokens.end() - max_prompt + 1);
    }

    const int chunk_size = 256;
    for (size_t offset = 0; offset < tokens.size(); offset += chunk_size) {
        if (g_stop_flag.load()) break;
        int len = static_cast<int>(std::min<size_t>(chunk_size, tokens.size() - offset));
        llama_batch batch = llama_batch_get_one(tokens.data() + offset, len);
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("llama_decode failed during prompt eval");
            return env->NewStringUTF("");
        }
    }

    // top_k / top_p avant penalties pour un échantillonnage ultra-rapide sur ARM64
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.88f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(
            llama_vocab_n_tokens(vocab), 64, 1.12f, 0.0f, 0.0f));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature > 0.05f ? temperature : 0.65f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    jclass cbClass = nullptr;
    jmethodID cbMethod = nullptr;
    if (tokenCallback != nullptr) {
        cbClass = env->GetObjectClass(tokenCallback);
        if (cbClass) cbMethod = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    }

    std::string full_output;
    std::string utf8_pending;
    const int limit = maxTokens > 16 ? maxTokens : 180;

    for (int i = 0; i < limit; ++i) {
        if (g_stop_flag.load()) break;

        llama_token tok = llama_sampler_sample(sampler, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        llama_sampler_accept(sampler, tok);

        char piece[256];
        int n = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
        if (n > 0) {
            utf8_pending.append(piece, n);
            size_t valid_len = 0;
            if (is_valid_utf8_prefix(utf8_pending, valid_len)) {
                std::string emit = utf8_pending.substr(0, valid_len);
                utf8_pending.erase(0, valid_len);
                full_output += emit;

                size_t s1 = full_output.find("<|eot_id|>");
                size_t s2 = full_output.find("<|start_header_id|>");
                size_t s3 = full_output.find("<end_of_turn>");
                size_t stopPos = std::min(s1, std::min(s2, s3));
                if (stopPos != std::string::npos) {
                    full_output.erase(stopPos);
                    break;
                }

                if (cbMethod && !emit.empty()) {
                    jstring jPiece = env->NewStringUTF(emit.c_str());
                    env->CallVoidMethod(tokenCallback, cbMethod, jPiece);
                    env->DeleteLocalRef(jPiece);
                }
            }
        }

        llama_batch next_batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(g_ctx, next_batch) != 0) break;
    }

    if (cbClass) env->DeleteLocalRef(cbClass);
    llama_sampler_free(sampler);
    return env->NewStringUTF(full_output.c_str());
}
