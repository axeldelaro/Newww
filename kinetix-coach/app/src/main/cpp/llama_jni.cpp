#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "KinetixLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct KinetixLlamaSession {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    uint32_t n_ctx = 2048;
    uint32_t n_batch = 512;
    int32_t n_threads = 4;
    std::atomic<bool> cancel_requested{false};
    std::mutex infer_mutex;
};

std::atomic<bool> g_backend_initialized{false};

void android_log_callback(enum ggml_log_level level, const char * text, void * /*user_data*/) {
    if (!text) return;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR:
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", text);
            break;
        case GGML_LOG_LEVEL_WARN:
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "%s", text);
            break;
        default:
            // Suppress verbose info/debug noise from ggml to keep logcat clean and fast
            break;
    }
}

bool abort_callback(void * data) {
    if (!data) return false;
    auto * flag = static_cast<std::atomic<bool> *>(data);
    return flag->load(std::memory_order_relaxed);
}

// Returns the number of leading complete valid UTF-8 bytes in `s`.
// Any trailing incomplete multi-byte sequence is left for the next token so French accents never corrupt.
size_t valid_utf8_prefix_len(const std::string & s) {
    const auto * bytes = reinterpret_cast<const unsigned char *>(s.data());
    const size_t len = s.size();
    size_t i = 0;
    while (i < len) {
        unsigned char c = bytes[i];
        size_t seq_len = 0;
        if ((c & 0x80) == 0x00) {
            seq_len = 1;
        } else if ((c & 0xE0) == 0xC0) {
            seq_len = 2;
        } else if ((c & 0xF0) == 0xE0) {
            seq_len = 3;
        } else if ((c & 0xF8) == 0xF0) {
            seq_len = 4;
        } else {
            // Unexpected/invalid byte: skip it to prevent permanent stalling
            i += 1;
            continue;
        }

        if (i + seq_len > len) {
            // Incomplete sequence at tail — keep in buffer until next token arrives
            break;
        }

        bool valid_cont = true;
        for (size_t k = 1; k < seq_len; ++k) {
            if ((bytes[i + k] & 0xC0) != 0x80) {
                valid_cont = false;
                break;
            }
        }
        if (!valid_cont) {
            i += 1;
            continue;
        }
        i += seq_len;
    }
    return i;
}

// Safe conversion from standard UTF-8 (including 4-byte emoji!) to Java jstring via UTF-16 jchar.
// Avoids ART CheckJNI crash on 4-byte UTF-8 sequences in NewStringUTF!
jstring utf8_to_jstring(JNIEnv * env, const std::string & utf8) {
    std::vector<jchar> utf16;
    utf16.reserve(utf8.size());
    const auto * bytes = reinterpret_cast<const unsigned char *>(utf8.data());
    const size_t len = utf8.size();
    size_t i = 0;
    while (i < len) {
        uint32_t cp = 0;
        unsigned char c = bytes[i];
        if ((c & 0x80) == 0x00) {
            cp = c;
            i += 1;
        } else if ((c & 0xE0) == 0xC0 && i + 1 < len && (bytes[i + 1] & 0xC0) == 0x80) {
            cp = ((c & 0x1F) << 6) | (bytes[i + 1] & 0x3F);
            i += 2;
        } else if ((c & 0xF0) == 0xE0 && i + 2 < len &&
                   (bytes[i + 1] & 0xC0) == 0x80 && (bytes[i + 2] & 0xC0) == 0x80) {
            cp = ((c & 0x0F) << 12) | ((bytes[i + 1] & 0x3F) << 6) | (bytes[i + 2] & 0x3F);
            i += 3;
        } else if ((c & 0xF8) == 0xF0 && i + 3 < len &&
                   (bytes[i + 1] & 0xC0) == 0x80 && (bytes[i + 2] & 0xC0) == 0x80 &&
                   (bytes[i + 3] & 0xC0) == 0x80) {
            cp = ((c & 0x07) << 18) | ((bytes[i + 1] & 0x3F) << 12) |
                 ((bytes[i + 2] & 0x3F) << 6) | (bytes[i + 3] & 0x3F);
            i += 4;
        } else {
            i += 1;
            continue;
        }

        if (cp <= 0xFFFF) {
            utf16.push_back(static_cast<jchar>(cp));
        } else if (cp <= 0x10FFFF) {
            cp -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

std::string token_to_piece(const llama_vocab * vocab, llama_token token) {
    char stack_buf[256];
    int32_t n = llama_token_to_piece(vocab, token, stack_buf, static_cast<int32_t>(sizeof(stack_buf)), 0, false);
    if (n >= 0) {
        return std::string(stack_buf, static_cast<size_t>(n));
    }
    std::string dyn_buf(static_cast<size_t>(-n), '\0');
    int32_t n2 = llama_token_to_piece(vocab, token, dyn_buf.data(), static_cast<int32_t>(dyn_buf.size()), 0, false);
    if (n2 < 0) return {};
    dyn_buf.resize(static_cast<size_t>(n2));
    return dyn_buf;
}

bool has_stop_marker(const std::string & text, size_t & stop_pos) {
    static const char * kStops[] = {
        "<end_of_turn>",
        "<start_of_turn>",
        "<|eot_id|>",
        "<|im_end|>"
    };
    size_t best = std::string::npos;
    for (const char * marker : kStops) {
        size_t pos = text.find(marker);
        if (pos != std::string::npos && pos < best) {
            best = pos;
        }
    }
    if (best != std::string::npos) {
        stop_pos = best;
        return true;
    }
    return false;
}

// Compute how many trailing bytes could be the prefix of any stop marker so we hold them back cleanly
size_t trailing_stop_prefix_len(const std::string & text) {
    static const char * kStops[] = {
        "<end_of_turn>",
        "<start_of_turn>",
        "<|eot_id|>",
        "<|im_end|>"
    };
    size_t max_keep = 0;
    for (const char * marker : kStops) {
        const size_t mlen = std::strlen(marker);
        const size_t max_check = std::min(mlen - 1, text.size());
        for (size_t k = max_check; k >= 1; --k) {
            if (text.compare(text.size() - k, k, marker, k) == 0) {
                if (k > max_keep) max_keep = k;
                break;
            }
        }
    }
    return max_keep;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_kinetix_coach_llm_LlamaBridge_nativeInitBackend(JNIEnv * /*env*/, jobject /*thiz*/) {
    bool expected = false;
    if (g_backend_initialized.compare_exchange_strong(expected, true)) {
        llama_log_set(android_log_callback, nullptr);
        llama_backend_init();
        LOGI("llama.cpp backend initialized (%s)", llama_version());
    }
}

JNIEXPORT jlong JNICALL
Java_com_kinetix_coach_llm_LlamaBridge_nativeLoadModel(
        JNIEnv * env,
        jobject /*thiz*/,
        jstring jModelPath,
        jint nCtx,
        jint nThreads) {
    if (!jModelPath) return 0L;

    if (!g_backend_initialized.load()) {
        llama_log_set(android_log_callback, nullptr);
        llama_backend_init();
        g_backend_initialized.store(true);
    }

    const char * path = env->GetStringUTFChars(jModelPath, nullptr);
    if (!path) return 0L;

    LOGI("Loading GGUF model: %s (nCtx=%d, nThreads=%d)", path, nCtx, nThreads);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;

    llama_model * model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jModelPath, path);

    if (!model) {
        LOGE("Failed to load llama model");
        return 0L;
    }

    auto * session = new KinetixLlamaSession();
    session->model = model;
    session->n_ctx = static_cast<uint32_t>(std::max<int>(512, nCtx));
    session->n_batch = 512;
    session->n_threads = std::max<int32_t>(1, std::min<int32_t>(8, nThreads));

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = session->n_ctx;
    cparams.n_batch = session->n_batch;
    cparams.n_ubatch = session->n_batch;
    cparams.n_threads = session->n_threads;
    cparams.n_threads_batch = session->n_threads;
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    cparams.no_perf = true;
    cparams.abort_callback = abort_callback;
    cparams.abort_callback_data = &session->cancel_requested;

    session->ctx = llama_init_from_model(model, cparams);
    if (!session->ctx) {
        LOGE("Failed to initialize llama_context");
        llama_model_free(model);
        delete session;
        return 0L;
    }

    char desc[128] = {};
    llama_model_desc(model, desc, sizeof(desc));
    LOGI("Model loaded successfully: %s (ctx=%u, threads=%d)", desc, llama_n_ctx(session->ctx), session->n_threads);

    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_com_kinetix_coach_llm_LlamaBridge_nativeUpdateThreads(
        JNIEnv * /*env*/,
        jobject /*thiz*/,
        jlong handle,
        jint nThreads) {
    if (handle == 0L) return;
    auto * session = reinterpret_cast<KinetixLlamaSession *>(handle);
    int32_t clamped = std::max<int32_t>(1, std::min<int32_t>(8, nThreads));
    session->n_threads = clamped;
    if (session->ctx) {
        llama_set_n_threads(session->ctx, clamped, clamped);
        LOGI("Updated inference threads to %d", clamped);
    }
}

JNIEXPORT jstring JNICALL
Java_com_kinetix_coach_llm_LlamaBridge_nativeFormatChat(
        JNIEnv * env,
        jobject /*thiz*/,
        jlong handle,
        jobjectArray jRoles,
        jobjectArray jContents) {
    if (handle == 0L || !jRoles || !jContents) return nullptr;
    auto * session = reinterpret_cast<KinetixLlamaSession *>(handle);
    if (!session->model) return nullptr;

    const char * tmpl = llama_model_chat_template(session->model, nullptr);
    if (!tmpl || tmpl[0] == '\0') {
        return nullptr;
    }

    const jsize count = env->GetArrayLength(jRoles);
    if (count <= 0 || count != env->GetArrayLength(jContents)) {
        return nullptr;
    }

    std::vector<std::string> role_storage;
    std::vector<std::string> content_storage;
    role_storage.reserve(static_cast<size_t>(count));
    content_storage.reserve(static_cast<size_t>(count));

    for (jsize i = 0; i < count; ++i) {
        auto jRole = static_cast<jstring>(env->GetObjectArrayElement(jRoles, i));
        auto jContent = static_cast<jstring>(env->GetObjectArrayElement(jContents, i));
        const char * r = jRole ? env->GetStringUTFChars(jRole, nullptr) : "";
        const char * c = jContent ? env->GetStringUTFChars(jContent, nullptr) : "";
        role_storage.emplace_back(r ? r : "");
        content_storage.emplace_back(c ? c : "");
        if (jRole) {
            env->ReleaseStringUTFChars(jRole, r);
            env->DeleteLocalRef(jRole);
        }
        if (jContent) {
            env->ReleaseStringUTFChars(jContent, c);
            env->DeleteLocalRef(jContent);
        }
    }

    std::vector<llama_chat_message> msgs(static_cast<size_t>(count));
    size_t total_chars = 0;
    for (size_t i = 0; i < msgs.size(); ++i) {
        msgs[i].role = role_storage[i].c_str();
        msgs[i].content = content_storage[i].c_str();
        total_chars += role_storage[i].size() + content_storage[i].size();
    }

    std::vector<char> formatted(std::max<size_t>(2048, total_chars * 2 + 512));
    int32_t res = llama_chat_apply_template(
            tmpl,
            msgs.data(),
            msgs.size(),
            true,
            formatted.data(),
            static_cast<int32_t>(formatted.size()));

    if (res > static_cast<int32_t>(formatted.size())) {
        formatted.resize(static_cast<size_t>(res) + 1);
        res = llama_chat_apply_template(
                tmpl,
                msgs.data(),
                msgs.size(),
                true,
                formatted.data(),
                static_cast<int32_t>(formatted.size()));
    }

    if (res <= 0) {
        return nullptr;
    }

    std::string out(formatted.data(), static_cast<size_t>(res));
    return utf8_to_jstring(env, out);
}

JNIEXPORT jint JNICALL
Java_com_kinetix_coach_llm_LlamaBridge_nativeGenerate(
        JNIEnv * env,
        jobject /*thiz*/,
        jlong handle,
        jstring jPrompt,
        jint maxTokens,
        jfloat temp,
        jfloat topP,
        jint topK,
        jfloat repeatPenalty,
        jobject callback) {
    if (handle == 0L || !jPrompt || !callback) return -1;

    auto * session = reinterpret_cast<KinetixLlamaSession *>(handle);
    std::lock_guard<std::mutex> lock(session->infer_mutex);

    if (!session->model || !session->ctx) return -2;

    session->cancel_requested.store(false, std::memory_order_relaxed);

    jclass cb_class = env->GetObjectClass(callback);
    jmethodID on_token_method = env->GetMethodID(cb_class, "onToken", "(Ljava/lang/String;)Z");
    env->DeleteLocalRef(cb_class);
    if (!on_token_method) {
        LOGE("TokenCallback.onToken(String)Z not found");
        return -3;
    }

    const char * prompt_cstr = env->GetStringUTFChars(jPrompt, nullptr);
    if (!prompt_cstr) return -4;
    std::string prompt(prompt_cstr);
    env->ReleaseStringUTFChars(jPrompt, prompt_cstr);

    const llama_vocab * vocab = llama_model_get_vocab(session->model);
    if (!vocab) return -5;

    // 1. Tokenize prompt
    int32_t n_prompt = -llama_tokenize(
            vocab,
            prompt.c_str(),
            static_cast<int32_t>(prompt.size()),
            nullptr,
            0,
            true,
            true);
    if (n_prompt <= 0) {
        LOGE("Prompt tokenization failed: %d", n_prompt);
        return -6;
    }

    std::vector<llama_token> prompt_tokens(static_cast<size_t>(n_prompt));
    int32_t n_tok = llama_tokenize(
            vocab,
            prompt.c_str(),
            static_cast<int32_t>(prompt.size()),
            prompt_tokens.data(),
            static_cast<int32_t>(prompt_tokens.size()),
            true,
            true);
    if (n_tok <= 0) {
        LOGE("Prompt tokenization pass 2 failed: %d", n_tok);
        return -7;
    }
    prompt_tokens.resize(static_cast<size_t>(n_tok));

    const uint32_t ctx_limit = llama_n_ctx(session->ctx);
    const uint32_t reserve_gen = 96;
    if (prompt_tokens.size() + reserve_gen >= ctx_limit) {
        const size_t keep = (ctx_limit > reserve_gen + 32) ? (ctx_limit - reserve_gen) : 128;
        // Keep the first token (BOS) and the tail of the prompt if it ever overflows n_ctx
        if (keep < prompt_tokens.size()) {
            std::vector<llama_token> trimmed;
            trimmed.reserve(keep);
            trimmed.push_back(prompt_tokens.front());
            trimmed.insert(trimmed.end(), prompt_tokens.end() - static_cast<ptrdiff_t>(keep - 1), prompt_tokens.end());
            prompt_tokens.swap(trimmed);
            LOGW("Prompt truncated to %zu tokens to fit ctx=%u", prompt_tokens.size(), ctx_limit);
        }
    }

    // 2. Reset KV cache for deterministic RAG-grounded turn
    llama_memory_clear(llama_get_memory(session->ctx), true);

    // 3. Prefill in n_batch chunks using modern llama_batch_ext
    llama_batch_ext * batch = llama_batch_ext_init(session->ctx);
    if (!batch) {
        LOGE("llama_batch_ext_init failed");
        return -8;
    }

    const int32_t n_batch = static_cast<int32_t>(llama_n_batch(session->ctx));
    const int32_t total_prompt = static_cast<int32_t>(prompt_tokens.size());
    bool failed = false;

    for (int32_t i = 0; i < total_prompt; i += n_batch) {
        if (session->cancel_requested.load(std::memory_order_relaxed)) {
            failed = true;
            break;
        }
        llama_batch_ext_clear(batch);
        const int32_t chunk = std::min(n_batch, total_prompt - i);
        for (int32_t j = 0; j < chunk; ++j) {
            int32_t idx = llama_batch_ext_add_token(batch, 0, prompt_tokens[static_cast<size_t>(i + j)]);
            llama_pos pos = i + j;
            llama_batch_ext_set_pos(batch, idx, &pos);
            llama_batch_ext_set_output_logits(batch, idx, (i + j == total_prompt - 1));
        }
        int32_t rc = llama_process(session->ctx, LLAMA_PROCESS_TYPE_DECODE, batch);
        if (rc != 0) {
            if (rc != 2) {
                LOGE("Prefill llama_process failed with rc=%d", rc);
            }
            failed = true;
            break;
        }
    }

    if (failed || session->cancel_requested.load(std::memory_order_relaxed)) {
        llama_batch_ext_free(batch);
        return 0;
    }

    // 4. Build fast sampler chain: Top-K FIRST (critical for Gemma-3 262k vocab speed!), then penalties, Top-P, Temp, Dist
    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler * smpl = llama_sampler_chain_init(sparams);

    const int32_t n_vocab = llama_vocab_n_tokens(vocab);
    if (topK > 0) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK));
    }
    if (repeatPenalty > 1.001f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(n_vocab, 64, repeatPenalty, 0.0f, 0.0f));
    }
    if (topP > 0.0f && topP < 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
    }
    if (temp <= 0.01f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    // 5. Generation loop with UTF-8 multi-byte buffering and stop-marker guard
    int32_t generated = 0;
    const int32_t max_steps = std::min<int32_t>(
            std::max<int32_t>(1, maxTokens),
            static_cast<int32_t>(ctx_limit) - total_prompt - 4);

    llama_pos cur_pos = total_prompt;
    std::string pending_bytes;
    pending_bytes.reserve(64);

    auto emit_text = [&](const std::string & text) -> bool {
        if (text.empty()) return true;
        jstring jPiece = utf8_to_jstring(env, text);
        if (!jPiece) return false;
        jboolean keep_going = env->CallBooleanMethod(callback, on_token_method, jPiece);
        env->DeleteLocalRef(jPiece);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        return keep_going == JNI_TRUE;
    };

    for (int32_t step = 0; step < max_steps; ++step) {
        if (session->cancel_requested.load(std::memory_order_relaxed)) {
            break;
        }

        const llama_token token = llama_sampler_sample(smpl, session->ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }

        generated++;
        std::string piece = token_to_piece(vocab, token);
        if (!piece.empty()) {
            pending_bytes.append(piece);

            // Check for complete stop markers (e.g., <end_of_turn>)
            size_t stop_pos = std::string::npos;
            if (has_stop_marker(pending_bytes, stop_pos)) {
                if (stop_pos > 0) {
                    std::string before = pending_bytes.substr(0, stop_pos);
                    size_t valid_len = valid_utf8_prefix_len(before);
                    if (valid_len > 0) {
                        emit_text(before.substr(0, valid_len));
                    }
                }
                pending_bytes.clear();
                break;
            }

            // Hold back any partial trailing stop-marker prefix or incomplete UTF-8 sequence
            size_t hold_stop = trailing_stop_prefix_len(pending_bytes);
            size_t safe_end = pending_bytes.size() - hold_stop;
            if (safe_end > 0) {
                std::string candidate = pending_bytes.substr(0, safe_end);
                size_t valid_len = valid_utf8_prefix_len(candidate);
                if (valid_len > 0) {
                    bool cont = emit_text(candidate.substr(0, valid_len));
                    pending_bytes.erase(0, valid_len);
                    if (!cont) {
                        session->cancel_requested.store(true, std::memory_order_relaxed);
                        break;
                    }
                }
            }
        }

        // Feed sampled token back for next step
        llama_batch_ext_clear(batch);
        int32_t idx = llama_batch_ext_add_token(batch, 0, token);
        llama_batch_ext_set_pos(batch, idx, &cur_pos);
        llama_batch_ext_set_output_logits(batch, idx, true);
        cur_pos++;

        int32_t rc = llama_process(session->ctx, LLAMA_PROCESS_TYPE_DECODE, batch);
        if (rc != 0) {
            if (rc != 2) {
                LOGE("Decode llama_process failed with rc=%d", rc);
            }
            break;
        }
    }

    // Flush any remaining non-stop valid UTF-8 bytes
    if (!pending_bytes.empty() && !session->cancel_requested.load(std::memory_order_relaxed)) {
        size_t stop_pos = std::string::npos;
        if (has_stop_marker(pending_bytes, stop_pos)) {
            pending_bytes.resize(stop_pos);
        }
        size_t valid_len = valid_utf8_prefix_len(pending_bytes);
        if (valid_len > 0) {
            emit_text(pending_bytes.substr(0, valid_len));
        }
    }

    llama_sampler_free(smpl);
    llama_batch_ext_free(batch);
    return generated;
}

JNIEXPORT void JNICALL
Java_com_kinetix_coach_llm_LlamaBridge_nativeCancel(
        JNIEnv * /*env*/,
        jobject /*thiz*/,
        jlong handle) {
    if (handle == 0L) return;
    auto * session = reinterpret_cast<KinetixLlamaSession *>(handle);
    session->cancel_requested.store(true, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_kinetix_coach_llm_LlamaBridge_nativeUnloadModel(
        JNIEnv * /*env*/,
        jobject /*thiz*/,
        jlong handle) {
    if (handle == 0L) return;
    auto * session = reinterpret_cast<KinetixLlamaSession *>(handle);
    session->cancel_requested.store(true, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(session->infer_mutex);
        if (session->ctx) {
            llama_free(session->ctx);
            session->ctx = nullptr;
        }
        if (session->model) {
            llama_model_free(session->model);
            session->model = nullptr;
        }
    }
    delete session;
    LOGI("Model unloaded cleanly");
}

} // extern "C"
