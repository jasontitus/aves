package deckers.thibault.aves.smartsearch

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.annotation.Keep
import java.io.File
import java.io.RandomAccessFile
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

// ORT session over a memory-mapped ORT-format model.
// Weights stay in the (file-backed, reclaimable) mapping instead of being copied to the heap.
// On low-RAM devices, and for large models, weight prepacking is disabled too: slower (~1.5x on Cortex-A55)
// but it avoids a packed anonymous copy of the weights (~75 MB for ViT-B/32).
class OrtModel(file: File, val threads: Int, lowMemory: Boolean) : AutoCloseable {
    // large models would duplicate hundreds of MB of weights when prepacked
    private val keepWeightsMapped = lowMemory || file.length() > LARGE_MODEL_BYTES

    private val env = OrtEnvironment.getEnvironment()

    // ORT reads initializers directly from this mapping after session creation.
    // R8 otherwise deletes the field in release builds, allowing GC to unmap live weights.
    @field:Keep
    private val mapping: MappedByteBuffer = RandomAccessFile(file, "r").use { raf ->
        raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
    }
    private val options = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(threads.coerceAtLeast(1))
        // idle worker threads would otherwise spin between runs, draining battery
        addConfigEntry("session.intra_op.allow_spinning", "0")
        setInterOpNumThreads(1)
        setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        // the default arena grows by powers of two and never shrinks
        setCPUArenaAllocator(false)
        setMemoryPatternOptimization(false)
        addConfigEntry("session.use_ort_model_bytes_directly", "1")
        addConfigEntry("session.use_ort_model_bytes_for_initializers", "1")
        if (keepWeightsMapped) addConfigEntry("session.disable_prepacking", "1")
    }
    val session: OrtSession = try {
        env.createSession(mapping, options)
    } catch (e: Exception) {
        options.close()
        throw e
    }
    val inputName: String = session.inputNames.first()

    fun run(tensor: OnnxTensor): FloatArray {
        session.run(mapOf(inputName to tensor)).use { result ->
            @Suppress("UNCHECKED_CAST")
            val output = result[0].value as Array<FloatArray>
            return output[0]
        }
    }

    override fun close() {
        session.close()
        options.close()
    }

    companion object {
        private const val LARGE_MODEL_BYTES = 200L * (1 shl 20)
    }
}

class ImageEncoder(private val spec: ModelSpec, modelDir: File, threads: Int, lowMemory: Boolean) : AutoCloseable {
    private val input: FloatBuffer = FloatBuffer.allocate(3 * spec.imageSize * spec.imageSize)
    private val shape = longArrayOf(1, 3, spec.imageSize.toLong(), spec.imageSize.toLong())
    val preprocessor = ImagePreprocessor(spec)
    private val model = try {
        OrtModel(File(modelDir, "image.ort"), threads, lowMemory)
    } catch (e: Exception) {
        preprocessor.release()
        throw e
    }

    val threads: Int get() = model.threads

    // input is written by `preprocessor` into `input()`
    fun input(): FloatBuffer = input

    fun encode(): FloatArray {
        input.rewind()
        OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), input, shape).use { tensor ->
            return checkDim(spec, model.run(tensor))
        }
    }

    override fun close() {
        model.close()
        preprocessor.release()
    }
}

class TextEncoder(private val spec: ModelSpec, modelDir: File, threads: Int, lowMemory: Boolean) : AutoCloseable {
    private val tokenize: (String, Int) -> LongArray = when (spec.tokenizerType) {
        TokenizerType.CLIP_BPE -> File(modelDir, ModelCatalog.BPE_FILE).inputStream().buffered().use { ClipTokenizer(it)::tokenize }
        TokenizerType.GEMMA_BPE -> Siglip2Tokenizer(File(modelDir, ModelCatalog.SIGLIP2_TOKENIZER_FILE))::tokenize
    }
    private val model = OrtModel(File(modelDir, "text.ort"), threads, lowMemory)

    fun encode(text: String): FloatArray {
        val ids = tokenize(text, spec.contextLength)
        OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), LongBuffer.wrap(ids), longArrayOf(1, spec.contextLength.toLong())).use { tensor ->
            return checkDim(spec, model.run(tensor))
        }
    }

    // Averages the embeddings of the query in several phrasings (CLIP prompt ensembling),
    // which improves results for short queries such as a single word.
    fun encodeQuery(query: String): FloatArray {
        val sum = FloatArray(spec.dim)
        QUERY_TEMPLATES.take(spec.queryPhrasings.coerceIn(1, QUERY_TEMPLATES.size)).forEach { template ->
            val v = encode(template.format(query))
            for (i in sum.indices) sum[i] += v[i]
        }
        val norm = kotlin.math.sqrt(sum.sumOf { (it * it).toDouble() }).toFloat()
        return if (norm > 0) FloatArray(sum.size) { sum[it] / norm } else sum
    }

    companion object {
        private val QUERY_TEMPLATES = listOf("%s", "a photo of %s.", "a photo of a %s.", "a picture of %s.")
    }

    override fun close() = model.close()
}

// the catalog must match the model output, otherwise every vector would be rejected by the store
private fun checkDim(spec: ModelSpec, vector: FloatArray): FloatArray {
    check(vector.size == spec.dim) { "model=${spec.id} outputs ${vector.size} dimensions, but the catalog declares ${spec.dim}" }
    return vector
}
