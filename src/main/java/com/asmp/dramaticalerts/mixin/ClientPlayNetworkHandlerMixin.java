package com.asmp.dramaticalerts.mixin;

import com.asmp.dramaticalerts.ChatListener;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Title ve Subtitle paketlerini ChatListener'a iletir.
 *
 * require = 0: hedef metot adı bu sürümde farklı çıkarsa oyun ÇÖKMEZ, yalnızca title dinleme devre dışı kalır.
 * (ActionBar zaten ClientReceiveMessageEvents.GAME üzerinden overlay=true olarak yakalanıyor.)
 */
@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {

    @Inject(method = "onTitle", at = @At("HEAD"), require = 0)
    private void asmpalerts$onTitle(TitleS2CPacket packet, CallbackInfo ci) {
        ChatListener.onTitleText(packet.text());
    }

    @Inject(method = "onSubtitle", at = @At("HEAD"), require = 0)
    private void asmpalerts$onSubtitle(SubtitleS2CPacket packet, CallbackInfo ci) {
        ChatListener.onTitleText(packet.text());
    }
}
