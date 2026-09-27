package deckers.thibault.aves.smartsearch

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import deckers.thibault.aves.utils.LogUtils
import java.io.File
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.withLock
import kotlin.concurrent.write

// Process-wide smart search engine, shared by the UI and the background Flutter engines.
//
// Locking:
// - each store has a use lock: operations hold it for reading, closing and deleting hold it for writing,
// - each store also has its own internal read/write lock for its data,
// - each encoder has its own lock, so queries are never blocked by image inference,
// - inference runs outside store locks.
// Encoders are loaded lazily, released after some idle time, and released on memory trim.
object SmartSearchEngine {
    private val LOG_TAG = LogUtils.createTag<SmartSearchEngine>()

    private const val PREFS_NAME = "smart_search"
    private const val PREF_ACTIVE_MODEL = "active_model"
    private const val PREF_PENDING_MODEL = "pending_model"
    private const val IMAGE_IDLE_RELEASE_MS = 20_000L
    private const val TEXT_IDLE_RELEASE_MS = 60_000L
    private const val STORE_IDLE_CLOSE_MS = 5 * 60_000L
    private const val QUERY_YIELD_MS = 50L
    private const val MIN_RESULTS = 12
    private const val SIMILAR_FLOOR_SCORE = .4f
    private const val TEXT_MIN_Z = 2.5f
    private const val TEXT_FILL_Z = 1.5f
    private const val MIN_STATISTICS_COUNT = 50

    // an indexing lease not renewed for this long is considered abandoned (e.g. its Flutter engine was destroyed)
    private const val LEASE_TIMEOUT_MS = 3 * 60_000L

    private lateinit var appContext: Context
    private lateinit var modelManager: ModelManager
    private val initialized = AtomicBoolean(false)
    private val scheduler = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "smart-search-idle").apply { isDaemon = true } }.apply {
        // idle release tasks are rescheduled for every item and store access
        removeOnCancelPolicy = true
    }

    private class StoreHolder(val store: VectorStore) {
        val useLock = ReentrantReadWriteLock()
        var closeTask: ScheduledFuture<*>? = null
    }

    private val storesLock = ReentrantLock()
    private val stores = HashMap<String, StoreHolder>()

    private val imageLock = ReentrantLock()
    private var imageEncoder: ImageEncoder? = null
    private var imageEncoderSpecId: String? = null
    private var imageReleaseTask: ScheduledFuture<*>? = null
    private val bitmapLoader by lazy { MediaBitmapLoader(appContext) }

    private val textLock = ReentrantLock()
    private var textEncoder: TextEncoder? = null
    private var textEncoderSpecId: String? = null
    private var textReleaseTask: ScheduledFuture<*>? = null

    private val leaseLock = Any()
    private var leaseOwner = 0L
    private var leaseRenewedAt = 0L

    private val queriesInFlight = AtomicInteger(0)

    // moving average of the embedding time per item, for ETA
    @Volatile
    var averageEmbedMillis: Long = 0
        private set

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        modelManager = ModelManager(appContext)
        Thread { modelManager.deleteObsolete() }.start()
        appContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                @Suppress("DEPRECATION")
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                    releaseEncoders(force = false)
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) {}

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = releaseEncoders(force = false)
        })
    }

    val models: ModelManager get() = modelManager

    private val prefs get() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var activeSpec: ModelSpec?
        get() = ModelCatalog.byId(prefs.getString(PREF_ACTIVE_MODEL, null))
        set(value) {
            prefs.edit().putString(PREF_ACTIVE_MODEL, value?.id).apply()
            releaseEncoders(force = true)
        }

    // a model chosen to replace the active one, which becomes active once installed,
    // so that search keeps working with the previous model in the meantime
    var pendingSpec: ModelSpec?
        get() = ModelCatalog.byId(prefs.getString(PREF_PENDING_MODEL, null))
        private set(value) = prefs.edit().putString(PREF_PENDING_MODEL, value?.id).apply()

    // model selection is changed by calls from any Flutter engine, on any thread
    private val selectionLock = Any()

    fun selectModel(spec: ModelSpec?) = synchronized(selectionLock) {
        if (spec == null || modelManager.isInstalled(spec) || activeSpec == null) {
            pendingSpec = null
            activeSpec = spec
        } else {
            pendingSpec = spec
        }
    }

    fun clearPending(spec: ModelSpec) = synchronized(selectionLock) {
        if (pendingSpec?.id == spec.id) pendingSpec = null
    }

    fun promotePendingIfInstalled() = synchronized(selectionLock) {
        val pending = pendingSpec ?: return
        if (modelManager.isInstalled(pending)) {
            pendingSpec = null
            activeSpec = pending
        }
    }

    // the active model, if it is installed and supported on this device
    fun readySpec(): ModelSpec? {
        val spec = activeSpec ?: return null
        if (!ResourceGate.isSupported(appContext, spec)) return null
        if (!modelManager.isInstalled(spec) && !modelManager.verifySideloaded(spec)) return null
        return spec
    }

    // stores

    private fun indexFile(spec: ModelSpec) = File(modelManager.rootDir, "index_${spec.id}.bin")

    private fun holder(spec: ModelSpec): StoreHolder = storesLock.withLock {
        stores.getOrPut(spec.id) { StoreHolder(VectorStore(indexFile(spec), spec.dim, spec.key)) }
    }

    fun <T> withStore(spec: ModelSpec, block: (VectorStore) -> T): T {
        val holder = holder(spec)
        holder.useLock.read {
            holder.store.open()
            scheduleStoreClose(holder)
            return block(holder.store)
        }
    }

    private fun scheduleStoreClose(holder: StoreHolder) {
        storesLock.withLock {
            holder.closeTask?.cancel(false)
            holder.closeTask = scheduler.schedule({ closeStore(holder) }, STORE_IDLE_CLOSE_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun closeStore(holder: StoreHolder) {
        // do not block the scheduler while the store is in use: try again later
        if (!holder.useLock.writeLock().tryLock()) {
            scheduleStoreClose(holder)
            return
        }
        try {
            // the same instance is kept, so that its removal bookkeeping survives reopening
            holder.store.close()
        } finally {
            holder.useLock.writeLock().unlock()
        }
    }

    fun hasIndex(spec: ModelSpec) = indexFile(spec).exists()

    fun deleteIndex(spec: ModelSpec) {
        val holder = holder(spec)
        holder.useLock.write { holder.store.deleteFile() }
    }

    fun deleteModel(spec: ModelSpec) {
        clearPending(spec)
        releaseEncoders(force = true)
        deleteIndex(spec)
        modelManager.delete(spec)
    }

    // indexing lease, so that only one Flutter engine indexes at a time

    fun tryAcquireIndexing(owner: Long): Boolean = synchronized(leaseLock) {
        val now = SystemClock.elapsedRealtime()
        if (leaseOwner == 0L || leaseOwner == owner || now - leaseRenewedAt > LEASE_TIMEOUT_MS) {
            leaseOwner = owner
            leaseRenewedAt = now
            true
        } else {
            false
        }
    }

    // returns whether `owner` still holds the lease (e.g. it may have expired while the device was asleep)
    private fun renewIndexing(owner: Long): Boolean = synchronized(leaseLock) {
        if (leaseOwner != owner) return false
        leaseRenewedAt = SystemClock.elapsedRealtime()
        true
    }

    fun releaseIndexing(owner: Long) {
        synchronized(leaseLock) {
            if (leaseOwner != owner) return
            leaseOwner = 0L
        }
        pruneRemovalsIfIdle()
    }

    // removal bookkeeping is only needed while embedding batches are in flight
    private val batchesInFlight = AtomicInteger(0)

    private fun pruneRemovalsIfIdle() {
        if (batchesInFlight.get() > 0) return
        val holders = storesLock.withLock { stores.values.toList() }
        holders.forEach { it.store.pruneRemovals() }
    }

    val isQueryPending: Boolean get() = queriesInFlight.get() > 0

    // Embeds the given items and appends them to the store.
    // Items that cannot be decoded are recorded as failed, items that are unavailable are skipped.
    // Encoder errors are thrown, after storing the items embedded so far.
    // Returns the number of items stored.
    // Throws `LeaseLostException` when `owner` does not hold the indexing lease.
    fun embedBatch(spec: ModelSpec, owner: Long, items: List<EmbedItem>, startGeneration: Long, threads: Int, background: Boolean): Int {
        if (!renewIndexing(owner)) throw LeaseLostException()
        // with a single inference thread, ORT runs on the calling thread, which we can deprioritize
        val tid = Process.myTid()
        val previousPriority = Process.getThreadPriority(tid)
        if (background) Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val records = ArrayList<EmbeddingRecord>(items.size)
        batchesInFlight.incrementAndGet()
        try {
            for (item in items) {
                // give way to interactive queries
                while (queriesInFlight.get() > 0) Thread.sleep(QUERY_YIELD_MS)
                // another engine may have taken over, in which case it will index the remaining items
                if (!renewIndexing(owner)) break
                val start = SystemClock.elapsedRealtime()
                when (val result = embedItem(spec, item, threads)) {
                    is EmbedResult.Embedded -> {
                        val (q, scale) = VectorStore.quantize(result.vector)
                        records.add(EmbeddingRecord(item.id, item.fingerprint, q, scale))
                    }

                    EmbedResult.Undecodable -> records.add(EmbeddingRecord(item.id, item.fingerprint, null, 0f))
                    EmbedResult.Unavailable -> {}
                }
                val elapsed = SystemClock.elapsedRealtime() - start
                averageEmbedMillis = if (averageEmbedMillis == 0L) elapsed else (averageEmbedMillis * 7 + elapsed) / 8
            }
        } finally {
            // the model may have been deleted meanwhile, in which case its index must not be recreated
            if (records.isNotEmpty() && modelManager.isInstalled(spec)) {
                withStore(spec) { it.append(records, startGeneration) }
            }
            if (background) Process.setThreadPriority(tid, previousPriority)
            batchesInFlight.decrementAndGet()
            if (synchronized(leaseLock) { leaseOwner == 0L }) pruneRemovalsIfIdle()
        }
        return records.size
    }

    class LeaseLostException : Exception("indexing lease is held by another owner")

    private sealed class EmbedResult {
        class Embedded(val vector: FloatArray) : EmbedResult()
        object Undecodable : EmbedResult()
        object Unavailable : EmbedResult()
    }

    // throws on encoder errors, which are not specific to the item
    private fun embedItem(spec: ModelSpec, item: EmbedItem, threads: Int): EmbedResult {
        val loaded = when (val result = bitmapLoader.load(item.uri.toUri(), item.mimeType, item.rotationDegrees, item.isFlipped, spec.imageSize)) {
            is MediaBitmapLoader.LoadResult.Loaded -> result.value
            MediaBitmapLoader.LoadResult.Undecodable -> return EmbedResult.Undecodable
            MediaBitmapLoader.LoadResult.Unavailable -> return EmbedResult.Unavailable
        }
        try {
            val vector = imageLock.withLock {
                val encoder = obtainImageEncoderLocked(spec, threads)
                encoder.preprocessor.process(loaded.bitmap, loaded.rotationDegrees, loaded.flipped, encoder.input())
                encoder.encode()
            }
            // e.g. an input the quantized model does not handle
            if (vector.any { !it.isFinite() }) return EmbedResult.Undecodable
            return EmbedResult.Embedded(normalize(vector))
        } finally {
            loaded.bitmap.recycle()
        }
    }

    // `threads` only applies when creating the encoder: recreating it for another thread count is too costly
    private fun obtainImageEncoderLocked(spec: ModelSpec, threads: Int): ImageEncoder {
        imageReleaseTask?.cancel(false)
        imageReleaseTask = scheduler.schedule({ releaseImageEncoder(force = false) }, IMAGE_IDLE_RELEASE_MS, TimeUnit.MILLISECONDS)
        imageEncoder?.let {
            if (imageEncoderSpecId == spec.id) return it
            it.close()
            imageEncoder = null
        }
        val encoder = ImageEncoder(spec, modelManager.modelDir(spec), threads, ResourceGate.isLowMemoryDevice(appContext))
        imageEncoder = encoder
        imageEncoderSpecId = spec.id
        return encoder
    }

    private fun obtainTextEncoderLocked(spec: ModelSpec, threads: Int): TextEncoder {
        textReleaseTask?.cancel(false)
        textReleaseTask = scheduler.schedule({ releaseTextEncoder(force = false) }, TEXT_IDLE_RELEASE_MS, TimeUnit.MILLISECONDS)
        textEncoder?.let {
            if (textEncoderSpecId == spec.id) return it
            it.close()
            textEncoder = null
        }
        val encoder = TextEncoder(spec, modelManager.modelDir(spec), threads, ResourceGate.isLowMemoryDevice(appContext))
        textEncoder = encoder
        textEncoderSpecId = spec.id
        return encoder
    }

    // without `force`, encoders in use are left alone
    private fun releaseImageEncoder(force: Boolean) {
        if (force) imageLock.lock() else if (!imageLock.tryLock()) return
        try {
            imageEncoder?.close()
            imageEncoder = null
            imageEncoderSpecId = null
        } finally {
            imageLock.unlock()
        }
    }

    private fun releaseTextEncoder(force: Boolean) {
        if (force) textLock.lock() else if (!textLock.tryLock()) return
        try {
            textEncoder?.close()
            textEncoder = null
            textEncoderSpecId = null
        } finally {
            textLock.unlock()
        }
    }

    private fun releaseEncoders(force: Boolean) {
        Log.d(LOG_TAG, "release encoders force=$force")
        releaseImageEncoder(force)
        releaseTextEncoder(force)
    }

    // queries

    fun searchText(spec: ModelSpec, query: String, allowedIds: IntArray, allowedFingerprints: LongArray, maxResults: Int, threads: Int): List<ScoredId> {
        queriesInFlight.incrementAndGet()
        try {
            val start = SystemClock.elapsedRealtime()
            val vector = textLock.withLock { obtainTextEncoderLocked(spec, threads).encodeQuery(query) }
            val encoded = SystemClock.elapsedRealtime()
            val result = withStore(spec) { it.search(vector, allowedIds, allowedFingerprints, maxResults, threads) }
            Log.i(LOG_TAG, "text search model=${spec.id} candidates=${result.count} encode=${encoded - start}ms search=${SystemClock.elapsedRealtime() - encoded}ms")
            // Text-image scores are not comparable across queries, so matches are the items
            // that stand out from all candidates for this query (cf Flickr30k single-word queries:
            // ~23 results with 72% precision, instead of up to 500 with 7-68% with a fixed threshold).
            // statistics are meaningless over few candidates (e.g. searching within a small album): rank them all
            if (result.count < MIN_STATISTICS_COUNT) return result.hits
            val kept = result.hits.takeWhile { result.zScore(it.score) >= TEXT_MIN_Z }
            if (kept.size >= MIN_RESULTS) return kept
            return result.hits.take(MIN_RESULTS).filter { result.zScore(it.score) >= TEXT_FILL_Z }
        } finally {
            queriesInFlight.decrementAndGet()
        }
    }

    // `item` is embedded on the fly when it is not indexed yet; returns null when it cannot be embedded
    fun searchSimilar(spec: ModelSpec, item: EmbedItem, allowedIds: IntArray, allowedFingerprints: LongArray, maxResults: Int, threads: Int): List<ScoredId>? {
        queriesInFlight.incrementAndGet()
        try {
            val stored = withStore(spec) { it.getVector(item.id, item.fingerprint) }
            val vector = if (stored != null) {
                VectorStore.dequantize(stored.first, stored.second)
            } else {
                val result = embedItem(spec, item, threads) as? EmbedResult.Embedded ?: return null
                val (q, scale) = VectorStore.quantize(result.vector)
                withStore(spec) { it.append(listOf(EmbeddingRecord(item.id, item.fingerprint, q, scale)), it.currentGeneration) }
                result.vector
            }
            val candidates = withStore(spec) { it.search(vector, allowedIds, allowedFingerprints, maxResults, threads) }.hits
            return keepResults(candidates, spec.similarMinScore, SIMILAR_FLOOR_SCORE)
        } finally {
            queriesInFlight.decrementAndGet()
        }
    }

    // keeps results above `threshold`, but at least `MIN_RESULTS` results above `floor` when available
    private fun keepResults(sortedCandidates: List<ScoredId>, threshold: Float, floor: Float): List<ScoredId> {
        val kept = sortedCandidates.takeWhile { it.score >= threshold }
        if (kept.size >= MIN_RESULTS) return kept
        return sortedCandidates.take(MIN_RESULTS).filter { it.score >= floor }
    }
}

// models already output normalized vectors, but scores and thresholds rely on it
private fun normalize(vector: FloatArray): FloatArray {
    val norm = kotlin.math.sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
    return if (norm > 0) FloatArray(vector.size) { vector[it] / norm } else vector
}

class EmbedItem(
    val id: Int,
    val fingerprint: Long,
    val uri: String,
    val mimeType: String,
    val rotationDegrees: Int,
    val isFlipped: Boolean,
)
