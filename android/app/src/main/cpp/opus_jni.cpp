// SWITCHY — Opus codec ผ่าน JNI (เฟส 2)
//
// ตรงกับฝั่ง Kotlin: com.switchy.intercom.audio.NativeAudio
//   opusCreate(sampleRate, channels, bitrate, frameMs) -> long   (0 = ล้มเหลว)
//   opusEncode(handle, pcm[], len) -> byte[] | null
//   opusDecode(handle, data[], len) -> byte[] | null
//   opusDestroy(handle)
//
// หมายเหตุ: ถ้าบิลด์โดยไม่มี libopus (SWITCHY_NO_OPUS) ฟังก์ชันจะคืน 0/null
// แล้วฝั่ง Kotlin จะ fallback ไปใช้ PCM เองโดยไม่ล้ม

#include <jni.h>

#ifdef SWITCHY_NO_OPUS
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "SwitchyOpus", __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_switchy_intercom_audio_NativeAudio_opusCreate(JNIEnv *, jobject, jint, jint, jint, jint) {
    LOGI("บิลด์นี้ไม่มี libopus");
    return 0;
}
JNIEXPORT jbyteArray JNICALL
Java_com_switchy_intercom_audio_NativeAudio_opusEncode(JNIEnv *, jobject, jlong, jbyteArray, jint) {
    return nullptr;
}
JNIEXPORT jbyteArray JNICALL
Java_com_switchy_intercom_audio_NativeAudio_opusDecode(JNIEnv *, jobject, jlong, jbyteArray, jint) {
    return nullptr;
}
JNIEXPORT void JNICALL
Java_com_switchy_intercom_audio_NativeAudio_opusDestroy(JNIEnv *, jobject, jlong) {}

} // extern "C"

#else // ------------------------------------------------------------ มี libopus จริง

#include <opus.h>
#include <vector>
#include <cstring>
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "SwitchyOpus", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "SwitchyOpus", __VA_ARGS__)

namespace {

/** ตัวจัดการที่ส่งกลับไปฝั่ง Kotlin (เป็น pointer ไม่ใช่ handle ของระบบ) */
struct OpusHandle {
    OpusEncoder *encoder = nullptr;
    OpusDecoder *decoder = nullptr;
    int sampleRate = 48000;
    int channels = 1;
    int frameSamples = 960;      // 20 ms @48k
    int maxPacketBytes = 1500;
    std::vector<unsigned char> pcmOut;
};

inline OpusHandle *asHandle(jlong h) { return reinterpret_cast<OpusHandle *>(h); }

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_switchy_intercom_audio_NativeAudio_opusCreate(
        JNIEnv *env, jobject, jint sampleRate, jint channels, jint bitrate, jint frameMs) {

    auto *h = new OpusHandle();
    h->sampleRate = sampleRate > 0 ? sampleRate : 48000;
    h->channels = channels > 0 ? channels : 1;
    h->frameSamples = h->sampleRate * (frameMs > 0 ? frameMs : 20) / 1000;
    h->pcmOut.resize(static_cast<size_t>(h->frameSamples) * 2 * h->channels);

    int err = OPUS_OK;
    h->encoder = opus_encoder_create(h->sampleRate, h->channels, OPUS_APPLICATION_VOIP, &err);
    if (err != OPUS_OK || !h->encoder) {
        LOGE("สร้าง encoder ไม่ได้: %s", opus_strerror(err));
        delete h;
        return 0;
    }
    opus_encoder_ctl(h->encoder, OPUS_SET_BITRATE(bitrate > 0 ? bitrate : 32000));
    // งาน intercom: ต้องการ latency ต่ำและทนแพ็กเก็ตหาย
    opus_encoder_ctl(h->encoder, OPUS_SET_INBAND_FEC(1));
    opus_encoder_ctl(h->encoder, OPUS_SET_PACKET_LOSS_PERC(5));
    opus_encoder_ctl(h->encoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(h->encoder, OPUS_SET_COMPLEXITY(5));
    opus_encoder_ctl(h->encoder, OPUS_SET_DTX(1)); // ไม่ส่งข้อมูลตอนเงียบ ช่วยประหยัด Wi-Fi

    h->decoder = opus_decoder_create(h->sampleRate, h->channels, &err);
    if (err != OPUS_OK || !h->decoder) {
        LOGE("สร้าง decoder ไม่ได้: %s", opus_strerror(err));
        opus_encoder_destroy(h->encoder);
        delete h;
        return 0;
    }
    opus_decoder_ctl(h->decoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));

    LOGI("Opus พร้อม: %d Hz, %d ch, %d kbps, %d samples/เฟรม",
         h->sampleRate, h->channels, (bitrate > 0 ? bitrate : 32000) / 1000, h->frameSamples);
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT jbyteArray JNICALL
Java_com_switchy_intercom_audio_NativeAudio_opusEncode(
        JNIEnv *env, jobject, jlong handle, jbyteArray pcm, jint len) {

    OpusHandle *h = asHandle(handle);
    if (!h || !h->encoder || pcm == nullptr || len <= 0) return nullptr;

    jbyte *in = env->GetByteArrayElements(pcm, nullptr);
    if (!in) return nullptr;

    std::vector<unsigned char> out(h->maxPacketBytes);
    const auto *samples = reinterpret_cast<const opus_int16 *>(in);
    const int sampleCount = len / 2;

    const int n = opus_encode(h->encoder, samples, sampleCount, out.data(),
                              static_cast<opus_int32>(out.size()));
    env->ReleaseByteArrayElements(pcm, in, JNI_ABORT);
    if (n < 0) {
        LOGE("encode ล้มเหลว: %s", opus_strerror(n));
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(n);
    if (result) env->SetByteArrayRegion(result, 0, n, reinterpret_cast<const jbyte *>(out.data()));
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_switchy_intercom_audio_NativeAudio_opusDecode(
        JNIEnv *env, jobject, jlong handle, jbyteArray data, jint len) {

    OpusHandle *h = asHandle(handle);
    if (!h || !h->decoder || data == nullptr || len <= 0) return nullptr;

    jbyte *in = env->GetByteArrayElements(data, nullptr);
    if (!in) return nullptr;

    // frameSamples/ช่อง คือเพดานสูงสุด (Opus รองรับ PLC/ขนาดเฟรมยืดหยุ่น)
    const int decoded = opus_decode(h->decoder,
                                    reinterpret_cast<const unsigned char *>(in), len,
                                    reinterpret_cast<opus_int16 *>(h->pcmOut.data()),
                                    h->frameSamples, 0);
    env->ReleaseByteArrayElements(data, in, JNI_ABORT);
    if (decoded < 0) {
        LOGE("decode ล้มเหลว: %s", opus_strerror(decoded));
        return nullptr;
    }

    const int bytes = decoded * 2 * h->channels;
    jbyteArray result = env->NewByteArray(bytes);
    if (result) {
        env->SetByteArrayRegion(result, 0, bytes, reinterpret_cast<const jbyte *>(h->pcmOut.data()));
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_switchy_intercom_audio_NativeAudio_opusDestroy(JNIEnv *, jobject, jlong handle) {
    OpusHandle *h = asHandle(handle);
    if (!h) return;
    if (h->encoder) opus_encoder_destroy(h->encoder);
    if (h->decoder) opus_decoder_destroy(h->decoder);
    delete h;
}

} // extern "C"

#endif // SWITCHY_NO_OPUS
