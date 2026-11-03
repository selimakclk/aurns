# 📱 ΛURNS Android (`v1.4.1`)
### %100 İkiz Platform Deneyimi • Sınırsız Kişilik Canlı Jam • 4 Kanallı Hızlı İndirici

Bu modül, **ΛURNS (`v1.4.1`)** müzik oynatıcısının resmi Android uygulamasıdır ve masaüstü Windows (`src/`) sürümüyle **piksel piksel %100 aynı arayüz ve özellik eşitliğine** sahiptir.

---

## ⚡ Öne Çıkan Özellikler (`v1.4.1`)

1. **%100 Masaüstü İkiz Arayüzü (Obsidian & Sunset-Neon):**
   * `#050505` Obsidian zemin, `#FF925C` / `#FF6E50` / `#F044B8` 3 renkli Sunset-Neon ilerleme çubuğu.
   * Her zaman görünür yüzen alt oynatıcı (`FloatingPlayerDock`), `# | PARÇA VE SANATÇI | X PARÇA` sütun başlığı, 2 satırlı saf Şarkı / Sanatçı ayrımı, Aura durum noktaları ve `⋮` satır menüsü.
2. **Sınırsız Kişilik Canlı Jam Odaları & Milisaniye Yakalama (`Seek-to-Live`):**
   * Bilgisayar (`Aurns.exe`) ve Android (`Aurns.apk`) kullanıcılarıyla aynı odada sınırsız katılımcıyla canlı müzik dinleme.
   * Odada çalan parça telefonda yoksa arka planda otomatik indirilip doğrudan canlı milisaniyeye (`Seek-to-Live`) atlayarak sıfır kaymayla başlar.
3. **Kişisel Kullanıcı Adı & Sabit `#Etiket` Kimliği:**
   * Yan menüdeki **SENİN ETİKETİN** kartından satır içi isim değiştirme ve cihaza bağlı sabit `#numara` kimliği.
4. **4 Kanallı Yüksek Hızlı YouTube Arama & İndirme:**
   * YouTube Innertube API üzerinden doğrudan yüksek kaliteli ses akışını (`M4A`) çözer ve 4 paralel kanal (`HTTP Range`) ile saniyeler içinde indirir.
   * Türkçe karakter duyarsız (`ı/i, ş/s, ğ/g, ü/u, ö/o, ç/c`) arşiv içi anlık arama ve paylaşılan `.m3u8` / `.aurns` liste eşitleme.
5. **Uygulama İçi Canlı Güncelleyici & Arka Planda Çalma:**
   * Açılışta otomatik sürüm kontrolü ve uygulama içinden canlı `%` çubuğuyla tek tıkla APK güncelleme (`FileProvider`).
   * AndroidX Media3 / ExoPlayer ile ekran kapalıyken kesintisiz çalma, bildirim kartı ve kilit ekranı kontrolleri.

---

## 🛠️ Derleme

JDK 21 ve Android SDK (API 34) ile:

```bash
cd android
./gradlew assembleRelease
```

İmzalı APK çıktısı `android/app/build/outputs/apk/release/app-release.apk` konumunda üretilir.
