package deckers.thibault.aves.smartsearch

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Base64

class Siglip2TokenizerTest {
    private fun fixture(): File {
        val url = requireNotNull(javaClass.classLoader!!.getResource("smartsearch/siglip2_tokenizer_fixture.json"))
        return File(url.toURI())
    }

    // Fixture vocabulary/merges are a strict rank-preserving subset of the pinned timm
    // tokenizer.json: all symbols and candidate merges for these prompts. The expected IDs
    // were generated independently with GemmaTokenizerFast from that pinned repository,
    // OpenCLIP's canonicalize cleaning, and padding/truncation to 64, 6 and 1 respectively.
    // The same golden IDs can also be checked against the ORIGINAL downloaded 33MB file
    // by setting SIGLIP2_TOKENIZER_JSON in the test environment.
    @Test
    fun matchesPinnedGemmaFastTokenizer() {
        val tokenizer = Siglip2Tokenizer(fixture())
        compareGoldens(tokenizer)
        System.getenv("SIGLIP2_TOKENIZER_JSON")?.let { compareGoldens(Siglip2Tokenizer(File(it))) }
    }

    @Test
    fun padsWithZeroAndAppendsOnlyEos() {
        val tokenizer = Siglip2Tokenizer(fixture())
        assertArrayEquals(longArrayOf(1, 0, 0, 0, 0, 0), tokenizer.tokenize("", 6))
        assertArrayEquals(longArrayOf(1), tokenizer.tokenize("A cat on a bicycle", 1))
    }

    private fun compareGoldens(tokenizer: Siglip2Tokenizer) {
        var cases = 0
        javaClass.classLoader!!.getResourceAsStream("smartsearch/siglip2_golden.tsv")!!.bufferedReader().use { reader ->
            reader.forEachLine { line ->
                val columns = line.split('\t')
                val text = String(Base64.getDecoder().decode(columns[0]), Charsets.UTF_8)
                for ((index, contextLength) in intArrayOf(64, 6, 1).withIndex()) {
                    val expected = columns[index + 1].split(',').map(String::toLong).toLongArray()
                    assertArrayEquals(expected, tokenizer.tokenize(text, contextLength), "text=$text context=$contextLength")
                }
                cases++
            }
        }
        assertEquals(17, cases)
    }
}
