package com.asmp.dramaticalerts;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.RenderTickCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 1.21.8 HUD render katmanları (arkadan öne):
 *   vinyet (K4+) → parıltı + sunburst ışınları (K3+) → şok dalgası halkaları → duman/parlama →
 *   kıvılcım/kor/yıldız çizgileri → meteor → bildirim kartı → yıldız/konfeti/elmas.
 *
 * Tüm Minecraft çizim çağrıları aşağıdaki "Gfx yardımcıları" bölümünde toplanmıştır
 * (rect, gradient, text, head, push/pop/translate/scale/rotate). 1.21.8 Yarn'da bir imza
 * farklı çıkarsa yalnızca o bölüm düzeltilir.
 *
 * Render döngüsünde nesne üretilmez; yalnızca sayaç metni değer değiştiğinde yeniden oluşturulur.
 */
public final class AlertHudRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("asmpalerts");

    private static final int GOLD = 0xFFFFD34D;
    private static final int PURPLE = 0xFFB44DFF;
    private static final int ORANGE = 0xFFFF9A3C;
    private static final int RED = 0xFFFF3B1F;
    private static final int CYAN = 0xFF7DF9FF;

    private static final int CARD_HALF_W = 105;
    private static final int CARD_HALF_H = 30;

    private static boolean failed;
    private static float globalAlpha = 1f;
    private static float screenW = 480f;
    private static float screenH = 270f;

    private static AlertData skinFor;
    private static PlayerListEntry skinEntry;
    private static boolean headBroken;

    private static long lastRollValue = -1L;
    private static String rollText = "+$0";

    private AlertHudRenderer() {
    }

    // ------------------------------------------------------------------
    // Kayıt ve ana döngü
    // ------------------------------------------------------------------

    public static void register() {
        HudRenderCallback.EVENT.register(AlertHudRenderer::onHudRender);
    }

    /** /asmpalert reload sonrası, hata yüzünden kapanan renderer'ı yeniden açar. */
    public static void resetFailure() {
        failed = false;
    }

    private static void onHudRender(DrawContext ctx, RenderTickCounter tickCounter) {
        if (failed) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) {
            return;
        }
        try {
            screenW = ctx.getScaledWindowWidth();
            screenH = ctx.getScaledWindowHeight();
            AlertManager.update(screenW, screenH);
            if (!AlertManager.needsRender()) {
                return;
            }
            render(ctx, mc);
        } catch (Throwable t) {
            failed = true;
            AlertManager.clear();
            LOGGER.error("AlertHudRenderer hata verdi ve kapatildi (/asmpalert reload ile yeniden acilir)", t);
        }
    }

    private static void render(DrawContext ctx, MinecraftClient mc) {
        final HudParticle.Engine eng = AlertManager.engine();
        final AlertData d = AlertManager.current();
        final float unit = eng.unit();
        final float time = AlertManager.elapsed();
        final float cx = screenW * 0.5f;
        final float cy = screenH * AlertManager.CARD_CENTER_Y;

        int lv = 0;
        float fx = 0f;
        float pulse = 0.5f + 0.5f * (float) Math.sin(time * 3.2f);
        if (d != null) {
            lv = d.tier.level;
            float t01 = time / d.durationSeconds;
            fx = EasingUtils.envelope(t01, 0.10f, 0.12f);
        }

        // Vinyet sarsıntıdan etkilenmesin: kenarlara sabit çizilir
        if (lv >= 4 && fx > 0.01f) {
            int vc = lv >= 5 ? EasingUtils.lerpColor(0xFF3A0E6E, 0xFF6B4A00, pulse) : 0xFF3A0E6E;
            drawVignette(ctx, vc, fx * (lv >= 5 ? 0.62f : 0.45f));
        }

        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            m.translate(AlertManager.shakeX(), AlertManager.shakeY());

            if (d != null && lv >= 3 && fx > 0.01f) {
                float intro = EasingUtils.easeOutCubic(AlertManager.introProgress());
                drawGlow(ctx, cx, cy, unit, lv, fx * intro, pulse);
                drawSunburst(ctx, cx, cy, screenW, unit, lv, time, intro, fx);
            }

            drawRings(ctx, eng);
            drawParticles(ctx, eng, 0);
            drawParticles(ctx, eng, 1);
            drawMeteors(ctx, eng);

            if (d != null) {
                drawCard(ctx, mc, d, cx, cy, unit);
            }

            drawParticles(ctx, eng, 2);
        } finally {
            m.popMatrix();
        }
    }

    // ------------------------------------------------------------------
    // Kademe renkleri
    // ------------------------------------------------------------------

    private static int accent(int lv) {
        return switch (lv) {
            case 1 -> GOLD;
            case 2 -> ORANGE;
            case 5 -> 0xFFFFF0A0;
            default -> GOLD;
        };
    }

    private static int secondary(int lv) {
        return switch (lv) {
            case 1 -> 0xFFFF6A3D;
            case 2 -> RED;
            case 5 -> CYAN;
            default -> PURPLE;
        };
    }

    /** Global kart alfasını uygular. */
    private static int a(int argb) {
        return EasingUtils.withAlpha(argb, globalAlpha);
    }

    // ------------------------------------------------------------------
    // Bildirim kartı
    // ------------------------------------------------------------------

    private static void drawCard(DrawContext ctx, MinecraftClient mc, AlertData d,
                                 float cx, float cy, float unit) {
        final float intro = AlertManager.introProgress();
        final float outro = EasingUtils.easeInOutQuad(AlertManager.outroProgress());
        final float pop = EasingUtils.easeOutBack(intro);
        globalAlpha = EasingUtils.clamp01(intro * 2.2f) * (1f - outro);
        if (globalAlpha <= 0.01f) {
            globalAlpha = 1f;
            return;
        }

        final int lv = d.tier.level;
        final float time = AlertManager.elapsed();
        final TextRenderer tr = mc.textRenderer;
        final int accent = accent(lv);
        final int second = secondary(lv);
        final float pulse = 0.5f + 0.5f * (float) Math.sin(time * 3.2f);
        final int hx = CARD_HALF_W;
        final int hy = CARD_HALF_H;

        float s = EasingUtils.clamp(unit, 0.8f, 2.2f) * Math.max(0.01f, pop) * (1f - 0.12f * outro);
        float slide = (1f - EasingUtils.easeOutCubic(intro)) * -26f * unit - outro * 10f * unit;

        // Sayaç metni yalnızca değer değişince yeniden üretilir
        long value = AlertManager.rollingAmount();
        if (value != lastRollValue) {
            lastRollValue = value;
            rollText = "+$" + AmountParser.formatCompact(value);
        }
        float rollEnd = AlertManager.rollEnd();
        float numScale = 1f;
        if (time >= rollEnd) {
            numScale += 0.16f * (1f - EasingUtils.easeOutCubic(EasingUtils.segment(time, rollEnd, rollEnd + 0.5f)));
        }

        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            m.translate(cx, cy + slide);
            m.scale(s, s);

            // Dış parıltı
            if (lv >= 2) {
                float g = 0.10f + 0.06f * pulse;
                rect(ctx, -hx - 4, -hy - 4, hx + 4, hy + 4, a(EasingUtils.withAlpha(accent, g)));
                rect(ctx, -hx - 2, -hy - 2, hx + 2, hy + 2, a(EasingUtils.withAlpha(second, g + 0.04f)));
            }

            // Gövde (köşeleri kırpılmış gradyan)
            int top = 0xEE16102A;
            int bottom = 0xEE090612;
            gradient(ctx, -hx + 2, -hy, hx - 2, hy, a(top), a(bottom));
            gradient(ctx, -hx, -hy + 2, -hx + 2, hy - 2, a(top), a(bottom));
            gradient(ctx, hx - 2, -hy + 2, hx, hy - 2, a(top), a(bottom));

            // Parlama süpürmesi (K2+)
            if (lv >= 2) {
                float ph = (time * 0.45f) % 1f;
                int sx0 = (int) (-hx - 20 + (2 * hx + 40) * ph);
                int x1 = Math.max(-hx, sx0);
                int x2 = Math.min(hx, sx0 + 14);
                if (x2 > x1) {
                    ctx.fill(x1, -hy + 2, x2, hy - 2, a(0x18FFFFFF));
                }
            }

            // Çerçeve
            if (lv >= 4) {
                drawAnimatedBorder(ctx, hx, hy, time, accent, second);
            } else {
                int bc = a(EasingUtils.withAlpha(accent, 0.9f));
                ctx.fill(-hx + 2, -hy, hx - 2, -hy + 1, bc);
                ctx.fill(-hx + 2, hy - 1, hx - 2, hy, bc);
                ctx.fill(-hx, -hy + 2, -hx + 1, hy - 2, bc);
                ctx.fill(hx - 1, -hy + 2, hx, hy - 2, bc);
            }

            // Üst rozet (pill)
            String badge = d.badgeText;
            float badgeScale = 0.8f;
            int bw = (int) (tr.getWidth(badge) * badgeScale) + 16;
            int bx1 = -bw / 2;
            int bx2 = bw / 2;
            int by = -hy;
            int pill = EasingUtils.lerpColor(accent, second, lv >= 3 ? pulse : 0f);
            if (lv >= 3) {
                rect(ctx, bx1 - 3, by - 8, bx2 + 3, by + 8, a(EasingUtils.withAlpha(pill, 0.25f + 0.2f * pulse)));
            }
            ctx.fill(bx1 + 1, by - 6, bx2 - 1, by + 6, a(pill));
            ctx.fill(bx1, by - 5, bx2, by + 5, a(pill));
            text(ctx, tr, badge, 0f, by, badgeScale, a(0xFF140A28), false);

            // Büyük sayaç: beyazdan kademe rengine geçer
            float tw = tr.getWidth(rollText);
            float amountScale = Math.min(2.6f, 190f / Math.max(1f, tw)) * numScale;
            float nProg = EasingUtils.easeOutQuad(AlertManager.numberProgress());
            int mainColor = EasingUtils.lerpColor(0xFFFFFFFF, accent, nProg);
            if (lv >= 5 && time >= rollEnd) {
                mainColor = EasingUtils.lerpColor(accent, 0xFFFFFFFF, pulse * 0.6f);
            }
            float ay = -3f;
            if (lv >= 3) {
                int glow = a(EasingUtils.withAlpha(second, 0.55f));
                text(ctx, tr, rollText, -1.5f, ay, amountScale, glow, false);
                text(ctx, tr, rollText, 1.5f, ay, amountScale, glow, false);
                text(ctx, tr, rollText, 0f, ay - 1.5f, amountScale, glow, false);
                text(ctx, tr, rollText, 0f, ay + 1.5f, amountScale, glow, false);
            }
            text(ctx, tr, rollText, 0f, ay, amountScale, a(mainColor), true);

            // Alt satır: kafa + oyuncu adı
            String name = d.playerName;
            int headSize = 14;
            int gap = 5;
            int total = headSize + gap + tr.getWidth(name);
            int sx = -total / 2;
            int headY = 12;
            ctx.fill(sx - 1, headY - 1, sx + headSize + 1, headY + headSize + 1, a(accent));
            drawHead(ctx, mc, d, sx, headY, headSize);
            float inv = 1f - globalAlpha;
            if (inv > 0.02f) {
                ctx.fill(sx, headY, sx + headSize, headY + headSize, EasingUtils.setAlpha(0xFF090612, inv));
            }
            ctx.drawText(tr, name, sx + headSize + gap, headY + 3, a(0xFFFFFFFF), true);
        } finally {
            m.popMatrix();
            globalAlpha = 1f;
        }
    }

    private static void drawAnimatedBorder(DrawContext ctx, int hx, int hy, float time, int c0, int c1) {
        final int seg = 10;
        for (int x = -hx; x < hx; x += seg) {
            int x2 = Math.min(hx, x + seg);
            ctx.fill(x, -hy, x2, -hy + 2, a(borderColor(time, x, c0, c1)));
            ctx.fill(x, hy - 2, x2, hy, a(borderColor(time, x + 90f, c0, c1)));
        }
        for (int y = -hy; y < hy; y += seg) {
            int y2 = Math.min(hy, y + seg);
            ctx.fill(-hx, y, -hx + 2, y2, a(borderColor(time, y + 40f, c0, c1)));
            ctx.fill(hx - 2, y, hx, y2, a(borderColor(time, y + 130f, c0, c1)));
        }
    }

    private static int borderColor(float time, float pos, int c0, int c1) {
        float k = 0.5f + 0.5f * (float) Math.sin(time * 2.4f + pos * 0.045f);
        return EasingUtils.lerpColor(c0, c1, k);
    }

    // ------------------------------------------------------------------
    // Oyuncu kafası (skin) — güvenli
    // ------------------------------------------------------------------

    private static void drawHead(DrawContext ctx, MinecraftClient mc, AlertData d, int x, int y, int size) {
        if (skinFor != d) {
            skinFor = d;
            skinEntry = lookupEntry(mc, d.playerName);
            headBroken = false;
        }
        if (skinEntry != null && !headBroken) {
            try {
                PlayerSkinDrawer.draw(ctx, skinEntry.getSkinTextures(), x, y, size);
                return;
            } catch (Throwable t) {
                headBroken = true;
                LOGGER.warn("Skin kafasi cizilemedi, varsayilan kafa kullanilacak: {}", t.toString());
            }
        }
        drawFallbackHead(ctx, x, y, size);
    }

    private static PlayerListEntry lookupEntry(MinecraftClient mc, String name) {
        try {
            ClientPlayNetworkHandler handler = mc.getNetworkHandler();
            return handler == null ? null : handler.getPlayerListEntry(name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Oyuncu çevrimiçi değilse: basit Steve benzeri piksel kafa. */
    private static void drawFallbackHead(DrawContext ctx, int x, int y, int size) {
        ctx.fill(x, y, x + size, y + size, 0xFFC08A62);
        ctx.fill(x, y, x + size, y + Math.max(2, size * 3 / 14), 0xFF4A3220);
        int ey = y + size * 6 / 14;
        int u = Math.max(1, size / 14);
        ctx.fill(x + 3 * u, ey, x + 6 * u, ey + 2 * u, 0xFFFFFFFF);
        ctx.fill(x + 8 * u, ey, x + 11 * u, ey + 2 * u, 0xFFFFFFFF);
        ctx.fill(x + 4 * u, ey, x + 6 * u, ey + 2 * u, 0xFF3B3BA0);
        ctx.fill(x + 8 * u, ey, x + 10 * u, ey + 2 * u, 0xFF3B3BA0);
        ctx.fill(x + 5 * u, y + 10 * u, x + 9 * u, y + 11 * u, 0xFF8C5A44);
    }

    // ------------------------------------------------------------------
    // Arka plan katmanları
    // ------------------------------------------------------------------

    private static void drawVignette(DrawContext ctx, int color, float strength) {
        final int n = 14;
        final int w = (int) screenW;
        final int h = (int) screenH;
        float depth = screenH * 0.22f;
        int step = Math.max(1, Math.round(depth / n));
        for (int i = 0; i < n; i++) {
            float f = 1f - i / (float) n;
            int col = EasingUtils.withAlpha(color, strength * f * f);
            if ((col >>> 24) < 2) {
                continue;
            }
            int o = i * step;
            ctx.fill(0, o, w, o + step, col);
            ctx.fill(0, h - o - step, w, h - o, col);
            ctx.fill(o, 0, o + step, h, col);
            ctx.fill(w - o - step, 0, w - o, h, col);
        }
    }

    private static void drawGlow(DrawContext ctx, float cx, float cy, float unit, int lv,
                                 float strength, float pulse) {
        float base = (0.07f + 0.03f * pulse) * strength;
        disc(ctx, cx, cy, 130f * unit, EasingUtils.withAlpha(lv >= 4 ? PURPLE : GOLD, base));
        disc(ctx, cx, cy, 95f * unit, EasingUtils.withAlpha(GOLD, base * 1.2f));
        disc(ctx, cx, cy, 62f * unit, EasingUtils.withAlpha(0xFFFFF0A0, base * 1.4f));
    }

    /** Kartın arkasında dönen ışık hüzmeleri; büyük/küçük hüzmeler zıt yönde döner. */
    private static void drawSunburst(DrawContext ctx, float cx, float cy, float w, float unit,
                                     int lv, float time, float lenScale, float alphaScale) {
        int rays = lv == 3 ? 14 : (lv == 4 ? 18 : 24);
        float len = w * 0.62f * lenScale;
        if (len < 4f) {
            return;
        }
        var m = ctx.getMatrices();
        for (int i = 0; i < rays; i++) {
            boolean big = (i & 1) == 0;
            float half = (big ? 5f : 3f) * unit;
            int base = big ? GOLD : PURPLE;
            float ang = EasingUtils.TAU * i / rays + (big ? time * 0.35f : -time * 0.21f);
            m.pushMatrix();
            m.translate(cx, cy);
            m.rotate(ang);
            for (int seg = 0; seg < 4; seg++) {
                float x0 = len * seg * 0.25f;
                float x1 = len * (seg + 1) * 0.25f;
                float hw = half * (1f - 0.22f * seg);
                float al = (0.30f - 0.06f * seg) * alphaScale;
                ctx.fill(Math.round(x0), Math.round(-hw), Math.round(x1), Math.round(hw),
                        EasingUtils.withAlpha(base, al));
            }
            m.popMatrix();
        }
    }

    private static void drawRings(DrawContext ctx, HudParticle.Engine eng) {
        for (HudParticle.Ring r : eng.rings()) {
            if (!r.isVisible()) {
                continue;
            }
            float rad = r.radius();
            float th = r.currentThickness();
            int col = EasingUtils.withAlpha(r.color, r.alpha());
            if ((col >>> 24) < 3 || rad < 2f) {
                continue;
            }
            int segs = (int) EasingUtils.clamp(EasingUtils.TAU * rad / (th * 1.0f), 24f, 150f);
            float half = th * 0.5f;
            for (int i = 0; i < segs; i++) {
                float ang = EasingUtils.TAU * i / segs;
                float px = r.cx + (float) Math.cos(ang) * rad;
                float py = r.cy + (float) Math.sin(ang) * rad;
                if (px < -10f || px > screenW + 10f || py < -10f || py > screenH + 10f) {
                    continue;
                }
                rect(ctx, px - half, py - half, px + half, py + half, col);
            }
        }
    }

    // ------------------------------------------------------------------
    // Parçacıklar (pass 0: duman/parlama, 1: kıvılcım/kor/çizgi, 2: yıldız/konfeti/elmas)
    // ------------------------------------------------------------------

    private static void drawParticles(DrawContext ctx, HudParticle.Engine eng, int pass) {
        HudParticle[] pool = eng.particles();
        for (int i = 0; i < pool.length; i++) {
            HudParticle p = pool[i];
            if (!p.alive) {
                continue;
            }
            if (p.x < -40f || p.x > screenW + 40f || p.y < -40f || p.y > screenH + 40f) {
                continue;
            }
            switch (p.kind) {
                case SMOKE:
                    if (pass == 0) {
                        drawSmoke(ctx, p);
                    }
                    break;
                case FLASH:
                    if (pass == 0) {
                        drawFlash(ctx, p);
                    }
                    break;
                case SPARK:
                    if (pass == 1) {
                        drawSpark(ctx, p);
                    }
                    break;
                case EMBER:
                    if (pass == 1) {
                        drawEmber(ctx, p);
                    }
                    break;
                case STREAK:
                    if (pass == 1) {
                        drawStreak(ctx, p);
                    }
                    break;
                case STAR:
                    if (pass == 2) {
                        drawStar(ctx, p);
                    }
                    break;
                case CONFETTI:
                    if (pass == 2) {
                        drawConfetti(ctx, p);
                    }
                    break;
                case DIAMOND:
                    if (pass == 2) {
                        drawDiamond(ctx, p);
                    }
                    break;
            }
        }
    }

    private static void drawSmoke(DrawContext ctx, HudParticle p) {
        int col = p.color();
        if ((col >>> 24) < 4) {
            return;
        }
        blob(ctx, p.x, p.y, p.size(), col);
    }

    private static void drawFlash(DrawContext ctx, HudParticle p) {
        int col = p.color();
        if ((col >>> 24) < 4) {
            return;
        }
        float r = p.size();
        disc(ctx, p.x, p.y, r, EasingUtils.withAlpha(col, 0.35f));
        disc(ctx, p.x, p.y, r * 0.55f, col);
    }

    private static void drawSpark(DrawContext ctx, HudParticle p) {
        int col = p.color();
        if ((col >>> 24) < 6) {
            return;
        }
        float s = Math.max(1f, p.size());
        float h = s * 0.5f;
        rect(ctx, p.prevX - h, p.prevY - h, p.prevX + h, p.prevY + h, EasingUtils.withAlpha(col, 0.5f));
        rect(ctx, p.x - h, p.y - h, p.x + h, p.y + h, col);
    }

    private static void drawEmber(DrawContext ctx, HudParticle p) {
        int col = p.color();
        if ((col >>> 24) < 6) {
            return;
        }
        float s = Math.max(1f, p.size());
        float h = s * 0.5f;
        rect(ctx, p.x - h, p.y - h, p.x + h, p.y + h, col);
    }

    private static void drawStreak(DrawContext ctx, HudParticle p) {
        int col = p.color();
        if ((col >>> 24) < 6) {
            return;
        }
        final int segs = 6;
        float tx = p.vx * 0.06f;
        float ty = p.vy * 0.06f;
        for (int k = 0; k < segs; k++) {
            float f = k / (float) segs;
            float px = p.x - tx * f;
            float py = p.y - ty * f;
            float sz = Math.max(1f, p.size() * (1f - 0.6f * f)) * 0.5f;
            float fade = (1f - f) * (1f - f);
            rect(ctx, px - sz, py - sz, px + sz, py + sz, EasingUtils.withAlpha(col, fade));
        }
    }

    private static void drawStar(DrawContext ctx, HudParticle p) {
        int col = p.color();
        int alpha = col >>> 24;
        if (alpha < 6) {
            return;
        }
        float s = Math.max(1.5f, p.size() * 1.3f);
        float t = Math.max(0.5f, s * 0.11f);
        rect(ctx, p.x - s, p.y - t, p.x + s, p.y + t, col);
        rect(ctx, p.x - t, p.y - s, p.x + t, p.y + s, col);
        float c = Math.max(0.6f, s * 0.28f);
        rect(ctx, p.x - c, p.y - c, p.x + c, p.y + c, EasingUtils.setAlpha(0xFFFFFFFF, alpha / 255f));
    }

    private static void drawConfetti(DrawContext ctx, HudParticle p) {
        int col = p.color();
        if ((col >>> 24) < 6) {
            return;
        }
        float s = p.size();
        float cw = s * (0.25f + 0.75f * Math.abs((float) Math.cos(p.rotation)));
        float ch = s * 1.3f;
        rect(ctx, p.x - cw * 0.5f, p.y - ch * 0.5f, p.x + cw * 0.5f, p.y + ch * 0.5f, col);
    }

    private static void drawDiamond(DrawContext ctx, HudParticle p) {
        int col = p.color();
        if ((col >>> 24) < 6) {
            return;
        }
        int r = Math.max(1, Math.round(p.size() * 0.8f));
        int cx = Math.round(p.x);
        int cy = Math.round(p.y);
        for (int dy = -r; dy <= r; dy++) {
            int hw = r - Math.abs(dy);
            ctx.fill(cx - hw, cy + dy, cx + hw + 1, cy + dy + 1, col);
        }
    }

    private static void drawMeteors(DrawContext ctx, HudParticle.Engine eng) {
        for (HudParticle.Engine.Meteor mt : eng.meteors()) {
            if (!mt.isFlying()) {
                continue;
            }
            float r = Math.max(2f, mt.headSize);
            for (int k = 1; k <= 8; k++) {
                float f = k / 8f;
                float px = mt.headX - mt.dirX * r * 5f * f;
                float py = mt.headY - mt.dirY * r * 5f * f;
                blob(ctx, px, py, r * (1f - 0.65f * f), EasingUtils.withAlpha(0xFFFF7A1A, 0.35f * (1f - f)));
            }
            disc(ctx, mt.headX, mt.headY, r * 2.2f, 0x40FF6A00);
            disc(ctx, mt.headX, mt.headY, r * 1.4f, 0x90FFA030);
            disc(ctx, mt.headX, mt.headY, r, 0xFFFFE08A);
            disc(ctx, mt.headX, mt.headY, r * 0.5f, 0xFFFFFFFF);
        }
    }

    // ------------------------------------------------------------------
    // Gfx yardımcıları — tüm Minecraft çizim çağrıları burada toplanır
    // ------------------------------------------------------------------

    /** En az 1x1 piksel garantili dikdörtgen. */
    private static void rect(DrawContext c, float x1, float y1, float x2, float y2, int color) {
        int ix1 = Math.round(x1);
        int iy1 = Math.round(y1);
        int ix2 = Math.round(x2);
        int iy2 = Math.round(y2);
        if (ix2 <= ix1) {
            ix2 = ix1 + 1;
        }
        if (iy2 <= iy1) {
            iy2 = iy1 + 1;
        }
        c.fill(ix1, iy1, ix2, iy2, color);
    }

    private static void gradient(DrawContext c, int x1, int y1, int x2, int y2, int top, int bottom) {
        c.fillGradient(x1, y1, x2, y2, top, bottom);
    }

    /** Dikey merkezli, ölçekli, ortalanmış metin. */
    private static void text(DrawContext c, TextRenderer tr, String s, float centerX, float centerY,
                             float scale, int color, boolean shadow) {
        if ((color >>> 24) < 4) {
            return;
        }
        var m = c.getMatrices();
        m.pushMatrix();
        m.translate(centerX, centerY);
        m.scale(scale, scale);
        c.drawText(tr, s, -(tr.getWidth(s) / 2), -4, color, shadow);
        m.popMatrix();
    }

    /** Satır tarama ile dolu daire; satır sayısı ~40 ile sınırlı (büyük parlamalar ucuz kalır). */
    private static void disc(DrawContext c, float cx, float cy, float r, int color) {
        if (r < 1f || (color >>> 24) < 2) {
            return;
        }
        int step = Math.max(1, (int) (r / 20f));
        int top = Math.round(cy - r);
        int rows = Math.max(1, Math.round(2f * r));
        for (int row = 0; row < rows; row += step) {
            int y1 = top + row;
            int y2 = Math.min(top + rows, y1 + step);
            float yc = (y1 + y2) * 0.5f - cy;
            float hw = (float) Math.sqrt(Math.max(0f, r * r - yc * yc));
            if (hw < 0.5f) {
                continue;
            }
            c.fill(Math.round(cx - hw), y1, Math.round(cx + hw), y2, color);
        }
    }

    /** Yuvarlak görünümlü ucuz leke: iki çakışan dikdörtgen (haç biçimi). */
    private static void blob(DrawContext c, float x, float y, float r, int color) {
        if ((color >>> 24) < 3) {
            return;
        }
        rect(c, x - r, y - r * 0.6f, x + r, y + r * 0.6f, color);
        rect(c, x - r * 0.6f, y - r, x + r * 0.6f, y + r, color);
    }
}
