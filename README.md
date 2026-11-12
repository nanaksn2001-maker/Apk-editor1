# APK Editor (Android, Kotlin + Jetpack Compose)

Aplikasi Android untuk membuka file APK, mengedit isinya, lalu menyimpan APK baru yang sudah ditandatangani.

## Fitur
- Buka APK dan lihat seluruh isinya (dengan pencarian nama file)
- **Edit teks** langsung (json, html, js, css, txt, properties, xml teks, dll.)
- **Ganti file** dengan file dari penyimpanan (gambar, audio, dll.)
- **Tambah file** baru ke path mana pun di dalam APK
- **Hapus** file, atau **Batalkan** perubahan per file
- **Ekstrak** file keluar dari APK
- **Cari & ganti** teks di semua file teks sekaligus
- Simpan sebagai APK baru: di-zipalign dan ditandatangani otomatis (v1 + v2 + v3)

## Cara build

### Opsi A: Android Studio
1. Buka folder ini di Android Studio (Koala atau lebih baru), tunggu Gradle sync.
2. Run, atau Build > Build APK(s). Hasil: `app/build/outputs/apk/debug/app-debug.apk`.

### Opsi B: Tanpa PC, lewat GitHub Actions
1. Buat repository baru di GitHub, unggah seluruh isi folder ini (termasuk `.github`).
2. Buka tab **Actions**, pilih **Build APK**, tekan **Run workflow**.
3. Setelah selesai, unduh artifact `ApkEditor-debug` (isinya `app-debug.apk`).

### Opsi C: Terminal
```
gradle assembleDebug
```
(butuh JDK 17 dan Android SDK 34)

## Batasan
- **Binary XML** (`AndroidManifest.xml` dan `res/*.xml` di dalam APK) tidak bisa diedit sebagai teks karena formatnya biner. Untuk itu bongkar dengan apktool di PC.
- `classes.dex` dan `resources.arsc` hanya bisa diganti utuh, bukan diedit isinya.
- APK hasil edit ditandatangani dengan kunci bawaan aplikasi ini, bukan kunci pembuat aslinya. Android tidak mengizinkan update di atas aplikasi lama; **uninstall dulu** versi asli sebelum memasang hasil edit.
- Aplikasi yang memakai pengecekan tanda tangan (banking, game online, dll.) bisa menolak berjalan setelah diubah.
- Pakai hanya untuk APK milikmu sendiri atau yang lisensinya mengizinkan modifikasi.
- File teks lebih dari ±1 MB tidak dibuka di editor.

## Mengganti kunci tanda tangan
Kunci ada di `app/src/main/assets/` (`signing_key.pk8`, `signing_cert.der`). Untuk membuat sendiri:
```
openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem -days 36500 -subj "/CN=Nama Kamu"
openssl pkcs8 -topk8 -nocrypt -inform PEM -outform DER -in key.pem -out signing_key.pk8
openssl x509 -outform DER -in cert.pem -out signing_cert.der
```
Salin kedua file hasilnya ke folder `assets`, lalu build ulang.
