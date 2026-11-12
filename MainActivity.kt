package com.example.apkeditor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                Surface(modifier = Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: EditorViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }

    // ----- Pemilih file -----
    val openApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.open(uri)
    }
    val replacePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = vm.selected
        if (uri != null && target != null) vm.replaceWith(target, uri)
    }
    val addPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.stageAdd(uri)
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val target = vm.selected
        if (uri != null && target != null) vm.exportTo(target, uri)
    }
    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri ->
        if (uri != null) vm.buildTo(uri)
    }

    var query by remember { mutableStateOf("") }
    var showReplaceAll by remember { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }

    BackHandler(enabled = vm.editingName != null) { vm.cancelEdit() }
    BackHandler(enabled = vm.editingName == null && vm.apkName != null) { vm.closeApk() }

    // ----- Editor teks -----
    val editing = vm.editingName
    if (editing != null) {
        TextEditorScreen(vm, editing, snackbar)
        return
    }

    // ----- Beranda -----
    if (vm.apkName == null) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
            Column(
                modifier = Modifier.fillMaxSize().padding(pad).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("APK Editor", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Buka file APK, edit teks, ganti atau hapus file di dalamnya, " +
                        "lalu simpan sebagai APK baru yang sudah ditandatangani.",
                    modifier = Modifier.padding(vertical = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { openApk.launch(arrayOf("*/*")) }, enabled = !vm.busy) {
                    Text("Pilih APK")
                }
                if (vm.busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 24.dp))
                }
            }
        }
        return
    }

    // ----- Daftar isi APK -----
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(vm.apkName ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = { TextButton(onClick = { vm.closeApk() }) { Text("Tutup") } },
                actions = {
                    TextButton(onClick = { addPicker.launch(arrayOf("*/*")) }) { Text("Tambah") }
                    TextButton(onClick = { showReplaceAll = true }) { Text("Ganti teks") }
                    TextButton(
                        onClick = { savePicker.launch("edited.apk") },
                        enabled = !vm.busy,
                    ) { Text("Simpan") }
                },
            )
        },
    ) { pad ->
        Column(modifier = Modifier.fillMaxSize().padding(pad)) {
            if (vm.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Cari file") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Text(
                "${vm.changeCount} perubahan menunggu disimpan",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            val rows = vm.rows(query)
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(rows, key = { it.name }) { row ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.selected = row.name
                                showActions = true
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(row.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val label = when (row.status) {
                            Status.ORIGINAL -> ""
                            Status.MODIFIED -> " • diubah"
                            Status.NEW -> " • baru"
                            Status.DELETED -> " • akan dihapus"
                        }
                        Text(
                            formatSize(row.size) + label,
                            style = MaterialTheme.typography.bodySmall,
                            color = when (row.status) {
                                Status.DELETED -> MaterialTheme.colorScheme.error
                                Status.ORIGINAL -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    // ----- Menu aksi untuk file terpilih -----
    val sel = vm.selected
    if (showActions && sel != null) {
        val isDeleted = vm.rows("").firstOrNull { it.name == sel }?.status == Status.DELETED
        AlertDialog(
            onDismissRequest = { showActions = false },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showActions = false }) { Text("Tutup") } },
            title = { Text(sel, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 16.sp) },
            text = {
                Column {
                    if (!isDeleted) {
                        TextButton(onClick = { showActions = false; vm.startEdit(sel) }) {
                            Text("Edit sebagai teks")
                        }
                        TextButton(onClick = { showActions = false; replacePicker.launch(arrayOf("*/*")) }) {
                            Text("Ganti file")
                        }
                        TextButton(onClick = { showActions = false; exportPicker.launch(sel.substringAfterLast('/')) }) {
                            Text("Ekstrak")
                        }
                        TextButton(onClick = { showActions = false; vm.delete(sel) }) {
                            Text("Hapus")
                        }
                    }
                    TextButton(onClick = { showActions = false; vm.restore(sel) }) {
                        Text("Batalkan perubahan file ini")
                    }
                }
            },
        )
    }

    // ----- Dialog tambah file -----
    val draft = vm.addDraft
    if (draft != null) {
        var path by remember(draft) { mutableStateOf(draft.second) }
        AlertDialog(
            onDismissRequest = { vm.addDraft = null },
            title = { Text("Letakkan di dalam APK") },
            text = {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("Path file di APK") },
                    singleLine = true,
                )
            },
            confirmButton = { TextButton(onClick = { vm.confirmAdd(path) }) { Text("Tambah") } },
            dismissButton = { TextButton(onClick = { vm.addDraft = null }) { Text("Batal") } },
        )
    }

    // ----- Dialog cari & ganti -----
    if (showReplaceAll) {
        var find by remember { mutableStateOf("") }
        var repl by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showReplaceAll = false },
            title = { Text("Cari & ganti di semua file teks") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = find, onValueChange = { find = it }, label = { Text("Cari") })
                    OutlinedTextField(value = repl, onValueChange = { repl = it }, label = { Text("Ganti dengan") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showReplaceAll = false
                    vm.replaceAll(find, repl)
                }) { Text("Ganti semua") }
            },
            dismissButton = { TextButton(onClick = { showReplaceAll = false }) { Text("Batal") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(vm: EditorViewModel, name: String, snackbar: SnackbarHostState) {
    var text by remember(name) { mutableStateOf(vm.editorInitial) }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp) },
                navigationIcon = { TextButton(onClick = { vm.cancelEdit() }) { Text("Batal") } },
                actions = { TextButton(onClick = { vm.saveEdit(text) }) { Text("Simpan") } },
            )
        },
    ) { pad ->
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxSize().padding(pad).padding(8.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
        )
    }
}

private fun formatSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> String.format("%.1f KB", b / 1024.0)
    else -> String.format("%.2f MB", b / 1024.0 / 1024.0)
}
