package com.asmp.dramaticalerts;

import com.asmp.dramaticalerts.AmountParser.Tier;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.OptionalLong;

/**
 * İstemci tarafı komut: /asmpalert
 *
 *   /asmpalert <oyuncu> <miktar> [seviye] [etiket...]   örn: /asmpalert Xhantos_yt 60B
 *                                                            /asmpalert Xhantos_yt 5M 3 SUPER BAGIS
 *   /asmpalert demo [oyuncu]    5 kademeyi (1M, 10M, 100M, 1B, 60B) sırayla oynatır (video çekimi için)
 *   /asmpalert top [sayı]       en çok gönderenler sıralaması (varsayılan 10, en fazla 25)
 *   /asmpalert top reset        sıralamayı silmek için uyarı verir; silmek: /asmpalert top reset confirm
 *   /asmpalert skip | clear | reload | toggle | status
 *
 * Komut mantığı, test edilebilmesi için Brigadier bağlantısından ayrıdır (Out arayüzü).
 */
public final class AlertCommand {

    /** Geri bildirim kanalı: oyunda sohbete, testlerde konsola yazılabilir. */
    interface Out {
        void ok(String message);

        void info(String message);

        void error(String message);
    }

    private static final long[] DEMO_AMOUNTS = {
            1_000_000L, 10_000_000L, 100_000_000L, 1_000_000_000L, 60_000_000_000L
    };

    private AlertCommand() {
    }

    // ------------------------------------------------------------------
    // Brigadier bağlantısı
    // ------------------------------------------------------------------

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> build(dispatcher));
    }

    private static void build(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("asmpalert")
                .then(ClientCommandManager.literal("reload").executes(c -> reload(out(c))))
                .then(ClientCommandManager.literal("clear").executes(c -> clear(out(c))))
                .then(ClientCommandManager.literal("skip").executes(c -> skip(out(c))))
                .then(ClientCommandManager.literal("toggle").executes(c -> toggle(out(c))))
                .then(ClientCommandManager.literal("status").executes(c -> status(out(c))))
                .then(ClientCommandManager.literal("top")
                        .executes(c -> top(out(c), 10))
                        .then(ClientCommandManager.literal("reset")
                                .executes(c -> topResetWarning(out(c)))
                                .then(ClientCommandManager.literal("confirm")
                                        .executes(c -> topReset(out(c)))))
                        .then(ClientCommandManager.argument("count", IntegerArgumentType.integer(1, 25))
                                .executes(c -> top(out(c), IntegerArgumentType.getInteger(c, "count")))))
                .then(ClientCommandManager.literal("demo")
                        .executes(c -> demo(out(c), ownName()))
                        .then(ClientCommandManager.argument("player", StringArgumentType.word())
                                .suggests((c, b) -> CommandSource.suggestMatching(c.getSource().getPlayerNames(), b))
                                .executes(c -> demo(out(c), StringArgumentType.getString(c, "player")))))
                .then(ClientCommandManager.argument("player", StringArgumentType.word())
                        .suggests((c, b) -> CommandSource.suggestMatching(c.getSource().getPlayerNames(), b))
                        .then(ClientCommandManager.argument("amount", StringArgumentType.word())
                                .suggests((c, b) -> {
                                    b.suggest("1M");
                                    b.suggest("10M");
                                    b.suggest("100M");
                                    b.suggest("1B");
                                    b.suggest("60B");
                                    return b.buildFuture();
                                })
                                .executes(c -> trigger(out(c),
                                        StringArgumentType.getString(c, "player"),
                                        StringArgumentType.getString(c, "amount"), null))
                                .then(ClientCommandManager.argument("extra", StringArgumentType.greedyString())
                                        .executes(c -> trigger(out(c),
                                                StringArgumentType.getString(c, "player"),
                                                StringArgumentType.getString(c, "amount"),
                                                StringArgumentType.getString(c, "extra")))))));
    }

    private static Out out(CommandContext<FabricClientCommandSource> ctx) {
        final FabricClientCommandSource src = ctx.getSource();
        return new Out() {
            @Override
            public void ok(String message) {
                src.sendFeedback(Text.literal(message).formatted(Formatting.GREEN));
            }

            @Override
            public void info(String message) {
                src.sendFeedback(Text.literal(message).formatted(Formatting.GRAY));
            }

            @Override
            public void error(String message) {
                src.sendError(Text.literal(message));
            }
        };
    }

    private static String ownName() {
        try {
            String n = MinecraftClient.getInstance().getSession().getUsername();
            return n == null || n.isBlank() ? "Xhantos_yt" : n;
        } catch (Throwable t) {
            return "Xhantos_yt";
        }
    }

    // ------------------------------------------------------------------
    // Komut mantığı
    // ------------------------------------------------------------------

    /** /asmpalert <oyuncu> <miktar> [seviye] [etiket...] */
    static int trigger(Out out, String player, String amountText, String extra) {
        OptionalLong amount = AmountParser.parse(amountText);
        if (amount.isEmpty()) {
            out.error("Geçersiz miktar: \"" + amountText + "\". Örnekler: 1M, 10M, 100M, 1B, 60B, 1500000");
            return 0;
        }

        Tier tierOverride = null;
        String label = null;
        if (extra != null && !extra.isBlank()) {
            String[] tokens = extra.trim().split("\\s+", 2);
            Tier parsed = Tier.fromString(tokens[0]);
            if (parsed != null) {
                tierOverride = parsed;
                label = tokens.length > 1 ? tokens[1] : null;
            } else {
                label = extra.trim();
            }
        }

        AlertData data = AlertData.create(player, amount.getAsLong(), tierOverride, label, true);
        if (data == null) {
            out.error("Miktar ilk kademe eşiğinin altında (en az "
                    + AmountParser.formatCompact(ConfigManager.threshold(Tier.HIGH_ROLLER))
                    + "). Zorlamak için: /asmpalert " + player + " " + amountText + " 1");
            return 0;
        }
        if (!AlertManager.enqueue(data, true)) {
            out.error("Bildirim eklenemedi (mod kapalı olabilir: /asmpalert toggle).");
            return 0;
        }
        out.ok("Kuyruğa eklendi: " + data.playerName + " " + data.amountCompact + " (" + data.tier.label + ")"
                + (AlertManager.queueSize() > 0 ? " — sırada: " + AlertManager.queueSize() : ""));
        return 1;
    }

    /** 5 kademeyi sırayla oynatır. */
    static int demo(Out out, String player) {
        Tier[] tiers = Tier.values();
        int added = 0;
        for (int i = 0; i < DEMO_AMOUNTS.length; i++) {
            AlertData data = AlertData.create(player, DEMO_AMOUNTS[i], tiers[i + 1], null, true);
            if (data != null && AlertManager.enqueue(data, true)) {
                added++;
            }
        }
        if (added == 0) {
            out.error("Demo eklenemedi (mod kapalı olabilir: /asmpalert toggle).");
            return 0;
        }
        out.ok("Demo başladı: " + added + " bildirim sıraya alındı (1M → 60B).");
        return 1;
    }

    /** En çok gönderenler sıralaması. */
    static int top(Out out, int count) {
        int n = Math.max(1, Math.min(count, 25));
        List<StatsManager.Row> rows = StatsManager.top(n);
        if (rows.isEmpty()) {
            out.info("Henüz kayıtlı işlem yok. Mod, oyundayken gördüğü para mesajlarını burada toplar.");
            return 1;
        }
        out.ok("En çok gönderenler — " + StatsManager.playerCount() + " oyuncu, "
                + StatsManager.totalCount() + " işlem (" + StatsManager.sinceText() + " tarihinden beri)");
        int rank = 1;
        for (StatsManager.Row r : rows) {
            out.info(rank + ". " + r.name() + " — " + AmountParser.formatCompact(r.total())
                    + "  (" + r.count() + " işlem, en büyük " + AmountParser.formatCompact(r.biggest()) + ")");
            rank++;
        }
        out.info("Not: yalnızca bu istemcinin gördüğü mesajlar sayılır.");
        return 1;
    }

    static int topResetWarning(Out out) {
        out.error("Sıralamadaki " + StatsManager.playerCount()
                + " oyuncunun tüm kayıtları silinecek. Onaylamak için: /asmpalert top reset confirm");
        return 0;
    }

    static int topReset(Out out) {
        StatsManager.reset();
        out.ok("Sıralama sıfırlandı.");
        return 1;
    }

    static int reload(Out out) {
        ConfigManager.load();
        AlertHudRenderer.resetFailure();
        out.ok("Ayarlar yeniden yüklendi (" + ConfigManager.patterns().size() + " aktif regex kalıbı).");
        return 1;
    }

    static int clear(Out out) {
        AlertManager.clear();
        out.ok("Bildirim kuyruğu ve efektler temizlendi.");
        return 1;
    }

    static int skip(Out out) {
        AlertManager.skipCurrent();
        out.ok("Aktif bildirim atlandı.");
        return 1;
    }

    static int toggle(Out out) {
        ConfigManager.Config cfg = ConfigManager.get();
        cfg.enabled = !cfg.enabled;
        ConfigManager.save();
        out.ok("ASMP Alerts: " + (cfg.enabled ? "AÇIK" : "KAPALI"));
        return 1;
    }

    static int status(Out out) {
        ConfigManager.Config cfg = ConfigManager.get();
        out.info("ASMP Alerts: " + (cfg.enabled ? "açık" : "kapalı")
                + " | kuyruk: " + AlertManager.queueSize()
                + " | aktif: " + (AlertManager.isActive() ? "evet" : "hayır")
                + " | regex: " + ConfigManager.patterns().size());
        StringBuilder sb = new StringBuilder("Kademeler: ");
        for (Tier t : Tier.values()) {
            if (t == Tier.NONE) {
                continue;
            }
            if (t.level > 1) {
                sb.append("  ");
            }
            sb.append(t.label).append(" ≥ ").append(AmountParser.formatCompact(ConfigManager.threshold(t)));
        }
        out.info(sb.toString());
        out.info("Dinleme → chat: " + cfg.listenChat + ", actionbar: " + cfg.listenActionBar
                + ", title: " + cfg.listenTitle + " | kendi adını yoksay: " + cfg.ignoreOwnName);
        return 1;
    }
}
