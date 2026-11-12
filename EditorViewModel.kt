package com.example.apkeditor

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Status { ORIGINAL, MODIFIED, NEW, DELETED }

data class FileRow(val name: String, val size: Long, val status: Status)

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = ApkEngine(app)

    var apkName by mutableStateOf<String?>(null)
        private set
    private var entries by mutableStateOf<List<ApkEntry>>(emptyList())
    private val changed = mutableStateMapOf<String, ByteArray>()
    private val deleted = mutableStateListOf<String>()

    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)

    /** Entry yang sedang diedit sebagai teks. */
    var editingName by mutableStateOf<String?>(null)
        private set
    var editorInitial by mutableStateOf("")
        private set

    /** Entry yang dipilih untuk menu aksi / diganti / diekstrak. */
    var selected by mutableStateOf<String?>(null)

    /** File yang dipilih untuk ditambahkan, menunggu nama path di dalam APK. */
    var addDraft by mutableStateOf<Pair<Uri, String>?>(null)

    val changeCount: Int get() = changed.size + deleted.size

    fun rows(query: String): List<FileRow> {
        val base = entries.map { e ->
            val status = when {
                e.name in deleted -> Status.DELETED
                changed.containsKey(e.name) -> Status.MODIFIED
                else -> Status.ORIGINAL
            }
            FileRow(e.name, changed[e.name]?.size?.toLong() ?: e.size, status)
        }
        val baseNames = entries.map { it.name }.toSet()
        val added = changed.filterKeys { it !in baseNames }
            .map { (n, d) -> FileRow(n, d.size.toLong(), Status.NEW) }
        val q = query.trim()
        return (base + added)
            .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
            .sortedBy { it.name }
    }

    fun isBaseEntry(name: String) = entries.any { it.name == name }

    // ---------- Buka APK ----------

    fun open(uri: Uri) {
        viewModelScope.launch {
            busy = true
            try {
                val (name, list) = withContext(Dispatchers.IO) {
                    val n = engine.displayName(uri)
                    engine.copyToSource(uri)
                    n to engine.list()
                }
                changed.clear()
                deleted.clear()
                entries = list
                apkName = name
                editingName = null
            } catch (e: Exception) {
                message = "Gagal membuka APK: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun closeApk() {
        apkName = null
        entries = emptyList()
        changed.clear()
        deleted.clear()
    }

    // ---------- Edit teks ----------

    fun startEdit(name: String) {
        val cached = changed[name]
        viewModelScope.launch {
            busy = true
            try {
                val bytes = cached ?: withContext(Dispatchers.IO) { engine.read(name) }
                val text = bytes?.let { ApkEngine.decodeText(it) }
                if (text == null) {
                    message = "File ini bukan teks biasa (binary atau terlalu besar), " +
                        "jadi tidak bisa diedit. Gunakan \"Ganti file\"."
                } else {
                    editorInitial = text
                    editingName = name
                }
            } catch (e: Exception) {
                message = "Gagal membaca file: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun saveEdit(text: String) {
        val name = editingName ?: return
        changed[name] = text.toByteArray(Charsets.UTF_8)
        deleted.remove(name)
        editingName = null
        message = "Perubahan $name disimpan (belum ditulis ke APK)"
    }

    fun cancelEdit() {
        editingName = null
    }

    // ---------- Ganti / tambah / hapus ----------

    fun replaceWith(name: String, uri: Uri) {
        viewModelScope.launch {
            busy = true
            try {
                val bytes = withContext(Dispatchers.IO) { engine.readUri(uri) }
                changed[name] = bytes
                deleted.remove(name)
                message = "$name diganti (${bytes.size} byte)"
            } catch (e: Exception) {
                message = "Gagal mengganti file: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun stageAdd(uri: Uri) {
        viewModelScope.launch {
            val display = withContext(Dispatchers.IO) { engine.displayName(uri) }
            addDraft = uri to "assets/$display"
        }
    }

    fun confirmAdd(rawName: String) {
        val draft = addDraft ?: return
        val name = ApkEngine.cleanName(rawName)
        if (name == null) {
            message = "Nama path tidak valid"
            return
        }
        addDraft = null
        viewModelScope.launch {
            busy = true
            try {
                val bytes = withContext(Dispatchers.IO) { engine.readUri(draft.first) }
                changed[name] = bytes
                deleted.remove(name)
                message = "$name ditambahkan"
            } catch (e: Exception) {
                message = "Gagal menambah file: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun delete(name: String) {
        changed.remove(name)
        if (isBaseEntry(name) && name !in deleted) deleted.add(name)
    }

    fun restore(name: String) {
        changed.remove(name)
        deleted.remove(name)
    }

    // ---------- Ekstrak ----------

    fun exportTo(name: String, uri: Uri) {
        val cached = changed[name]
        viewModelScope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val bytes = cached ?: engine.read(name) ?: error("Entry tidak ditemukan")
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                        it.write(bytes)
                    } ?: error("Tidak bisa menulis file")
                }
                message = "$name diekstrak"
            } catch (e: Exception) {
                message = "Gagal mengekstrak: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    // ---------- Cari & ganti di semua file teks ----------

    fun replaceAll(find: String, replacement: String) {
        if (find.isEmpty()) return
        val changedCopy = changed.toMap()
        val deletedCopy = deleted.toSet()
        val baseNames = entries.map { it.name }
        viewModelScope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) {
                    val out = HashMap<String, ByteArray>()
                    val names = (baseNames + changedCopy.keys).distinct().filter { it !in deletedCopy }
                    for (n in names) {
                        val bytes = changedCopy[n] ?: engine.read(n) ?: continue
                        val text = ApkEngine.decodeText(bytes) ?: continue
                        if (text.contains(find)) {
                            out[n] = text.replace(find, replacement).toByteArray(Charsets.UTF_8)
                        }
                    }
                    out
                }
                changed.putAll(result)
                message = if (result.isEmpty()) "Teks tidak ditemukan di file teks mana pun"
                else "Diganti di ${result.size} file"
            } catch (e: Exception) {
                message = "Gagal mencari/mengganti: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    // ---------- Simpan APK baru ----------

    fun buildTo(uri: Uri) {
        val changedCopy = changed.toMap()
        val deletedCopy = deleted.toSet()
        viewModelScope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val os = getApplication<Application>().contentResolver.openOutputStream(uri)
                        ?: error("Tidak bisa menulis file tujuan")
                    os.use { engine.buildSigned(changedCopy, deletedCopy, it) }
                }
                message = "APK baru tersimpan dan sudah ditandatangani"
            } catch (e: Exception) {
                message = "Gagal membangun APK: ${e.message}"
            } finally {
                busy = false
            }
        }
    }
}
