#ifndef A8_MPC_CODEC_H
#define A8_MPC_CODEC_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    double duration_sec;
    int sample_rate;
    int channels;
    int64_t total_samples;
} a8_mpc_info_t;

/**
 * Encodes a 16-bit PCM WAV file (32000/37800/44100/48000 Hz, 1 or 2 channels)
 * into a Musepack SV8 (.mpc) file.
 * quality: 4.0f (radio/compact), 6.0f (extreme/standard), 7.0f (insane/high), 8.0f (braindead/ultra)
 * Returns 0 on success, negative error code on failure.
 */
int a8_mpc_encode_wav(const char *wav_path, const char *mpc_path, float quality);

/**
 * Decodes a Musepack SV8/SV7 (.mpc) file to a standard 16-bit PCM WAV file.
 * Returns 0 on success, negative error code on failure.
 */
int a8_mpc_decode_wav(const char *mpc_path, const char *wav_path);

/**
 * Probes duration, sample rate, channels, and sample count of a Musepack file.
 * Returns 0 on success, negative error code on failure.
 */
int a8_mpc_probe_info(const char *mpc_path, a8_mpc_info_t *out_info);

#ifdef __cplusplus
}
#endif

#endif /* A8_MPC_CODEC_H */
