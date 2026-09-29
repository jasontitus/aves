package deckers.thibault.aves.smartsearch

// Embedding model packs available for smart search.
// Models are downloaded on demand, never bundled, and verified against the hashes below.
// Each pack documents its own export and conversion recipe at its pinned download source.
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
    const val SIGLIP2_TOKENIZER_FILE = "tokenizer.json"
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

    val siglip2 = ModelSpec(
        id = "siglip2-b32-256-selective-int8-v1",
        // Matched timm SigLIP2 Base/32 256 towers, Apache-2.0, pinned separately from existing packs.
        // Export recipe: scripts/smart_search/convert_siglip2.py; timm source revision
        // cd1efb47643f2794413dd79ef24397c175032780. ORT serialization is not byte-reproducible;
        // compare embeddings and verify the pinned published hashes before distribution.
        downloadBaseUrl = "https://huggingface.co/sliderforthewin/aves-smart-search-siglip2-selective-int8/resolve/7ab165533402fa42eedc575efc110d2b01ed61a5/",
        tokenizerType = TokenizerType.GEMMA_BPE,
        dim = 768,
        imageSize = 256,
        resize = ResizeMode.SQUASH,
        // The exported image graph applies its own [-1, 1] normalization to RGB [0, 1].
        mean = floatArrayOf(0f, 0f, 0f),
        std = floatArrayOf(1f, 1f, 1f),
        contextLength = 64,
        queryPhrasings = 1,
        // ~98th percentile of unrelated image pairs in the 100-image XM3600 smoke gallery.
        similarMinScore = .66f,
        minRamBytes = 3_500_000_000L,
        requires64Bit = true,
        files = listOf(
            ModelFile("image.ort", "d6a54abc8d25ea7f5d633de8d27fbae6b3cdace0e87703140641e7317133efb6", 195003280L),
            ModelFile("text.ort", "27c420458dfac229ed98c17dc57f516441dd10c912aa1682195cf0a2e578d591", 368788736L),
            ModelFile(SIGLIP2_TOKENIZER_FILE, "220c63d496e0c14e63eb656c91e0215e926202e4c74b1f089e09f1920d779b04", 34362885L),
        ),
    )

    val all = listOf(standard, bestQuality, siglip2)

    fun byId(id: String?) = all.firstOrNull { it.id == id }
}

enum class ResizeMode {
    // scale so that the short side matches the input size, then center crop
    SHORT_SIDE_CROP,

    // scale both dimensions to the input size, ignoring aspect ratio
    SQUASH,
}

enum class TokenizerType {
    CLIP_BPE,
    GEMMA_BPE,
}

class ModelFile(val name: String, val sha256: String, val size: Long)

class ModelSpec(
    val id: String,
    val downloadBaseUrl: String = ModelCatalog.MODEL_BASE_URL,
    val tokenizerType: TokenizerType = TokenizerType.CLIP_BPE,
    val dim: Int,
    val imageSize: Int,
    val resize: ResizeMode,
    val mean: FloatArray,
    val std: FloatArray,
    val contextLength: Int,
    // number of phrasings of a text query to average, cf `TextEncoder.encodeQuery`
    val queryPhrasings: Int,
    // similar images are kept when `score >= similarMinScore` (calibrated per embedding space)
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
