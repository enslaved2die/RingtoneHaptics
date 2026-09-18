#ifndef RINGTONE_HAPTICS_OGG_VORBIS_ENCODER_H
#define RINGTONE_HAPTICS_OGG_VORBIS_ENCODER_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jint JNICALL
Java_com_ringtonehaptics_app_data_encoder_NativeVorbisEncoder_nativeEncode3ChannelOgg(
        JNIEnv *env,
        jobject thiz,
        jobject left_buffer,
        jobject right_buffer,
        jobject haptic_buffer,
        jint num_samples,
        jint sample_rate,
        jfloat quality,
        jstring output_path,
        jstring title,
        jstring artist
);

#ifdef __cplusplus
}
#endif

#endif // RINGTONE_HAPTICS_OGG_VORBIS_ENCODER_H
