package deckers.thibault.aves.smartsearch

import android.util.Log
import deckers.thibault.aves.utils.LogUtils
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.PriorityQueue
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.CRC32
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Append-only store of int8-quantized, L2-normalized embeddings, keyed by entry ID.
//
// File layout (little endian):
// - header (HEADER_SIZE bytes): magic, version, dim, record size, model key
// - fixed-size records: id (int32), fingerprint (int64), crc32 (int32), scale (float32), vector (int8 * dim)
//
// A record with `id == TOMBSTONE_ID` is deleted. A record with `scale == 0` marks media that failed to embed,
// so that it is not retried until its fingerprint changes. It is never scored.
// Records are only ever served when the caller provides a matching fingerprint,
// so reused entry IDs or modified media cannot surface stale vectors.
class VectorStore(
    private val file: File,
    val dim: Int,
    private val modelKey: Long,
) {
    private val recordSize = RECORD_HEADER_SIZE + dim
    private val lock = ReentrantReadWriteLock()
    private var channel: FileChannel? = null

    // slot index -> id / fingerprint / failure flag, for live records only
    private var slotIds = IntArray(0)
    private var slotFingerprints = LongArray(0)
    private var slotFailed = BooleanArray(0)
    private var slotCount = 0
    private val slotById = IntIntMap()
    private var tombstoneCount = 0

    // removal bookkeeping, so that an embedding batch started before a removal cannot resurrect a removed entry
    private var generation = 0L
    private val removedAtGeneration = HashMap<Int, Long>()

    val liveCount: Int get() = lock.read { slotById.size }

    val failedCount: Int get() = lock.read { (0 until slotCount).count { slotIds[it] != TOMBSTONE_ID && slotFailed[it] } }

    val fileSize: Long get() = lock.read { if (file.exists()) file.length() else 0 }

    val currentGeneration: Long get() = lock.read { generation }

    fun open() {
        // fast path, without contending with ongoing searches
        if (lock.read { channel != null }) return
        openLocked()
    }

    private fun openLocked() = lock.write {
        if (channel != null) return
        file.parentFile?.mkdirs()
        if (!file.exists() || !isHeaderValid()) {
            createEmpty()
        }
        channel = RandomAccessFile(file, "rw").channel
        load()
    }

    fun close() = lock.write {
        channel?.close()
        channel = null
        slotIds = IntArray(0)
        slotFingerprints = LongArray(0)
        slotFailed = BooleanArray(0)
        slotCount = 0
        slotById.clear()
        tombstoneCount = 0
    }

    fun deleteFile() = lock.write {
        close()
        file.delete()
    }

    // returns the IDs, among the given ones, that have no record matching their fingerprint
    fun pending(ids: IntArray, fingerprints: LongArray): IntArray = lock.read {
        require(ids.size == fingerprints.size)
        val result = ArrayList<Int>()
        for (i in ids.indices) {
            val slot = slotById.get(ids[i])
            if (slot < 0 || slotFingerprints[slot] != fingerprints[i]) {
                result.add(ids[i])
            }
        }
        result.toIntArray()
    }

    fun append(records: List<EmbeddingRecord>, startGeneration: Long) = lock.write {
        val ch = channel ?: throw IllegalStateException("store is not open")
        val accepted = records.filter { record ->
            require(record.id > 0) { "invalid id=${record.id}" }
            require(record.vector == null || record.vector.size == dim) { "invalid vector size" }
            val removedAt = removedAtGeneration[record.id]
            removedAt == null || removedAt <= startGeneration
        }
        if (accepted.isEmpty()) return

        // write new records first, then tombstone the ones they replace,
        // so that a crash in between leaves duplicates (resolved at load) rather than a loss
        val buffer = ByteBuffer.allocate(recordSize * accepted.size).order(ByteOrder.LITTLE_ENDIAN)
        accepted.forEach { writeRecord(buffer, it) }
        buffer.flip()
        var position = HEADER_SIZE + slotCount.toLong() * recordSize
        while (buffer.hasRemaining()) {
            position += ch.write(buffer, position)
        }
        ch.force(false)

        accepted.forEach { record ->
            val oldSlot = slotById.get(record.id)
            if (oldSlot >= 0) {
                tombstone(ch, oldSlot)
            }
            addSlot(record.id, record.fingerprint, record.vector == null)
        }
        ch.force(false)
        // replaced records leave tombstones too
        compactIfNeeded()
    }

    fun remove(ids: IntArray) = lock.write {
        val ch = channel ?: throw IllegalStateException("store is not open")
        generation++
        var changed = false
        ids.forEach { id ->
            removedAtGeneration[id] = generation
            val slot = slotById.get(id)
            if (slot >= 0) {
                tombstone(ch, slot)
                changed = true
            }
        }
        if (changed) ch.force(false)
        compactIfNeeded()
    }

    // removes all records whose ID is not in the given set
    fun retainOnly(ids: IntArray) {
        val keep = ids.toHashSet()
        val toRemove = lock.read { (0 until slotCount).map { slotIds[it] }.filter { it != TOMBSTONE_ID && it !in keep } }
        if (toRemove.isNotEmpty()) remove(toRemove.toIntArray())
    }

    // returns a copy of the stored vector and its scale, if the record matches the fingerprint and did not fail
    fun getVector(id: Int, fingerprint: Long): Pair<ByteArray, Float>? = lock.read {
        val ch = channel ?: return null
        val slot = slotById.get(id)
        if (slot < 0 || slotFingerprints[slot] != fingerprint || slotFailed[slot]) return null
        val buffer = ByteBuffer.allocate(recordSize).order(ByteOrder.LITTLE_ENDIAN)
        readFully(ch, buffer, slotOffset(slot))
        buffer.flip()
        val record = readRecord(buffer) ?: return null
        val vector = record.vector ?: return null
        Pair(vector, record.scale)
    }

    // exact search over the records of the allowed entries (with matching fingerprints)
    fun search(
        query: FloatArray,
        allowedIds: IntArray,
        allowedFingerprints: LongArray,
        topK: Int,
        threads: Int,
    ): SearchResult = lock.read {
        require(query.size == dim)
        require(allowedIds.size == allowedFingerprints.size)
        val ch = channel ?: return SearchResult.EMPTY
        if (topK <= 0) return SearchResult.EMPTY

        // resolve allowed entries to valid slots
        val slots = ArrayList<Int>(allowedIds.size)
        for (i in allowedIds.indices) {
            val slot = slotById.get(allowedIds[i])
            if (slot >= 0 && slotFingerprints[slot] == allowedFingerprints[i] && !slotFailed[slot]) {
                slots.add(slot)
            }
        }
        if (slots.isEmpty()) return SearchResult.EMPTY
        slots.sort()

        // quantize the query to int8 for integer dot products
        val queryMax = query.maxOf { abs(it) }.takeIf { it > 0 } ?: return SearchResult.EMPTY
        val queryScale = 127f / queryMax
        val q = IntArray(dim) { (query[it] * queryScale).roundToInt().coerceIn(-127, 127) }

        val workerCount = max(1, min(threads, slots.size / MIN_SLOTS_PER_WORKER + 1))
        val chunkSize = (slots.size + workerCount - 1) / workerCount
        val tasks = (0 until workerCount).map { w ->
            val from = w * chunkSize
            val to = min(slots.size, from + chunkSize)
            Callable { scoreSlots(ch, slots, from, to, q, queryScale, topK) }
        }
        val partials = if (workerCount == 1) {
            listOf(tasks[0].call())
        } else {
            val executor = Executors.newFixedThreadPool(workerCount)
            try {
                executor.invokeAll(tasks).map { it.get() }
            } finally {
                executor.shutdown()
            }
        }
        val count = partials.sumOf { it.count }
        if (count == 0) return SearchResult.EMPTY
        val mean = partials.sumOf { it.sum } / count
        val variance = (partials.sumOf { it.sumOfSquares } / count - mean * mean).coerceAtLeast(.0)
        SearchResult(
            hits = partials.flatMap { it.hits }.sortedByDescending { it.score }.take(topK),
            count = count,
            mean = mean.toFloat(),
            std = kotlin.math.sqrt(variance).toFloat(),
        )
    }

    private fun scoreSlots(
        ch: FileChannel,
        slots: List<Int>,
        from: Int,
        to: Int,
        q: IntArray,
        queryScale: Float,
        topK: Int,
    ): PartialResult {
        val heap = PriorityQueue<ScoredId>(topK + 1, compareBy { it.score })
        var count = 0
        var sum = .0
        var sumOfSquares = .0
        val buffer = ByteBuffer.allocate(READ_CHUNK_RECORDS * recordSize).order(ByteOrder.LITTLE_ENDIAN)
        val vector = ByteArray(dim)
        var i = from
        while (i < to) {
            // read a run of consecutive slots in one go
            val first = slots[i]
            var last = first
            var j = i + 1
            while (j < to && slots[j] - first < READ_CHUNK_RECORDS) {
                last = slots[j]
                j++
            }
            buffer.clear()
            buffer.limit((last - first + 1) * recordSize)
            readFully(ch, buffer, slotOffset(first))

            for (k in i until j) {
                val base = (slots[k] - first) * recordSize
                val id = buffer.getInt(base)
                if (id == TOMBSTONE_ID) continue
                val scale = buffer.getFloat(base + RECORD_SCALE_OFFSET)
                if (scale == 0f) continue
                buffer.position(base + RECORD_HEADER_SIZE)
                buffer.get(vector)
                var acc = 0
                for (d in 0 until dim) {
                    acc += q[d] * vector[d]
                }
                val score = acc / (queryScale * scale)
                count++
                sum += score
                sumOfSquares += score.toDouble() * score
                if (heap.size < topK) {
                    heap.add(ScoredId(id, score))
                } else if (score > heap.peek()!!.score) {
                    heap.poll()
                    heap.add(ScoredId(id, score))
                }
            }
            i = j
        }
        return PartialResult(heap.toList(), count, sum, sumOfSquares)
    }

    private class PartialResult(val hits: List<ScoredId>, val count: Int, val sum: Double, val sumOfSquares: Double)

    private fun compactIfNeeded() {
        if (tombstoneCount < COMPACTION_MIN_TOMBSTONES || tombstoneCount < slotCount * COMPACTION_TOMBSTONE_RATIO) return
        try {
            compact()
        } catch (e: Exception) {
            // e.g. no space left: keep using the store with its tombstones
            File(file.parentFile, "${file.name}.tmp").delete()
            Log.w(LOG_TAG, "failed to compact store", e)
        }
    }

    // forgets removals, when no embedding batch can be in flight
    fun pruneRemovals() = lock.write { removedAtGeneration.clear() }

    // rewrites the file without tombstones; must be called under the write lock
    private fun compact() {
        val ch = channel ?: return
        val temp = File(file.parentFile, "${file.name}.tmp")
        RandomAccessFile(temp, "rw").channel.use { out ->
            out.truncate(0)
            val header = headerBuffer()
            while (header.hasRemaining()) out.write(header)
            val buffer = ByteBuffer.allocate(READ_CHUNK_RECORDS * recordSize).order(ByteOrder.LITTLE_ENDIAN)
            var slot = 0
            while (slot < slotCount) {
                val n = min(READ_CHUNK_RECORDS, slotCount - slot)
                buffer.clear()
                buffer.limit(n * recordSize)
                readFully(ch, buffer, slotOffset(slot))
                for (k in 0 until n) {
                    val id = buffer.getInt(k * recordSize)
                    if (id != TOMBSTONE_ID) {
                        val record = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                        record.position(k * recordSize)
                        record.limit((k + 1) * recordSize)
                        while (record.hasRemaining()) out.write(record)
                    }
                }
                slot += n
            }
            out.force(true)
        }
        ch.close()
        try {
            if (!temp.renameTo(file)) {
                temp.delete()
                throw IllegalStateException("failed to replace store file")
            }
        } finally {
            // reopen whichever file is in place, so that the store stays usable
            channel = RandomAccessFile(file, "rw").channel
            load()
        }
    }

    private fun isHeaderValid(): Boolean {
        if (file.length() < HEADER_SIZE) return false
        RandomAccessFile(file, "r").channel.use { ch ->
            val buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            readFully(ch, buffer, 0)
            buffer.flip()
            return buffer.getInt() == MAGIC &&
                    buffer.getInt() == VERSION &&
                    buffer.getInt() == dim &&
                    buffer.getInt() == recordSize &&
                    buffer.getLong() == modelKey
        }
    }

    private fun headerBuffer(): ByteBuffer {
        val buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(MAGIC).putInt(VERSION).putInt(dim).putInt(recordSize).putLong(modelKey)
        buffer.position(0)
        return buffer
    }

    private fun createEmpty() {
        RandomAccessFile(file, "rw").channel.use { ch ->
            ch.truncate(0)
            val header = headerBuffer()
            while (header.hasRemaining()) ch.write(header)
            ch.force(true)
        }
    }

    // scans all records, dropping corrupted ones and resolving duplicates (last valid record wins)
    private fun load() {
        val ch = channel!!
        slotCount = 0
        slotById.clear()
        tombstoneCount = 0
        val size = ch.size()
        val fullRecords = ((size - HEADER_SIZE) / recordSize).toInt()
        if (HEADER_SIZE + fullRecords.toLong() * recordSize != size) {
            // drop partial trailing record
            ch.truncate(HEADER_SIZE + fullRecords.toLong() * recordSize)
        }
        ensureCapacity(fullRecords)
        val buffer = ByteBuffer.allocate(READ_CHUNK_RECORDS * recordSize).order(ByteOrder.LITTLE_ENDIAN)
        var dirty = false
        var slot = 0
        while (slot < fullRecords) {
            val n = min(READ_CHUNK_RECORDS, fullRecords - slot)
            buffer.clear()
            buffer.limit(n * recordSize)
            readFully(ch, buffer, slotOffset(slot))
            for (k in 0 until n) {
                buffer.position(k * recordSize)
                buffer.limit((k + 1) * recordSize)
                val id = buffer.getInt(k * recordSize)
                val record = if (id == TOMBSTONE_ID) null else readRecord(buffer.slice().order(ByteOrder.LITTLE_ENDIAN))
                buffer.limit(n * recordSize)
                if (record == null) {
                    if (id != TOMBSTONE_ID) {
                        // corrupted record
                        writeTombstone(ch, slotCount)
                        dirty = true
                    }
                    slotIds[slotCount] = TOMBSTONE_ID
                    slotCount++
                    tombstoneCount++
                } else {
                    val previous = slotById.get(record.id)
                    if (previous >= 0) {
                        tombstone(ch, previous)
                        dirty = true
                    }
                    addSlot(record.id, record.fingerprint, record.vector == null)
                }
            }
            slot += n
        }
        if (dirty) ch.force(false)
    }

    private fun addSlot(id: Int, fingerprint: Long, failed: Boolean) {
        ensureCapacity(slotCount + 1)
        slotIds[slotCount] = id
        slotFingerprints[slotCount] = fingerprint
        slotFailed[slotCount] = failed
        slotById.put(id, slotCount)
        slotCount++
    }

    private fun writeTombstone(ch: FileChannel, slot: Int) {
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(TOMBSTONE_ID)
        buffer.flip()
        while (buffer.hasRemaining()) ch.write(buffer, slotOffset(slot) + buffer.position())
    }

    private fun tombstone(ch: FileChannel, slot: Int) {
        writeTombstone(ch, slot)
        val id = slotIds[slot]
        if (id != TOMBSTONE_ID) {
            if (slotById.get(id) == slot) slotById.remove(id)
            slotIds[slot] = TOMBSTONE_ID
            tombstoneCount++
        }
    }

    private fun ensureCapacity(capacity: Int) {
        if (capacity <= slotIds.size) return
        val newCapacity = max(capacity, slotIds.size * 3 / 2 + 16)
        slotIds = slotIds.copyOf(newCapacity)
        slotFingerprints = slotFingerprints.copyOf(newCapacity)
        slotFailed = slotFailed.copyOf(newCapacity)
    }

    private fun slotOffset(slot: Int) = HEADER_SIZE + slot.toLong() * recordSize

    private fun writeRecord(buffer: ByteBuffer, record: EmbeddingRecord) {
        val start = buffer.position()
        val vector = record.vector
        val scale = if (vector == null) 0f else record.scale
        buffer.putInt(record.id)
        buffer.putLong(record.fingerprint)
        buffer.putInt(0) // CRC placeholder
        buffer.putFloat(scale)
        if (vector != null) buffer.put(vector) else buffer.put(ByteArray(dim))
        val crc = computeCrc(buffer, start)
        buffer.putInt(start + RECORD_CRC_OFFSET, crc)
    }

    // expects a buffer positioned at the start of a record, with at least one record remaining
    private fun readRecord(buffer: ByteBuffer): EmbeddingRecord? {
        val start = buffer.position()
        val id = buffer.getInt()
        val fingerprint = buffer.getLong()
        val crc = buffer.getInt()
        val scale = buffer.getFloat()
        val vector = ByteArray(dim)
        buffer.get(vector)
        if (id <= 0 || crc != computeCrc(buffer, start)) return null
        return EmbeddingRecord(id, fingerprint, if (scale == 0f) null else vector, scale)
    }

    private fun computeCrc(buffer: ByteBuffer, start: Int): Int {
        val crc = CRC32()
        val view = buffer.duplicate()
        view.limit(start + RECORD_CRC_OFFSET)
        view.position(start)
        crc.update(view)
        view.limit(start + recordSize)
        view.position(start + RECORD_SCALE_OFFSET)
        crc.update(view)
        return crc.value.toInt()
    }

    private fun readFully(ch: FileChannel, buffer: ByteBuffer, position: Long) {
        var pos = position
        while (buffer.hasRemaining()) {
            val read = ch.read(buffer, pos)
            if (read < 0) throw IllegalStateException("unexpected end of store file")
            pos += read
        }
    }

    companion object {
        private val LOG_TAG = LogUtils.createTag<VectorStore>()
        const val TOMBSTONE_ID = -1
        private const val MAGIC = 0x49535641 // "AVSI"
        private const val VERSION = 1
        private const val HEADER_SIZE = 64
        private const val RECORD_CRC_OFFSET = 12
        private const val RECORD_SCALE_OFFSET = 16
        private const val RECORD_HEADER_SIZE = 20
        private const val READ_CHUNK_RECORDS = 256
        private const val MIN_SLOTS_PER_WORKER = 2000
        private const val COMPACTION_MIN_TOMBSTONES = 1000
        private const val COMPACTION_TOMBSTONE_RATIO = .25f

        // symmetric per-vector int8 quantization
        fun quantize(vector: FloatArray): Pair<ByteArray, Float> {
            val maxAbs = vector.maxOf { abs(it) }
            if (maxAbs == 0f) return Pair(ByteArray(vector.size), 0f)
            val scale = 127f / maxAbs
            val q = ByteArray(vector.size) { (vector[it] * scale).roundToInt().coerceIn(-127, 127).toByte() }
            return Pair(q, scale)
        }

        fun dequantize(vector: ByteArray, scale: Float) = FloatArray(vector.size) { vector[it] / scale }
    }
}

// `vector == null` marks media that could not be embedded
class EmbeddingRecord(
    val id: Int,
    val fingerprint: Long,
    val vector: ByteArray?,
    val scale: Float,
)

data class ScoredId(val id: Int, val score: Float)

// top hits, and statistics of the scores over all searched records
class SearchResult(val hits: List<ScoredId>, val count: Int, val mean: Float, val std: Float) {
    // how many standard deviations above the mean of all searched records
    fun zScore(score: Float) = if (std > 0) (score - mean) / std else 0f

    companion object {
        val EMPTY = SearchResult(emptyList(), 0, 0f, 0f)
    }
}

// minimal open addressing map from non-negative int keys to non-negative int values
class IntIntMap {
    private var keys = IntArray(16) { EMPTY }
    private var values = IntArray(16)
    var size = 0
        private set

    // removed slots still occupy probe sequences until the table is rehashed
    private var deleted = 0

    fun get(key: Int): Int {
        var i = index(key)
        // bounded probing: the table always keeps empty slots, but never loop forever
        repeat(keys.size) {
            val k = keys[i]
            if (k == EMPTY) return -1
            if (k == key) return values[i]
            i = (i + 1) and (keys.size - 1)
        }
        return -1
    }

    fun put(key: Int, value: Int) {
        require(key != EMPTY && key != DELETED)
        if ((size + deleted + 1) * 4 > keys.size * 3) rehash()
        var i = index(key)
        var firstDeleted = -1
        while (true) {
            val k = keys[i]
            if (k == key) {
                values[i] = value
                return
            }
            if (k == DELETED && firstDeleted < 0) firstDeleted = i
            if (k == EMPTY) {
                if (firstDeleted >= 0) {
                    keys[firstDeleted] = key
                    values[firstDeleted] = value
                    deleted--
                } else {
                    keys[i] = key
                    values[i] = value
                }
                size++
                return
            }
            i = (i + 1) and (keys.size - 1)
        }
    }

    fun remove(key: Int) {
        var i = index(key)
        repeat(keys.size) {
            val k = keys[i]
            if (k == EMPTY) return
            if (k == key) {
                keys[i] = DELETED
                size--
                deleted++
                return
            }
            i = (i + 1) and (keys.size - 1)
        }
    }

    fun clear() {
        keys = IntArray(16) { EMPTY }
        values = IntArray(16)
        size = 0
        deleted = 0
    }

    // grows when live entries need it, otherwise only purges removed slots
    private fun rehash() {
        val oldKeys = keys
        val oldValues = values
        var capacity = oldKeys.size
        while ((size + 1) * 2 > capacity) capacity *= 2
        keys = IntArray(capacity) { EMPTY }
        values = IntArray(capacity)
        size = 0
        deleted = 0
        for (i in oldKeys.indices) {
            val k = oldKeys[i]
            if (k != EMPTY && k != DELETED) put(k, oldValues[i])
        }
    }

    private fun index(key: Int): Int {
        var h = key * -0x61c88647
        h = h xor (h ushr 16)
        return h and (keys.size - 1)
    }

    companion object {
        private const val EMPTY = Int.MIN_VALUE
        private const val DELETED = Int.MIN_VALUE + 1
    }
}
