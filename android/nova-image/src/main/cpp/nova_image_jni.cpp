#include <jni.h>
#include <android/log.h>
#include <stable-diffusion.h>
#include <atomic>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
std::mutex active_mutex;
sd_ctx_t* active_ctx = nullptr;  // Protected by active_mutex when read/written
std::atomic<bool> is_running{false};
std::atomic<bool> cancel_requested{false};
std::atomic<int> completed_steps{0};
std::atomic<int> total_steps{0};

void throw_java(JNIEnv* env, const std::string& message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) {
        env->ThrowNew(cls, message.c_str());
        env->DeleteLocalRef(cls);
    }
}

class UtfChars {
public:
    UtfChars(JNIEnv* env, jstring source) : env_(env), source_(source) {
        if (source_) chars_ = env_->GetStringUTFChars(source_, nullptr);
    }
    ~UtfChars() {
        if (chars_) env_->ReleaseStringUTFChars(source_, chars_);
    }
    std::string str() const { return chars_ ? std::string(chars_) : std::string(); }
private:
    JNIEnv* env_;
    jstring source_;
    const char* chars_ = nullptr;
};

void on_progress(int step, int steps, float /*time*/, void* /*data*/) {
    completed_steps.store(step);
    total_steps.store(steps);
}

class RunningFlag {
public:
    RunningFlag() {
        if (is_running.exchange(true)) {
            throw std::runtime_error("NOVA уже создаёт изображение");
        }
        cancel_requested.store(false);
        completed_steps.store(0);
        total_steps.store(0);
    }
    ~RunningFlag() { is_running.store(false); }
};

class Context {
public:
    explicit Context(sd_ctx_t* ctx) : ctx_(ctx) {}
    ~Context() {
        {
            std::lock_guard<std::mutex> lock(active_mutex);
            if (active_ctx == ctx_) active_ctx = nullptr;
        }
        if (ctx_) free_sd_ctx(ctx_);
    }
    sd_ctx_t* get() const { return ctx_; }
private:
    sd_ctx_t* ctx_;
};

class Result {
public:
    sd_image_generation_result_t* value = nullptr;
    ~Result() {
        if (value) free_sd_image_generation_result(value);
    }
};

std::vector<uint8_t> copy_rgb(sd_image_t img, int size) {
    if (img.width != static_cast<uint32_t>(size) ||
        img.height != static_cast<uint32_t>(size) ||
        (img.channel != 3 && img.channel != 4) || !img.data) {
        throw std::runtime_error("Неверный формат результата генератора");
    }
    const size_t pixels = static_cast<size_t>(size) * static_cast<size_t>(size);
    std::vector<uint8_t> rgb(pixels * 3);
    if (img.channel == 3) {
        std::memcpy(rgb.data(), img.data, rgb.size());
    } else {
        for (size_t i = 0; i < pixels; ++i) {
            rgb[3 * i] = img.data[4 * i];
            rgb[3 * i + 1] = img.data[4 * i + 1];
            rgb[3 * i + 2] = img.data[4 * i + 2];
        }
    }
    return rgb;
}
}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ai_nova_image_NativeImageEngine_nativeGenerate(
    JNIEnv* env, jobject /*self*/, jstring modelPath,
    jstring prompt, jstring negativePrompt, jint size, jint steps, jlong seed) {
    try {
        RunningFlag guard;
        if (!modelPath || !prompt || !negativePrompt)
            throw std::runtime_error("Недостаточно параметров генерации");
        if (size != 256 && size != 512)
            throw std::runtime_error("Разрешены размеры 256 или 512");
        if (steps < 1 || steps > 30)
            throw std::runtime_error("Количество шагов вне диапазона");

        const std::string path = UtfChars(env, modelPath).str();
        const std::string text = UtfChars(env, prompt).str();
        const std::string negative = UtfChars(env, negativePrompt).str();
        if (path.empty() || text.empty())
            throw std::runtime_error("Выбери графическую модель и опиши картинку");
        if (cancel_requested.load())
            throw std::runtime_error("Генерация остановлена");

        sd_ctx_params_t ctx_params;
        sd_ctx_params_init(&ctx_params);
        ctx_params.model_path = path.c_str();
        ctx_params.n_threads = 4;
        ctx_params.backend = "cpu";
        ctx_params.enable_mmap = true;

        sd_set_progress_callback(on_progress, nullptr);
        Context context(new_sd_ctx(&ctx_params));
        if (!context.get())
            throw std::runtime_error("Не удалось загрузить графическую модель: несовместимый файл или мало памяти");
        if (!sd_ctx_supports_image_generation(context.get()))
            throw std::runtime_error("Эта модель не поддерживает создание изображений");

        {
            std::lock_guard<std::mutex> lock(active_mutex);
            active_ctx = context.get();
            if (cancel_requested.load()) sd_cancel_generation(active_ctx, SD_CANCEL_ALL);
        }
        sd_img_gen_params_t image_params;
        sd_img_gen_params_init(&image_params);
        image_params.prompt = text.c_str();
        image_params.negative_prompt = negative.c_str();
        image_params.width = size;
        image_params.height = size;
        image_params.seed = static_cast<int64_t>(seed);
        image_params.batch_count = 1;
        image_params.sample_params.sample_steps = steps;
        image_params.sample_params.sample_method = EULER_A_SAMPLE_METHOD;
        image_params.sample_params.scheduler = DISCRETE_SCHEDULER;

        Result result;
        if (!generate_image(context.get(), &image_params, &result.value)
            || !result.value || result.value->count < 1) {
            throw std::runtime_error(cancel_requested.load() ?
                "Генерация остановлена" :
                "Генератор не создал изображение. Проверь модель и свободную память");
        }
        if (cancel_requested.load())
            throw std::runtime_error("Генерация остановлена");

        auto rgb = copy_rgb(result.value->items[0].image, size);
        jbyteArray javaImage = env->NewByteArray(static_cast<jsize>(rgb.size()));
        if (!javaImage)
            throw std::runtime_error("Недостаточно памяти для передачи изображения");
        env->SetByteArrayRegion(javaImage, 0, static_cast<jsize>(rgb.size()),
                                reinterpret_cast<const jbyte*>(rgb.data()));
        return javaImage;
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, "NOVAImage", "%s", e.what());
        if (!env->ExceptionCheck()) throw_java(env, e.what());
        return nullptr;
    } catch (...) {
        if (!env->ExceptionCheck()) throw_java(env, "Неизвестная ошибка нативного генератора");
        return nullptr;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ai_nova_image_NativeImageEngine_nativeCancel(
    JNIEnv* /*env*/, jobject /*self*/) {
    cancel_requested.store(true);
    std::lock_guard<std::mutex> lock(active_mutex);
    if (active_ctx) {
        sd_cancel_generation(active_ctx, SD_CANCEL_ALL);
        return JNI_TRUE;
    }
    return is_running.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_ai_nova_image_NativeImageEngine_nativeProgress(
    JNIEnv* env, jobject /*self*/) {
    jint values[2] = {completed_steps.load(), total_steps.load()};
    jintArray output = env->NewIntArray(2);
    if (output) env->SetIntArrayRegion(output, 0, 2, values);
    return output;
}
