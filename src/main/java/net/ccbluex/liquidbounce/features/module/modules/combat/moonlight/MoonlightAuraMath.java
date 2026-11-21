/*
 * MoonLight KillAura rotation smoothing, adapted from MoonLight RotationUtils.smooth.
 * Copyright MoonLight contributors; GPL-3.0-or-later.
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.moonlight;

public final class MoonlightAuraMath {
    private MoonlightAuraMath() { }

    public static float wrap(float value) {
        value %= 360.0f;
        if (value >= 180.0f) value -= 360.0f;
        if (value < -180.0f) value += 360.0f;
        return value;
    }

    public static float smooth(float current, float target, String mode, float speed,
                               float[] p, float elasticity, float damping) {
        if (p == null || p.length != 8) throw new IllegalArgumentException("Eight Bezier points required");
        final float s = Math.max(0.0f, Math.min(1.0f, speed / 180.0f));
        final float delta = wrap(target - current);
        return switch (mode) {
            case "Slerp" -> current + delta * s;
            case "Adaptive Bezier" -> {
                float factor = bezier(s, p);
                yield current + Math.min(Math.abs(delta), factor) * Math.signum(delta);
            }
            case "Adaptive Slerp" -> current +
                Math.min(Math.abs(delta) * Math.abs(delta) * s, Math.abs(delta)) * Math.signum(delta);
            case "Sinusoidal" -> current + delta * (float) Math.sin(s * Math.PI / 2.0);
            case "Cosine Interpolation" -> current + delta * (float) ((1.0 - Math.cos(Math.PI * s)) * 0.5);
            case "Logarithmic Interpolation" -> current + delta * (float) Math.log(1.0 + s);
            case "Elastic Spring" -> current + delta *
                (float) (Math.exp(-elasticity * s) * Math.cos(damping * s * Math.PI));
            case "Bezier" -> current + delta * bezier(s, p);
            // MoonLight lists "Spring" but its smooth() switch has no Spring case.
            default -> target;
        };
    }

    private static float bezier(float s, float[] p) {
        double q = 1.0 - s;
        return (float) (Math.pow(q, 7) * p[0] + 7 * Math.pow(q, 6) * s * p[1]
            + 21 * Math.pow(q, 5) * Math.pow(s, 2) * p[2]
            + 35 * Math.pow(q, 4) * Math.pow(s, 3) * p[3]
            + 35 * Math.pow(q, 3) * Math.pow(s, 4) * p[4]
            + 21 * Math.pow(q, 2) * Math.pow(s, 5) * p[5]
            + 7 * q * Math.pow(s, 6) * p[6] + Math.pow(s, 7) * p[7]);
    }
}
