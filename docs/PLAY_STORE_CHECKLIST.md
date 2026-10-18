# Daftar periksa rilis Google Play — Delaas

## 1. Tanda tangan (wajib, sekali saja)
Google Play meminta file yang ditandatangani dengan kunci unggah Anda sendiri. Tanpa itu, build memakai kunci debug dan **ditolak Play**.

1. Buat keystore (di komputer/Termux/Android Studio):
   `keytool -genkeypair -v -keystore ride.jks -alias ride -keyalg RSA -keysize 2048 -validity 10000`
2. Ubah ke teks: `base64 -w0 ride.jks` (hasilnya satu baris panjang).
3. Di GitHub: Settings → Secrets and variables → Actions → New repository secret:
   - `KEYSTORE_BASE64` = hasil base64 di atas
   - `KEYSTORE_PASSWORD`, `KEY_ALIAS` (mis. `ride`), `KEY_PASSWORD`
4. **Simpan file `ride.jks` dan passwordnya di tempat aman.** Kalau hilang, aplikasi tidak bisa diperbarui lagi.

## 2. File yang diunggah
Jalankan workflow, lalu unduh artifact **Delaas-AAB** (`app-release.aab`) dan unggah ke Play Console. `versionCode` naik otomatis tiap build.

## 3. Isian Play Console
- **Kebijakan privasi**: unggah `docs/PRIVACY_POLICY.md` ke GitHub Pages atau situs lain, lalu tempel URL-nya.
- **Data safety**: lokasi presisi dikumpulkan untuk fungsi inti aplikasi; data tidak dibagikan ke pihak ketiga dan tidak dikirim ke server pengembang (disimpan di perangkat). Peta memuat tile dari OpenStreetMap/Esri.
- **Data safety (tambahan)**: nama dan foto profil, nama grup, daftar teman, dan titik kumpul hanya disimpan di perangkat dan tidak dikirim ke server pengembang, jadi tidak dihitung sebagai data yang dikumpulkan. Jawaban untuk lokasi presisi tetap "dikumpulkan, tidak dibagikan, hanya di perangkat". Pastikan jawabannya sama dengan `docs/PRIVACY_POLICY.md`.
- **Penjelasan izin**: aplikasi menampilkan dialog penjelasan sebelum meminta izin lokasi dan notifikasi. Ambil tangkapan layar dialog ini bila Play Console meminta bukti.
- **Foreground service (lokasi)**: jelaskan bahwa layanan lokasi latar depan dipakai untuk merekam rute saat layar mati, dan sertakan video pendek menunjukkan tombol Mulai → rekaman berjalan dengan notifikasi.
- **Data safety (kamera)**: izin kamera hanya dipakai untuk menyalakan lampu flash pada fitur Kedip flash. Tidak ada foto/video yang diambil, disimpan, atau dikirim. Jangan centang pengumpulan foto/video.
- **Foreground service (kamera)**: jelaskan bahwa layanan latar depan jenis kamera dipakai menyalakan lampu flash secara berkala (fitur opsional, mati secara bawaan) sebagai penanda saat bersepeda, dan hanya berjalan setelah pengguna menyalakannya di Pengaturan. Sertakan video pendek: Pengaturan → Kedip flash AKTIF → notifikasi "Kedip flash aktif" → flash berkedip. Bila Play meminta, fitur ini bisa dihapus dari versi Play (izin CAMERA, FOREGROUND_SERVICE_CAMERA, FlashBlinkService, dan tombol di Pengaturan).
- **Target API**: sejak 31 Agustus 2026 Play mewajibkan target API 36 (sudah diatur di versi 3.70).
- **Data safety (status)**: teks dan foto status hanya disimpan di perangkat dan tidak dikirim ke server pengembang. Dibagikan hanya atas tindakan pengguna (tombol Bagikan).
- **Kategori**: Kesehatan & kebugaran. **Iklan**: tidak ada.
- **Aset toko**: ikon 512×512, feature graphic 1024×500, minimal 2 screenshot HP.
- Akun pengembang baru (perorangan) wajib menjalankan **uji tertutup** dengan beberapa penguji selama beberapa minggu sebelum bisa rilis produksi.

## 4. Sebelum rilis, uji di HP sungguhan
- Tombol Kembali: di layar Rute, Profil, dan Pengaturan harus kembali ke layar sebelumnya (target API 36).
- Kedip flash: nyalakan di Pengaturan, izinkan kamera, ganti jeda, pastikan flash berkedip 2× sesuai jeda; matikan dan pastikan berhenti.
- Rekam 10–15 menit dengan layar mati; pastikan rute tidak putus.
- Tekan Jeda/Lanjut dan Selesai dari notifikasi.
- Matikan data seluler: peta bisa kosong di area baru (cache hanya area yang pernah dibuka), tetapi rekaman harus tetap jalan.
- Ekspor GPX lalu impor ke Strava/Garmin Connect untuk memastikan waktu dan tinggi terbaca.
- Uji di HP Xiaomi/Oppo/Vivo bila ada (pengecualian baterai).
- Matikan GPS di pengaturan HP lalu tekan Mulai: harus muncul dialog "GPS HP sedang mati". Matikan GPS di tengah rekaman: notifikasi harus menampilkan peringatan GPS mati.
- Impor file GPX dari Strava/Garmin (Pengaturan > Impor aktivitas dari file GPX): aktivitas muncul di Riwayat dengan rute dan grafik.
- Putar layar peta ke lanskap: peta mengisi layar dan tidak memuat ulang.
