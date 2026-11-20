# ASMP Dramatic Alerts (asmpalerts)

AesirSmp icin Minecraft 1.21.8 Fabric istemci modu. Para/bagis geldiginde miktara gore 5 kademeli
(1M / 10M / 100M / 1B / 10B+) ekran efekti gosterir. Sunucuya mod kurmak gerekmez.

## Derleme
- GitHub: projeyi bir depoya yukle -> Actions sekmesi -> "build" -> Artifacts -> asmpalerts-jar
- Yerel: JDK 21 + Gradle 8.14 kur, proje klasorunde `gradle build` -> build/libs/asmpalerts-1.0.0.jar

## Kurulum
`.jar` dosyasini `.minecraft/mods` klasorune at (Fabric Loader + Fabric API, Minecraft 1.21.8).

## Komutlar
/asmpalert <oyuncu> <miktar> [seviye] [etiket]   ornek: /asmpalert Xhantos_yt 60B
/asmpalert demo | skip | clear | reload | toggle | status

## Ayar
`.minecraft/config/asmpalerts.json` (regex kaliplari, kademe esikleri, ses/sarsinti/parcacik yogunlugu).
Degisiklikten sonra oyunda `/asmpalert reload`.
