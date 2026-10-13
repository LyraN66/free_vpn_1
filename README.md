# Free VPN (Android, WireGuard)

Reklamsız, sade bir WireGuard VPN istemcisi. Bir WireGuard yapılandırması (`.conf`)
yapıştırıp bağlanırsınız. Sunucuyu siz sağlarsınız (kendi sunucunuz ya da ücretsiz
paylaşılan yapılandırmalar).

## Kullanım
1. Uygulamayı açın.
2. Elinizdeki WireGuard yapılandırmasını metin kutusuna yapıştırın.
3. **Bağlan**'a basın; Android VPN izni ister, onaylayın.
4. Durum 🟢 Bağlı olunca trafiğiniz VPN üzerinden geçer. **Bağlantıyı kes** ile durur.

## Ücretsiz yapılandırma nereden bulunur?
- **VPN Gate** (vpngate.net) — akademik proje, WireGuard/OpenVPN yapılandırmaları.
- Ücretsiz WireGuard config paylaşan siteler.

> Uyarı: Tanımadığınız kişilerin işlettiği ücretsiz sunucular trafiğinizi görebilir.
> Hassas işler (bankacılık vb.) için güvenilir, kendi sunucunuzu kullanın.

## Teknoloji
- Kotlin, Jetpack Compose
- Resmi `com.wireguard.android:tunnel` kütüphanesi (native wireguard-go dahil)
- Android 7.0+ (minSdk 24)

## APK
Her `push` sonrası GitHub Actions APK'yı derler ve **Releases › son-surum** altında
`FreeVPN.apk` olarak yayınlar.
