# Catatan perubahan

## 3.71
- **Kode Delaas pribadi** di Profil (mis. DLS-7K3Q9X): dibuat otomatis sekali, bisa disalin dan dibagikan. Kode hanya ada di HP ini; belum dikirim ke server.
- **Status** di tab Grup: tulis status singkat (teks dan emoji), tambah foto, atau isi otomatis dari aktivitas terakhir. Status hilang otomatis setelah 24 jam dan bisa dibagikan sebagai kartu gambar lewat WhatsApp dan aplikasi lain. Tersimpan di HP.
- **Kode status**: pesan Bagikan memuat kode; teman menempelkannya di Grup > Status teman untuk melihat status (teks saja) selama 24 jam. Tampil otomatis tanpa kode butuh server dan belum ada.
- Unit test bertambah (teks status, masa berlaku 24 jam, label sisa waktu).

## 3.70
- **Target Android 16 (API 36)**, wajib untuk Google Play sejak 31 Agustus 2026. Alat build dinaikkan (Android Gradle Plugin 8.9.1, Gradle 8.11.1).
- **Kedip flash**: lampu flash berkedip 2× cepat berkala (Pengaturan); jeda bisa diatur dari 10 detik sampai 5 menit (bawaan 60 detik).
- **Peta (halaman Rute)**: tombol **Gaya** untuk gaya peta; panel Rencana rute bisa dilipat; tombol sekunder dipindah ke menu **Lainnya**; kontras tombol dan teks diperbaiki.
- Kebijakan privasi dan daftar periksa Play diperbarui (kamera/flash, target API).
- Perbaikan: izin kamera tidak lagi bentrok dengan izin lokasi; pembersihan kode tak terpakai.

## 3.69
- **Rute tersimpan**: layar baru di Profil > Rute tersimpan. Daftar kartu dengan peta mini, jarak, tanjakan, perkiraan waktu, dan jumlah diikuti; cari nama, saring per Sepeda/Motor/Lari/Jalan atau Favorit, tandai bintang.
- **Detail rute**: peta jalur, profil tinggi, tombol **Ikuti rute** (jadi garis panduan dan membuka layar Mulai), Ekspor GPX, Ganti nama, Hapus, dan Matikan panduan.
- **Tambah rute** dari tiga sumber: buat di peta (Simpan rute), impor file GPX, atau **Simpan sebagai rute** dari detail riwayat.
- Rute lama dari peta otomatis muncul di daftar (memakai penyimpanan yang sama). Di peta, tombol Rute saya punya tombol **Daftar lengkap** menuju layar baru.
- Unit test bertambah (penyederhanaan titik rute dan pembacaan titik).

## 3.68
- **GPS mati**: tombol Mulai memeriksa GPS dan menampilkan dialog dengan jalan pintas ke pengaturan Lokasi. Kalau GPS dimatikan di tengah rekaman, notifikasi menampilkan peringatan. Halaman peta juga memberi tahu.
- **Penjelasan izin** ditampilkan sebelum dialog izin lokasi dan notifikasi (syarat Google Play).
- **Peta lebih aman**: tautan di dalam peta dibuka di peramban (peta tidak pindah halaman), akses berkas lokal dimatikan, dan kalau proses WebView mati aplikasi tidak ikut tertutup.
- **Impor GPX**: Pengaturan > Impor aktivitas dari file GPX (Strava, Garmin, aplikasi lain, atau ekspor Delaas). Jarak, durasi, kecepatan, tanjakan, rute, dan grafik dihitung dari file.
- **Peta** bisa diputar ke lanskap (dan tidak dimuat ulang saat diputar). Halaman lain tetap portrait.
- Kebijakan privasi dan daftar periksa Play diperbarui (profil, grup, suara, impor).
- Unit test bertambah (impor GPX, format baris riwayat, kode grup, format waktu) dan **sekarang wajib lulus** di build GitHub.
- Kode: data riwayat (Rec, RecordDb, RecordStore) dipindah ke `Records.kt`; teks baru ada di `strings.xml`.

## 3.67
- Nama aplikasi diganti menjadi **Delaas** (nama di layar HP, notifikasi, kartu bagikan, nama file ekspor, dan folder Download/Delaas). ID paket dan database tidak berubah, jadi bisa langsung menimpa versi lama dan riwayat tetap ada.

## 3.66
- Halaman **Rute** muat satu layar tanpa terpotong: speedometer kecil diperkecil (130dp), tinggi minimum peta diturunkan sehingga peta mengisi sisa layar, dan tulisan "km/jam" tidak lagi terpotong di bawah.

## 3.65
- Kartu **Target** di halaman Rekam dipendekkan sedikit lagi.

## 3.64
- Kartu **Target** di halaman Rekam dibuat lebih pendek (tinggi kartu diperkecil, lebar kembali penuh).

## 3.63
- Halaman **Rute**: speedometer kecil ikut gaya baru (jarum + angka di bawah untuk Analog, panel neon untuk Digital) dan mengikuti pilihan Analog/Digital di Rekam.

## 3.62
- Tampilan **Digital** dirancang ulang: panel neon bersudut miring, ikon pesepeda dengan garis kecepatan, angka bergaya 7-segmen miring bercahaya, plat "KM/JAM", dan teks kecepatan maksimum di bawahnya.

## 3.61
- Speedometer **analog** dirancang ulang: jarum meruncing dengan poros, busur warna cerah penuh, angka skala putih tebal, angka kecepatan di bawah poros.
- Kartu **Target** di halaman Rekam dibuat sedikit lebih sempit.

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