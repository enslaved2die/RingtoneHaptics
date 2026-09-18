#include "ogg_vorbis_encoder.h"
#include <ogg/ogg.h>
#include <vorbis/codec.h>
#include <vorbis/vorbisenc.h>
#include <android/log.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <algorithm>

#define TAG "RingtoneHapticsNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define BUFFER_CHUNK_SIZE 1024

extern "C" JNIEXPORT jint JNICALL
Java_com_ringtonehaptics_app_data_encoder_NativeVorbisEncoder_nativeEncode3ChannelOgg(
        JNIEnv *env,
        jobject /* thiz */,
        jobject left_buffer,
        jobject right_buffer,
        jobject haptic_buffer,
        jint num_samples,
        jint sample_rate,
        jfloat quality,
        jstring output_path,
        jstring title,
        jstring artist
) {
    if (left_buffer == nullptr || right_buffer == nullptr || haptic_buffer == nullptr) {
        LOGE("Null direct byte buffer provided to encoder");
        return -1;
    }

    const float *left_samples = static_cast<const float *>(env->GetDirectBufferAddress(left_buffer));
    const float *right_samples = static_cast<const float *>(env->GetDirectBufferAddress(right_buffer));
    const float *haptic_samples = static_cast<const float *>(env->GetDirectBufferAddress(haptic_buffer));

    if (left_samples == nullptr || right_samples == nullptr || haptic_samples == nullptr) {
        LOGE("Failed to get direct buffer addresses");
        return -2;
    }

    const char *out_path_str = env->GetStringUTFChars(output_path, nullptr);
    const char *title_str = title != nullptr ? env->GetStringUTFChars(title, nullptr) : "Ringtone";
    const char *artist_str = artist != nullptr ? env->GetStringUTFChars(artist, nullptr) : "RingtoneHaptics";

    FILE *out_file = fopen(out_path_str, "wb");
    if (!out_file) {
        LOGE("Cannot open destination file: %s", out_path_str);
        env->ReleaseStringUTFChars(output_path, out_path_str);
        if (title) env->ReleaseStringUTFChars(title, title_str);
        if (artist) env->ReleaseStringUTFChars(artist, artist_str);
        return -3;
    }

    vorbis_info vi;
    vorbis_comment vc;
    vorbis_dsp_state vd;
    vorbis_block vb;

    ogg_stream_state os;
    ogg_page og;
    ogg_packet op;

    vorbis_info_init(&vi);

    // 3 channels: 0 = Left, 1 = Right, 2 = Haptic_A
    int channels = 3;
    int ret = vorbis_encode_init_vbr(&vi, channels, sample_rate, quality);
    if (ret != 0) {
        LOGE("vorbis_encode_init_vbr failed with error: %d", ret);
        fclose(out_file);
        vorbis_info_clear(&vi);
        env->ReleaseStringUTFChars(output_path, out_path_str);
        if (title) env->ReleaseStringUTFChars(title, title_str);
        if (artist) env->ReleaseStringUTFChars(artist, artist_str);
        return -4;
    }

    vorbis_comment_init(&vc);
    // CRITICAL: This tag instructs Android AudioFlinger / OggExtractor to route channel 2 to the haptic motor!
    vorbis_comment_add_tag(&vc, "ANDROID_HAPTIC", "1");
    vorbis_comment_add_tag(&vc, "TITLE", title_str);
    vorbis_comment_add_tag(&vc, "ARTIST", artist_str);
    vorbis_comment_add_tag(&vc, "ENCODER", "RingtoneHaptics Android");

    vorbis_analysis_init(&vd, &vi);
    vorbis_block_init(&vd, &vb);

    srand(12345);
    ogg_stream_init(&os, rand());

    // Write Vorbis headers
    ogg_packet header;
    ogg_packet header_comm;
    ogg_packet header_code;

    vorbis_analysis_headerout(&vd, &vc, &header, &header_comm, &header_code);
    ogg_stream_packetin(&os, &header);
    ogg_stream_packetin(&os, &header_comm);
    ogg_stream_packetin(&os, &header_code);

    while (true) {
        int result = ogg_stream_flush(&os, &og);
        if (result == 0) break;
        fwrite(og.header, 1, og.header_len, out_file);
        fwrite(og.body, 1, og.body_len, out_file);
    }

    int sample_idx = 0;
    while (sample_idx < num_samples) {
        int chunk = std::min(BUFFER_CHUNK_SIZE, num_samples - sample_idx);
        float **buffer = vorbis_analysis_buffer(&vd, chunk);

        for (int i = 0; i < chunk; ++i) {
            buffer[0][i] = left_samples[sample_idx + i];
            buffer[1][i] = right_samples[sample_idx + i];
            buffer[2][i] = haptic_samples[sample_idx + i];
        }

        vorbis_analysis_wrote(&vd, chunk);
        sample_idx += chunk;

        while (vorbis_analysis_blockout(&vd, &vb) == 1) {
            vorbis_analysis(&vb, nullptr);
            vorbis_bitrate_addblock(&vb);

            while (vorbis_bitrate_flushpacket(&vd, &op)) {
                ogg_stream_packetin(&os, &op);

                while (true) {
                    int result = ogg_stream_pageout(&os, &og);
                    if (result == 0) break;
                    fwrite(og.header, 1, og.header_len, out_file);
                    fwrite(og.body, 1, og.body_len, out_file);
                    if (ogg_page_eos(&og)) break;
                }
            }
        }
    }

    // Signal End Of Stream
    vorbis_analysis_wrote(&vd, 0);

    while (vorbis_analysis_blockout(&vd, &vb) == 1) {
        vorbis_analysis(&vb, nullptr);
        vorbis_bitrate_addblock(&vb);

        while (vorbis_bitrate_flushpacket(&vd, &op)) {
            ogg_stream_packetin(&os, &op);

            while (true) {
                int result = ogg_stream_pageout(&os, &og);
                if (result == 0) break;
                fwrite(og.header, 1, og.header_len, out_file);
                fwrite(og.body, 1, og.body_len, out_file);
                if (ogg_page_eos(&og)) break;
            }
        }
    }

    // Clean up
    ogg_stream_clear(&os);
    vorbis_block_clear(&vb);
    vorbis_dsp_clear(&vd);
    vorbis_comment_clear(&vc);
    vorbis_info_clear(&vi);

    fclose(out_file);

    env->ReleaseStringUTFChars(output_path, out_path_str);
    if (title) env->ReleaseStringUTFChars(title, title_str);
    if (artist) env->ReleaseStringUTFChars(artist, artist_str);

    LOGI("Successfully encoded 3-channel OGG Vorbis with ANDROID_HAPTIC=1 (%d samples)", num_samples);
    return 0;
}
