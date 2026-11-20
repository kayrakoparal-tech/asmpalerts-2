package com.asmp.dramaticalerts;

/**
 * Animasyon matematiği: easing fonksiyonları, renk/alfa yardımcıları ve
 * ekran sarsıntısı için deterministik yumuşak gürültü.
 *
 * Tüm metotlar statiktir ve nesne üretmez (render döngüsünde güvenle çağrılabilir).
 */
public final class EasingUtils {

    public static final float PI = (float) Math.PI;
    public static final float TAU = PI * 2f;

    private EasingUtils() {
    }

    // ------------------------------------------------------------------
    // Temel yardımcılar
    // ------------------------------------------------------------------

    public static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    public static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** t zamanının [start, end] aralığındaki 0..1 ilerlemesi. */
    public static float segment(float t, float start, float end) {
        if (end <= start) {
            return t >= end ? 1f : 0f;
        }
        return clamp01((t - start) / (end - start));
    }

    public static float smoothStep(float edge0, float edge1, float x) {
        float t = segment(x, edge0, edge1);
        return t * t * (3f - 2f * t);
    }

    /** Yumuşak giriş + yumuşak çıkış zarfı (0..1..0). t, 0..1 aralığında normalize edilmiş zaman. */
    public static float envelope(float t, float fadeIn, float fadeOut) {
        float in = smoothStep(0f, Math.max(fadeIn, 1e-4f), t);
        float out = 1f - smoothStep(1f - Math.max(fadeOut, 1e-4f), 1f, t);
        return Math.min(in, out);
    }

    // ------------------------------------------------------------------
    // Easing fonksiyonları (t: 0..1)
    // ------------------------------------------------------------------

    public static float easeInQuad(float t) {
        t = clamp01(t);
        return t * t;
    }

    public static float easeOutQuad(float t) {
        t = clamp01(t);
        return 1f - (1f - t) * (1f - t);
    }

    public static float easeInCubic(float t) {
        t = clamp01(t);
        return t * t * t;
    }

    public static float easeOutCubic(float t) {
        t = clamp01(t);
        float k = 1f - t;
        return 1f - k * k * k;
    }

    public static float easeOutQuart(float t) {
        t = clamp01(t);
        float k = 1f - t;
        return 1f - k * k * k * k;
    }

    public static float easeInOutQuad(float t) {
        t = clamp01(t);
        if (t < 0.5f) {
            return 2f * t * t;
        }
        float k = -2f * t + 2f;
        return 1f - (k * k) / 2f;
    }

    public static float easeInOutCubic(float t) {
        t = clamp01(t);
        if (t < 0.5f) {
            return 4f * t * t * t;
        }
        float k = -2f * t + 2f;
        return 1f - (k * k * k) / 2f;
    }

    /** Hedefi hafifçe aşıp geri oturan "pop" hareketi (kart giriş animasyonu için ideal). */
    public static float easeOutBack(float t) {
        t = clamp01(t);
        final float c1 = 1.70158f;
        final float c3 = c1 + 1f;
        float k = t - 1f;
        return 1f + c3 * k * k * k + c1 * k * k;
    }

    public static float easeOutExpo(float t) {
        t = clamp01(t);
        return t >= 1f ? 1f : 1f - (float) Math.pow(2.0, -10.0 * t);
    }

    public static float easeOutElastic(float t) {
        t = clamp01(t);
        if (t <= 0f) {
            return 0f;
        }
        if (t >= 1f) {
            return 1f;
        }
        final float c4 = TAU / 3f;
        return (float) (Math.pow(2.0, -10.0 * t) * Math.sin((t * 10.0 - 0.75) * c4) + 1.0);
    }

    // ------------------------------------------------------------------
    // Rolling number
    // ------------------------------------------------------------------

    /**
     * 0'dan hedef değere sayan değer. easeOutCubic ile başta hızlı, sonda yavaşlayan sayım verir.
     * double kullanılır; 2^53'e kadar (~9e15) tam doğrudur.
     */
    public static long rollingValue(long target, float progress) {
        if (progress >= 1f) {
            return target;
        }
        if (progress <= 0f) {
            return 0L;
        }
        return (long) (target * (double) easeOutCubic(progress));
    }

    // ------------------------------------------------------------------
    // Renk yardımcıları (ARGB int)
    // ------------------------------------------------------------------

    public static int lerpColor(int c0, int c1, float t) {
        t = clamp01(t);
        int a = lerpChannel((c0 >>> 24) & 0xFF, (c1 >>> 24) & 0xFF, t);
        int r = lerpChannel((c0 >>> 16) & 0xFF, (c1 >>> 16) & 0xFF, t);
        int g = lerpChannel((c0 >>> 8) & 0xFF, (c1 >>> 8) & 0xFF, t);
        int b = lerpChannel(c0 & 0xFF, c1 & 0xFF, t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerpChannel(int a, int b, float t) {
        return (int) (a + (b - a) * t + 0.5f);
    }

    /** Rengin mevcut alfasını verilen çarpanla ölçekler. */
    public static int withAlpha(int argb, float alphaMul) {
        int a = (int) (((argb >>> 24) & 0xFF) * clamp01(alphaMul));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /** RGB'yi korur, alfayı 0..1 değerine ayarlar. */
    public static int setAlpha(int argb, float alpha) {
        int a = (int) (clamp01(alpha) * 255f + 0.5f);
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    // ------------------------------------------------------------------
    // Gürültü (ekran sarsıntısı)
    // ------------------------------------------------------------------

    private static float hash(int i, int seed) {
        int h = i * 374761393 + seed * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= (h >>> 16);
        return (h & 0xFFFFFF) / 16777216f;
    }

    /** 1B yumuşak değer gürültüsü, çıktı aralığı -1..1. */
    public static float noise1(float x, int seed) {
        int i = (int) Math.floor(x);
        float f = x - i;
        float u = f * f * (3f - 2f * f);
        return lerp(hash(i, seed), hash(i + 1, seed), u) * 2f - 1f;
    }
}
