/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Pure freestanding C99 native engine for ModuleBongoCat (libbongocat.so).
 * Exports plain C ABI symbols invoked via LWJGL SharedLibrary + JNI.invoke*
 * on Android PojavLauncher (arm64-v8a / x86_64) without external libc/libm deps.
 */

#include <stdint.h>

#if defined(__GNUC__) || defined(__clang__)
#define BONGOCAT_EXPORT __attribute__((visibility("default"), used))
#else
#define BONGOCAT_EXPORT
#endif

/* Input bitmask flags */
#define BC_IN_LEFT_CLICK   (1 << 0)
#define BC_IN_RIGHT_CLICK  (1 << 1)
#define BC_IN_KEY_W        (1 << 2)
#define BC_IN_KEY_A        (1 << 3)
#define BC_IN_KEY_S        (1 << 4)
#define BC_IN_KEY_D        (1 << 5)
#define BC_IN_KEY_SPACE    (1 << 6)
#define BC_IN_MODIFIER     (1 << 7)

#define BC_CPS_SLOTS 20

typedef struct BongoCatState {
    uint32_t magic;
    uint32_t rng;
    float time_sec;

    /* Left & right paw vertical strike [0 = raised, 1 = striking surface] + velocity */
    float left_down;
    float left_vel;
    float left_x;
    float left_y;

    float right_down;
    float right_vel;
    float right_x;
    float right_y;

    /* Chubby body bounce, squash, head tilt, ear wiggle */
    float body_squash;
    float body_squash_vel;
    float body_bounce_y;
    float body_bounce_vel;
    float head_tilt;
    float head_tilt_vel;
    float ear_wiggle;
    float ear_wiggle_vel;

    /* Blink & mouth animation */
    float blink_timer;
    float blink_phase;
    float eye_open;
    float mouth_open;
    float mouth_vel;

    /* Drum / keycap hit glow */
    float left_glow;
    float right_glow;

    /* Edge detection & rolling 1.0s CPS buckets (20 x 50ms) */
    int32_t prev_mask;
    float bucket_accum;
    int32_t bucket_index;
    uint16_t left_buckets[BC_CPS_SLOTS];
    uint16_t right_buckets[BC_CPS_SLOTS];
} BongoCatState;

static inline float bc_clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

/* Fast minimax sine approximation on arbitrary radians, freestanding (no libm needed) */
static float bc_sinf(float x) {
    const float two_pi = 6.283185307f;
    const float inv_two_pi = 0.159154943f;
    const float pi = 3.141592653f;
    int32_t k = (int32_t)(x * inv_two_pi);
    float r = x - (float)k * two_pi;
    if (r > pi) r -= two_pi;
    if (r < -pi) r += two_pi;
    /* Bhaskara / parabola + precision correction */
    float abs_r = r < 0.0f ? -r : r;
    float y = 1.27323954f * r - 0.405284735f * r * abs_r;
    float abs_y = y < 0.0f ? -y : y;
    return 0.225f * (y * abs_y - y) + y;
}

static uint32_t bc_xorshift32(uint32_t *state) {
    uint32_t x = *state;
    if (x == 0u) x = 0x9E3779B9u;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    *state = x;
    return x;
}

BONGOCAT_EXPORT int32_t bongocat_get_version(void) {
    return 0x00010000;
}

BONGOCAT_EXPORT int32_t bongocat_state_size(void) {
    return (int32_t)sizeof(BongoCatState);
}

BONGOCAT_EXPORT void bongocat_init(void *raw_state) {
    if (!raw_state) return;
    uint8_t *bytes = (uint8_t *)raw_state;
    for (uint32_t i = 0; i < (uint32_t)sizeof(BongoCatState); ++i) {
        bytes[i] = 0u;
    }
    BongoCatState *s = (BongoCatState *)raw_state;
    s->magic = 0xB0460CA7u;
    s->rng = 0xC0FFEE42u;
    s->blink_timer = 2.6f;
    s->eye_open = 1.0f;
}

/*
 * Steps BongoCat kinematics & spring physics and writes 16 floats into out_pose:
 *   [0]  leftPawDown   (0..1)
 *   [1]  leftPawX      (-1..1)
 *   [2]  leftPawY      (-1..1)
 *   [3]  rightPawDown  (0..1)
 *   [4]  rightPawX     (-1..1)
 *   [5]  rightPawY     (-1..1)
 *   [6]  bodySquash    (-0.25..0.35)
 *   [7]  bodyBounceY   (-12..12 px normalized)
 *   [8]  headTiltDeg   (-14..14 deg)
 *   [9]  earWiggle     (-1..1)
 *   [10] eyeOpen       (0..1)
 *   [11] mouthOpen     (0..1)
 *   [12] leftGlow      (0..1)
 *   [13] rightGlow     (0..1)
 *   [14] leftCps
 *   [15] rightCps
 */
BONGOCAT_EXPORT void bongocat_step(
    void *raw_state,
    const int32_t *in_mask_ptr,
    const float *in_floats,
    float *out_pose
) {
    if (!raw_state || !in_mask_ptr || !in_floats || !out_pose) return;
    float dt = in_floats[0];
    float mouse_nx = in_floats[1];
    float mouse_ny = in_floats[2];
    int32_t input_mask = in_mask_ptr[0];
    BongoCatState *s = (BongoCatState *)raw_state;
    if (s->magic != 0xB0460CA7u) {
        bongocat_init(raw_state);
    }

    float clamped_dt = bc_clampf(dt, 0.0005f, 0.05f);
    s->time_sec += clamped_dt;

    int32_t rising = input_mask & (~s->prev_mask);
    s->prev_mask = input_mask;

    /* Left side inputs: Left Click or A / W / Space */
    int32_t left_held = (input_mask & (BC_IN_LEFT_CLICK | BC_IN_KEY_A | BC_IN_KEY_W | BC_IN_KEY_SPACE)) != 0;
    int32_t left_edge = (rising & (BC_IN_LEFT_CLICK | BC_IN_KEY_A | BC_IN_KEY_W | BC_IN_KEY_SPACE)) != 0;

    /* Right side inputs: Right Click or D / S / Modifier */
    int32_t right_held = (input_mask & (BC_IN_RIGHT_CLICK | BC_IN_KEY_D | BC_IN_KEY_S | BC_IN_MODIFIER)) != 0;
    int32_t right_edge = (rising & (BC_IN_RIGHT_CLICK | BC_IN_KEY_D | BC_IN_KEY_S | BC_IN_MODIFIER)) != 0;

    /* Rolling 1-second CPS window */
    s->bucket_accum += clamped_dt;
    while (s->bucket_accum >= 0.05f) {
        s->bucket_accum -= 0.05f;
        s->bucket_index = (s->bucket_index + 1) % BC_CPS_SLOTS;
        s->left_buckets[s->bucket_index] = 0u;
        s->right_buckets[s->bucket_index] = 0u;
    }
    if ((rising & BC_IN_LEFT_CLICK) != 0) {
        s->left_buckets[s->bucket_index]++;
    }
    if ((rising & BC_IN_RIGHT_CLICK) != 0) {
        s->right_buckets[s->bucket_index]++;
    }

    int32_t left_cps_sum = 0;
    int32_t right_cps_sum = 0;
    for (int32_t i = 0; i < BC_CPS_SLOTS; ++i) {
        left_cps_sum += (int32_t)s->left_buckets[i];
        right_cps_sum += (int32_t)s->right_buckets[i];
    }

    /* Strike impulses on rising edge */
    if (left_edge) {
        s->left_down = 1.0f;
        s->left_vel = 8.5f;
        s->left_glow = 1.0f;
        s->body_squash_vel += 3.4f;
        s->body_bounce_vel += 42.0f;
        s->head_tilt_vel -= 68.0f;
        s->ear_wiggle_vel += 14.0f;
        s->mouth_vel += 5.5f;
    }
    if (right_edge) {
        s->right_down = 1.0f;
        s->right_vel = 8.5f;
        s->right_glow = 1.0f;
        s->body_squash_vel += 3.4f;
        s->body_bounce_vel += 42.0f;
        s->head_tilt_vel += 68.0f;
        s->ear_wiggle_vel -= 14.0f;
        s->mouth_vel += 5.5f;
    }

    /* Fixed sub-stepping for unconditionally stable spring physics */
    int32_t steps = (int32_t)(clamped_dt / 0.0083333f) + 1;
    if (steps > 6) steps = 6;
    float h = clamped_dt / (float)steps;

    float target_left = left_held ? 1.0f : 0.0f;
    float target_right = right_held ? 1.0f : 0.0f;

    float target_lx = 0.0f;
    float target_ly = 0.0f;
    if (input_mask & BC_IN_KEY_A) target_lx -= 0.55f;
    if (input_mask & BC_IN_KEY_W) target_ly -= 0.45f;
    if (input_mask & BC_IN_KEY_SPACE) target_lx += 0.45f;

    float clamped_mx = bc_clampf(mouse_nx, -1.0f, 1.0f);
    float clamped_my = bc_clampf(mouse_ny, -1.0f, 1.0f);
    float target_rx = clamped_mx * 0.75f + ((input_mask & BC_IN_KEY_D) ? 0.35f : 0.0f) - ((input_mask & BC_IN_KEY_S) ? 0.25f : 0.0f);
    float target_ry = clamped_my * 0.65f + ((input_mask & BC_IN_KEY_S) ? 0.35f : 0.0f);

    float idle_bob = bc_sinf(s->time_sec * 3.1f) * 1.4f;
    float idle_tilt = bc_sinf(s->time_sec * 1.85f) * 2.2f;
    float total_cps = (float)(left_cps_sum + right_cps_sum);
    float mouth_target = (left_held || right_held || total_cps >= 4.0f)
        ? bc_clampf(0.38f + total_cps * 0.045f, 0.35f, 1.0f)
        : 0.05f;

    for (int32_t i = 0; i < steps; ++i) {
        /* Paw vertical springs (snappy 420 stiffness) */
        s->left_vel += ((target_left - s->left_down) * 440.0f - s->left_vel * 28.0f) * h;
        s->left_down = bc_clampf(s->left_down + s->left_vel * h, 0.0f, 1.12f);

        s->right_vel += ((target_right - s->right_down) * 440.0f - s->right_vel * 28.0f) * h;
        s->right_down = bc_clampf(s->right_down + s->right_vel * h, 0.0f, 1.12f);

        /* Smooth horizontal/vertical reach */
        s->left_x += (target_lx - s->left_x) * bc_clampf(20.0f * h, 0.0f, 1.0f);
        s->left_y += (target_ly - s->left_y) * bc_clampf(20.0f * h, 0.0f, 1.0f);
        s->right_x += (target_rx - s->right_x) * bc_clampf(18.0f * h, 0.0f, 1.0f);
        s->right_y += (target_ry - s->right_y) * bc_clampf(18.0f * h, 0.0f, 1.0f);

        /* Jelly body squash & bounce */
        s->body_squash_vel += ((0.0f - s->body_squash) * 210.0f - s->body_squash_vel * 16.0f) * h;
        s->body_squash = bc_clampf(s->body_squash + s->body_squash_vel * h, -0.22f, 0.35f);

        s->body_bounce_vel += ((idle_bob - s->body_bounce_y) * 180.0f - s->body_bounce_vel * 15.0f) * h;
        s->body_bounce_y = bc_clampf(s->body_bounce_y + s->body_bounce_vel * h, -10.0f, 12.0f);

        /* Head tilt & ear wiggle */
        float desired_tilt = idle_tilt + (s->right_down - s->left_down) * 4.8f + clamped_mx * 3.0f;
        s->head_tilt_vel += ((desired_tilt - s->head_tilt) * 165.0f - s->head_tilt_vel * 14.0f) * h;
        s->head_tilt = bc_clampf(s->head_tilt + s->head_tilt_vel * h, -15.0f, 15.0f);

        s->ear_wiggle_vel += ((0.0f - s->ear_wiggle) * 260.0f - s->ear_wiggle_vel * 13.0f) * h;
        s->ear_wiggle = bc_clampf(s->ear_wiggle + s->ear_wiggle_vel * h, -1.2f, 1.2f);

        /* Happy ':O' mouth spring */
        s->mouth_vel += ((mouth_target - s->mouth_open) * 190.0f - s->mouth_vel * 17.0f) * h;
        s->mouth_open = bc_clampf(s->mouth_open + s->mouth_vel * h, 0.0f, 1.0f);
    }

    /* Natural random blink cycle */
    s->blink_timer -= clamped_dt;
    if (s->blink_timer <= 0.0f && s->blink_phase <= 0.0f) {
        s->blink_phase = 0.15f;
        uint32_t rv = bc_xorshift32(&s->rng) & 0xFFFFu;
        s->blink_timer = 2.2f + ((float)rv / 65535.0f) * 3.4f;
    }
    if (s->blink_phase > 0.0f) {
        s->blink_phase -= clamped_dt;
        float norm = s->blink_phase / 0.15f; /* 1 -> 0 */
        float tri = norm > 0.5f ? (norm - 0.5f) * 2.0f : (0.5f - norm) * 2.0f;
        s->eye_open = bc_clampf(tri, 0.0f, 1.0f);
    } else {
        s->eye_open = 1.0f;
    }

    /* Drum / keycap glow decay */
    s->left_glow = bc_clampf(s->left_glow - clamped_dt * 4.2f, 0.0f, 1.0f);
    s->right_glow = bc_clampf(s->right_glow - clamped_dt * 4.2f, 0.0f, 1.0f);

    out_pose[0] = bc_clampf(s->left_down, 0.0f, 1.0f);
    out_pose[1] = s->left_x;
    out_pose[2] = s->left_y;
    out_pose[3] = bc_clampf(s->right_down, 0.0f, 1.0f);
    out_pose[4] = s->right_x;
    out_pose[5] = s->right_y;
    out_pose[6] = s->body_squash;
    out_pose[7] = s->body_bounce_y;
    out_pose[8] = s->head_tilt;
    out_pose[9] = s->ear_wiggle;
    out_pose[10] = s->eye_open;
    out_pose[11] = s->mouth_open;
    out_pose[12] = s->left_glow;
    out_pose[13] = s->right_glow;
    out_pose[14] = (float)left_cps_sum;
    out_pose[15] = (float)right_cps_sum;
}
