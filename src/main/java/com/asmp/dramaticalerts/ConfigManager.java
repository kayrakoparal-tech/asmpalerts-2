package com.asmp.dramaticalerts;

import com.asmp.dramaticalerts.AmountParser.Tier;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * .minecraft/config/asmpalerts.json yönetimi.
 *
 * - Kademe eşikleri/süreleri, ses-sarsıntı-parçacık yoğunlukları, mod aç/kapa
 * - Chat/ActionBar/Title için Regex kalıpları ((?<player>...) ve (?<amount>...) isimli gruplar)
 *
 * Config okuma ve eşleşme yalnızca bu sınıf üzerinden yapılır; render döngüsünde
 * yalnızca önbelleğe alınmış dizilere erişilir.
 */
public final class ConfigManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("asmpalerts");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final Pattern FORMAT_CODES = Pattern.compile("(?i)\u00a7[0-9A-FK-OR]");

    // Varsayılan Regex parçaları (CASE_INSENSITIVE ile derlenir)
    private static final String PLAYER = "(?<![A-Za-z0-9_])(?<player>[A-Za-z0-9_]{3,16})(?![A-Za-z0-9_])";
    private static final String AMOUNT =
            "\\$?(?<amount>[0-9][0-9.,]*(?:mn|bn|mrd|tn|[kmbt])?)(?![A-Za-z0-9])";

    private static volatile Config config = new Config();
    private static volatile List<CompiledPattern> patterns = List.of();
    private static volatile long[] thresholdCache = new long[Tier.ACTIVE.length];
    private static volatile float[] durationCache = new float[Tier.ACTIVE.length];

    static {
        Config c = new Config();
        sanitize(c);
        apply(c);
    }

    private ConfigManager() {
    }

    // ------------------------------------------------------------------
    // Veri sınıfları
    // ------------------------------------------------------------------

    public static final class Config {
        public String _info = "ASMP Dramatic Alerts ayarlari. Regex'lerde (?<player>...) ve (?<amount>...) "
                + "isimli gruplar zorunludur (player istege bagli). Degisiklikten sonra /asmpalert reload kullanin.";
        public boolean enabled = true;
        public boolean listenChat = true;
        public boolean listenActionBar = true;
        public boolean listenTitle = true;
        public boolean debugLog = false;
        /** true ise mesajdaki oyuncu adı kendi adınla aynıysa bildirim gösterilmez. */
        public boolean ignoreOwnName = true;
        /** true ise gorulen para mesajlari oyuncu basina toplanir (/asmpalert top). */
        public boolean trackStats = true;
        public float soundVolume = 1.0f;
        public float shakeIntensity = 1.0f;
        public float particleDensity = 1.0f;
        public int maxQueueSize = 8;
        public long dedupeWindowMs = 2500L;
        public List<TierSettings> tiers = defaultTiers();
        public List<PatternEntry> patterns = defaultPatterns();
    }

    public static final class TierSettings {
        public String id = "";
        public long threshold;
        public float durationSeconds;

        public TierSettings() {
        }

        public TierSettings(String id, long threshold, float durationSeconds) {
            this.id = id;
            this.threshold = threshold;
            this.durationSeconds = durationSeconds;
        }
    }

    public static final class PatternEntry {
        public boolean enabled = true;
        public String note = "";
        public String regex = "";

        public PatternEntry() {
        }

        public PatternEntry(String note, String regex) {
            this.note = note;
            this.regex = regex;
        }
    }

    public record CompiledPattern(Pattern pattern, boolean hasPlayer, String note) {
    }

    public record AlertMatch(String player, String amountText) {
    }

    // ------------------------------------------------------------------
    // Varsayılanlar
    // ------------------------------------------------------------------

    private static List<TierSettings> defaultTiers() {
        List<TierSettings> list = new ArrayList<>();
        for (Tier t : Tier.ACTIVE) {
            list.add(new TierSettings(t.name(), t.defaultThreshold, t.defaultDuration));
        }
        return list;
    }

    private static List<PatternEntry> defaultPatterns() {
        List<PatternEntry> list = new ArrayList<>();
        // AesirSmp gercek mesaji: "1000000<ikon> mcahitomer2 hesabindan alindi!"
        // Ikon, sunucunun ozel font karakteridir; araya 0-3 harf/rakam olmayan karakter girebilir.
        // ^ ve $ ile tum satira baglidir: bir oyuncu chat'e "<Ad> 1000000 X hesabindan alindi!" yazarak
        // sahte bildirim tetikleyemez (satir miktarla baslamak zorunda).
        list.add(new PatternEntry("AesirSmp: <miktar><ikon> <oyuncu> hesabindan alindi!",
                "^\\s*" + AMOUNT + "\\s*[^\\sA-Za-z0-9]{0,3}\\s+" + PLAYER
                        + "\\s+hesab[\u0131i]ndan\\s+al[\u0131i]nd[\u0131i]\\s*!?\\s*$"));
        list.add(new PatternEntry("Oyuncu sana/birine para gonderdi (EN)",
                PLAYER + "\\s+(?:just\\s+)?(?:paid|sent|gave|donated|transferred)\\s+(?:you\\s+)?" + AMOUNT));
        list.add(new PatternEntry("You received $X from Oyuncu (EN)",
                "(?:you\\s+)?(?:received|got)\\s+" + AMOUNT + "\\s+from\\s+" + PLAYER));
        list.add(new PatternEntry("Oyuncu sana $X gonderdi (TR)",
                PLAYER + "\\s+(?:sana|size)\\s+" + AMOUNT
                        + "\\s+(?:g\u00f6nderdi|yollad\u0131|ba\u011f\u0131\u015flad\u0131)"));
        list.add(new PatternEntry("[Donation] Oyuncu ... $X",
                "\\[(?:donation|ba\u011f\u0131\u015f|transfer)]\\s*" + PLAYER + "\\D+?" + AMOUNT));
        return list;
    }

    // ------------------------------------------------------------------
    // Yükleme / kaydetme
    // ------------------------------------------------------------------

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("asmpalerts.json");
    }

    /** Config'i diskten okur. Dosya yoksa varsayılanlarla oluşturur; bozuksa .broken olarak yedekler. */
    public static synchronized void load() {
        Path path = configPath();
        Config loaded = null;

        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                loaded = GSON.fromJson(reader, Config.class);
            } catch (IOException | JsonParseException e) {
                LOGGER.error("asmpalerts.json okunamadi, varsayilanlar kullanilacak: {}", e.toString());
                try {
                    Files.move(path, path.resolveSibling("asmpalerts.json.broken"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException moveError) {
                    LOGGER.warn("Bozuk config yedeklenemedi: {}", moveError.toString());
                }
            }
        }

        if (loaded == null) {
            loaded = new Config();
        }
        sanitize(loaded);
        apply(loaded);
        save();
    }

    public static synchronized void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(config, writer);
            }
        } catch (IOException e) {
            LOGGER.error("asmpalerts.json yazilamadi: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------
    // Doğrulama ve uygulama
    // ------------------------------------------------------------------

    private static void sanitize(Config c) {
        c.soundVolume = EasingUtils.clamp(c.soundVolume, 0f, 2f);
        c.shakeIntensity = EasingUtils.clamp(c.shakeIntensity, 0f, 3f);
        c.particleDensity = EasingUtils.clamp(c.particleDensity, 0f, 3f);
        c.maxQueueSize = Math.max(1, Math.min(c.maxQueueSize, 50));
        c.dedupeWindowMs = Math.max(0L, c.dedupeWindowMs);
        if (c.patterns == null) {
            c.patterns = defaultPatterns();
        }
        c.tiers = sanitizeTiers(c.tiers);
    }

    private static List<TierSettings> sanitizeTiers(List<TierSettings> input) {
        List<TierSettings> out = new ArrayList<>();
        long prev = 0L;
        boolean valid = true;

        for (Tier t : Tier.ACTIVE) {
            TierSettings found = null;
            if (input != null) {
                for (TierSettings s : input) {
                    if (s != null && t.name().equalsIgnoreCase(s.id)) {
                        found = s;
                        break;
                    }
                }
            }
            if (found == null) {
                found = new TierSettings(t.name(), t.defaultThreshold, t.defaultDuration);
            }
            found.id = t.name();
            found.durationSeconds = EasingUtils.clamp(found.durationSeconds, 2f, 20f);
            if (found.threshold <= prev) {
                valid = false;
            }
            prev = found.threshold;
            out.add(found);
        }

        if (!valid) {
            LOGGER.warn("Kademe esikleri artan sirada degil; varsayilanlara donuldu.");
            return defaultTiers();
        }
        return out;
    }

    private static void apply(Config c) {
        List<CompiledPattern> compiled = new ArrayList<>();
        for (PatternEntry e : c.patterns) {
            if (e == null || !e.enabled || e.regex == null || e.regex.isBlank()) {
                continue;
            }
            if (!e.regex.contains("(?<amount>")) {
                LOGGER.warn("Regex atlandi ((?<amount>...) grubu yok): {}", e.regex);
                continue;
            }
            try {
                Pattern p = Pattern.compile(e.regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                compiled.add(new CompiledPattern(p, e.regex.contains("(?<player>"), e.note));
            } catch (PatternSyntaxException ex) {
                LOGGER.warn("Gecersiz regex atlandi ({}): {}", ex.getDescription(), e.regex);
            }
        }

        long[] th = new long[Tier.ACTIVE.length];
        float[] du = new float[Tier.ACTIVE.length];
        for (int i = 0; i < Tier.ACTIVE.length; i++) {
            TierSettings s = c.tiers.get(i);
            th[i] = s.threshold;
            du[i] = s.durationSeconds;
        }

        config = c;
        patterns = List.copyOf(compiled);
        thresholdCache = th;
        durationCache = du;
    }

    // ------------------------------------------------------------------
    // Erişimciler
    // ------------------------------------------------------------------

    public static Config get() {
        return config;
    }

    public static List<CompiledPattern> patterns() {
        return patterns;
    }

    /** Kademenin config'teki alt eşiği (NONE için 0). */
    public static long threshold(Tier tier) {
        return tier.level <= 0 ? 0L : thresholdCache[tier.level - 1];
    }

    /** Kademenin config'teki animasyon süresi, saniye (NONE için 0). */
    public static float durationSeconds(Tier tier) {
        return tier.level <= 0 ? 0f : durationCache[tier.level - 1];
    }

    // ------------------------------------------------------------------
    // Eşleştirme
    // ------------------------------------------------------------------

    public static String stripFormatting(String s) {
        return FORMAT_CODES.matcher(s).replaceAll("");
    }

    /** Metni sırayla tüm Regex kalıplarına karşı dener, ilk eşleşmeyi döndürür. */
    public static Optional<AlertMatch> findMatch(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return Optional.empty();
        }
        String text = stripFormatting(rawText);
        for (CompiledPattern cp : patterns) {
            Matcher m = cp.pattern().matcher(text);
            if (m.find()) {
                String amount = m.group("amount");
                if (amount == null || amount.isBlank()) {
                    continue;
                }
                String player = cp.hasPlayer() ? m.group("player") : null;
                if (player == null || player.isBlank()) {
                    player = "Unknown";
                }
                return Optional.of(new AlertMatch(player, amount.trim()));
            }
        }
        return Optional.empty();
    }
}
