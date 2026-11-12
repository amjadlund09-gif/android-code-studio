package com.example.mynative.androidide

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Shows the app's home folder (projects, SDK and configuration) in the system file picker,
 * so other apps and the Files app can browse, open and edit those files.
 */
class AppDocumentsProvider : DocumentsProvider() {

    private lateinit var base: File

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        base = SdkRepository.homeDir(ctx).apply {
            mkdirs()
            File(this, "AndroidIDEProjects").mkdirs()
        }
        return true
    }

    override fun queryRoots(projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_PROJECTION)
        val ctx = context ?: return cursor
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, idOf(base))
            add(Root.COLUMN_TITLE, ctx.getString(R.string.app_name) + " Home")
            add(Root.COLUMN_SUMMARY, "Project files and configuration")
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(Root.COLUMN_AVAILABLE_BYTES, base.usableSpace)
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY
            )
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOC_PROJECTION)
        addRow(cursor, fileOf(documentId))
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String, projection: Array<String>?, sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DOC_PROJECTION)
        fileOf(parentDocumentId).listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?.forEach { addRow(cursor, it) }
        return cursor
    }

    override fun openDocument(
        documentId: String, mode: String, signal: CancellationSignal?
    ): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileOf(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileOf(parentDocumentId)
        var file = File(parent, displayName)
        var n = 1
        while (file.exists()) {
            file = File(parent, "${file.nameWithoutExtension} ($n)" +
                if (file.extension.isEmpty()) "" else ".${file.extension}")
            n++
        }
        val ok = if (mimeType == Document.MIME_TYPE_DIR) file.mkdir() else file.createNewFile()
        if (!ok) throw FileNotFoundException("Failed to create ${file.path}")
        notifyChildren(parentDocumentId)
        return idOf(file)
    }

    override fun deleteDocument(documentId: String) {
        val file = fileOf(documentId)
        if (file == base) throw SecurityException("Cannot delete the root folder")
        if (!file.deleteRecursively()) throw FileNotFoundException("Failed to delete ${file.path}")
        file.parentFile?.let { notifyChildren(idOf(it)) }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = fileOf(documentId)
        if (file == base) throw SecurityException("Cannot rename the root folder")
        val target = File(file.parentFile, displayName)
        if (!file.renameTo(target)) throw FileNotFoundException("Failed to rename ${file.path}")
        file.parentFile?.let { notifyChildren(idOf(it)) }
        return idOf(target)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        runCatching {
            fileOf(documentId).canonicalPath.startsWith(fileOf(parentDocumentId).canonicalPath)
        }.getOrDefault(false)

    override fun getDocumentType(documentId: String): String = mimeOf(fileOf(documentId))

    // ---- helpers -----------------------------------------------------------------------

    private fun idOf(file: File) = file.absolutePath

    /** Only paths inside the home folder are allowed. */
    private fun fileOf(documentId: String): File {
        val file = File(documentId)
        val root = base.canonicalPath
        val path = file.canonicalPath
        if (path != root && !path.startsWith("$root/")) {
            throw FileNotFoundException("Outside of home folder: $documentId")
        }
        if (!file.exists()) throw FileNotFoundException("Missing: $documentId")
        return file
    }

    private fun mimeOf(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun addRow(cursor: MatrixCursor, file: File) {
        var flags = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        flags = if (file.isDirectory) flags or Document.FLAG_DIR_SUPPORTS_CREATE
        else flags or Document.FLAG_SUPPORTS_WRITE
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, idOf(file))
            add(Document.COLUMN_DISPLAY_NAME, if (file == base) "Home" else file.name)
            add(Document.COLUMN_SIZE, file.length())
            add(Document.COLUMN_MIME_TYPE, mimeOf(file))
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun notifyChildren(parentId: String) {
        val ctx = context ?: return
        ctx.contentResolver.notifyChange(
            android.provider.DocumentsContract.buildChildDocumentsUri(
                "${ctx.packageName}.documents", parentId
            ), null
        )
    }

    companion object {
        private const val ROOT_ID = "home"
        private val ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_MIME_TYPES, Root.COLUMN_FLAGS, Root.COLUMN_ICON,
            Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES
        )
        private val DOC_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE
        )
    }
}
