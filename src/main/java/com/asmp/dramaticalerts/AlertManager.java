package com.asmp.dramaticalerts;

import com.asmp.dramaticalerts.AmountParser.Tier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Bildirim kuyruğu (FIFO), kademe bazlı zaman çizelgesi, ses katmanları ve ekran sarsıntısı.
 *
 * Kullanım (renderer, her HUD karesinde):
 *   AlertManager.update(scaledWidth, scaledHeight);
 *   if (AlertManager.needsRender()) { ... çiz ... }
 *
 * Zamanlama System.nanoTime() tabanlıdır, FPS'ten bağımsızdır.
 * Tüm metotlar istemci (render) thread'inde çağrılmalıdır; {@link #enqueue} başka thread'den
 * çağrılırsa işi kendisi ana thread'e taşır.
 */
public final class AlertManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("asmpalerts");

    /** Kartın ekran yüksekliğine göre dikey merkezi (renderer ve meteor hedefi aynı noktayı kullanır). */
    public static final float CARD_CENTER_Y = 0.26f;

    public static final float INTRO_SECONDS = 0.65f;
    public static final float OUTRO_SECONDS = 0.6f;
    public static final float ROLL_START = 0.45f;

    private static final float GAP_SECONDS = 0.4f;
    /** İlk (ana) meteorun çarpma anı: 0.9 sn fırlatma + 0.85 sn uçuş. */
    private static final float IMPACT_TIME = 1.75f;

    private static final HudParticle.Engine ENGINE = new HudParticle.Engine(1200);
    private static final ArrayDeque<AlertData> QUEUE = new ArrayDeque<>();
    private static final Map<String, Long> RECENT = new HashMap<>();
    private static final Map<String, SoundEvent> SOUND_CACHE = new HashMap<>();
    private static final ScheduledEvent[] NO_EVENTS = new ScheduledEvent[0];

    private static AlertData current;
    private static ScheduledEvent[] timeline = NO_EVENTS;
    private static int eventIndex;
    private static float elapsed;
    private static float gap;
    private static float rollEnd = 2f;
    private static long lastNanos;

    private static float trauma;
    private static float shakeClock;
    private static float shakeX;
    private static float shakeY;
    private static float impactVolume = 0.7f;

    static {
        ENGINE.setImpactListener(AlertManager::onMeteorImpact);
    }

    private AlertManager() {
    }

    // ------------------------------------------------------------------
    // Zaman çizelgesi olayları
    // ------------------------------------------------------------------

    private enum EventType {
        SOUND, FIREWORK, METEOR, CONFETTI, STAR_RAIN, RING, SHAKE
    }

    private static final class ScheduledEvent {
        final float time;
        final EventType type;
        String sound;
        float pitch, volume;
        float p0, p1, p2, p3, p4, p5;

        ScheduledEvent(float time, EventType type) {
            this.time = Math.max(0f, time);
            this.type = type;
        }
    }

    private static ScheduledEvent sound(float t, String path, float pitch, float volume) {
        ScheduledEvent e = new ScheduledEvent(t, EventType.SOUND);
        e.sound = path;
        e.pitch = pitch;
        e.volume = volume;
        return e;
    }

    // ------------------------------------------------------------------
    // Genel API: kuyruğa ekleme
    // ------------------------------------------------------------------

    /**
     * @param bypassDedupe true ise tekrar-filtresi atlanır (manuel /asmpalert komutu için)
     * @return bildirim kuyruğa alındıysa true (mod kapalı, tekrar veya kuyruk dolu ise false)
     */
    public static boolean enqueue(AlertData data, boolean bypassDedupe) {
        if (data == null) {
            return false;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!mc.isOnThread()) {
            mc.execute(() -> enqueue(data, bypassDedupe));
            return true;
        }

        ConfigManager.Config cfg = ConfigManager.get();
        if (!cfg.enabled) {
            return false;
        }
        if (!bypassDedupe && isDuplicate(data, cfg.dedupeWindowMs)) {
            if (cfg.debugLog) {
                LOGGER.info("Tekrar eden bildirim yok sayildi: {}", data.dedupeKey());
            }
            return false;
        }

        if (QUEUE.size() >= cfg.maxQueueSize) {
            AlertData lowest = null;
            for (AlertData queued : QUEUE) {
                if (lowest == null || queued.tier.level < lowest.tier.level) {
                    lowest = queued;
                }
            }
            if (lowest != null && data.tier.level > lowest.tier.level) {
                QUEUE.remove(lowest);
            } else {
                LOGGER.warn("Bildirim kuyrugu dolu, bildirim atlandi: {}", data.dedupeKey());
                return false;
            }
        }

        QUEUE.addLast(data);
        if (cfg.debugLog) {
            LOGGER.info("Kuyruga eklendi: {} {} ({})", data.playerName, data.amountCompact, data.tier);
        }
        return true;
    }

    private static boolean isDuplicate(AlertData data, long windowMs) {
        if (windowMs <= 0L) {
            return false;
        }
        long now = System.nanoTime();
        long windowNanos = windowMs * 1_000_000L;

        if (RECENT.size() > 32) {
            Iterator<Map.Entry<String, Long>> it = RECENT.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > windowNanos) {
                    it.remove();
                }
            }
        }

        String key = data.dedupeKey();
        Long last = RECENT.get(key);
        RECENT.put(key, now);
        return last != null && now - last <= windowNanos;
    }

    /** Aktif bildirimi hemen bitirir (kuyruktaki sıradaki başlar). */
    public static void skipCurrent() {
        if (current != null) {
            current = null;
            timeline = NO_EVENTS;
            gap = 0.2f;
        }
    }

    /** Her şeyi temizler (sunucudan çıkışta ve /asmpalert clear için). */
    public static void clear() {
        QUEUE.clear();
        RECENT.clear();
        current = null;
        timeline = NO_EVENTS;
        eventIndex = 0;
        elapsed = 0f;
        gap = 0f;
        trauma = 0f;
        shakeX = 0f;
        shakeY = 0f;
        lastNanos = 0L;
        ENGINE.clear();
    }

    // ------------------------------------------------------------------
    // Kare güncellemesi
    // ------------------------------------------------------------------

    /** Renderer her karede en başta çağırır. Zaman, kuyruk, olaylar, parçacıklar ve sarsıntı ilerler. */
    public static void update(float scaledWidth, float scaledHeight) {
        long now = System.nanoTime();
        float dt = lastNanos == 0L ? 0f : (now - lastNanos) * 1e-9f;
        lastNanos = now;
        if (dt < 0f) {
            dt = 0f;
        } else if (dt > 0.25f) {
            dt = 0.25f;
        }

        ENGINE.setViewport(scaledWidth, scaledHeight);
        ConfigManager.Config cfg = ConfigManager.get();

        boolean started = false;
        if (current == null) {
            if (gap > 0f) {
                gap -= dt;
            } else if (cfg.enabled && !QUEUE.isEmpty()) {
                start(QUEUE.pollFirst(), cfg);
                started = true;
            }
        }

        if (current != null) {
            if (!started) {
                elapsed += dt;
            }
            while (eventIndex < timeline.length && timeline[eventIndex].time <= elapsed) {
                fire(timeline[eventIndex++], scaledWidth, scaledHeight);
            }
            if (elapsed >= current.durationSeconds) {
                current = null;
                timeline = NO_EVENTS;
                gap = GAP_SECONDS;
            }
        }

        ENGINE.update(dt);
        updateShake(dt, cfg);
    }

    private static void start(AlertData data, ConfigManager.Config cfg) {
        current = data;
        elapsed = 0f;
        eventIndex = 0;
        rollEnd = rollEndFor(data.tier);
        impactVolume = 0.75f / (float) Math.sqrt(meteorCount(data.tier.level));
        ENGINE.setDensity(cfg.particleDensity);
        timeline = buildTimeline(data);
        if (cfg.debugLog) {
            LOGGER.info("Bildirim basladi: {} {} ({}, {} olay)", data.playerName, data.amountCompact,
                    data.tier, timeline.length);
        }
    }

    private static void updateShake(float dt, ConfigManager.Config cfg) {
        trauma = Math.max(0f, trauma - dt * 1.3f);
        shakeClock += dt;
        float amount = trauma * trauma;
        float maxPx = 5f * ENGINE.unit() * cfg.shakeIntensity;
        float t = shakeClock * 30f;
        shakeX = EasingUtils.noise1(t, 11) * amount * maxPx;
        shakeY = EasingUtils.noise1(t, 23) * amount * maxPx;
    }

    private static void addTrauma(float amount) {
        trauma = Math.min(0.85f, trauma + amount);
    }

    // ------------------------------------------------------------------
    // Renderer için okuma API'si
    // ------------------------------------------------------------------

    public static boolean isActive() {
        return current != null;
    }

    /** Aktif bildirim veya geride kalan parçacık/meteor/halka varsa true. */
    public static boolean needsRender() {
        if (current != null || ENGINE.aliveCount() > 0) {
            return true;
        }
        for (HudParticle.Engine.Meteor m : ENGINE.meteors()) {
            if (m.active) {
                return true;
            }
        }
        for (HudParticle.Ring r : ENGINE.rings()) {
            if (r.active) {
                return true;
            }
        }
        return false;
    }

    public static AlertData current() {
        return current;
    }

    public static int queueSize() {
        return QUEUE.size();
    }

    public static HudParticle.Engine engine() {
        return ENGINE;
    }

    public static float elapsed() {
        return elapsed;
    }

    public static float duration() {
        return current == null ? 0f : current.durationSeconds;
    }

    /** Kartın giriş ilerlemesi 0..1 (renderer easeOutBack uygular). */
    public static float introProgress() {
        return EasingUtils.segment(elapsed, 0f, INTRO_SECONDS);
    }

    /** Kartın çıkış ilerlemesi 0..1 (son OUTRO_SECONDS saniye). */
    public static float outroProgress() {
        return current == null ? 0f
                : EasingUtils.segment(elapsed, current.durationSeconds - OUTRO_SECONDS, current.durationSeconds);
    }

    /** Sayacın 0..1 ilerlemesi (ROLL_START → rollEnd). */
    public static float numberProgress() {
        return EasingUtils.segment(elapsed, ROLL_START, rollEnd);
    }

    /** O an gösterilecek sayı: 0'dan hedefe easeOutCubic ile sayar. */
    public static long rollingAmount() {
        return current == null ? 0L : EasingUtils.rollingValue(current.amount, numberProgress());
    }

    public static float rollEnd() {
        return rollEnd;
    }

    /** Ana meteor çarpma / doruk anı (saniye). */
    public static float climaxTime() {
        return IMPACT_TIME;
    }

    public static float shakeX() {
        return shakeX;
    }

    public static float shakeY() {
        return shakeY;
    }

    // ------------------------------------------------------------------
    // Zaman çizelgesi oluşturma
    // ------------------------------------------------------------------

    private static float rollEndFor(Tier tier) {
        return switch (tier.level) {
            case 1 -> 1.9f;
            case 2 -> 2.1f;
            case 3 -> 2.3f;
            case 4 -> 2.5f;
            default -> 2.8f;
        };
    }

    private static int meteorCount(int level) {
        return switch (level) {
            case 0, 1 -> 1;
            case 2 -> 1;
            case 3 -> 2;
            case 4 -> 4;
            default -> 6;
        };
    }

    private static ScheduledEvent[] buildTimeline(AlertData d) {
        final int lv = d.tier.level;
        final float dur = d.durationSeconds;
        final Random r = new Random();
        final List<ScheduledEvent> ev = new ArrayList<>(96);

        // ---- Kademe 1+: havai fişekler ----
        int bursts = switch (lv) {
            case 1 -> 4;
            case 2 -> 5;
            case 3 -> 7;
            case 4 -> 9;
            default -> 14;
        };
        float fwStart = 0.5f;
        float fwEnd = Math.min(dur * 0.62f, 4.2f);
        for (int i = 0; i < bursts; i++) {
            float k = bursts == 1 ? 0f : i / (float) (bursts - 1);
            float t = Math.max(0.45f, fwStart + (fwEnd - fwStart) * k + (r.nextFloat() - 0.5f) * 0.15f);
            ScheduledEvent fw = new ScheduledEvent(t, EventType.FIREWORK);
            fw.p0 = 0.22f + 0.56f * r.nextFloat();
            fw.p1 = 0.08f + 0.20f * r.nextFloat();
            ev.add(sound(t - 0.42f, "entity.firework_rocket.launch", 0.9f + 0.2f * r.nextFloat(), 0.5f));
            ev.add(fw);
            ev.add(sound(t, lv >= 3 ? "entity.firework_rocket.large_blast" : "entity.firework_rocket.blast",
                    0.85f + 0.3f * r.nextFloat(), 0.7f));
            if (lv >= 3) {
                ev.add(sound(t + 0.12f, "entity.firework_rocket.twinkle", 0.9f + 0.2f * r.nextFloat(), 0.5f));
            }
        }

        // ---- Kademe 2+: meteorlar ----
        if (lv >= 2) {
            int meteors = meteorCount(lv);
            float spacing = lv >= 5 ? 0.28f : 0.4f;
            float baseIntensity = 1.0f + 0.15f * (lv - 2);
            for (int i = 0; i < meteors; i++) {
                float launch = 0.9f + i * spacing;
                boolean right = (i % 2) == 0;
                ScheduledEvent m = new ScheduledEvent(launch, EventType.METEOR);
                m.p0 = right ? 1.08f + 0.1f * r.nextFloat() : -0.08f - 0.1f * r.nextFloat();
                m.p1 = -0.08f;
                m.p2 = 0.5f + (i == 0 ? 0f : (r.nextFloat() - 0.5f) * 0.35f);
                m.p3 = CARD_CENTER_Y + (i == 0 ? 0f : (r.nextFloat() - 0.5f) * 0.06f);
                m.p4 = i == 0 ? 0.85f : 0.7f;
                m.p5 = i == 0 ? baseIntensity : baseIntensity * 0.75f;
                ev.add(sound(launch, "item.firecharge.use", 0.7f + 0.2f * r.nextFloat(), 0.5f));
                ev.add(m);
            }
        }

        // ---- Kademe 3+: portal/beacon/chime katmanı ve altın-mor konfeti ----
        if (lv >= 3) {
            ev.add(sound(IMPACT_TIME - 0.5f, "block.end_portal.spawn", 1.3f, 0.35f));
            ev.add(sound(IMPACT_TIME, "block.beacon.activate", 1.0f, 0.8f));
            ev.add(sound(IMPACT_TIME + 0.2f, "block.amethyst_block.chime", 1.0f, 0.8f));
            ev.add(sound(IMPACT_TIME + 0.45f, "block.amethyst_block.chime", 1.26f, 0.8f));
            ev.add(sound(IMPACT_TIME + 0.7f, "block.amethyst_block.chime", 1.5f, 0.8f));

            float confettiEnd = dur * 0.85f;
            int n = 0;
            for (float t = 0.6f; t < confettiEnd; t += 0.55f, n++) {
                ScheduledEvent c = new ScheduledEvent(t, EventType.CONFETTI);
                c.p0 = 22 + 8 * lv;
                c.p1 = (lv >= 5 && (n & 1) == 1) ? 1f : 0f;
                ev.add(c);
            }
        }

        // ---- Kademe 4+: çift/çoklu şok dalgası, yıldız yağmuru, doruk sarsıntısı ----
        if (lv >= 4) {
            int ringCount = lv >= 5 ? 3 : 2;
            float ringGap = lv >= 5 ? 0.18f : 0.22f;
            for (int i = 0; i < ringCount; i++) {
                ScheduledEvent ring = new ScheduledEvent(IMPACT_TIME + i * ringGap, EventType.RING);
                ring.p1 = 1.1f;
                ring.p2 = 0.42f;
                ring.p3 = 6f;
                ring.p4 = i % 3;
                ev.add(ring);
            }
            if (lv >= 5) {
                ScheduledEvent last = new ScheduledEvent(IMPACT_TIME + 0.6f, EventType.RING);
                last.p1 = 1.3f;
                last.p2 = 0.55f;
                last.p3 = 8f;
                last.p4 = 3f;
                ev.add(last);
            }

            ScheduledEvent shake = new ScheduledEvent(IMPACT_TIME, EventType.SHAKE);
            shake.p0 = lv >= 5 ? 0.6f : 0.45f;
            ev.add(shake);

            if (lv >= 5) {
                for (float t = 1.0f; t < dur * 0.85f; t += 0.5f) {
                    ScheduledEvent s = new ScheduledEvent(t, EventType.STAR_RAIN);
                    s.p0 = 60;
                    ev.add(s);
                }
            } else {
                float[] times = {1.3f, 2.4f, 3.5f};
                for (float t : times) {
                    if (t < dur * 0.85f) {
                        ScheduledEvent s = new ScheduledEvent(t, EventType.STAR_RAIN);
                        s.p0 = 30;
                        ev.add(s);
                    }
                }
            }

            ev.add(sound(IMPACT_TIME + 0.1f, "entity.player.levelup", 1.0f, 0.7f));
            ev.add(sound(IMPACT_TIME + 0.1f, "entity.player.levelup", 1.5f, 0.5f));
        }

        // ---- Kademe 5: katmanlı epik akor ----
        if (lv >= 5) {
            ev.add(sound(IMPACT_TIME, "entity.lightning_bolt.thunder", 1.0f, 0.55f));
            ev.add(sound(IMPACT_TIME + 0.1f, "entity.player.levelup", 0.5f, 0.6f));
            ev.add(sound(IMPACT_TIME + 0.1f, "entity.player.levelup", 0.67f, 0.6f));
            ev.add(sound(IMPACT_TIME + 0.1f, "entity.player.levelup", 0.84f, 0.6f));
            ev.add(sound(IMPACT_TIME + 0.3f, "ui.toast.challenge_complete", 1.0f, 0.8f));
            ev.add(sound(IMPACT_TIME + 1.2f, "block.amethyst_block.resonate", 0.8f, 0.8f));
            ev.add(sound(IMPACT_TIME + 1.2f, "entity.player.levelup", 0.75f, 0.5f));
            ev.add(sound(IMPACT_TIME + 1.2f, "entity.player.levelup", 1.0f, 0.5f));
            ev.add(sound(IMPACT_TIME + 1.2f, "entity.player.levelup", 1.26f, 0.5f));
        }

        ev.sort((a, b) -> Float.compare(a.time, b.time));
        return ev.toArray(NO_EVENTS);
    }

    // ------------------------------------------------------------------
    // Olay tetikleme
    // ------------------------------------------------------------------

    private static void fire(ScheduledEvent e, float w, float h) {
        final float unit = ENGINE.unit();
        final int lv = current == null ? 1 : current.tier.level;
        switch (e.type) {
            case SOUND -> playSound(e.sound, e.pitch, e.volume);
            case FIREWORK -> ENGINE.spawnFireworkBurst(w * e.p0, h * e.p1,
                    36 + 6 * lv, 105f + 8f * lv, ENGINE.randomFireworkColor(), ENGINE.randomFireworkColor());
            case METEOR -> ENGINE.launchMeteor(w * e.p0, h * e.p1, w * e.p2, h * e.p3,
                    0f, e.p4, 6f * unit * e.p5, e.p5);
            case CONFETTI -> ENGINE.spawnConfetti(0f, w, -6f * unit, (int) e.p0,
                    e.p1 > 0.5f ? HudParticle.PALETTE_DIAMOND_GOLD : HudParticle.PALETTE_GOLD_PURPLE);
            case STAR_RAIN -> ENGINE.spawnStarRain((int) e.p0, h * 0.35f);
            case RING -> ENGINE.launchRing(w * 0.5f, h * CARD_CENTER_Y, 0f, e.p1,
                    w * e.p2, e.p3 * unit, ringColor((int) e.p4));
            case SHAKE -> addTrauma(e.p0);
        }
    }

    private static int ringColor(int id) {
        return switch (id) {
            case 0 -> 0xFFFFD34D;
            case 1 -> 0xFFB44DFF;
            case 2 -> 0xFFFFFFFF;
            default -> 0xFF7DF9FF;
        };
    }

    /** Meteor çarpması: sarsıntı + patlama sesi katmanı. */
    private static void onMeteorImpact(float x, float y, float intensity) {
        addTrauma(0.28f * intensity);
        playSound("entity.generic.explode", 0.8f + 0.15f * intensity, impactVolume);
        playSound("entity.dragon_fireball.explode", 0.9f, impactVolume * 0.7f);
    }

    // ------------------------------------------------------------------
    // Ses
    // ------------------------------------------------------------------

    /**
     * Sesi kimlik (path) ile çözer; böylece SoundEvent / RegistryEntry tip farklılıklarından
     * bağımsız çalışır. Bulunamazsa sessizce atlanır.
     */
    private static SoundEvent resolve(String path) {
        SoundEvent cached = SOUND_CACHE.get(path);
        if (cached != null) {
            return cached;
        }
        SoundEvent found = Registries.SOUND_EVENT.get(Identifier.of("minecraft", path));
        if (found != null) {
            SOUND_CACHE.put(path, found);
        }
        return found;
    }

    private static void playSound(String path, float pitch, float volume) {
        float v = volume * ConfigManager.get().soundVolume;
        if (v <= 0.001f) {
            return;
        }
        SoundEvent event = resolve(path);
        if (event == null) {
            return;
        }
        MinecraftClient.getInstance().getSoundManager()
                .play(PositionedSoundInstance.master(event, pitch, Math.min(v, 1.5f)));
    }
}
