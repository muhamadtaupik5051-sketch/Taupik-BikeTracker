# Daftar periksa rilis Google Play — RideTrack Pro

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
Jalankan workflow, lalu unduh artifact **RideTrack-Pro-AAB** (`app-release.aab`) dan unggah ke Play Console. `versionCode` naik otomatis tiap build.

## 3. Isian Play Console
- **Kebijakan privasi**: unggah `docs/PRIVACY_POLICY.md` ke GitHub Pages atau situs lain, lalu tempel URL-nya.
- **Data safety**: lokasi presisi dikumpulkan untuk fungsi inti aplikasi; data tidak dibagikan ke pihak ketiga dan tidak dikirim ke server pengembang (disimpan di perangkat). Peta memuat tile dari OpenStreetMap/Esri.
- **Foreground service (lokasi)**: jelaskan bahwa layanan lokasi latar depan dipakai untuk merekam rute saat layar mati, dan sertakan video pendek menunjukkan tombol Mulai → rekaman berjalan dengan notifikasi.
- **Kategori**: Kesehatan & kebugaran. **Iklan**: tidak ada.
- **Aset toko**: ikon 512×512, feature graphic 1024×500, minimal 2 screenshot HP.
- Akun pengembang baru (perorangan) wajib menjalankan **uji tertutup** dengan beberapa penguji selama beberapa minggu sebelum bisa rilis produksi.

## 4. Sebelum rilis, uji di HP sungguhan
- Rekam 10–15 menit dengan layar mati; pastikan rute tidak putus.
- Tekan Jeda/Lanjut dan Selesai dari notifikasi.
- Matikan data seluler: peta bisa kosong di area baru (cache hanya area yang pernah dibuka), tetapi rekaman harus tetap jalan.
- Ekspor GPX lalu impor ke Strava/Garmin Connect untuk memastikan waktu dan tinggi terbaca.
- Uji di HP Xiaomi/Oppo/Vivo bila ada (pengecualian baterai).
