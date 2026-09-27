package deckers.thibault.aves.smartsearch

// Embedding model packs available for smart search.
// Models are downloaded on demand, never bundled, and verified against the hashes below.
// Conversion: OpenCLIP export to ONNX (opset 18), dynamic int8 per-channel quantization
// (`MatMul`/`Gemm`, plus `Gather` for text), keeping `resblocks.0/mlp/c_proj` in float
// as its activation outliers break int8 text embeddings, then conversion to ORT format for ARM.
// The conversion script and license notices are published with the model files.
object ModelCatalog {
    // to increment whenever image loading or preprocessing changes, so that indexes are rebuilt
    // - 4: images at least as large as the model input (decoding the image when its thumbnail is too small),
    //      and decoding the image instead of degenerate (e.g. blank) thumbnails
    const val PIPELINE_VERSION = 4

    // Base URL where model files are hosted, pinned to a commit so that files never change. Downloads are disabled when empty.
    // Files and licenses: https://huggingface.co/sliderforthewin/aves-smart-search
    // Conversion script: scripts/smart_search/convert.py
    const val MODEL_BASE_URL = "https://huggingface.co/sliderforthewin/aves-smart-search/resolve/ebc8231d926b17968cbcc47b32e4c9c0cf956cd1/"

    const val BPE_FILE = "bpe_simple_vocab_16e6.txt.gz"
    private const val BPE_SHA256 = "924691ac288e54409236115652ad4aa250f48203de50a9e4722a6ecd48d6804a"
    private const val BPE_SIZE = 1356917L

    val standard = ModelSpec(
        id = "openclip-vitb32-laion2b-v1",
        // OpenCLIP ViT-B/32, `laion2b_s34b_b79k` weights, MIT license
        dim = 512,
        imageSize = 224,
        resize = ResizeMode.SHORT_SIDE_CROP,
        mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f),
        std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f),
        contextLength = 77,
        queryPhrasings = 4,
        similarMinScore = .55f,
        minRamBytes = 0,
        requires64Bit = false,
        files = listOf(
            ModelFile("image.ort", "ab85a2e04732c61f426e47f48e2b152f014346205d13a6d491264de6a6e34bad", 96259920L),
            ModelFile("text.ort", "6a1ac8363ed68f4b11132dd28c5cb2cb69cca8068647c368be40f304fc6957bc", 67919656L),
            ModelFile(BPE_FILE, BPE_SHA256, BPE_SIZE),
        ),
    )

    val bestQuality = ModelSpec(
        id = "pe-core-b16-224-v1",
        // Meta Perception Encoder PE-Core-B16-224, Apache-2.0 license
        dim = 1024,
        imageSize = 224,
        resize = ResizeMode.SQUASH,
        mean = floatArrayOf(.5f, .5f, .5f),
        std = floatArrayOf(.5f, .5f, .5f),
        contextLength = 32,
        // phrasing ensembles do not improve this model (Flickr30k single-word queries, P@12 .82 v .83)
        queryPhrasings = 1,
        similarMinScore = .54f,
        minRamBytes = 3_500_000_000L,
        requires64Bit = true,
        files = listOf(
            ModelFile("image.ort", "b177ed44ac762805459487ef40b88d0de23c987613416d4f62c4120f292dd656", 99129192L),
            ModelFile("text.ort", "51257ebc3d96c0e9b4ec311325b157387118e8d55261f34d86900c3c3fa24430", 369691864L),
            ModelFile(BPE_FILE, BPE_SHA256, BPE_SIZE),
        ),
    )

    val all = listOf(standard, bestQuality)

    fun byId(id: String?) = all.firstOrNull { it.id == id }
}

enum class ResizeMode {
    // scale so that the short side matches the input size, then center crop
    SHORT_SIDE_CROP,

    // scale both dimensions to the input size, ignoring aspect ratio
    SQUASH,
}

class ModelFile(val name: String, val sha256: String, val size: Long)

class ModelSpec(
    val id: String,
    val dim: Int,
    val imageSize: Int,
    val resize: ResizeMode,
    val mean: FloatArray,
    val std: FloatArray,
    val contextLength: Int,
    // number of phrasings of a text query to average, cf `TextEncoder.encodeQuery`
    val queryPhrasings: Int,
    // similar images are kept when `score >= similarMinScore` (~ top 2% of image pairs on Flickr30k)
    val similarMinScore: Float,
    val minRamBytes: Long,
    val requires64Bit: Boolean,
    val files: List<ModelFile>,
) {
    val totalSize: Long get() = files.sumOf { it.size }

    // identifies the embedding space, so that an index built with another model, or another
    // preprocessing pipeline version, is discarded
    val key: Long
        get() {
            var h = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
            "$id/${ModelCatalog.PIPELINE_VERSION}".forEach { c ->
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
            return h
        }
}
