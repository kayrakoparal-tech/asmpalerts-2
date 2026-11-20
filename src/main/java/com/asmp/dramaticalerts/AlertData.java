package com.asmp.dramaticalerts;

import com.asmp.dramaticalerts.AmountParser.Tier;

import java.util.Locale;
import java.util.OptionalLong;

/**
 * Tek bir bildirimin (bağış/transfer olayı) değişmez verisi.
 * Oluşturma anında kademe, süre ve gösterim metinleri hesaplanır;
 * render döngüsünde yeniden hesaplama yapılmaz.
 */
public final class AlertData {

    public final String playerName;
    public final long amount;
    public final Tier tier;
    public final float durationSeconds;
    public final String badgeText;
    public final String amountCompact;
    public final String amountGrouped;
    public final boolean manual;

    private AlertData(String playerName, long amount, Tier tier, float durationSeconds,
                      String badgeText, boolean manual) {
        this.playerName = playerName;
        this.amount = amount;
        this.tier = tier;
        this.durationSeconds = durationSeconds;
        this.badgeText = badgeText;
        this.amountCompact = AmountParser.formatCompact(amount);
        this.amountGrouped = AmountParser.formatGrouped(amount);
        this.manual = manual;
    }

    /**
     * @param tierOverride  null ise kademe miktardan belirlenir
     * @param badgeOverride null/boş ise kademenin varsayılan rozet metni kullanılır
     * @return miktar geçersizse veya ilk kademe eşiğinin altındaysa (override yoksa) null
     */
    public static AlertData create(String player, long amount, Tier tierOverride,
                                   String badgeOverride, boolean manual) {
        if (amount <= 0L) {
            return null;
        }
        Tier tier = tierOverride != null ? tierOverride : AmountParser.tierFor(amount);
        if (tier == null || tier == Tier.NONE) {
            return null;
        }
        String badge = (badgeOverride != null && !badgeOverride.isBlank())
                ? limit(badgeOverride.trim().toUpperCase(Locale.ROOT), 28)
                : defaultBadge(tier);
        return new AlertData(sanitizeName(player), amount, tier,
                ConfigManager.durationSeconds(tier), badge, manual);
    }

    /** Regex eşleşmesinden (chat/actionbar/title) bildirim üretir; miktar geçersiz/küçükse null. */
    public static AlertData fromMatch(ConfigManager.AlertMatch match) {
        if (match == null) {
            return null;
        }
        OptionalLong parsed = AmountParser.parse(match.amountText());
        if (parsed.isEmpty()) {
            return null;
        }
        return create(match.player(), parsed.getAsLong(), null, null, false);
    }

    /** Tekrar eden mesajları (chat + actionbar aynı olayı iki kez gösterirse) ayıklamak için anahtar. */
    public String dedupeKey() {
        return playerName.toLowerCase(Locale.ROOT) + '|' + amount;
    }

    public static String defaultBadge(Tier tier) {
        return tier == Tier.ASTRONOMICAL ? "ASTRONOMICAL EVENT" : tier.label;
    }

    private static String sanitizeName(String raw) {
        if (raw == null) {
            return "Unknown";
        }
        String s = ConfigManager.stripFormatting(raw).trim();
        if (s.isEmpty()) {
            return "Unknown";
        }
        return limit(s, 24);
    }

    private static String limit(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
