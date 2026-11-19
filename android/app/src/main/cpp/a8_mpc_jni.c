#include <jni.h>
#include "a8_mpc_codec.h"

JNIEXPORT jint JNICALL
Java_com_pythonnxd_a8ostpatcher_engine_NativeMpcCodec_nativeEncodeWavToMpc(
    JNIEnv *env,
    jobject thiz,
    jstring jWavPath,
    jstring jMpcPath,
    jfloat quality
) {
    (void)thiz;
    if (!jWavPath || !jMpcPath) return -1;
    const char *wavPath = (*env)->GetStringUTFChars(env, jWavPath, NULL);
    const char *mpcPath = (*env)->GetStringUTFChars(env, jMpcPath, NULL);
    int rc = a8_mpc_encode_wav(wavPath, mpcPath, (float)quality);
    (*env)->ReleaseStringUTFChars(env, jWavPath, wavPath);
    (*env)->ReleaseStringUTFChars(env, jMpcPath, mpcPath);
    return (jint)rc;
}

JNIEXPORT jint JNICALL
Java_com_pythonnxd_a8ostpatcher_engine_NativeMpcCodec_nativeDecodeMpcToWav(
    JNIEnv *env,
    jobject thiz,
    jstring jMpcPath,
    jstring jWavPath
) {
    (void)thiz;
    if (!jMpcPath || !jWavPath) return -1;
    const char *mpcPath = (*env)->GetStringUTFChars(env, jMpcPath, NULL);
    const char *wavPath = (*env)->GetStringUTFChars(env, jWavPath, NULL);
    int rc = a8_mpc_decode_wav(mpcPath, wavPath);
    (*env)->ReleaseStringUTFChars(env, jMpcPath, mpcPath);
    (*env)->ReleaseStringUTFChars(env, jWavPath, wavPath);
    return (jint)rc;
}

JNIEXPORT jdoubleArray JNICALL
Java_com_pythonnxd_a8ostpatcher_engine_NativeMpcCodec_nativeProbeMpcInfo(
    JNIEnv *env,
    jobject thiz,
    jstring jMpcPath
) {
    (void)thiz;
    if (!jMpcPath) return NULL;
    const char *mpcPath = (*env)->GetStringUTFChars(env, jMpcPath, NULL);
    a8_mpc_info_t info;
    int rc = a8_mpc_probe_info(mpcPath, &info);
    (*env)->ReleaseStringUTFChars(env, jMpcPath, mpcPath);
    if (rc != 0) return NULL;

    jdoubleArray arr = (*env)->NewDoubleArray(env, 4);
    if (!arr) return NULL;
    jdouble vals[4] = {
        info.duration_sec,
        (jdouble)info.sample_rate,
        (jdouble)info.channels,
        (jdouble)info.total_samples
    };
    (*env)->SetDoubleArrayRegion(env, arr, 0, 4, vals);
    return arr;
}
