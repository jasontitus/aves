package deckers.thibault.aves.smartsearch

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sqrt
import kotlin.random.Random

class VectorStoreTest {
    @TempDir
    lateinit var dir: File

    private val dim = 32

    private fun store(name: String = "index.bin", modelKey: Long = 42L) = VectorStore(File(dir, name), dim, modelKey).apply { open() }

    private fun randomUnit(random: Random): FloatArray {
        val v = FloatArray(dim) { random.nextFloat() * 2 - 1 }
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(dim) { v[it] / norm }
    }

    private fun record(id: Int, fingerprint: Long, v: FloatArray): EmbeddingRecord {
        val (q, scale) = VectorStore.quantize(v)
        return EmbeddingRecord(id, fingerprint, q, scale)
    }

    private fun dot(a: FloatArray, b: FloatArray) = a.indices.sumOf { (a[it] * b[it]).toDouble() }.toFloat()

    @Test
    fun search_ranksLikeFloatBruteForce() {
        val random = Random(1)
        val s = store()
        val vectors = (1..500).associateWith { randomUnit(random) }
        s.append(vectors.map { (id, v) -> record(id, id * 10L, v) }, s.currentGeneration)
        val query = randomUnit(random)
        val ids = vectors.keys.toIntArray()
        val fps = ids.map { it * 10L }.toLongArray()

        val results = s.search(query, ids, fps, topK = 10, threads = 1).hits
        val expected = vectors.entries.sortedByDescending { dot(query, it.value) }.take(10).map { it.key }
        assertEquals(10, results.size)
        // int8 quantization may swap near ties, but the top result and most of the top 10 must agree
        assertEquals(expected.first(), results.first().id)
        assertTrue(results.map { it.id }.intersect(expected.toSet()).size >= 9)
        results.forEach { r -> assertEquals(dot(query, vectors[r.id]!!), r.score, .02f) }
    }

    @Test
    fun search_multiThreadedMatchesSingleThreaded() {
        val random = Random(2)
        val s = store()
        val vectors = (1..9000).associateWith { randomUnit(random) }
        s.append(vectors.map { (id, v) -> record(id, 7L, v) }, s.currentGeneration)
        val ids = vectors.keys.toIntArray()
        val fps = LongArray(ids.size) { 7L }
        val query = randomUnit(random)
        val single = s.search(query, ids, fps, topK = 50, threads = 1)
        val multi = s.search(query, ids, fps, topK = 50, threads = 4)
        assertEquals(single.hits, multi.hits)
        assertEquals(single.count, multi.count)
        assertEquals(single.mean, multi.mean, 1e-5f)
        assertEquals(single.std, multi.std, 1e-5f)
    }

    @Test
    fun search_onlyAllowedIdsWithMatchingFingerprint() {
        val random = Random(3)
        val s = store()
        s.append(listOf(record(1, 100, randomUnit(random)), record(2, 200, randomUnit(random)), record(3, 300, randomUnit(random))), s.currentGeneration)
        val query = randomUnit(random)
        // id 2 is not allowed, id 3 has a stale fingerprint (e.g. reused ID or modified file)
        val results = s.search(query, intArrayOf(1, 3), longArrayOf(100, 999), topK = 10, threads = 1).hits
        assertEquals(listOf(1), results.map { it.id })
        assertArrayEquals(intArrayOf(3, 4), s.pending(intArrayOf(1, 3, 4), longArrayOf(100, 999, 400)))
        assertNull(s.getVector(3, 999))
        assertNotNull(s.getVector(3, 300))
    }

    @Test
    fun failedRecord_isNotPendingAndNeverScored() {
        val random = Random(4)
        val s = store()
        s.append(listOf(EmbeddingRecord(5, 55, null, 0f), record(6, 66, randomUnit(random))), s.currentGeneration)
        assertEquals(0, s.pending(intArrayOf(5), longArrayOf(55)).size)
        assertEquals(1, s.pending(intArrayOf(5), longArrayOf(56)).size)
        val results = s.search(randomUnit(random), intArrayOf(5, 6), longArrayOf(55, 66), topK = 10, threads = 1).hits
        assertEquals(listOf(6), results.map { it.id })
        assertNull(s.getVector(5, 55))
        assertEquals(1, s.failedCount)
    }

    @Test
    fun replace_keepsLatestAndPersists() {
        val random = Random(5)
        val s = store()
        val v1 = randomUnit(random)
        val v2 = randomUnit(random)
        s.append(listOf(record(1, 1, v1)), s.currentGeneration)
        s.append(listOf(record(1, 2, v2)), s.currentGeneration)
        assertEquals(1, s.liveCount)
        s.close()
        s.open()
        assertEquals(1, s.liveCount)
        assertNull(s.getVector(1, 1))
        val (q, scale) = s.getVector(1, 2)!!
        assertEquals(1f, dot(VectorStore.dequantize(q, scale), v2), .02f)
    }

    @Test
    fun remove_persistsAndBlocksStaleBatch() {
        val random = Random(6)
        val s = store()
        s.append((1..5).map { record(it, 1, randomUnit(random)) }, s.currentGeneration)
        val batchStart = s.currentGeneration
        s.remove(intArrayOf(2, 3))
        assertEquals(3, s.liveCount)
        // a batch that started before the removal must not resurrect removed entries
        s.append(listOf(record(2, 1, randomUnit(random)), record(6, 1, randomUnit(random))), batchStart)
        assertEquals(4, s.liveCount)
        assertEquals(1, s.pending(intArrayOf(2), longArrayOf(1)).size)
        // a batch started after the removal may add it back
        s.append(listOf(record(2, 1, randomUnit(random))), s.currentGeneration)
        assertEquals(0, s.pending(intArrayOf(2), longArrayOf(1)).size)
        s.close()
        s.open()
        assertEquals(5, s.liveCount)
        assertEquals(1, s.pending(intArrayOf(3), longArrayOf(1)).size)
    }

    @Test
    fun retainOnly_removesOthers() {
        val random = Random(7)
        val s = store()
        s.append((1..10).map { record(it, 1, randomUnit(random)) }, s.currentGeneration)
        s.retainOnly(intArrayOf(2, 4, 6, 99))
        assertEquals(3, s.liveCount)
        assertArrayEquals(intArrayOf(1, 3), s.pending(intArrayOf(1, 2, 3, 4), longArrayOf(1, 1, 1, 1)))
    }

    @Test
    fun open_dropsPartialTrailingRecord() {
        val random = Random(8)
        val file = File(dir, "partial.bin")
        val s = VectorStore(file, dim, 1).apply { open() }
        s.append((1..3).map { record(it, 1, randomUnit(random)) }, s.currentGeneration)
        s.close()
        // simulate a crash in the middle of an append
        RandomAccessFile(file, "rw").use { it.setLength(it.length() + 7) }
        s.open()
        assertEquals(3, s.liveCount)
        s.append(listOf(record(4, 1, randomUnit(random))), s.currentGeneration)
        s.close()
        s.open()
        assertEquals(4, s.liveCount)
    }

    @Test
    fun open_dropsCorruptedRecord() {
        val random = Random(9)
        val file = File(dir, "corrupt.bin")
        val s = VectorStore(file, dim, 1).apply { open() }
        s.append((1..3).map { record(it, 1, randomUnit(random)) }, s.currentGeneration)
        s.close()
        // flip a vector byte of the second record
        val recordSize = 20 + dim
        RandomAccessFile(file, "rw").use { raf ->
            val pos = 64L + recordSize + 20 + 3
            raf.seek(pos)
            val b = raf.read()
            raf.seek(pos)
            raf.write(b xor 0x5A)
        }
        s.open()
        assertEquals(2, s.liveCount)
        assertArrayEquals(intArrayOf(2), s.pending(intArrayOf(1, 2, 3), longArrayOf(1, 1, 1)))
    }

    @Test
    fun open_resolvesDuplicatesLastWins() {
        val random = Random(10)
        val file = File(dir, "dup.bin")
        val other = File(dir, "other.bin")
        val s = VectorStore(file, dim, 1).apply { open() }
        s.append(listOf(record(1, 111, randomUnit(random))), s.currentGeneration)
        s.close()
        val t = VectorStore(other, dim, 1).apply { open() }
        t.append(listOf(record(1, 222, randomUnit(random))), t.currentGeneration)
        t.close()
        // simulate a crash after writing the replacement but before tombstoning the original
        val recordBytes = other.readBytes().copyOfRange(64, other.length().toInt())
        file.appendBytes(recordBytes)
        s.open()
        assertEquals(1, s.liveCount)
        assertEquals(0, s.pending(intArrayOf(1), longArrayOf(222)).size)
        assertEquals(1, s.pending(intArrayOf(1), longArrayOf(111)).size)
    }

    @Test
    fun open_resetsOnModelChange() {
        val random = Random(11)
        val s = store(modelKey = 1)
        s.append(listOf(record(1, 1, randomUnit(random))), s.currentGeneration)
        s.close()
        val t = store(modelKey = 2)
        assertEquals(0, t.liveCount)
    }

    @Test
    fun remove_compactsFile() {
        val random = Random(12)
        val s = store()
        s.append((1..4000).map { record(it, 1, randomUnit(random)) }, s.currentGeneration)
        val before = s.fileSize
        s.remove((1..3000).toList().toIntArray())
        assertTrue(s.fileSize < before / 2, "file should have been compacted")
        assertEquals(1000, s.liveCount)
        val ids = (1..4000).toList().toIntArray()
        val results = s.search(randomUnit(random), ids, LongArray(ids.size) { 1 }, topK = 5000, threads = 2).hits
        assertEquals(1000, results.size)
        assertTrue(results.all { it.id > 3000 })
        s.close()
        s.open()
        assertEquals(1000, s.liveCount)
    }

    @Test
    fun intIntMap_matchesHashMap() {
        val random = Random(13)
        val map = IntIntMap()
        val reference = HashMap<Int, Int>()
        repeat(20000) {
            val key = random.nextInt(5000)
            if (random.nextInt(4) == 0) {
                map.remove(key)
                reference.remove(key)
            } else {
                val value = random.nextInt(100000)
                map.put(key, value)
                reference[key] = value
            }
        }
        assertEquals(reference.size, map.size)
        for (key in 0 until 5000) {
            assertEquals(reference[key] ?: -1, map.get(key))
        }
    }

    // regression: removed slots used to never be reclaimed, so lookups of absent keys looped forever
    @Test
    fun intIntMap_survivesRemoveAddChurn() {
        val map = IntIntMap()
        var nextKey = 0
        val live = ArrayDeque<Int>()
        repeat(375) {
            map.put(nextKey, nextKey)
            live.addLast(nextKey++)
        }
        repeat(20000) {
            map.remove(live.removeFirst())
            map.put(nextKey, nextKey)
            live.addLast(nextKey++)
            assertEquals(-1, map.get(-5))
        }
        assertEquals(375, map.size)
        live.forEach { assertEquals(it, map.get(it)) }
    }

    @Test
    fun remove_churnKeepsStoreResponsive() {
        val random = Random(14)
        val s = store()
        var nextId = 1
        s.append((1..300).map { record(nextId++, 1, randomUnit(random)) }, s.currentGeneration)
        repeat(1500) {
            s.remove(intArrayOf(nextId - 300))
            s.append(listOf(record(nextId++, 1, randomUnit(random))), s.currentGeneration)
        }
        assertEquals(300, s.liveCount)
        assertEquals(1, s.pending(intArrayOf(999999), longArrayOf(1)).size)
    }

    @Test
    fun search_reportsScoreStatisticsOverAllCandidates() {
        val random = Random(15)
        val s = store()
        val vectors = (1..3000).associateWith { randomUnit(random) }
        s.append(vectors.map { (id, v) -> record(id, 1, v) }, s.currentGeneration)
        val query = randomUnit(random)
        val ids = vectors.keys.toIntArray()
        val result = s.search(query, ids, LongArray(ids.size) { 1 }, topK = 5, threads = 3)
        val scores = vectors.values.map { dot(query, it).toDouble() }
        val mean = scores.average()
        val std = sqrt(scores.sumOf { (it - mean) * (it - mean) } / scores.size)
        assertEquals(3000, result.count)
        assertEquals(5, result.hits.size)
        assertEquals(mean.toFloat(), result.mean, .005f)
        assertEquals(std.toFloat(), result.std, .005f)
        assertTrue(result.zScore(result.hits.first().score) > 2f)
    }
}
