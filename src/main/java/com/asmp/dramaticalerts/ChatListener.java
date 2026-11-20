package com.asmp.dramaticalerts;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * Sunucudan gelen metinleri dinler ve Regex kalıplarına uyanları bildirim kuyruğuna ekler.
 *
 * Kaynaklar:
 *  - GAME (overlay=false): sunucu sistem mesajları  → config.listenChat
 *  - GAME (overlay=true) : ActionBar yazıları        → config.listenActionBar
 *  - CHAT                : oyuncu sohbeti            → yalnızca gönderen profili YOKSA (sahteciliği önler)
 *  - Title / Subtitle    : TitleMixin üzerinden      → config.listenTitle
 */
public final class ChatListener {

    private static final Logger LOGGER = LoggerFactory.getLogger("asmpalerts");

    /** Bundan uzun metinler regex'e sokulmaz (performans ve spam koruması). */
    private static final int MAX_LENGTH = 400;

    private ChatListener() {
    }

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            ConfigManager.Config cfg = ConfigManager.get();
            if (overlay ? cfg.listenActionBar : cfg.listenChat) {
                handleIncoming(message.getString(), overlay ? "actionbar" : "game");
            }
        });

        // Oyuncu sohbeti herkesin yazabileceği bir kaynaktır; yalnızca gönderensiz (sunucu) satırları kabul et.
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, timestamp) -> {
            if (sender == null && ConfigManager.get().listenChat) {
                handleIncoming(message.getString(), "chat");
            }
        });
    }

    /** TitleMixin tarafından çağrılır (Title ve Subtitle paketleri). */
    public static void onTitleText(Text text) {
        if (text == null) {
            return;
        }
        // Paket işleyicisi önce ağ thread'inde, sonra ana thread'de iki kez çalışabilir; yalnızca ana thread'i işle.
        if (!MinecraftClient.getInstance().isOnThread()) {
            return;
        }
        if (!ConfigManager.get().listenTitle) {
            return;
        }
        handleIncoming(text.getString(), "title");
    }

    /**
     * Ortak işleme hattı: ön süzme → regex eşleşmesi → kendi adını yoksayma → kuyruğa ekleme.
     */
    public static void handleIncoming(String raw, String source) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_LENGTH) {
            return;
        }
        ConfigManager.Config cfg = ConfigManager.get();
        if ((!cfg.enabled && !cfg.trackStats) || !containsDigit(raw)) {
            return;
        }

        Optional<ConfigManager.AlertMatch> match = ConfigManager.findMatch(raw);
        if (cfg.debugLog) {
            LOGGER.info("[{}] {} -> {}", source, raw, match.isPresent() ? "ESLESTI" : "eslesmedi");
        }
        if (match.isEmpty()) {
            return;
        }

        ConfigManager.AlertMatch m = match.get();
        if (cfg.ignoreOwnName && isOwnName(m.player())) {
            if (cfg.debugLog) {
                LOGGER.info("Kendi adin oldugu icin yok sayildi: {}", m.player());
            }
            return;
        }

        // Sıralama: kademe eşiğinin altındaki miktarlar da toplama dahildir
        if (cfg.trackStats) {
            OptionalLong amount = AmountParser.parse(m.amountText());
            if (amount.isPresent()) {
                StatsManager.record(m.player(), amount.getAsLong());
            }
        }

        AlertData data = AlertData.fromMatch(m);
        if (data == null) {
            return; // miktar geçersiz veya ilk kademe eşiğinin altında
        }
        AlertManager.enqueue(data, false);
    }

    private static boolean containsDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                return true;
            }
        }
        return false;
    }

    private static boolean isOwnName(String name) {
        try {
            String own = MinecraftClient.getInstance().getSession().getUsername();
            return own != null && own.equalsIgnoreCase(name);
        } catch (Throwable t) {
            return false;
        }
    }
}
