package com.asmp.dramaticalerts;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ASMP Dramatic Alerts — istemci giriş noktası (fabric.mod.json → entrypoints.client).
 *
 * Sunucuya mod yüklemek gerekmez: her şey oyuncunun kendi istemcisinde çalışır.
 */
public final class AsmpAlertsClient implements ClientModInitializer {

    public static final String MOD_ID = "asmpalerts";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        ConfigManager.load();
        StatsManager.load();

        ChatListener.register();
        AlertCommand.register();
        AlertHudRenderer.register();

        // Sunucudan çıkınca kuyruk ve efektler temizlenir (eski bildirimler yeni sunucuya taşınmasın).
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> AlertManager.clear());

        LOGGER.info("ASMP Dramatic Alerts yuklendi ({} regex kalibi aktif). Komut: /asmpalert",
                ConfigManager.patterns().size());
    }
}
