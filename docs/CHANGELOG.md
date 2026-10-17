# Catatan perubahan

## 3.60
- Menu bawah baru: **Beranda, Rute, Rekam, Grup, Profil**.
- Mode baru **Motor** (beranda, rekam, riwayat, target jarak, batas kecepatan 180 km/jam; kalori tidak dihitung).
- **Rekam**: halaman Utama, Rute, Grafik; speedometer Sepeda/Motor punya pilihan **Analog atau Digital** (pilihan diingat).
- **Grup** (lokal): buat atau gabung dengan kode undangan, daftar teman, peta grup dengan **titik kumpul**, bagikan posisi dan titik kumpul. Posisi teman real-time belum ada (butuh server).
- **Profil**: foto dan nama, ringkasan, serta jalan masuk ke **Riwayat**, **Tema**, dan **Atur**.
- Pembersihan: kode survei lama yang tidak terpakai, gaya `AppTheme` yang tidak dipakai, gambar bawaan Leaflet, dan metadata Kotlin tidak ikut dikemas.

## 3.41
- Riwayat disimpan di **database SQLite** (migrasi otomatis dari penyimpanan lama; teks lama tetap sebagai cadangan sampai "Hapus semua riwayat").
- Layar Riwayat menampilkan **Rekor pribadi**; Pengaturan punya **Ekspor CSV**.
- Pengingat penghematan baterai (sekali) dan label aksesibilitas untuk tombol ikon.
- Semua fitur 3.40 di bawah ini ikut disertakan.

## 3.40
- Notifikasi punya tombol **Jeda/Lanjut** dan **Selesai**.
- **Suara tiap kilometer** (waktu dan pace/kecepatan rata-rata), bisa dimatikan di Pengaturan.
- Rekaman **dipulihkan otomatis** bila sistem mematikan proses aplikasi saat merekam.
- **GPX lengkap**: tiap titik membawa waktu dan tinggi (diimpor Strava/Garmin Connect); jalur penuh disimpan terpisah dari riwayat.
- Penyimpanan sesi tidak lagi membebani layar (ditulis di thread latar belakang).
- Filter GPS direset di awal aktivitas dan setelah jeda (titik pertama tidak lagi tertarik ke posisi lama).
- Peta memuat Leaflet dari dalam aplikasi lebih dulu (CDN jadi cadangan).
- Pengingat satu kali untuk mengecualikan aplikasi dari penghematan baterai.
- Build: varian **release** (R8) + **AAB**, nomor versi otomatis, unit test, lint, Gradle wrapper, ikon adaptif, ikon notifikasi sendiri, aturan cadangan Android.