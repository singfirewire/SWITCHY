// SWITCHY — Oboe low-latency engine ผ่าน JNI (เฟส 2)
//
// ตรงกับฝั่ง Kotlin: com.switchy.intercom.audio.NativeAudio
//   oboeVersion() -> String
//   oboeStart(sampleRate, frameMs, callback, pttMode) -> long   (0 = ล้มเหลว)
//   oboeStop(handle)
//   oboeSetPTT(handle, on)
//
// ออกแบบ: เปิด "สองสตรีม" (ไมค์ + ลำโพง) แยกกัน
//   เหตุผล: Oboe ไม่มี Direction::FullDuplex — การเปิดสตรีมเดียวแบบ duplex
//   ต้องใช้ FullDuplexStream ซึ่งบังคับให้ input/output ใช้ sample rate เดียวกันเสมอ
//   และเมื่ออุปกรณ์เปลี่ยน (ถอดหูฟัง) จะพังทั้งคู่ งาน intercom แบบกดพูดจึงแยกสองสตรีม
//   ปลอดภัยกว่าและ latency ที่ได้พอ ๆ กัน (AAudio เดินทั้งคู่บนเส้นทางความหน่วงต่ำเหมือนกัน)
//
// ตรวจสอบกับ Oboe 1.9.3 จริง (คอมไพล์ผ่านด้วย NDK r30 / CMake 4.1.2)
// ใช้ InputPreset::VoiceCommunication -> ได้ AEC/NS ของระบบสำหรับงานสนทนา

#include <jni.h>
#include <oboe/Oboe.h>
#include <android/log.h>

#include <atomic>
#include <cstring>
#include <memory>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "SwitchyOboe", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "SwitchyOboe", __VA_ARGS__)

using namespace oboe;

namespace {

/** ตัวช่วย Attach/Detach thread ของ audio callback เข้ากับ JVM อัตโนมัติ */
class EnvScope {
public:
    explicit EnvScope(JavaVM *vm) : vm_(vm) {
        if (vm_ == nullptr) return;
        const jint r = vm_->GetEnv(reinterpret_cast<void **>(&env_), JNI_VERSION_1_6);
        if (r == JNI_EDETACHED) {
            if (vm_->AttachCurrentThread(&env_, nullptr) == JNI_OK) attached_ = true;
        }
    }

    ~EnvScope() {
        if (attached_ && vm_ != nullptr) vm_->DetachCurrentThread();
    }

    JNIEnv *env() const { return env_; }

private:
    JavaVM *vm_;
    JNIEnv *env_ = nullptr;
    bool attached_ = false;
};

/** ค่าที่แชร์ระหว่างสตรีมไมค์/ลำโพง */
struct Bridge {
    JavaVM *vm = nullptr;
    jobject callback = nullptr;          // global ref ของ NativeAudio.OboeCallback
    jmethodID onCapture = nullptr;       // onCapture([BI)V
    jmethodID getPlaybackFrame = nullptr;// getPlaybackFrame([BI)I

    void init(JNIEnv *env, jobject cb) {
        env->GetJavaVM(&vm);
        callback = env->NewGlobalRef(cb);
        jclass cls = env->GetObjectClass(cb);
        onCapture = env->GetMethodID(cls, "onCapture", "([BI)V");
        getPlaybackFrame = env->GetMethodID(cls, "getPlaybackFrame", "([BI)I");
    }

    void release(JNIEnv *env) {
        if (env != nullptr && callback != nullptr) env->DeleteGlobalRef(callback);
        callback = nullptr;
    }
};

/** callback ฝั่งไมค์: ส่ง PCM ขึ้น Kotlin */
class InputCallback : public AudioStreamDataCallback {
public:
    explicit InputCallback(Bridge &b) : b_(b) {}

    DataCallbackResult onAudioReady(AudioStream * /*stream*/, void *audioData, int32_t numFrames) override {
        const int bytes = numFrames * static_cast<int>(sizeof(int16_t));
        EnvScope scope(b_.vm);
        JNIEnv *env = scope.env();
        if (env == nullptr || b_.callback == nullptr) return DataCallbackResult::Continue;

        jbyteArray arr = env->NewByteArray(bytes);
        if (arr == nullptr) return DataCallbackResult::Continue;
        env->SetByteArrayRegion(arr, 0, bytes, reinterpret_cast<const jbyte *>(audioData));
        env->CallVoidMethod(b_.callback, b_.onCapture, arr, bytes);
        env->DeleteLocalRef(arr);
        return DataCallbackResult::Continue;
    }

private:
    Bridge &b_;
};

/** callback ฝั่งลำโพง: ขอเฟรมที่จะเล่นจาก Kotlin */
class OutputCallback : public AudioStreamDataCallback {
public:
    explicit OutputCallback(Bridge &b) : b_(b) {}

    DataCallbackResult onAudioReady(AudioStream * /*stream*/, void *audioData, int32_t numFrames) override {
        const int bytes = numFrames * static_cast<int>(sizeof(int16_t));
        auto *out = static_cast<int16_t *>(audioData);

        EnvScope scope(b_.vm);
        JNIEnv *env = scope.env();
        if (env == nullptr || b_.callback == nullptr) {
            std::memset(out, 0, static_cast<size_t>(bytes));
            return DataCallbackResult::Continue;
        }

        jbyteArray arr = env->NewByteArray(bytes);
        if (arr == nullptr) {
            std::memset(out, 0, static_cast<size_t>(bytes));
            return DataCallbackResult::Continue;
        }
        const jint got = env->CallIntMethod(b_.callback, b_.getPlaybackFrame, arr, bytes);
        if (got > 0) {
            env->GetByteArrayRegion(arr, 0, got, reinterpret_cast<jbyte *>(out));
        } else {
            std::memset(out, 0, static_cast<size_t>(bytes));
        }
        env->DeleteLocalRef(arr);
        return DataCallbackResult::Continue;
    }

private:
    Bridge &b_;
};

/** ตัวจัดการที่ส่งกลับไปฝั่ง Kotlin */
class Engine : public AudioStreamErrorCallback {
public:
    Engine() : inputCallback_(bridge_), outputCallback_(bridge_) {}

    /** ผูก callback ของ Kotlin (ต้องเรียกก่อน start) */
    void init(JNIEnv *env, jobject callback) { bridge_.init(env, callback); }

    /** ปล่อย global ref (ต้องเรียกหลัง stop) */
    void shutdown(JNIEnv *env) { bridge_.release(env); }

    Result start(int sampleRate, int frameMs) {
        sampleRate_ = sampleRate > 0 ? sampleRate : 48000;
        frameMs_ = frameMs > 0 ? frameMs : 20;

        Result r = openInput();
        if (r != Result::OK) return r;
        return openOutput();
    }

    void setPTT(bool on) { ptt_.store(on); }
    bool ptt() const { return ptt_.load(); }

    void stop() {
        stopping_.store(true);
        if (inputStream_) {
            inputStream_->requestStop();
            inputStream_->close();
            inputStream_.reset();
        }
        if (outputStream_) {
            outputStream_->requestStop();
            outputStream_->close();
            outputStream_.reset();
        }
        stopping_.store(false);
    }

    bool running() const { return inputStream_ != nullptr && outputStream_ != nullptr; }

    // ---- AudioStreamErrorCallback: อุปกรณ์เปลี่ยน (เช่นถอดหูฟัง) ให้เปิดใหม่ให้เอง ----
    void onErrorAfterClose(AudioStream * /*stream*/, Result error) override {
        if (stopping_.load()) return;
        LOGW("สตรีมเสียงถูกปิด (%s) - เปิดใหม่", convertToText(error));
        stop();
        start(sampleRate_, frameMs_);
    }

private:
    Result openInput() {
        AudioStreamBuilder builder;
        builder.setDirection(Direction::Input)
                ->setPerformanceMode(PerformanceMode::LowLatency)
                ->setSharingMode(SharingMode::Exclusive)
                ->setFormat(AudioFormat::I16)
                ->setChannelCount(1)                     // mono พอสำหรับ intercom
                ->setSampleRate(sampleRate_)
                ->setSampleRateConversionQuality(SampleRateConversionQuality::Medium)
                ->setInputPreset(InputPreset::VoiceCommunication) // ได้ AEC/NS ของระบบ
                ->setDataCallback(&inputCallback_)
                ->setErrorCallback(this);

        const Result r = builder.openStream(inputStream_);
        if (r != Result::OK) {
            LOGW("เปิดสตรีมไมค์ไม่ได้: %s", convertToText(r));
            return r;
        }
        inputStream_->setBufferSizeInFrames(inputStream_->getFramesPerBurst() * 2);
        const Result sr = inputStream_->requestStart();
        if (sr != Result::OK) {
            LOGW("เริ่มสตรีมไมค์ไม่สำเร็จ: %s", convertToText(sr));
            return sr;
        }
        LOGI("ไมค์พร้อม (Oboe): %d Hz, burst=%d เฟรม", inputStream_->getSampleRate(),
             inputStream_->getFramesPerBurst());
        return Result::OK;
    }

    Result openOutput() {
        AudioStreamBuilder builder;
        builder.setDirection(Direction::Output)
                ->setPerformanceMode(PerformanceMode::LowLatency)
                ->setSharingMode(SharingMode::Exclusive)
                ->setFormat(AudioFormat::I16)
                ->setChannelCount(1)
                ->setSampleRate(sampleRate_)
                ->setSampleRateConversionQuality(SampleRateConversionQuality::Medium)
                ->setUsage(Usage::VoiceCommunication)    // เส้นทางเสียงสนทนา (เสียงผ่านหูฟังสนทนา)
                ->setDataCallback(&outputCallback_)
                ->setErrorCallback(this);

        const Result r = builder.openStream(outputStream_);
        if (r != Result::OK) {
            LOGW("เปิดสตรีมลำโพงไม่ได้: %s", convertToText(r));
            return r;
        }
        outputStream_->setBufferSizeInFrames(outputStream_->getFramesPerBurst() * 2);
        const Result sr = outputStream_->requestStart();
        if (sr != Result::OK) {
            LOGW("เริ่มสตรีมลำโพงไม่สำเร็จ: %s", convertToText(sr));
            return sr;
        }
        LOGI("ลำโพงพร้อม (Oboe): %d Hz, burst=%d เฟรม", outputStream_->getSampleRate(),
             outputStream_->getFramesPerBurst());
        return Result::OK;
    }

    Bridge bridge_;
    InputCallback inputCallback_;
    OutputCallback outputCallback_;
    std::shared_ptr<AudioStream> inputStream_;
    std::shared_ptr<AudioStream> outputStream_;
    std::atomic<bool> ptt_{false};
    std::atomic<bool> stopping_{false};
    int sampleRate_ = 48000;
    int frameMs_ = 20;
};

inline Engine *asEngine(jlong h) { return reinterpret_cast<Engine *>(h); }

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_switchy_intercom_audio_NativeAudio_oboeVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF(getVersionText());
}

JNIEXPORT jlong JNICALL
Java_com_switchy_intercom_audio_NativeAudio_oboeStart(
        JNIEnv *env, jobject, jint sampleRate, jint frameMs, jobject callback, jboolean pttMode) {

    auto *engine = new Engine();
    engine->init(env, callback);
    if (engine->start(sampleRate, frameMs) != Result::OK) {
        engine->shutdown(env);
        delete engine;
        return 0;
    }
    engine->setPTT(pttMode == JNI_TRUE);
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_switchy_intercom_audio_NativeAudio_oboeStop(JNIEnv *env, jobject, jlong handle) {
    Engine *engine = asEngine(handle);
    if (engine == nullptr) return;
    engine->stop();
    engine->shutdown(env);
    delete engine;
}

JNIEXPORT void JNICALL
Java_com_switchy_intercom_audio_NativeAudio_oboeSetPTT(JNIEnv *, jobject, jlong handle, jboolean on) {
    Engine *engine = asEngine(handle);
    if (engine != nullptr) engine->setPTT(on == JNI_TRUE);
}

} // extern "C"
