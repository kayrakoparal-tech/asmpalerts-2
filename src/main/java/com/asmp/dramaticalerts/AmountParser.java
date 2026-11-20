package com.asmp.dramaticalerts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Miktar metnini ("1M", "60B", "$1.5m", "1,000,000", "2,5mn" ...) long değere çevirir,
 * kademe (Tier) belirler ve ekranda gösterilecek biçimleri üretir.
 */
public final class AmountParser {

    /** Efekt kademeleri. Eşikler ve süreler config'den (asmpalerts.json) değiştirilebilir. */
    public enum Tier {
        NONE(0, "NONE", 0L, 0f),
        HIGH_ROLLER(1, "HIGH ROLLER", 1_000_000L, 5.5f),
        MEGA_DROP(2, "MEGA DROP", 10_000_000L, 5.8f),
        ULTRA_MYTHIC(3, "ULTRA / MYTHIC", 100_000_000L, 6.2f),
        GODLIKE(4, "GODLIKE", 1_000_000_000L, 6.6f),
        ASTRONOMICAL(5, "ASTRONOMICAL", 10_000_000_000L, 7.0f);

        public final int level;
        public final String label;
        public final long defaultThreshold;
        public final float defaultDuration;

        Tier(int level, String label, long defaultThreshold, float defaultDuration) {
            this.level = level;
            this.label = label;
            this.defaultThreshold = defaultThreshold;
            this.defaultDuration = defaultDuration;
        }

        /** NONE hariç, artan sırada kademeler. */
        static final Tier[] ACTIVE = {HIGH_ROLLER, MEGA_DROP, ULTRA_MYTHIC, GODLIKE, ASTRONOMICAL};

        public boolean atLeast(Tier other) {
            return this.level >= other.level;
        }

        /**
         * "1".."5", "HIGH_ROLLER", "mega", "mythic", "godlike", "astronomical" gibi girdileri çözer.
         *
         * @return tanınmazsa null
         */
        public static Tier fromString(String s) {
            if (s == null) {
                return null;
            }
            String k = s.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
            return switch (k) {
                case "1", "HIGH", "HIGH_ROLLER" -> HIGH_ROLLER;
                case "2", "MEGA", "MEGA_DROP" -> MEGA_DROP;
                case "3", "ULTRA", "MYTHIC", "ULTRA_MYTHIC", "LEGENDARY" -> ULTRA_MYTHIC;
                case "4", "GOD", "GODLIKE" -> GODLIKE;
                case "5", "ASTRO", "ASTRONOMICAL" -> ASTRONOMICAL;
                default -> null;
            };
        }
    }

    private static final Pattern AMOUNT_PATTERN =
            Pattern.compile("^[+]?\\s*\\$?\\s*([0-9][0-9.,_\\s]*?)\\s*([A-Za-z]{0,3})\\s*$");

    private AmountParser() {
    }

    // ------------------------------------------------------------------
    // Ayrıştırma
    // ------------------------------------------------------------------

    /**
     * @return miktar (tam sayı, "coin" birimi) ya da geçersizse boş
     */
    public static OptionalLong parse(String text) {
        if (text == null) {
            return OptionalLong.empty();
        }
        Matcher m = AMOUNT_PATTERN.matcher(text.trim());
        if (!m.matches()) {
            return OptionalLong.empty();
        }
        String suffix = m.group(2).toLowerCase(Locale.ROOT);
        long mult = multiplier(suffix);
        if (mult < 0L) {
            return OptionalLong.empty();
        }
        BigDecimal number = normalizeNumber(m.group(1), !suffix.isEmpty());
        if (number == null) {
            return OptionalLong.empty();
        }
        BigDecimal total = number.multiply(BigDecimal.valueOf(mult)).setScale(0, RoundingMode.HALF_UP);
        if (total.signum() <= 0 || total.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(total.longValueExact());
    }

    private static long multiplier(String suffix) {
        return switch (suffix) {
            case "" -> 1L;
            case "k" -> 1_000L;
            case "m", "mn", "mio" -> 1_000_000L;
            case "b", "bn", "mrd" -> 1_000_000_000L;
            case "t", "tn" -> 1_000_000_000_000L;
            default -> -1L;
        };
    }

    /**
     * Nokta/virgül belirsizliğini çözer:
     * "1,000,000" ve "1.000.000" binlik ayraç; "1.5" ve "1,5" ondalık;
     * "1.500" (son ek yoksa) binlik, "1.500M" (son ek varsa) ondalık kabul edilir.
     */
    private static BigDecimal normalizeNumber(String raw, boolean hasSuffix) {
        String s = raw.replaceAll("[\\s_]", "");
        while (!s.isEmpty() && (s.endsWith(".") || s.endsWith(","))) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty() || s.length() > 40) {
            return null;
        }

        int lastDot = s.lastIndexOf('.');
        int lastComma = s.lastIndexOf(',');
        String normalized;

        if (lastDot >= 0 && lastComma >= 0) {
            char dec = lastDot > lastComma ? '.' : ',';
            char grp = dec == '.' ? ',' : '.';
            normalized = s.replace(String.valueOf(grp), "").replace(dec, '.');
        } else if (lastDot >= 0 || lastComma >= 0) {
            char sep = lastDot >= 0 ? '.' : ',';
            int count = countChar(s, sep);
            int last = s.lastIndexOf(sep);
            int digitsAfter = s.length() - last - 1;
            boolean grouping = count > 1
                    || (!hasSuffix && digitsAfter == 3 && !s.startsWith("0"));
            normalized = grouping ? s.replace(String.valueOf(sep), "") : s.replace(sep, '.');
        } else {
            normalized = s;
        }

        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------
    // Kademe belirleme
    // ------------------------------------------------------------------

    /** Güncel config eşiklerine göre kademe döndürür; ilk eşiğin altı {@link Tier#NONE}. */
    public static Tier tierFor(long amount) {
        Tier result = Tier.NONE;
        for (Tier t : Tier.ACTIVE) {
            if (amount >= ConfigManager.threshold(t)) {
                result = t;
            } else {
                break;
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Biçimlendirme
    // ------------------------------------------------------------------

    /**
     * Kısa biçim: 60000000000 → "60B", 1500000 → "1.5M", 999 → "999".
     * Ondalıklar yuvarlanmaz, kesilir; böylece sayaç hedefi asla aşmaz.
     */
    public static String formatCompact(long value) {
        if (value < 1_000L) {
            return Long.toString(Math.max(0L, value));
        }
        long div;
        char suffix;
        if (value >= 1_000_000_000_000L) {
            div = 1_000_000_000_000L;
            suffix = 'T';
        } else if (value >= 1_000_000_000L) {
            div = 1_000_000_000L;
            suffix = 'B';
        } else if (value >= 1_000_000L) {
            div = 1_000_000L;
            suffix = 'M';
        } else {
            div = 1_000L;
            suffix = 'K';
        }
        long whole = value / div;
        long frac = (value % div) * 100L / div;

        StringBuilder sb = new StringBuilder(8);
        sb.append(whole);
        if (frac > 0L) {
            sb.append('.');
            if (frac % 10L == 0L) {
                sb.append(frac / 10L);
            } else {
                if (frac < 10L) {
                    sb.append('0');
                }
                sb.append(frac);
            }
        }
        sb.append(suffix);
        return sb.toString();
    }

    /** Binlik ayraçlı tam biçim: 60000000000 → "60,000,000,000". */
    public static String formatGrouped(long value) {
        String s = Long.toString(Math.max(0L, value));
        int len = s.length();
        if (len <= 3) {
            return s;
        }
        StringBuilder sb = new StringBuilder(len + len / 3);
        int first = len % 3;
        if (first == 0) {
            first = 3;
        }
        sb.append(s, 0, first);
        for (int i = first; i < len; i += 3) {
            sb.append(',').append(s, i, i + 3);
        }
        return sb.toString();
    }
}
