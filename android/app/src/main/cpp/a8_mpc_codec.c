/*
 * Asphalt 8 Custom OST Patcher - Native Musepack SV8 Codec Bridge
 * Wraps official Musepack SV8 libmpcenc, libmpcpsy, and libmpcdec in-process
 * with zero process spawning and deterministic state cleanup between encodes.
 */

#include "a8_mpc_codec.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if defined(__unix__) || defined(__ANDROID__) || defined(__APPLE__)
#include <pthread.h>
static pthread_mutex_t g_mpc_lock = PTHREAD_MUTEX_INITIALIZER;
#define MPC_LOCK()   pthread_mutex_lock(&g_mpc_lock)
#define MPC_UNLOCK() pthread_mutex_unlock(&g_mpc_lock)
#else
#define MPC_LOCK()   ((void)0)
#define MPC_UNLOCK() ((void)0)
#endif

#include <mpc/datatypes.h>
#include <mpc/minimax.h>
#include <mpc/mpcmath.h>
#include <mpc/mpcdec.h>
#include "libmpcenc.h"
#include "libmpcpsy.h"

#define DECODER_DELAY (512 - 32 + 1)
#define SCFfac 0.832980664785f
#define LAST_HUFFMAN 7
#define MPPENC_DENORMAL_FIX_BASE (32.0f * 1024.0f / ((float)(1 << 24)))
#define MPPENC_DENORMAL_FIX_LEFT (MPPENC_DENORMAL_FIX_BASE)
#define MPPENC_DENORMAL_FIX_RIGHT (MPPENC_DENORMAL_FIX_BASE * 0.5f)
#define MPCENC_MAJOR 1
#define MPCENC_MINOR 30
#define MPCENC_BUILD 0

/* Declarations from libmpcpsy / libmpcenc (normally in mpcenc.h) */
typedef float SCFTriple[3];
extern void Init_Psychoakustik(PsyModel *m);
extern void Init_Psychoakustiktabellen(PsyModel *m);
extern void SetQualityParams(PsyModel *m, float qual);
extern SMRTyp Psychoakustisches_Modell(PsyModel *m, const int MaxBand, const PCMDataTyp *data, int *TransientL, int *TransientR);
extern void TransientenCalc(int *Transient, const int *TransientL, const int *TransientR);
extern void RaiseSMR(PsyModel *m, const int MaxBand, SMRTyp *SMR);
extern void MS_LR_Entscheidung(const int MaxBand, unsigned char *MS, SMRTyp *SMR, SubbandFloatTyp *X);
extern void NS_Analyse(PsyModel *m, const int MaxBand, const unsigned char *MS, const SMRTyp SMR, const int *Transient);
extern void Analyse_Filter(const PCMDataTyp *data, SubbandFloatTyp *out, const int MaxBand);
extern void Analyse_Init(float Left, float Right, SubbandFloatTyp *out, const int MaxBand);

extern float __invSCF[128 + 6];
#define invSCF (__invSCF + 6)
extern float ISNR_Schaetzer(const float *samples, const float comp, const int res);
extern float ISNR_Schaetzer_Trans(const float *samples, const float comp, const int res);
extern void QuantizeSubband(mpc_int16_t *qu_output, const float *input, const int res, float *errors, const int maxNsOrder);
extern void QuantizeSubbandWithNoiseShaping(mpc_int16_t *qu_output, const float *input, const int res, float *errors, const float *FIR);

static const unsigned char g_penalty[256] = {
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
      0,  2,  5,  9, 15, 23, 36, 54, 79,116,169,246,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
    255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,255,
};

#define PENALTY(new_v, old_v) g_penalty[128 + (old_v) - (new_v)]

static void fill_float_buf(float *buffer, float val, unsigned count) {
    for (unsigned n = 0; n < count; n++) {
        buffer[n] = val;
    }
}

static void scf_extraction(
    PsyModel *m,
    mpc_encoder_t *e,
    const int max_band,
    SubbandFloatTyp *x,
    SCFTriple *power_l,
    SCFTriple *power_r
) {
    for (int band = 0; band <= max_band; band++) {
        float tmp_l[3], tmp_r[3];
        for (int s = 0; s < 3; s++) {
            int base = s * 12;
            float l_max = FABS(x[band].L[base]);
            float r_max = FABS(x[band].R[base]);
            float sl = x[band].L[base] * x[band].L[base];
            float sr = x[band].R[base] * x[band].R[base];
            for (int n = base + 1; n < base + 12; n++) {
                float al = FABS(x[band].L[n]);
                float ar = FABS(x[band].R[n]);
                if (l_max < al) l_max = al;
                if (r_max < ar) r_max = ar;
                sl += x[band].L[n] * x[band].L[n];
                sr += x[band].R[n] * x[band].R[n];
            }
            power_l[band][s] = sl;
            power_r[band][s] = sr;
            tmp_l[s] = l_max;
            tmp_r[s] = r_max;
        }

        int *scf_l = e->SCF_Index_L[band];
        int *scf_r = e->SCF_Index_R[band];
        scf_l[0] = scf_l[1] = scf_l[2] = 121;
        scf_r[0] = scf_r[1] = scf_r[2] = 121;
        if (tmp_l[0] > 0.f) scf_l[0] = IFLOORF(-12.6f * LOG10(tmp_l[0]) + 57.8945021823f);
        if (tmp_l[1] > 0.f) scf_l[1] = IFLOORF(-12.6f * LOG10(tmp_l[1]) + 57.8945021823f);
        if (tmp_l[2] > 0.f) scf_l[2] = IFLOORF(-12.6f * LOG10(tmp_l[2]) + 57.8945021823f);
        if (tmp_r[0] > 0.f) scf_r[0] = IFLOORF(-12.6f * LOG10(tmp_r[0]) + 57.8945021823f);
        if (tmp_r[1] > 0.f) scf_r[1] = IFLOORF(-12.6f * LOG10(tmp_r[1]) + 57.8945021823f);
        if (tmp_r[2] > 0.f) scf_r[2] = IFLOORF(-12.6f * LOG10(tmp_r[2]) + 57.8945021823f);

        int warn_l = 0, warn_r = 0;
        for (int n = 0; n < 3; n++) {
            if (scf_l[n] < -6) { scf_l[n] = -6; warn_l = 1; }
            if (scf_l[n] > 121) { scf_l[n] = 121; warn_l = 1; }
            if (scf_r[n] < -6) { scf_r[n] = -6; warn_r = 1; }
            if (scf_r[n] > 121) { scf_r[n] = 121; warn_r = 1; }
        }

        int comp_l[3] = { scf_l[0], scf_l[1], scf_l[2] };
        int comp_r[3] = { scf_r[0], scf_r[1], scf_r[2] };

        if (m->CombPenalities >= 0) {
            if      (PENALTY(scf_l[0], scf_l[1]) + PENALTY(scf_l[0], scf_l[2]) <= m->CombPenalities) scf_l[2] = scf_l[1] = scf_l[0];
            else if (PENALTY(scf_l[1], scf_l[0]) + PENALTY(scf_l[1], scf_l[2]) <= m->CombPenalities) scf_l[0] = scf_l[2] = scf_l[1];
            else if (PENALTY(scf_l[2], scf_l[0]) + PENALTY(scf_l[2], scf_l[1]) <= m->CombPenalities) scf_l[0] = scf_l[1] = scf_l[2];
            else if (PENALTY(scf_l[0], scf_l[1]) <= m->CombPenalities) scf_l[1] = scf_l[0];
            else if (PENALTY(scf_l[1], scf_l[0]) <= m->CombPenalities) scf_l[0] = scf_l[1];
            else if (PENALTY(scf_l[1], scf_l[2]) <= m->CombPenalities) scf_l[2] = scf_l[1];
            else if (PENALTY(scf_l[2], scf_l[1]) <= m->CombPenalities) scf_l[1] = scf_l[2];

            if      (PENALTY(scf_r[0], scf_r[1]) + PENALTY(scf_r[0], scf_r[2]) <= m->CombPenalities) scf_r[2] = scf_r[1] = scf_r[0];
            else if (PENALTY(scf_r[1], scf_r[0]) + PENALTY(scf_r[1], scf_r[2]) <= m->CombPenalities) scf_r[0] = scf_r[2] = scf_r[1];
            else if (PENALTY(scf_r[2], scf_r[0]) + PENALTY(scf_r[2], scf_r[1]) <= m->CombPenalities) scf_r[0] = scf_r[1] = scf_r[2];
            else if (PENALTY(scf_r[0], scf_r[1]) <= m->CombPenalities) scf_r[1] = scf_r[0];
            else if (PENALTY(scf_r[1], scf_r[0]) <= m->CombPenalities) scf_r[0] = scf_r[1];
            else if (PENALTY(scf_r[1], scf_r[2]) <= m->CombPenalities) scf_r[2] = scf_r[1];
            else if (PENALTY(scf_r[2], scf_r[1]) <= m->CombPenalities) scf_r[1] = scf_r[2];
        } else {
            int d12 = scf_l[2] - scf_l[1];
            int d01 = scf_l[1] - scf_l[0];
            int d02 = scf_l[2] - scf_l[0];
            if      (0 < d12 && d12 < 5) scf_l[2] = scf_l[1];
            else if (-3 < d12 && d12 < 0) scf_l[1] = scf_l[2];
            else if (0 < d01 && d01 < 5) scf_l[1] = scf_l[0];
            else if (-3 < d01 && d01 < 0) scf_l[0] = scf_l[1];
            else if (0 < d02 && d02 < 4) scf_l[2] = scf_l[0];
            else if (-2 < d02 && d02 < 0) scf_l[0] = scf_l[2];

            d12 = scf_r[2] - scf_r[1];
            d01 = scf_r[1] - scf_r[0];
            d02 = scf_r[2] - scf_r[0];
            if      (0 < d12 && d12 < 5) scf_r[2] = scf_r[1];
            else if (-3 < d12 && d12 < 0) scf_r[1] = scf_r[2];
            else if (0 < d01 && d01 < 5) scf_r[1] = scf_r[0];
            else if (-3 < d01 && d01 < 0) scf_r[0] = scf_r[1];
            else if (0 < d02 && d02 < 4) scf_r[2] = scf_r[0];
            else if (-2 < d02 && d02 < 0) scf_r[0] = scf_r[2];
        }

        tmp_l[0] = invSCF[comp_l[0] - scf_l[0]];
        tmp_l[1] = invSCF[comp_l[1] - scf_l[1]];
        tmp_l[2] = invSCF[comp_l[2] - scf_l[2]];
        tmp_r[0] = invSCF[comp_r[0] - scf_r[0]];
        tmp_r[1] = invSCF[comp_r[1] - scf_r[1]];
        tmp_r[2] = invSCF[comp_r[2] - scf_r[2]];
        m->SNR_comp_L[band] = (tmp_l[0]*tmp_l[0] + tmp_l[1]*tmp_l[1] + tmp_l[2]*tmp_l[2]) * 0.3333333333f;
        m->SNR_comp_R[band] = (tmp_r[0]*tmp_r[0] + tmp_r[1]*tmp_r[1] + tmp_r[2]*tmp_r[2]) * 0.3333333333f;

        for (int s = 0; s < 3; s++) {
            float fl = invSCF[scf_l[s]];
            float fr = invSCF[scf_r[s]];
            for (int n = s * 12; n < (s + 1) * 12; n++) {
                x[band].L[n] *= fl;
                x[band].R[n] *= fr;
            }
        }

        if (warn_l) {
            for (int n = 0; n < 36; n++) {
                if (x[band].L[n] > 32767.f) { e->Overflows++; x[band].L[n] = 32767.f; }
                else if (x[band].L[n] < -32767.f) { e->Overflows++; x[band].L[n] = -32767.f; }
            }
        }
        if (warn_r) {
            for (int n = 0; n < 36; n++) {
                if (x[band].R[n] > 32767.f) { e->Overflows++; x[band].R[n] = 32767.f; }
                else if (x[band].R[n] < -32767.f) { e->Overflows++; x[band].R[n] = -32767.f; }
            }
        }
    }
}

static void quantize_bands(
    PsyModel *m,
    const int max_band,
    const int *res_l,
    const int *res_r,
    const SubbandFloatTyp *subx,
    mpc_quantizer *subq,
    float error_l[32][36 + MAX_NS_ORDER],
    float error_r[32][36 + MAX_NS_ORDER]
) {
    for (int band = 0; band <= max_band; band++, res_l++, res_r++) {
        if (*res_l > 0) {
            if (m->NS_Order_L[band] > 0) {
                QuantizeSubbandWithNoiseShaping(subq[band].L, subx[band].L, *res_l, error_l[band], m->FIR_L[band]);
            } else {
                QuantizeSubband(subq[band].L, subx[band].L, *res_l, error_l[band], MAX_NS_ORDER);
            }
            memcpy(error_l[band], error_l[band] + 36, MAX_NS_ORDER * sizeof(float));
        }
        if (*res_r > 0) {
            if (m->NS_Order_R[band] > 0) {
                QuantizeSubbandWithNoiseShaping(subq[band].R, subx[band].R, *res_r, error_r[band], m->FIR_R[band]);
            } else {
                QuantizeSubband(subq[band].R, subx[band].R, *res_r, error_r[band], MAX_NS_ORDER);
            }
            memcpy(error_r[band], error_r[band] + 36, MAX_NS_ORDER * sizeof(float));
        }
    }
}

static int pns_scf(int *scf, float s0, float s1, float s2) {
    if (s0 < 0.5f * s1 || s1 < 0.5f * s2 || s0 < 0.5f * s2) return 0;
    if (s1 < 0.25f * s0 || s2 < 0.25f * s1 || s2 < 0.25f * s0) return 0;

    if (s0 >= 0.8f * s1) {
        if (s0 >= 0.8f * s2 && s1 > 0.8f * s2) s0 = s1 = s2 = 0.3333333333f * (s0 + s1 + s2);
        else s0 = s1 = 0.5f * (s0 + s1);
    } else if (s1 >= 0.8f * s2) {
        s1 = s2 = 0.5f * (s1 + s2);
    }

    scf[0] = scf[1] = scf[2] = 63;
    const double norm = 4.0 / (12.0 * 1.2005080577484075047860806747022);
    s0 = (float)sqrt((double)s0 * norm);
    s1 = (float)sqrt((double)s1 * norm);
    s2 = (float)sqrt((double)s2 * norm);
    if (s0 > 0.f) scf[0] = IFLOORF(-12.6f * LOG10(s0) + 57.8945021823f);
    if (s1 > 0.f) scf[1] = IFLOORF(-12.6f * LOG10(s1) + 57.8945021823f);
    if (s2 > 0.f) scf[2] = IFLOORF(-12.6f * LOG10(s2) + 57.8945021823f);
    for (int i = 0; i < 3; i++) {
        if (scf[i] < 0) scf[i] = 0;
        if (scf[i] > 63) scf[i] = 63;
    }
    return 1;
}

static void allocate_bits(
    const int max_band,
    int *res,
    float *x,
    int *scf,
    const float *comp,
    const float *smr,
    const SCFTriple *pow_tbl,
    const int *transient,
    const float pns
) {
    float save[36];
    for (int band = 0; band <= max_band; band++, res++, comp++, smr++, scf += 3, x += 72) {
        float mnr = 0.f;
        if (band > 0 && res[-1] < 3 && *smr >= 1.f && *smr < band * pns &&
            pns_scf(scf, pow_tbl[band][0], pow_tbl[band][1], pow_tbl[band][2])) {
            *res = -1;
        } else {
            for (mnr = *smr; mnr > 1.f && *res != 15;) {
                mnr = *smr * (transient[band] ? ISNR_Schaetzer_Trans : ISNR_Schaetzer)(x, *comp, ++(*res));
            }
        }
        if (*res > 0 && *res <= LAST_HUFFMAN && mnr < 1.f && mnr > 0.f && !transient[band]) {
            while (scf[0] > 0 && scf[1] > 0 && scf[2] > 0) {
                --scf[2]; --scf[1]; --scf[0];
                memcpy(save, x, sizeof(save));
                for (int k = 0; k < 36; k++) x[k] *= SCFfac;
                float tmp_mnr = *smr * ISNR_Schaetzer(x, *comp, *res);
                if (tmp_mnr <= 1.f) {
                    mnr = tmp_mnr;
                } else {
                    ++scf[0]; ++scf[1]; ++scf[2];
                    memcpy(x, save, sizeof(save));
                    break;
                }
            }
        }
    }
}

typedef struct {
    FILE *fp;
    uint32_t sample_rate;
    uint16_t channels;
    uint16_t bits_per_sample;
    uint64_t total_frames;
    uint64_t read_frames;
} simple_wav_t;

static uint16_t rd_le16(const uint8_t *p) {
    return (uint16_t)p[0] | ((uint16_t)p[1] << 8);
}

static uint32_t rd_le32(const uint8_t *p) {
    return (uint32_t)p[0]
        | ((uint32_t)p[1] << 8)
        | ((uint32_t)p[2] << 16)
        | ((uint32_t)p[3] << 24);
}

static void wr_le16(uint8_t *p, uint16_t v) {
    p[0] = (uint8_t)(v & 0xFF);
    p[1] = (uint8_t)((v >> 8) & 0xFF);
}

static void wr_le32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v & 0xFF);
    p[1] = (uint8_t)((v >> 8) & 0xFF);
    p[2] = (uint8_t)((v >> 16) & 0xFF);
    p[3] = (uint8_t)((v >> 24) & 0xFF);
}

static int open_pcm16_wav(const char *path, simple_wav_t *w) {
    memset(w, 0, sizeof(*w));
    w->fp = fopen(path, "rb");
    if (!w->fp) return -1;

    uint8_t hdr[12];
    if (fread(hdr, 1, 12, w->fp) != 12 || memcmp(hdr, "RIFF", 4) != 0 || memcmp(hdr + 8, "WAVE", 4) != 0) {
        fclose(w->fp);
        w->fp = NULL;
        return -2;
    }

    int got_fmt = 0;
    int got_data = 0;
    uint8_t chdr[8];
    while (fread(chdr, 1, 8, w->fp) == 8) {
        uint32_t csize = rd_le32(chdr + 4);
        if (memcmp(chdr, "fmt ", 4) == 0 && csize >= 16) {
            uint8_t fmt[16];
            if (fread(fmt, 1, 16, w->fp) != 16) break;
            uint16_t audio_fmt = rd_le16(fmt + 0);
            w->channels = rd_le16(fmt + 2);
            w->sample_rate = rd_le32(fmt + 4);
            w->bits_per_sample = rd_le16(fmt + 14);
            if (csize > 16) fseek(w->fp, (long)(csize - 16), SEEK_CUR);
            if (csize & 1) fseek(w->fp, 1, SEEK_CUR);
            if (audio_fmt == 1 && w->bits_per_sample == 16 && (w->channels == 1 || w->channels == 2)) {
                got_fmt = 1;
            }
        } else if (memcmp(chdr, "data", 4) == 0 && got_fmt) {
            uint32_t frame_bytes = (uint32_t)w->channels * 2u;
            w->total_frames = frame_bytes > 0 ? (uint64_t)(csize / frame_bytes) : 0;
            got_data = 1;
            break;
        } else {
            fseek(w->fp, (long)(csize + (csize & 1)), SEEK_CUR);
        }
    }

    if (!got_fmt || !got_data || w->total_frames == 0) {
        fclose(w->fp);
        w->fp = NULL;
        return -3;
    }
    return 0;
}

static size_t read_pcm16_block(simple_wav_t *w, size_t req_frames, PCMDataTyp *main_pcm, int *is_silence) {
    int16_t raw[BLOCK * 2];
    if (req_frames > BLOCK) req_frames = BLOCK;
    size_t got = 0;
    if (req_frames > 0 && w->read_frames < w->total_frames) {
        size_t rem = (size_t)(w->total_frames - w->read_frames);
        size_t to_read = req_frames < rem ? req_frames : rem;
        got = fread(raw, (size_t)w->channels * 2u, to_read, w->fp);
        w->read_frames += got;
    }
    if (got < BLOCK) {
        memset(raw + got * w->channels, 0, (BLOCK - got) * (size_t)w->channels * sizeof(int16_t));
    }

    int silent = 1;
    size_t total_samples = got * (size_t)w->channels;
    for (size_t i = 0; i < total_samples; i++) {
        if (raw[i] != 0) { silent = 0; break; }
    }
    *is_silence = silent;

    float *l = main_pcm->L + CENTER;
    float *r = main_pcm->R + CENTER;
    float *m = main_pcm->M + CENTER;
    float *s = main_pcm->S + CENTER;

    if (w->channels == 1) {
        for (size_t i = 0; i < BLOCK; i++) {
            float v = (float)raw[i];
            l[i] = v + MPPENC_DENORMAL_FIX_LEFT;
            r[i] = v + MPPENC_DENORMAL_FIX_RIGHT;
            m[i] = (l[i] + r[i]) * 0.5f;
            s[i] = (l[i] - r[i]) * 0.5f;
        }
    } else {
        for (size_t i = 0; i < BLOCK; i++) {
            l[i] = (float)raw[2 * i] + MPPENC_DENORMAL_FIX_LEFT;
            r[i] = (float)raw[2 * i + 1] + MPPENC_DENORMAL_FIX_RIGHT;
            m[i] = (l[i] + r[i]) * 0.5f;
            s[i] = (l[i] - r[i]) * 0.5f;
        }
    }
    return got;
}

static int g_fastmath_ready = 0;

int a8_mpc_encode_wav(const char *wav_path, const char *mpc_path, float quality) {
    if (!wav_path || !mpc_path) return -1;

    MPC_LOCK();

#ifdef FAST_MATH
    if (!g_fastmath_ready) {
        Init_FastMath();
        g_fastmath_ready = 1;
    }
#endif

    simple_wav_t wav;
    int rc = open_pcm16_wav(wav_path, &wav);
    if (rc != 0) {
        MPC_UNLOCK();
        return rc;
    }

    if (wav.sample_rate != 44100 && wav.sample_rate != 48000 &&
        wav.sample_rate != 37800 && wav.sample_rate != 32000) {
        fclose(wav.fp);
        MPC_UNLOCK();
        return -4;
    }

    FILE *out_fp = fopen(mpc_path, "w+b");
    if (!out_fp) {
        fclose(wav.fp);
        MPC_UNLOCK();
        return -5;
    }

    PsyModel m;
    mpc_encoder_t e;
    memset(&m, 0, sizeof(m));
    memset(&e, 0, sizeof(e));
    m.SCF_Index_L = (int *)e.SCF_Index_L;
    m.SCF_Index_R = (int *)e.SCF_Index_R;

    Init_Psychoakustik(&m);
    m.SampleFreq = (float)wav.sample_rate;
    if (quality < 0.f) quality = 4.f;
    if (quality > 10.f) quality = 10.f;
    SetQualityParams(&m, quality);
    mpc_encoder_init(&e, wav.total_frames, 6, 1);
    Init_Psychoakustiktabellen(&m);

    e.outputFile = out_fp;
    e.MS_Channelmode = m.MS_Channelmode;
    e.seek_ref = (mpc_uint32_t)ftell(e.outputFile);

    writeMagic(&e);
    writeStreamInfo(&e, m.Max_Band, m.MS_Channelmode > 0, (unsigned int)wav.total_frames, 0,
                    wav.sample_rate, wav.channels);
    mpc_uint_t si_size = writeBlock(&e, "SH", MPC_TRUE, 0);
    writeGainInfo(&e, 0, 0, 0, 0);
    writeBlock(&e, "RG", MPC_FALSE, 0);
    writeEncoderInfo(&e, m.FullQual, m.PNS > 0, MPCENC_MAJOR, MPCENC_MINOR, MPCENC_BUILD);
    writeBlock(&e, "EI", MPC_FALSE, 0);

    e.seek_ptr = (mpc_uint32_t)ftell(e.outputFile);
    writeBits(&e, 0, 16);
    writeBits(&e, 0, 24);
    writeBlock(&e, "SO", MPC_FALSE, 0);

    PCMDataTyp main_pcm;
    SubbandFloatTyp X[32];
    SCFTriple power_l[32], power_r[32];
    float error_l[32][36 + MAX_NS_ORDER];
    float error_r[32][36 + MAX_NS_ORDER];
    int transient_l[PART_SHORT], transient_r[PART_SHORT], transient[32];
    memset(&main_pcm, 0, sizeof(main_pcm));
    memset(X, 0, sizeof(X));
    memset(power_l, 0, sizeof(power_l));
    memset(power_r, 0, sizeof(power_r));
    memset(error_l, 0, sizeof(error_l));
    memset(error_r, 0, sizeof(error_r));
    memset(transient_l, 0, sizeof(transient_l));
    memset(transient_r, 0, sizeof(transient_r));
    memset(transient, 0, sizeof(transient));

    int silence = 0, old_silence = 0;
    size_t current_read = read_pcm16_block(&wav, BLOCK, &main_pcm, &silence);
    if (current_read > 0) {
        fill_float_buf(main_pcm.L, main_pcm.L[CENTER], CENTER);
        fill_float_buf(main_pcm.R, main_pcm.R[CENTER], CENTER);
        fill_float_buf(main_pcm.M, main_pcm.M[CENTER], CENTER);
        fill_float_buf(main_pcm.S, main_pcm.S[CENTER], CENTER);
    }
    Analyse_Init(main_pcm.L[CENTER], main_pcm.R[CENTER], X, m.Max_Band);

    uint64_t samples_in_wave = wav.total_frames;
    for (unsigned int N = 0; (uint64_t)N * BLOCK < samples_in_wave + DECODER_DELAY; N++) {
        if (current_read < BLOCK && N > 0) {
            float last_l = current_read > 0 ? main_pcm.L[CENTER + current_read - 1] : 0.f;
            float last_r = current_read > 0 ? main_pcm.R[CENTER + current_read - 1] : 0.f;
            float last_m = current_read > 0 ? main_pcm.M[CENTER + current_read - 1] : 0.f;
            float last_s = current_read > 0 ? main_pcm.S[CENTER + current_read - 1] : 0.f;
            fill_float_buf(main_pcm.L + CENTER + current_read, last_l, (unsigned)(BLOCK - current_read));
            fill_float_buf(main_pcm.R + CENTER + current_read, last_r, (unsigned)(BLOCK - current_read));
            fill_float_buf(main_pcm.M + CENTER + current_read, last_m, (unsigned)(BLOCK - current_read));
            fill_float_buf(main_pcm.S + CENTER + current_read, last_s, (unsigned)(BLOCK - current_read));
        }

        memset(e.Res_L, 0, sizeof(e.Res_L));
        memset(e.Res_R, 0, sizeof(e.Res_R));

        if (!silence || !old_silence) {
            Analyse_Filter(&main_pcm, X, m.Max_Band);
            SMRTyp smr = Psychoakustisches_Modell(&m, 31, &main_pcm, transient_l, transient_r);
            if (m.minSMR > 0) RaiseSMR(&m, m.Max_Band, &smr);
            if (m.MS_Channelmode > 0) MS_LR_Entscheidung(m.Max_Band, e.MS_Flag, &smr, X);
            scf_extraction(&m, &e, m.Max_Band, X, power_l, power_r);
            TransientenCalc(transient, transient_l, transient_r);
            if (m.NS_Order > 0) NS_Analyse(&m, m.Max_Band, e.MS_Flag, smr, transient);
            allocate_bits(m.Max_Band, e.Res_L, X[0].L, e.SCF_Index_L[0], m.SNR_comp_L, smr.L, power_l, transient, m.PNS);
            allocate_bits(m.Max_Band, e.Res_R, X[0].R, e.SCF_Index_R[0], m.SNR_comp_R, smr.R, power_r, transient, m.PNS);
            quantize_bands(&m, m.Max_Band, e.Res_L, e.Res_R, X, e.Q, error_l, error_r);
        }

        old_silence = silence;
        writeBitstream_SV8(&e, m.Max_Band);

        memmove(main_pcm.L, main_pcm.L + BLOCK, CENTER * sizeof(float));
        memmove(main_pcm.R, main_pcm.R + BLOCK, CENTER * sizeof(float));
        memmove(main_pcm.M, main_pcm.M + BLOCK, CENTER * sizeof(float));
        memmove(main_pcm.S, main_pcm.S + BLOCK, CENTER * sizeof(float));

        current_read = read_pcm16_block(&wav, BLOCK, &main_pcm, &silence);
    }

    if (e.framesInBlock != 0) {
        if ((e.block_cnt & ((1u << e.seek_pwr) - 1u)) == 0) {
            e.seek_table[e.seek_pos] = (mpc_uint32_t)ftell(e.outputFile);
            e.seek_pos++;
        }
        e.block_cnt++;
        writeBlock(&e, "AP", MPC_FALSE, 0);
    }
    writeSeekTable(&e);
    writeBlock(&e, "ST", MPC_FALSE, 0);
    writeBlock(&e, "SE", MPC_FALSE, 0);

    if (wav.read_frames != wav.total_frames) {
        fseek(e.outputFile, (long)(e.seek_ref + 4), SEEK_SET);
        writeStreamInfo(&e, m.Max_Band, m.MS_Channelmode > 0, (unsigned int)wav.read_frames, 0,
                        wav.sample_rate, wav.channels);
        writeBlock(&e, "SH", MPC_TRUE, si_size);
        fseek(e.outputFile, 0, SEEK_END);
    }

    fclose(e.outputFile);
    fclose(wav.fp);
    mpc_encoder_exit(&e);

    MPC_UNLOCK();
    return 0;
}

int a8_mpc_decode_wav(const char *mpc_path, const char *wav_path) {
    if (!mpc_path || !wav_path) return -1;

    mpc_reader reader;
    if (mpc_reader_init_stdio(&reader, mpc_path) < 0) return -2;

    mpc_demux *demux = mpc_demux_init(&reader);
    if (!demux) {
        mpc_reader_exit_stdio(&reader);
        return -3;
    }

    mpc_streaminfo si;
    mpc_demux_get_info(demux, &si);

    FILE *out = fopen(wav_path, "wb");
    if (!out) {
        mpc_demux_exit(demux);
        mpc_reader_exit_stdio(&reader);
        return -4;
    }

    uint8_t header[44];
    memset(header, 0, sizeof(header));
    fwrite(header, 1, 44, out);

    MPC_SAMPLE_FORMAT sample_buf[MPC_DECODER_BUFFER_LENGTH];
    int16_t pcm16[MPC_DECODER_BUFFER_LENGTH];
    uint64_t total_bytes = 0;

    while (1) {
        mpc_frame_info frame;
        frame.buffer = sample_buf;
        mpc_status err = mpc_demux_decode(demux, &frame);
        if (err != MPC_STATUS_OK || frame.bits == -1) break;

        uint32_t count = frame.samples * si.channels;
        for (uint32_t i = 0; i < count; i++) {
#ifdef MPC_FIXED_POINT
            int v = sample_buf[i] >> MPC_FIXED_POINT_FRACTPART;
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            pcm16[i] = (int16_t)v;
#else
            float f = sample_buf[i] * 32768.0f;
            if (f > 32767.0f) f = 32767.0f;
            if (f < -32768.0f) f = -32768.0f;
            pcm16[i] = (int16_t)lrintf(f);
#endif
        }
        size_t written = fwrite(pcm16, sizeof(int16_t), count, out);
        total_bytes += (uint64_t)(written * sizeof(int16_t));
    }

    uint32_t data_size = total_bytes > 0xFFFFFFFFULL ? 0xFFFFFFFFu : (uint32_t)total_bytes;
    uint32_t byte_rate = si.sample_freq * si.channels * 2u;
    uint16_t block_align = (uint16_t)(si.channels * 2u);

    memcpy(header + 0, "RIFF", 4);
    wr_le32(header + 4, 36u + data_size);
    memcpy(header + 8, "WAVE", 4);
    memcpy(header + 12, "fmt ", 4);
    wr_le32(header + 16, 16u);
    wr_le16(header + 20, 1u);
    wr_le16(header + 22, (uint16_t)si.channels);
    wr_le32(header + 24, (uint32_t)si.sample_freq);
    wr_le32(header + 28, byte_rate);
    wr_le16(header + 32, block_align);
    wr_le16(header + 34, 16u);
    memcpy(header + 36, "data", 4);
    wr_le32(header + 40, data_size);

    fseek(out, 0, SEEK_SET);
    fwrite(header, 1, 44, out);
    fclose(out);

    mpc_demux_exit(demux);
    mpc_reader_exit_stdio(&reader);
    return total_bytes > 0 ? 0 : -5;
}

int a8_mpc_probe_info(const char *mpc_path, a8_mpc_info_t *out_info) {
    if (!mpc_path || !out_info) return -1;
    memset(out_info, 0, sizeof(*out_info));

    mpc_reader reader;
    if (mpc_reader_init_stdio(&reader, mpc_path) < 0) return -2;

    mpc_demux *demux = mpc_demux_init(&reader);
    if (!demux) {
        mpc_reader_exit_stdio(&reader);
        return -3;
    }

    mpc_streaminfo si;
    mpc_demux_get_info(demux, &si);
    out_info->duration_sec = mpc_streaminfo_get_length(&si);
    out_info->sample_rate = (int)si.sample_freq;
    out_info->channels = (int)si.channels;
    out_info->total_samples = (int64_t)mpc_streaminfo_get_length_samples(&si);

    mpc_demux_exit(demux);
    mpc_reader_exit_stdio(&reader);
    return 0;
}
