package com.example.apkeditor

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.android.apksig.ApkSigner
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class ApkEntry(val name: String, val size: Long, val compressedSize: Long)

/** Stream pembungkus yang menghitung jumlah byte yang sudah ditulis (untuk alignment). */
private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    var count = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        count += len
    }
}

class ApkEngine(private val context: Context) {

    val sourceFile = File(context.cacheDir, "source.apk")
    private val stageFile = File(context.cacheDir, "stage.apk")
    private val signedFile = File(context.cacheDir, "signed.apk")

    fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) return c.getString(i) ?: "file"
                }
            }
        return uri.lastPathSegment ?: "file"
    }

    fun readUri(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Tidak bisa membaca file")

    fun copyToSource(uri: Uri) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            sourceFile.outputStream().use { input.copyTo(it) }
        } ?: error("Tidak bisa membaca file")
    }

    fun list(): List<ApkEntry> = ZipFile(sourceFile).use { z ->
        z.entries().asSequence()
            .filter { !it.isDirectory }
            .map { ApkEntry(it.name, it.size, it.compressedSize) }
            .sortedBy { it.name }
            .toList()
    }

    fun read(name: String): ByteArray? = ZipFile(sourceFile).use { z ->
        z.getEntry(name)?.let { e -> z.getInputStream(e).use { it.readBytes() } }
    }

    /**
     * Bangun APK baru dari sumber + perubahan, lalu tanda tangani (v1+v2+v3)
     * dan salin hasilnya ke [out].
     */
    fun buildSigned(changes: Map<String, ByteArray>, deleted: Set<String>, out: OutputStream) {
        writeAligned(changes, deleted, stageFile)

        val signer = ApkSigner.Builder(listOf(loadSignerConfig()))
            .setInputApk(stageFile)
            .setOutputApk(signedFile)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
        signer.sign()

        signedFile.inputStream().use { it.copyTo(out) }
        stageFile.delete()
        signedFile.delete()
    }

    private fun loadSignerConfig(): ApkSigner.SignerConfig {
        val keyBytes = context.assets.open("signing_key.pk8").use { it.readBytes() }
        val key: PrivateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        val cert = context.assets.open("signing_cert.der").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        return ApkSigner.SignerConfig.Builder("apkeditor", key, listOf(cert)).build()
    }

    private fun isOldSignature(n: String): Boolean =
        n.startsWith("META-INF/") && n.count { it == '/' } == 1 &&
            (n == "META-INF/MANIFEST.MF" || n.endsWith(".SF") || n.endsWith(".RSA") ||
                n.endsWith(".DSA") || n.endsWith(".EC"))

    private fun writeAligned(changes: Map<String, ByteArray>, deleted: Set<String>, dest: File) {
        val counter = CountingOutputStream(BufferedOutputStream(FileOutputStream(dest)))
        val written = HashSet<String>()
        ZipOutputStream(counter).use { zos ->
            ZipFile(sourceFile).use { zf ->
                for (e in zf.entries()) {
                    val n = e.name
                    if (e.isDirectory || isOldSignature(n) || n in deleted) continue
                    val data = changes[n] ?: zf.getInputStream(e).use { it.readBytes() }
                    val stored = e.method == ZipEntry.STORED || n == "resources.arsc"
                    putEntry(zos, counter, n, data, stored)
                    written.add(n)
                }
            }
            for ((n, data) in changes) {
                if (n in written || n in deleted) continue
                putEntry(zos, counter, n, data, stored = n == "resources.arsc")
            }
        }
    }

    private fun putEntry(
        zos: ZipOutputStream,
        counter: CountingOutputStream,
        name: String,
        data: ByteArray,
        stored: Boolean,
    ) {
        val ze = ZipEntry(name)
        ze.time = System.currentTimeMillis()
        if (stored) {
            ze.method = ZipEntry.STORED
            ze.size = data.size.toLong()
            ze.compressedSize = data.size.toLong()
            ze.crc = CRC32().also { it.update(data) }.value

            // Entry STORED harus rata 4 byte (resources.arsc wajib untuk Android 11+),
            // library .so rata 4096 byte seperti zipalign.
            val align = if (name.endsWith(".so")) 4096 else 4
            val nameLen = name.toByteArray(Charsets.UTF_8).size
            val dataStart = counter.count + 30 + nameLen
            var pad = ((align - dataStart % align) % align).toInt()
            if (pad in 1..3) pad += align
            if (pad > 0) {
                val extra = ByteArray(pad)
                extra[0] = 0x35
                extra[1] = 0xD9.toByte()
                extra[2] = ((pad - 4) and 0xFF).toByte()
                extra[3] = (((pad - 4) shr 8) and 0xFF).toByte()
                ze.extra = extra
            }
        } else {
            ze.method = ZipEntry.DEFLATED
        }
        zos.putNextEntry(ze)
        zos.write(data)
        zos.closeEntry()
    }

    companion object {
        /** Mengembalikan isi sebagai teks UTF-8, atau null kalau file binary / terlalu besar. */
        fun decodeText(bytes: ByteArray, maxSize: Int = 1_000_000): String? {
            if (bytes.size > maxSize) return null
            // Binary XML Android (AndroidManifest.xml, res/*.xml di dalam APK)
            if (bytes.size >= 4 && bytes[0] == 0x03.toByte() && bytes[1] == 0.toByte() &&
                bytes[2] == 0x08.toByte() && bytes[3] == 0.toByte()
            ) return null
            val limit = minOf(bytes.size, 8000)
            for (i in 0 until limit) if (bytes[i] == 0.toByte()) return null
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (e: CharacterCodingException) {
                null
            }
        }

        fun cleanName(raw: String): String? {
            val n = raw.trim().replace('\\', '/').trimStart('/')
            if (n.isEmpty() || n.endsWith("/")) return null
            if (n.split('/').any { it == ".." || it.isEmpty() }) return null
            return n
        }
    }
}
