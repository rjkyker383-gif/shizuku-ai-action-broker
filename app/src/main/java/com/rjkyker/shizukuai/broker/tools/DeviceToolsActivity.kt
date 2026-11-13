package com.rjkyker.shizukuai.broker.tools

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** SAF-scoped file tools. The owner chooses the tree; no broad storage permission or hidden path access. */
class DeviceToolsActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var listing: LinearLayout
    private var treeUri: Uri? = null
    private var currentDir: DocumentFile? = null
    private var pendingTransfer: DocumentFile? = null
    private var pendingMove = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(14)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(TextView(this).apply { text = "DEVICE STORAGE TOOLS"; textSize = 22f })
        root.addView(TextView(this).apply { text = "SAF-scoped access · you choose the folder · destructive actions require confirmation"; textSize = 12f })
        status = TextView(this).apply { text = "Choose a folder to begin."; setPadding(0, dp(8), 0, dp(8)) }
        root.addView(status)
        addButton("Choose storage folder") { chooseTree() }
        addButton("Parent folder") { currentDir?.parentFile?.let { currentDir = it; render() } ?: toast("Already at selected root") }
        addButton("Create folder") { createFolder() }
        addButton("Create ZIP of this folder") { createArchive(zip = true) }
        addButton("Create TAR of this folder") { createArchive(zip = false) }
        addButton("Refresh listing") { render() }
        addButton("Open Android Wi-Fi settings / saved networks") {
            runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }.onFailure { toast("Wi-Fi settings unavailable") }
        }
        addButton("Open app storage settings") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        listing = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, 0) }
        root.addView(listing)
        setContentView(ScrollView(this).apply { addView(root) })
        val saved = getPreferences(MODE_PRIVATE).getString("tree_uri", null)
        if (saved != null) runCatching {
            treeUri = Uri.parse(saved)
            currentDir = DocumentFile.fromTreeUri(this, treeUri!!)
            render()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun addButton(label: String, action: () -> Unit) {
        root.addView(Button(this).apply { text = label; setOnClickListener { action() } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun chooseTree() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        startActivityForResult(i, REQ_TREE)
    }

    @Deprecated("Activity result retained for compatibility with the platform SAF picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data?.data == null) return
        if (requestCode == REQ_TREE) {
            val uri = data.data!!
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
            treeUri = uri
            currentDir = DocumentFile.fromTreeUri(this, uri)
            getPreferences(MODE_PRIVATE).edit().putString("tree_uri", uri.toString()).apply()
            render()
        } else if (requestCode == REQ_DESTINATION) {
            val dest = DocumentFile.fromTreeUri(this, data.data!!)
            val source = pendingTransfer
            if (source != null && dest != null) {
                val ok = runCatching {
                    val copied = copyDocument(source, dest)
                    copied != null && (!pendingMove || source.delete())
                }.getOrDefault(false)
                toast(if (ok) "Transfer complete" else "Transfer failed; source kept when possible")
                pendingTransfer = null
                pendingMove = false
                render()
            }
    }

    private fun render() {
        listing.removeAllViews()
        val dir = currentDir
        if (dir == null || !dir.canRead()) { status.text = "No readable folder selected."; return }
        status.text = "Folder: ${dir.name ?: dir.uri.lastPathSegment}\nItems: ${runCatching { dir.listFiles().size }.getOrDefault(0)}"
        val children = runCatching { dir.listFiles().sortedWith(compareBy<DocumentFile>({ !it.isDirectory }, { it.name?.lowercase() ?: "" })) }.getOrDefault(emptyList())
        if (children.isEmpty()) listing.addView(TextView(this).apply { text = "(empty folder)" })
        for (doc in children) {
            val label = (if (doc.isDirectory) "📁  " else "📄  ") + (doc.name ?: "unnamed") + if (doc.isFile) "  ·  ${doc.length()} B" else ""
            listing.addView(Button(this).apply {
                text = label; gravity = Gravity.START
                setOnClickListener { if (doc.isDirectory) { currentDir = doc; render() } else fileMenu(doc) }
                setOnLongClickListener { fileMenu(doc); true }
            })
        }
    }

    private fun fileMenu(doc: DocumentFile) {
        val actions = if (doc.isDirectory) arrayOf("Open", "Rename", "Copy to folder", "Move to folder", "Delete") else
            arrayOf("Open", "Rename", "Copy to folder", "Move to folder", "Extract archive", "Delete")
        AlertDialog.Builder(this).setTitle(doc.name ?: "Item").setItems(actions) { _, which ->
            when (actions[which]) {
                "Open" -> if (doc.isDirectory) { currentDir = doc; render() } else if (isArchive(doc.name)) extractArchivePicker(doc)
                    else runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(doc.uri, contentResolver.getType(doc.uri) ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.onFailure { toast("No app can open this file") }
                "Rename" -> rename(doc)
                "Copy to folder" -> transfer(doc, false)
                "Move to folder" -> transfer(doc, true)
                "Extract archive" -> extractDocument(doc)
                "Delete" -> confirmDelete(doc)
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun isArchive(name: String?) = name?.endsWith(".zip", true) == true || name?.endsWith(".tar", true) == true
    private fun rename(doc: DocumentFile) {
        val input = EditText(this).apply { setText(doc.name ?: "") }
        AlertDialog.Builder(this).setTitle("Rename item").setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank() && !name.contains('/')) toast(if (doc.renameTo(name)) "Renamed" else "Rename failed")
                render()
            }.setNegativeButton("Cancel", null).show()
    }
    private fun confirmDelete(doc: DocumentFile) {
        AlertDialog.Builder(this).setTitle("Delete ${doc.name}?").setMessage("This permanently deletes the selected item and its contents.")
            .setPositiveButton("Delete") { _, _ -> toast(if (doc.delete()) "Deleted" else "Delete failed"); render() }
            .setNegativeButton("Cancel", null).show()
    }
    private fun createFolder() {
        val input = EditText(this).apply { hint = "Folder name" }
        AlertDialog.Builder(this).setTitle("New folder").setView(input).setPositiveButton("Create") { _, _ ->
            val name = input.text.toString().trim()
            if (name.isNotBlank()) toast(if (currentDir?.createDirectory(name) != null) "Folder created" else "Could not create folder")
            render()
        }.setNegativeButton("Cancel", null).show()
    }
    private fun transfer(doc: DocumentFile, move: Boolean) { pendingTransfer = doc; pendingMove = move; chooseDestination() }
    private fun chooseDestination() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(i, REQ_DESTINATION)
    }
    private fun copyDocument(source: DocumentFile, target: DocumentFile): DocumentFile? {
        if (source.isDirectory) {
            val folder = target.createDirectory(source.name ?: "copied-folder") ?: return null
            try {
                for (child in source.listFiles()) {
                    if (copyDocument(child, folder) == null) {
                        deleteTree(folder)
                        return null
                    }
                }
                return folder
            } catch (e: Exception) {
                deleteTree(folder)
                throw e
            }
        }
        val out = target.createFile(contentResolver.getType(source.uri) ?: "application/octet-stream", source.name ?: "copy") ?: return null
        val input = contentResolver.openInputStream(source.uri)
        if (input == null) { out.delete(); return null }
        val output = contentResolver.openOutputStream(out.uri)
        if (output == null) { input.close(); out.delete(); return null }
        try {
            input.use { src -> output.use { dst -> src.copyTo(dst) } }
            return out
        } catch (e: Exception) {
            out.delete()
            throw e
        }
    }
    private fun deleteTree(doc: DocumentFile) {
        if (doc.isDirectory) doc.listFiles().forEach { deleteTree(it) }
        doc.delete()
    }

    private fun createArchive(zip: Boolean) {
        val dir = currentDir ?: run { toast("Choose a folder first"); return }
        val name = (dir.name ?: "folder") + if (zip) ".zip" else ".tar"
        val finalName = if (dir.findFile(name) == null) name else name.removeSuffix(if (zip) ".zip" else ".tar") + "-${System.currentTimeMillis()}" + if (zip) ".zip" else ".tar"
        val archive = dir.createFile(if (zip) "application/zip" else "application/x-tar", finalName) ?: run { toast("Cannot create archive here"); return }
        runCatching {
            contentResolver.openOutputStream(archive.uri)?.use { raw ->
                if (zip) ZipOutputStream(BufferedOutputStream(raw)).use { out -> zipTree(dir, out, archive.uri) }
                else TarArchiveOutputStream(BufferedOutputStream(raw)).use { out ->
                    out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    tarTree(dir, out, archive.uri)
                }
            } ?: error("No output stream")
        }.onSuccess { toast("Created $finalName") }.onFailure { archive.delete(); toast("Archive failed: ${it.message}") }
        render()
    }
    private fun zipTree(node: DocumentFile, out: ZipOutputStream, archiveUri: Uri, prefix: String = "") {
        for (child in node.listFiles()) {
            if (child.uri == archiveUri) continue
            val rel = listOf(prefix, child.name ?: "item").filter { it.isNotBlank() }.joinToString("/")
            if (child.isDirectory) { out.putNextEntry(ZipEntry("$rel/")); out.closeEntry(); zipTree(child, out, archiveUri, rel) }
            else {
                out.putNextEntry(ZipEntry(rel))
                contentResolver.openInputStream(child.uri)?.use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }
    private fun tarTree(node: DocumentFile, out: TarArchiveOutputStream, archiveUri: Uri, prefix: String = "") {
        for (child in node.listFiles()) {
            if (child.uri == archiveUri) continue
            val rel = listOf(prefix, child.name ?: "item").filter { it.isNotBlank() }.joinToString("/")
            if (child.isDirectory) {
                val e = TarArchiveEntry("$rel/"); e.size = 0; out.putArchiveEntry(e); out.closeArchiveEntry(); tarTree(child, out, archiveUri, rel)
            } else {
                val e = TarArchiveEntry(rel); e.size = child.length().coerceAtLeast(0); out.putArchiveEntry(e)
                contentResolver.openInputStream(child.uri)?.use { it.copyTo(out) }
                out.closeArchiveEntry()
            }
        }
    }

    private fun extractArchivePicker(doc: DocumentFile) { extractDocument(doc) }
    private fun extractDocument(doc: DocumentFile) {
        val target = currentDir ?: return
        AlertDialog.Builder(this).setTitle("Extract ${doc.name}?")
            .setMessage("Files will be created in the current folder. Existing same-name files are skipped; unsafe paths are rejected.")
            .setPositiveButton("Extract") { _, _ ->
                runCatching {
                    contentResolver.openInputStream(doc.uri)?.use { input -> if (doc.name?.endsWith(".zip", true) == true) extractZip(input, target) else extractTar(input, target) }
                        ?: error("Cannot read archive")
                }.onSuccess { toast("Extraction finished") }.onFailure { toast("Extraction failed: ${it.message}") }
                render()
            }.setNegativeButton("Cancel", null).show()
    }
    private fun safeTarget(rootDir: DocumentFile, path: String): DocumentFile? {
        val clean = path.replace('\\', '/').trimStart('/')
        if (clean.isBlank() || clean.split('/').any { it == ".." || it.isBlank() }) return null
        var dir = rootDir
        val parts = clean.split('/')
        for (part in parts.dropLast(1)) dir = dir.findFile(part) ?: dir.createDirectory(part) ?: return null
        if (dir.findFile(parts.last()) != null) return null
        return dir.createFile("application/octet-stream", parts.last())
    }
    private fun extractZip(input: java.io.InputStream, target: DocumentFile) {
        var total = 0L
        var count = 0
        ZipInputStream(BufferedInputStream(input)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (++count > MAX_ARCHIVE_ENTRIES) error("Archive contains too many entries")
                val clean = e.name.replace('\\', '/')
                if (clean.startsWith('/') || clean.split('/').any { it == ".." }) { zin.closeEntry(); continue }
                if (e.isDirectory) {
                    var d = target
                    clean.trimEnd('/').split('/').filter { it.isNotBlank() }.forEach { d = d.findFile(it) ?: d.createDirectory(it) ?: d }
                } else safeTarget(target, clean)?.let { file -> contentResolver.openOutputStream(file.uri)?.use { total += copyLimited(zin, it, MAX_EXTRACTED_BYTES - total) } }
                zin.closeEntry()
            }
        }
    }
    private fun extractTar(input: java.io.InputStream, target: DocumentFile) {
        var total = 0L
        var count = 0
        TarArchiveInputStream(BufferedInputStream(input)).use { tin ->
            while (true) {
                val e = tin.nextTarEntry ?: break
                if (++count > MAX_ARCHIVE_ENTRIES) error("Archive contains too many entries")
                val clean = e.name.replace('\\', '/')
                if (clean.startsWith('/') || clean.split('/').any { it == ".." }) continue
                if (e.isDirectory) {
                    var d = target
                    clean.trimEnd('/').split('/').filter { it.isNotBlank() }.forEach { d = d.findFile(it) ?: d.createDirectory(it) ?: d }
                } else safeTarget(target, clean)?.let { file -> contentResolver.openOutputStream(file.uri)?.use { total += copyLimited(tin, it, MAX_EXTRACTED_BYTES - total) } }
            }
        }
    }
    private fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream, remaining: Long): Long {
        val buffer = ByteArray(32 * 1024)
        var copied = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (copied + n > remaining) error("Extraction limit exceeded (512 MiB)")
            output.write(buffer, 0, n)
            copied += n
        }
        return copied
    }

    companion object {
        private const val REQ_TREE = 3101
        private const val REQ_DESTINATION = 3102
        private const val MAX_ARCHIVE_ENTRIES = 5_000
        private const val MAX_EXTRACTED_BYTES = 512L * 1024L * 1024L
    }
}
