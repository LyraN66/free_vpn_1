# Free VPN (Android)

Tek dokunuşla **en hızlı ücretsiz sunucuya** bağlanan, reklamsız bir VPN uygulaması.
Sunucuları **VPN Gate** (Tsukuba Üniversitesi akademik projesi) listesinden otomatik bulur;
bağlantının kendisini ücretsiz, açık kaynak **OpenVPN for Android** yapar.

## Nasıl çalışır
1. Uygulamayı açıp **"En hızlı sunucuya bağlan"**a basarsınız.
2. OpenVPN for Android kurulu değilse, kurmanız için yönlendirir (bir kez).
3. Uygulama VPN Gate'ten en hızlı sunucuyu seçer, izinleri ister ve bağlanır.
4. Durum 🟢 Bağlı olunca trafiğiniz VPN üzerinden geçer. **Bağlantıyı kes** ile durur.

> Uyarı: VPN Gate sunucularını gönüllüler işletir ve trafiğinizi görebilirler.
> Bankacılık gibi hassas işlemler için kullanmayın.

## Teknoloji
- Kotlin, Jetpack Compose
- VPN Gate açık API'si (`/api/iphone/`)
- OpenVPN for Android dış API'si (AIDL ile `startVPN`)
- Android 7.0+ (minSdk 24)

## APK
Her `push` sonrası GitHub Actions APK'yı derler ve **Releases › son-surum** altında
`FreeVPN.apk` olarak yayınlar.
