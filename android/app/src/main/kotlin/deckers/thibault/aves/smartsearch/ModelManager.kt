package deckers.thibault.aves.smartsearch

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.util.Log
import androidx.core.net.toUri
import deckers.thibault.aves.BuildConfig
import deckers.thibault.aves.utils.LogUtils
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// Installs model packs: downloads with the system `DownloadManager` (resumable, survives process death,
// optionally restricted to unmetered networks), verifies SHA-256 against the compiled-in catalog,
// then moves files to app private storage excluded from backups.
class ModelManager(private val context: Context) {
    private val downloadManager = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val rootDir: File get() = File(context.noBackupFilesDir, ROOT_DIR_NAME)

    fun modelDir(spec: ModelSpec) = File(rootDir, "models/${spec.id}")

    // removes files of models that are no longer in the catalog (e.g. replaced by another model)
    fun deleteObsolete() {
        val known = ModelCatalog.all.map { it.id }.toSet()
        File(rootDir, "models").listFiles()?.filter { it.name !in known }?.forEach { it.deleteRecursively() }
        // only complete index files, not temporary files of an ongoing compaction
        rootDir.listFiles()?.filter { f -> f.name.startsWith("index_") && f.name.endsWith(".bin") && known.none { f.name == "index_$it.bin" } }?.forEach { it.delete() }
        context.getExternalFilesDir(null)?.let { File(it, ROOT_DIR_NAME) }?.listFiles()?.filter { it.name !in known }?.forEach { it.deleteRecursively() }
    }

    private fun downloadDir(spec: ModelSpec) = File(context.getExternalFilesDir(null), "$ROOT_DIR_NAME/${spec.id}")

    // Each download attempt uses its own directory: the download provider registers downloaded files in the media store,
    // and stale entries at the same paths (e.g. from a previous installation) make it crash on some devices (Fire OS 8).
    private fun attemptDir(spec: ModelSpec): File {
        val attempt = prefs.getString(attemptKey(spec), null) ?: return downloadDir(spec)
        return File(downloadDir(spec), attempt)
    }

    private fun verifiedMarker(spec: ModelSpec) = File(modelDir(spec), VERIFIED_MARKER)

    fun isInstalled(spec: ModelSpec) = verifiedMarker(spec).exists() && spec.files.all { File(modelDir(spec), it.name).exists() }

    val canDownload: Boolean get() = ModelCatalog.MODEL_BASE_URL.isNotEmpty()

    private val installLock = ReentrantLock()

    // signature of sideloaded files that failed verification, to avoid hashing them again
    private val failedSideloadSignatures = HashMap<String, String>()

    private fun filesSignature(spec: ModelSpec): String {
        val dir = modelDir(spec)
        return spec.files.joinToString("|") { f -> File(dir, f.name).let { "${it.length()}:${it.lastModified()}" } }
    }

    // Files placed in the model directory by other means (development builds only) are verified before use.
    // Returns whether the pack is installed after verification.
    fun verifySideloaded(spec: ModelSpec): Boolean {
        if (isInstalled(spec)) return true
        if (!BuildConfig.DEBUG) return false
        val dir = modelDir(spec)
        if (!spec.files.all { File(dir, it.name).exists() }) return false
        // do not verify concurrently, nor while installing a download
        if (!installLock.tryLock()) return false
        try {
            if (isInstalled(spec)) return true
            val signature = filesSignature(spec)
            if (failedSideloadSignatures[spec.id] == signature) return false
            val valid = spec.files.all { f ->
                val file = File(dir, f.name)
                file.length() == f.size && sha256(file) == f.sha256
            }
            if (valid) {
                verifiedMarker(spec).createNewFile()
            } else {
                failedSideloadSignatures[spec.id] = signature
                Log.w(LOG_TAG, "sideloaded model=${spec.id} failed verification")
            }
            return valid
        } finally {
            installLock.unlock()
        }
    }

    fun startDownload(spec: ModelSpec, unmeteredOnly: Boolean): DownloadStartResult {
        if (!canDownload) return DownloadStartResult.UNAVAILABLE
        if (isInstalled(spec)) return DownloadStartResult.ALREADY_INSTALLED
        // files of a pack that is not verified are not trusted (e.g. partially copied)
        modelDir(spec).deleteRecursively()
        val missing = spec.files
        val needed = missing.sumOf { it.size } * 2
        if (!hasFreeSpace(context.noBackupFilesDir, needed) || context.getExternalFilesDir(null)?.let { hasFreeSpace(it, needed / 2) } != true) {
            return DownloadStartResult.NO_SPACE
        }
        cancelDownload(spec)
        prefs.edit().putString(attemptKey(spec), System.currentTimeMillis().toString()).apply()
        val dlDir = attemptDir(spec).apply { mkdirs() }
        val ids = missing.map { f ->
            val target = File(dlDir, f.name)
            target.delete()
            val request = DownloadManager.Request("${ModelCatalog.MODEL_BASE_URL}${spec.id}/${f.name}".toUri())
                .setDestinationUri(Uri.fromFile(target))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(!unmeteredOnly)
                .setAllowedOverRoaming(false)
            downloadManager.enqueue(request)
        }
        prefs.edit().putString(downloadKey(spec), ids.joinToString(",")).apply()
        return DownloadStartResult.STARTED
    }

    fun cancelDownload(spec: ModelSpec) {
        val ids = downloadIds(spec)
        if (ids.isNotEmpty()) downloadManager.remove(*ids)
        prefs.edit().remove(downloadKey(spec)).remove(attemptKey(spec)).apply()
        downloadDir(spec).deleteRecursively()
    }

    // Checks pending downloads, and installs completed files. Returns the download progress, if any.
    fun pollDownload(spec: ModelSpec): DownloadProgress? {
        val ids = downloadIds(spec)
        if (ids.isEmpty()) return null
        var downloaded = 0L
        var failed = false
        var allDone = true
        var waiting = false
        downloadManager.query(DownloadManager.Query().setFilterById(*ids)).use { cursor ->
            if (cursor.count < ids.size) failed = true
            val statusIdx = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val soFarIdx = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            while (cursor.moveToNext()) {
                when (cursor.getInt(statusIdx)) {
                    DownloadManager.STATUS_FAILED -> failed = true
                    DownloadManager.STATUS_SUCCESSFUL -> {}
                    // e.g. waiting for a network, for an unmetered network, or to retry
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED -> {
                        allDone = false
                        waiting = true
                    }

                    else -> allDone = false
                }
                downloaded += cursor.getLong(soFarIdx)
            }
        }
        val total = spec.totalSize
        if (failed) {
            cancelDownload(spec)
            return DownloadProgress(downloaded, total, DownloadState.FAILED)
        }
        if (!allDone) return DownloadProgress(downloaded, total, if (waiting) DownloadState.WAITING else DownloadState.RUNNING)

        // all files downloaded: verify and install them in the background, once
        if (installFailures.remove(spec.id)) return DownloadProgress(0, total, DownloadState.FAILED)
        if (installing.add(spec.id)) {
            installExecutor.execute {
                try {
                    install(spec)
                } finally {
                    installing.remove(spec.id)
                }
            }
        }
        return DownloadProgress(total, total, DownloadState.RUNNING)
    }

    private val installExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "smart-search-install") }
    private val installing: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val installFailures: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private fun install(spec: ModelSpec) {
        installLock.withLock {
            if (downloadIds(spec).isEmpty()) return
            val dlDir = attemptDir(spec)
            val downloaded = downloadedFiles(spec)
            val dir = modelDir(spec).apply { mkdirs() }
            try {
                for (f in spec.files) {
                    val source = downloaded[f.name] ?: File(dlDir, f.name)
                    val target = File(dir, f.name)
                    if (!source.exists() && target.exists()) {
                        // installed before an interruption
                        if (target.length() == f.size && sha256(target) == f.sha256) continue
                        throw IOException("installed file=${f.name} failed verification")
                    }
                    // Verify the copy in private storage, rather than the downloaded file which other apps
                    // may be able to modify (external app-specific storage before Android 10),
                    // and copy to a temporary file first, so that an interrupted copy is never mistaken for a complete file.
                    val temp = File(dir, "${f.name}.part")
                    val hash = copyWithSha256(source, temp)
                    if (temp.length() != f.size || hash != f.sha256) {
                        temp.delete()
                        throw IOException("downloaded file=${f.name} failed verification")
                    }
                    if (!temp.renameTo(target)) throw IOException("failed to install file=${f.name}")
                    source.delete()
                }
                verifiedMarker(spec).createNewFile()
                // also clears the completed downloads from the system list
                downloadManager.remove(*downloadIds(spec))
                prefs.edit().remove(downloadKey(spec)).remove(attemptKey(spec)).apply()
                downloadDir(spec).deleteRecursively()
            } catch (e: Exception) {
                // e.g. no space left, or a corrupted download
                Log.w(LOG_TAG, "failed to install model=${spec.id}", e)
                cancelDownload(spec)
                dir.deleteRecursively()
                installFailures.add(spec.id)
            }
        }
    }

    fun delete(spec: ModelSpec) {
        cancelDownload(spec)
        modelDir(spec).deleteRecursively()
    }

    private fun downloadIds(spec: ModelSpec): LongArray {
        val raw = prefs.getString(downloadKey(spec), null) ?: return LongArray(0)
        return raw.split(',').mapNotNull { it.toLongOrNull() }.toLongArray()
    }

    // Files as actually written by the download provider, which may rename them (e.g. `image (1).ort`).
    // Download IDs are stored in the order of the spec files.
    private fun downloadedFiles(spec: ModelSpec): Map<String, File> {
        val ids = downloadIds(spec)
        if (ids.size != spec.files.size) return emptyMap()
        val files = HashMap<String, File>()
        downloadManager.query(DownloadManager.Query().setFilterById(*ids)).use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val uriIdx = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
            while (cursor.moveToNext()) {
                val index = ids.indexOf(cursor.getLong(idIdx))
                val path = cursor.getString(uriIdx)?.toUri()?.path ?: continue
                if (index >= 0) files[spec.files[index].name] = File(path)
            }
        }
        return files
    }

    private fun downloadKey(spec: ModelSpec) = "download_${spec.id}"

    private fun attemptKey(spec: ModelSpec) = "download_attempt_${spec.id}"

    private fun hasFreeSpace(dir: File, bytes: Long): Boolean {
        return try {
            dir.mkdirs()
            StatFs(dir.path).availableBytes >= bytes
        } catch (e: IllegalArgumentException) {
            Log.w(LOG_TAG, "failed to get free space for dir=$dir", e)
            false
        }
    }

    companion object {
        private val LOG_TAG = LogUtils.createTag<ModelManager>()
        const val ROOT_DIR_NAME = "smart_search"
        private const val PREFS_NAME = "smart_search"
        private const val VERIFIED_MARKER = ".verified"

        // returns the SHA-256 of the copied bytes
        fun copyWithSha256(source: File, target: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered(1 shl 16).use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

enum class DownloadStartResult { STARTED, ALREADY_INSTALLED, UNAVAILABLE, NO_SPACE }

enum class DownloadState { RUNNING, WAITING, FAILED, INSTALLED }

class DownloadProgress(val bytes: Long, val total: Long, val state: DownloadState)
