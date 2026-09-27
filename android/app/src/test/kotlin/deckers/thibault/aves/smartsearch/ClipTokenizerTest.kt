package deckers.thibault.aves.smartsearch

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Base64

class ClipTokenizerTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream("smartsearch/$name")!!

    // golden ids generated with `open_clip.get_tokenizer('ViT-B-32')` (context 77) and context 32 (PE-Core)
    @Test
    fun tokenize_matchesOpenClipReference() {
        val tokenizer = ClipTokenizer(resource("bpe_simple_vocab_16e6.txt.gz"))
        var count = 0
        resource("tokenizer_golden.tsv").bufferedReader().forEachLine { line ->
            val (b64, ids77, ids32) = line.split('\t')
            val text = String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
            val expected77 = ids77.split(',').map { it.toLong() }.toLongArray()
            val expected32 = ids32.split(',').map { it.toLong() }.toLongArray()
            assertArrayEquals(expected77, tokenizer.tokenize(text, 77), "context 77 for text=`$text`")
            assertArrayEquals(expected32, tokenizer.tokenize(text, 32), "context 32 for text=`$text`")
            count++
        }
        assertEquals(260, count)
    }

    @Test
    fun tokenize_specialIds() {
        val tokenizer = ClipTokenizer(resource("bpe_simple_vocab_16e6.txt.gz"))
        assertEquals(49406, tokenizer.sotTokenId)
        assertEquals(49407, tokenizer.eotTokenId)
        val ids = tokenizer.tokenize("", 8)
        assertArrayEquals(longArrayOf(49406, 49407, 0, 0, 0, 0, 0, 0), ids)
    }
}
