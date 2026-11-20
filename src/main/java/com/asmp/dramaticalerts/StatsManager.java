package com.asmp.dramaticalerts;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Oyuncu başına toplam miktar sıralaması ("en çok gönderenler").
 *
 * ÖNEMLİ SINIR: Yalnızca BU istemcinin gördüğü mesajları sayar. Sen oyunda değilken olanları
 * veya sana gösterilmeyen mesajları bilmez; sunucunun resmi sıralaması değildir.
 *
 * Veri .minecraft/config/asmpalerts_stats.tsv dosyasında saklanır (sekmeyle ayrılmış metin):
 *   # asmpalerts stats v1
 *   since \t <epochMs>
 *   <ad> \t <toplam> \t <işlem sayısı> \t <en büyük> \t <son zaman epochMs>
 */
public final class StatsManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("asmpalerts");
    private static final String HEADER = "# asmpalerts stats v1";
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.ROOT);

    /** Dışarıya verilen değişmez satır görünümü. */
    public record Row(String name, long total, int count, long biggest, long lastAt) {
    }

    private static final class Entry {
        final String name;
        long total;
        int count;
        long biggest;
        long lastAt;

        Entry(String name) {
            this.name = name;
        }
    }

    private static final Map<String, Entry> ENTRIES = new HashMap<>();
    private static final Map<String, Long> RECENT = new HashMap<>();
    private static long since = System.currentTimeMillis();

    private StatsManager() {
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("asmpalerts_stats.tsv");
    }

    // ------------------------------------------------------------------
    // Kayıt
    // ------------------------------------------------------------------

    /**
     * @return sayıldıysa true; geçersiz ad/miktar veya kısa süre içinde tekrar eden mesajsa false
     */
    public static synchronized boolean record(String player, long amount) {
        if (amount <= 0L || player == null) {
            return false;
        }
        String name = ConfigManager.stripFormatting(player).trim();
        if (name.isEmpty() || name.equals("Unknown")) {
            return false;
        }
        if (name.length() > 24) {
            name = name.substring(0, 24);
        }

        if (isDuplicate(name, amount)) {
            return false;
        }

        String key = name.toLowerCase(Locale.ROOT);
        Entry e = ENTRIES.get(key);
        if (e == null) {
            e = new Entry(name);
            ENTRIES.put(key, e);
        }
        e.total = e.total > Long.MAX_VALUE - amount ? Long.MAX_VALUE : e.total + amount;
        e.count++;
        e.biggest = Math.max(e.biggest, amount);
        e.lastAt = System.currentTimeMillis();

        save();
        return true;
    }

    /** Aynı mesaj chat + actionbar'dan iki kez gelirse tek sayılır (AlertManager ile aynı pencere). */
    private static boolean isDuplicate(String name, long amount) {
        long windowNanos = ConfigManager.get().dedupeWindowMs * 1_000_000L;
        if (windowNanos <= 0L) {
            return false;
        }
        long now = System.nanoTime();
        if (RECENT.size() > 32) {
            Iterator<Map.Entry<String, Long>> it = RECENT.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > windowNanos) {
                    it.remove();
                }
            }
        }
        String key = name.toLowerCase(Locale.ROOT) + '|' + amount;
        Long last = RECENT.get(key);
        RECENT.put(key, now);
        return last != null && now - last <= windowNanos;
    }

    // ------------------------------------------------------------------
    // Sorgular
    // ------------------------------------------------------------------

    /** Toplama göre azalan sıralı ilk n oyuncu (eşitlikte işlem sayısı, sonra ad). */
    public static synchronized List<Row> top(int n) {
        List<Row> rows = new ArrayList<>(ENTRIES.size());
        for (Entry e : ENTRIES.values()) {
            rows.add(new Row(e.name, e.total, e.count, e.biggest, e.lastAt));
        }
        rows.sort((a, b) -> {
            int c = Long.compare(b.total(), a.total());
            if (c != 0) {
                return c;
            }
            c = Integer.compare(b.count(), a.count());
            return c != 0 ? c : a.name().compareToIgnoreCase(b.name());
        });
        return rows.size() > n ? new ArrayList<>(rows.subList(0, Math.max(0, n))) : rows;
    }

    public static synchronized int playerCount() {
        return ENTRIES.size();
    }

    public static synchronized long totalCount() {
        long n = 0L;
        for (Entry e : ENTRIES.values()) {
            n += e.count;
        }
        return n;
    }

    public static synchronized String sinceText() {
        return DATE_FORMAT.format(Instant.ofEpochMilli(since).atZone(ZoneId.systemDefault()));
    }

    public static synchronized void reset() {
        ENTRIES.clear();
        RECENT.clear();
        since = System.currentTimeMillis();
        save();
    }

    // ------------------------------------------------------------------
    // Disk
    // ------------------------------------------------------------------

    /** Dosyadan okur (önce bellekteki veriyi temizler). Dosya yoksa boş başlar. */
    public static synchronized void load() {
        ENTRIES.clear();
        RECENT.clear();
        since = System.currentTimeMillis();

        Path p = path();
        if (!Files.exists(p)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\t");
                try {
                    if (f.length == 2 && f[0].equals("since")) {
                        since = Long.parseLong(f[1].trim());
                    } else if (f.length == 5) {
                        Entry e = new Entry(f[0]);
                        e.total = Long.parseLong(f[1].trim());
                        e.count = Integer.parseInt(f[2].trim());
                        e.biggest = Long.parseLong(f[3].trim());
                        e.lastAt = Long.parseLong(f[4].trim());
                        ENTRIES.put(f[0].toLowerCase(Locale.ROOT), e);
                    }
                } catch (NumberFormatException ignored) {
                    // bozuk satır atlanır
                }
            }
        } catch (IOException e) {
            LOGGER.error("asmpalerts_stats.tsv okunamadi: {}", e.toString());
        }
    }

    public static synchronized void save() {
        Path p = path();
        Path tmp = p.resolveSibling("asmpalerts_stats.tsv.tmp");
        try {
            Files.createDirectories(p.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                w.write(HEADER);
                w.newLine();
                w.write("since\t" + since);
                w.newLine();
                for (Entry e : ENTRIES.values()) {
                    w.write(e.name + '\t' + e.total + '\t' + e.count + '\t' + e.biggest + '\t' + e.lastAt);
                    w.newLine();
                }
            }
            try {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("asmpalerts_stats.tsv yazilamadi: {}", e.toString());
        }
    }
}
