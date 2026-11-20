package com.asmp.dramaticalerts;

/**
 * 2B HUD parçacık motoru.
 *
 * - {@link HudParticle}: tek bir parçacığın durumu ve matematiği (render'dan bağımsız).
 * - {@link Engine}: sabit boyutlu havuz (pool). Render döngüsünde nesne üretilmez.
 * - {@link Engine.Meteor}: kuyruklu meteor (kayan ateş topu + iz üretimi + çarpma).
 * - {@link Ring}: şok dalgası halkası.
 *
 * Koordinatlar GUI-ölçekli piksel cinsindendir (DrawContext ile aynı uzay).
 * Hızlar, {@link Engine#setViewport(float, float)} ile hesaplanan "unit" ile
 * ekran yüksekliğine göre ölçeklenir (referans: 270 px yükseklik).
 */
public final class HudParticle {

    public enum Kind {
        SPARK, EMBER, SMOKE, STAR, STREAK, CONFETTI, DIAMOND, FLASH
    }

    public interface ImpactListener {
        void onMeteorImpact(float x, float y, float intensity);
    }

    // ARGB paletler
    public static final int[] PALETTE_FIREWORK = {
            0xFFFF3B3B, 0xFFFFB02E, 0xFFFFE94D, 0xFF4DFF88,
            0xFF4DD2FF, 0xFF8F6BFF, 0xFFFF5CE1, 0xFFFFFFFF
    };
    public static final int[] PALETTE_GOLD_PURPLE = {
            0xFFFFD34D, 0xFFFFB800, 0xFFFFF0A0, 0xFFB44DFF, 0xFF8A2BE2, 0xFFE0A3FF
    };
    public static final int[] PALETTE_DIAMOND_GOLD = {
            0xFFFFD34D, 0xFFFFF0A0, 0xFF7DF9FF, 0xFFB8FFFF, 0xFFFFFFFF, 0xFFFFB800
    };

    // ------------------------------------------------------------------
    // Parçacık durumu (alanlar render için public)
    // ------------------------------------------------------------------

    public Kind kind = Kind.SPARK;
    public boolean alive;
    public float x, y, prevX, prevY;
    public float vx, vy, ax, ay, drag;
    public float age, life;
    public float size0, size1;
    public float rotation, rotationSpeed;
    public float twinklePhase, twinkleSpeed;
    public int color0, color1;

    /** @return parçacık hâlâ yaşıyorsa true */
    public boolean update(float dt) {
        age += dt;
        if (age >= life) {
            alive = false;
            return false;
        }
        prevX = x;
        prevY = y;
        float damp = 1f / (1f + drag * dt);
        vx = (vx + ax * dt) * damp;
        vy = (vy + ay * dt) * damp;
        x += vx * dt;
        y += vy * dt;
        rotation += rotationSpeed * dt;
        return true;
    }

    public float progress() {
        return life <= 0f ? 1f : age / life;
    }

    public float size() {
        return EasingUtils.lerp(size0, size1, EasingUtils.clamp01(progress()));
    }

    /** Anlık ARGB renk (renk geçişi + alfa zarfı + yıldız parıltısı dahil). */
    public int color() {
        float p = EasingUtils.clamp01(progress());
        int base = EasingUtils.lerpColor(color0, color1, p);
        float a;
        switch (kind) {
            case FLASH: {
                float k = 1f - p;
                a = k * k;
                break;
            }
            case SMOKE:
                a = Math.min(p / 0.15f, (1f - p) / 0.85f) * 0.55f;
                break;
            case STAR:
            case DIAMOND:
                a = Math.min(Math.min(p / 0.08f, (1f - p) / 0.35f), 1f)
                        * (0.65f + 0.35f * (float) Math.sin(age * twinkleSpeed + twinklePhase));
                break;
            default:
                a = Math.min(Math.min(p / 0.06f, (1f - p) / 0.35f), 1f);
                break;
        }
        return EasingUtils.withAlpha(base, a);
    }

    // ------------------------------------------------------------------
    // Şok dalgası halkası
    // ------------------------------------------------------------------

    public static final class Ring {
        public boolean active;
        public float cx, cy;
        public float delay, duration, age;
        public float maxRadius, thickness;
        public int color;

        void update(float dt) {
            if (!active) {
                return;
            }
            age += dt;
            if (age >= delay + duration) {
                active = false;
            }
        }

        public boolean isVisible() {
            return active && age >= delay;
        }

        public float progress() {
            return EasingUtils.clamp01((age - delay) / Math.max(duration, 1e-4f));
        }

        public float radius() {
            return maxRadius * EasingUtils.easeOutCubic(progress());
        }

        /** Başta hızlı belirir, sonra sönümlenir. */
        public float alpha() {
            float p = progress();
            float k = 1f - p;
            return Math.min(1f, p * 12f) * k * k;
        }

        /** Halka genişledikçe incelir. */
        public float currentThickness() {
            return Math.max(0.6f, thickness * (1f - 0.7f * progress()));
        }
    }

    // ------------------------------------------------------------------
    // Motor
    // ------------------------------------------------------------------

    public static final class Engine {

        private final HudParticle[] pool;
        private final Meteor[] meteors = new Meteor[16];
        private final Ring[] rings = new Ring[10];

        private int cursor;
        private int aliveCount;
        private boolean full;

        private float width = 480f;
        private float height = 270f;
        private float unit = 1f;
        private float density = 1f;

        private int seed = 0x9E3779B9;
        private ImpactListener impactListener;

        public Engine(int capacity) {
            pool = new HudParticle[Math.max(64, capacity)];
            for (int i = 0; i < pool.length; i++) {
                pool[i] = new HudParticle();
            }
            for (int i = 0; i < meteors.length; i++) {
                meteors[i] = new Meteor();
            }
            for (int i = 0; i < rings.length; i++) {
                rings[i] = new Ring();
            }
            seed ^= (int) System.nanoTime();
            if (seed == 0) {
                seed = 1;
            }
        }

        // --- yapılandırma ---

        public void setViewport(float scaledWidth, float scaledHeight) {
            this.width = scaledWidth;
            this.height = scaledHeight;
            this.unit = EasingUtils.clamp(scaledHeight / 270f, 0.6f, 3f);
        }

        public void setDensity(float density) {
            this.density = Math.max(0f, density);
        }

        public void setImpactListener(ImpactListener listener) {
            this.impactListener = listener;
        }

        public void clear() {
            for (HudParticle p : pool) {
                p.alive = false;
            }
            for (Meteor m : meteors) {
                m.active = false;
            }
            for (Ring r : rings) {
                r.active = false;
            }
            aliveCount = 0;
            full = false;
            cursor = 0;
        }

        // --- render erişimi ---

        public HudParticle[] particles() {
            return pool;
        }

        public Meteor[] meteors() {
            return meteors;
        }

        public Ring[] rings() {
            return rings;
        }

        public int aliveCount() {
            return aliveCount;
        }

        public float unit() {
            return unit;
        }

        public float width() {
            return width;
        }

        public float height() {
            return height;
        }

        // --- güncelleme ---

        public void update(float dt) {
            if (dt > 0.1f) {
                dt = 0.1f;
            }
            if (dt <= 0f) {
                return;
            }
            for (Meteor m : meteors) {
                m.update(dt);
            }
            for (Ring r : rings) {
                r.update(dt);
            }
            int alive = 0;
            for (HudParticle p : pool) {
                if (p.alive && p.update(dt)) {
                    alive++;
                }
            }
            aliveCount = alive;
            full = alive >= pool.length;
        }

        // --- rastgelelik (xorshift, allocation yok) ---

        private float rand() {
            int s = seed;
            s ^= s << 13;
            s ^= s >>> 17;
            s ^= s << 5;
            seed = s;
            return (s >>> 8) * (1f / 16777216f);
        }

        private float range(float a, float b) {
            return a + (b - a) * rand();
        }

        private int scaled(int base) {
            return density <= 0.001f ? 0 : Math.max(1, Math.round(base * density));
        }

        private int pick(int[] palette) {
            return palette[Math.min(palette.length - 1, (int) (rand() * palette.length))];
        }

        public int randomFireworkColor() {
            return pick(PALETTE_FIREWORK);
        }

        // --- havuz ---

        private HudParticle obtain() {
            if (full) {
                return null;
            }
            int n = pool.length;
            for (int i = 0; i < n; i++) {
                int idx = cursor;
                cursor = (cursor + 1 == n) ? 0 : cursor + 1;
                HudParticle p = pool[idx];
                if (!p.alive) {
                    p.alive = true;
                    return p;
                }
            }
            full = true;
            return null;
        }

        private HudParticle spawn(Kind kind, float x, float y, float vx, float vy,
                                  float ax, float ay, float drag, float life,
                                  float size0, float size1, int color0, int color1) {
            HudParticle p = obtain();
            if (p == null) {
                return null;
            }
            p.kind = kind;
            p.x = x;
            p.y = y;
            p.prevX = x;
            p.prevY = y;
            p.vx = vx;
            p.vy = vy;
            p.ax = ax;
            p.ay = ay;
            p.drag = drag;
            p.age = 0f;
            p.life = life;
            p.size0 = size0;
            p.size1 = size1;
            p.color0 = color0;
            p.color1 = color1;
            p.rotation = 0f;
            p.rotationSpeed = 0f;
            p.twinklePhase = 0f;
            p.twinkleSpeed = 0f;
            return p;
        }

        // ------------------------------------------------------------------
        // Efekt üreticileri
        // ------------------------------------------------------------------

        /**
         * Havai fişek patlaması: radyal kıvılcımlar + parıldayan yıldızlar + merkez parlaması.
         *
         * @param speed referans hız (GUI px/sn, unit ile ölçeklenir), tipik 90..160
         */
        public void spawnFireworkBurst(float cx, float cy, int baseCount, float speed,
                                       int colorA, int colorB) {
            if (density <= 0.001f) {
                return;
            }
            spawn(Kind.FLASH, cx, cy, 0f, 0f, 0f, 0f, 0f, 0.28f,
                    8f * unit, 40f * unit, 0xFFFFFFFF, colorA);

            int count = scaled(baseCount);
            float step = EasingUtils.TAU / count;
            float offset = rand() * EasingUtils.TAU;
            for (int i = 0; i < count; i++) {
                float ang = offset + i * step + (rand() - 0.5f) * step * 0.7f;
                float sp = speed * unit * range(0.5f, 1f);
                int c0 = (i & 1) == 0 ? colorA : colorB;
                int c1 = EasingUtils.lerpColor(c0, 0xFF200A00, 0.55f);
                spawn(Kind.SPARK, cx, cy,
                        (float) Math.cos(ang) * sp, (float) Math.sin(ang) * sp,
                        0f, 140f * unit, 1.5f, range(0.9f, 1.6f),
                        range(1.8f, 2.6f) * unit, 0.5f * unit, c0, c1);
            }

            int stars = Math.max(1, count / 4);
            for (int i = 0; i < stars; i++) {
                float ang = rand() * EasingUtils.TAU;
                float sp = speed * unit * range(0.15f, 0.6f);
                HudParticle p = spawn(Kind.STAR, cx, cy,
                        (float) Math.cos(ang) * sp, (float) Math.sin(ang) * sp,
                        0f, 55f * unit, 1.1f, range(1.2f, 2.1f),
                        range(3.0f, 4.4f) * unit, 1.0f * unit, 0xFFFFFFFF, colorA);
                if (p != null) {
                    p.twinklePhase = rand() * EasingUtils.TAU;
                    p.twinkleSpeed = range(10f, 18f);
                    p.rotationSpeed = range(-3f, 3f);
                }
            }
        }

        /** Meteorun bıraktığı ateş/duman izinden tek bir "nefes". Meteor tarafından çağrılır. */
        public void spawnMeteorTrail(float x, float y, float dirX, float dirY, float scale) {
            if (density <= 0.001f) {
                return;
            }
            float u = unit * Math.max(0.5f, scale);

            spawn(Kind.EMBER,
                    x + (rand() - 0.5f) * 6f * u, y + (rand() - 0.5f) * 6f * u,
                    -dirX * range(30f, 90f) * unit + (rand() - 0.5f) * 40f * unit,
                    -dirY * range(30f, 90f) * unit + (rand() - 0.5f) * 40f * unit,
                    0f, 60f * unit, 1.8f, range(0.45f, 0.9f),
                    range(3.2f, 4.6f) * u, 0.4f * u, 0xFFFFE08A, 0xFFC43A00);

            if (rand() < 0.6f) {
                spawn(Kind.SMOKE,
                        x + (rand() - 0.5f) * 4f * u, y + (rand() - 0.5f) * 4f * u,
                        -dirX * 20f * unit + (rand() - 0.5f) * 24f * unit,
                        -dirY * 20f * unit + (rand() - 0.5f) * 24f * unit,
                        0f, -15f * unit, 1.2f, range(0.9f, 1.5f),
                        4f * u, 12f * u, 0xFF5A4A40, 0xFF1E1A18);
            }
            if (rand() < 0.35f) {
                spawn(Kind.SPARK, x, y,
                        -dirX * range(60f, 140f) * unit + (rand() - 0.5f) * 90f * unit,
                        -dirY * range(60f, 140f) * unit + (rand() - 0.5f) * 90f * unit,
                        0f, 120f * unit, 1.4f, range(0.4f, 0.8f),
                        range(1.6f, 2.4f) * u, 0.4f * u, 0xFFFFD34D, 0xFFFF6A00);
            }
        }

        /** Meteor çarpma patlaması: kıvılcım/kor + duman + büyük parlama. */
        public void spawnImpact(float cx, float cy, float intensity) {
            if (density <= 0.001f) {
                return;
            }
            float k = Math.max(0.5f, intensity);
            float ks = (float) Math.sqrt(k);

            spawn(Kind.FLASH, cx, cy, 0f, 0f, 0f, 0f, 0f, 0.35f,
                    14f * unit * k, 90f * unit * k, 0xFFFFF2C0, 0xFFFF7A1A);

            int n = scaled(Math.round(34 * k));
            for (int i = 0; i < n; i++) {
                float ang = rand() * EasingUtils.TAU;
                float sp = range(70f, 240f) * unit * ks;
                boolean spark = rand() < 0.6f;
                spawn(spark ? Kind.SPARK : Kind.EMBER, cx, cy,
                        (float) Math.cos(ang) * sp, (float) Math.sin(ang) * sp,
                        0f, 220f * unit, 1.2f, range(0.7f, 1.4f),
                        range(2.2f, 3.8f) * unit, 0.4f * unit,
                        spark ? 0xFFFFF0B0 : 0xFFFFB347, 0xFFB01E00);
            }

            int smoke = scaled(Math.round(9 * k));
            for (int i = 0; i < smoke; i++) {
                spawn(Kind.SMOKE,
                        cx + (rand() - 0.5f) * 14f * unit, cy + (rand() - 0.5f) * 10f * unit,
                        range(-30f, 30f) * unit, range(-50f, -10f) * unit,
                        0f, -10f * unit, 1.4f, range(1.0f, 1.8f),
                        8f * unit, 26f * unit, 0xFF6B5A50, 0xFF1C1816);
            }
        }

        /**
         * Yukarıdan süzülen konfeti/elmas yağmuru.
         *
         * @param palette {@link #PALETTE_GOLD_PURPLE} veya {@link #PALETTE_DIAMOND_GOLD} gibi
         */
        public void spawnConfetti(float xMin, float xMax, float y, int baseCount, int[] palette) {
            if (density <= 0.001f) {
                return;
            }
            int count = scaled(baseCount);
            for (int i = 0; i < count; i++) {
                boolean diamond = rand() < 0.28f;
                int col = pick(palette);
                HudParticle p = spawn(diamond ? Kind.DIAMOND : Kind.CONFETTI,
                        range(xMin, xMax), y + range(-12f, 4f) * unit,
                        range(-28f, 28f) * unit, range(35f, 115f) * unit,
                        range(-6f, 6f) * unit, 30f * unit, 0.35f, range(2.4f, 4.0f),
                        range(2.4f, 4.2f) * unit, range(1.8f, 3.2f) * unit, col, col);
                if (p != null) {
                    p.rotation = rand() * EasingUtils.TAU;
                    p.rotationSpeed = range(-7f, 7f);
                    p.twinklePhase = rand() * EasingUtils.TAU;
                    p.twinkleSpeed = range(6f, 14f);
                }
            }
        }

        /**
         * Çapraz düşen yıldız çizgileri (STREAK). Renderer çizgiyi (x,y) → (x - vx*k, y - vy*k) olarak çizer.
         *
         * @param yTop başlangıç y aralığının üst sınırı
         */
        public void spawnStarRain(int baseCount, float yTop) {
            if (density <= 0.001f) {
                return;
            }
            int count = scaled(baseCount);
            final float dx = -0.55f;
            final float dy = 0.835f;
            for (int i = 0; i < count; i++) {
                float sx = range(width * 0.1f, width * 1.25f);
                float sy = range(-30f * unit, yTop);
                float sp = range(300f, 460f) * unit;
                spawn(Kind.STREAK, sx, sy, dx * sp, dy * sp, 0f, 0f, 0f,
                        range(0.6f, 1.1f), range(1.4f, 2.2f) * unit, 1.0f * unit,
                        0xFFFFFFFF, 0xFFFFC857);
            }
        }

        // ------------------------------------------------------------------
        // Meteor ve halka başlatıcıları
        // ------------------------------------------------------------------

        /**
         * @return boş slot yoksa false
         */
        public boolean launchMeteor(float sx, float sy, float ex, float ey,
                                    float delay, float duration, float headSize, float intensity) {
            for (Meteor m : meteors) {
                if (!m.active) {
                    m.active = true;
                    m.startX = sx;
                    m.startY = sy;
                    m.endX = ex;
                    m.endY = ey;
                    m.headX = sx;
                    m.headY = sy;
                    float dx = ex - sx;
                    float dy = ey - sy;
                    float len = (float) Math.sqrt(dx * dx + dy * dy);
                    if (len < 1e-3f) {
                        m.dirX = 0f;
                        m.dirY = 1f;
                    } else {
                        m.dirX = dx / len;
                        m.dirY = dy / len;
                    }
                    m.delay = delay;
                    m.duration = Math.max(0.05f, duration);
                    m.age = 0f;
                    m.progress = 0f;
                    m.headSize = headSize;
                    m.intensity = intensity;
                    m.emitRate = 85f * density * Math.max(0.6f, intensity);
                    m.emitAccum = 0f;
                    return true;
                }
            }
            return false;
        }

        /**
         * @return boş slot yoksa false
         */
        public boolean launchRing(float cx, float cy, float delay, float duration,
                                  float maxRadius, float thickness, int color) {
            for (Ring r : rings) {
                if (!r.active) {
                    r.active = true;
                    r.cx = cx;
                    r.cy = cy;
                    r.delay = delay;
                    r.duration = Math.max(0.05f, duration);
                    r.age = 0f;
                    r.maxRadius = maxRadius;
                    r.thickness = thickness;
                    r.color = color;
                    return true;
                }
            }
            return false;
        }

        // ------------------------------------------------------------------
        // Meteor
        // ------------------------------------------------------------------

        public final class Meteor {
            public boolean active;
            public float startX, startY, endX, endY;
            public float dirX, dirY;
            public float headX, headY;
            public float progress;
            public float headSize, intensity;
            public float delay, duration, age;
            float emitRate, emitAccum;

            /** Ekranda görünür şekilde uçuyor mu? (renderer ateş topunu bu durumda çizer) */
            public boolean isFlying() {
                return active && age >= delay;
            }

            void update(float dt) {
                if (!active) {
                    return;
                }
                age += dt;
                if (age < delay) {
                    return;
                }
                float t = (age - delay) / duration;
                if (t >= 1f) {
                    headX = endX;
                    headY = endY;
                    progress = 1f;
                    active = false;
                    spawnImpact(endX, endY, intensity);
                    if (impactListener != null) {
                        impactListener.onMeteorImpact(endX, endY, intensity);
                    }
                    return;
                }

                float prevX = headX;
                float prevY = headY;
                progress = t;
                float e = EasingUtils.easeInQuad(t); // yerçekimiyle hızlanan düşüş
                headX = EasingUtils.lerp(startX, endX, e);
                headY = EasingUtils.lerp(startY, endY, e);

                emitAccum += dt * emitRate;
                int whole = (int) emitAccum;
                emitAccum -= whole;
                int m = Math.min(whole, 10);
                for (int j = 1; j <= m; j++) {
                    float f = j / (float) m;
                    spawnMeteorTrail(EasingUtils.lerp(prevX, headX, f),
                            EasingUtils.lerp(prevY, headY, f), dirX, dirY, intensity);
                }
            }
        }
    }
}
