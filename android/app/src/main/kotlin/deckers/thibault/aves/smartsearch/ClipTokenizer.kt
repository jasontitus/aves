package deckers.thibault.aves.smartsearch

import java.io.InputStream
import java.text.Normalizer
import java.util.Locale
import java.util.zip.GZIPInputStream

// Port of the OpenAI CLIP byte-level BPE tokenizer, as implemented by `open_clip.tokenizer.SimpleTokenizer`
// with the default `lower` cleaning. `ftfy.fix_text` is approximated by NFC normalization.
// Token ids must match the Python implementation exactly, cf `ClipTokenizerTest`.
class ClipTokenizer(mergesGz: InputStream) {
    private val encoder: HashMap<String, Int>
    private val bpeRanks: HashMap<String, Int>
    private val byteEncoder: Array<String>
    private val cache = object : LinkedHashMap<String, List<Int>>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Int>>?) = size > CACHE_SIZE
    }

    val sotTokenId: Int
    val eotTokenId: Int

    init {
        val lines = GZIPInputStream(mergesGz).bufferedReader(Charsets.UTF_8).use { it.readLines() }
        // same slice as the reference implementation: skip header, keep 48894 merges
        val merges = lines.subList(1, MERGE_COUNT + 1)
        val (encoderTable, orderedSymbols) = bytesToUnicode()
        byteEncoder = encoderTable
        // base vocabulary follows the reference dict insertion order, not byte order
        val vocab = ArrayList<String>(VOCAB_SIZE)
        vocab.addAll(orderedSymbols)
        orderedSymbols.forEach { vocab.add("$it</w>") }
        bpeRanks = HashMap(merges.size * 2)
        merges.forEachIndexed { rank, merge ->
            val parts = merge.split(' ')
            vocab.add(parts[0] + parts[1])
            bpeRanks[pairKey(parts[0], parts[1])] = rank
        }
        vocab.add(START_OF_TEXT)
        vocab.add(END_OF_TEXT)
        encoder = HashMap(vocab.size * 2)
        vocab.forEachIndexed { i, token -> encoder[token] = i }
        sotTokenId = encoder[START_OF_TEXT]!!
        eotTokenId = encoder[END_OF_TEXT]!!
    }

    // returns `contextLength` token ids: SOT + tokens + EOT, zero padded, truncated with EOT last
    fun tokenize(text: String, contextLength: Int): LongArray {
        val tokens = ArrayList<Int>()
        tokens.add(sotTokenId)
        tokens.addAll(encode(text))
        tokens.add(eotTokenId)
        val result = LongArray(contextLength)
        val count = minOf(tokens.size, contextLength)
        for (i in 0 until count) {
            result[i] = tokens[i].toLong()
        }
        if (tokens.size > contextLength) {
            result[contextLength - 1] = eotTokenId.toLong()
        }
        return result
    }

    fun encode(text: String): List<Int> {
        val ids = ArrayList<Int>()
        val cleaned = clean(text)
        val matcher = TOKEN_PATTERN.matcher(cleaned)
        while (matcher.find()) {
            val token = matcher.group()
            val bytes = token.toByteArray(Charsets.UTF_8)
            val sb = StringBuilder()
            bytes.forEach { sb.append(byteEncoder[it.toInt() and 0xFF]) }
            ids.addAll(bpe(sb.toString()))
        }
        return ids
    }

    private fun bpe(token: String): List<Int> {
        if (token == START_OF_TEXT || token == END_OF_TEXT) return listOf(encoder[token]!!)
        synchronized(cache) { cache[token]?.let { return it } }

        // split into code points, marking the last symbol as end of word
        val word = ArrayList<String>()
        var i = 0
        while (i < token.length) {
            val cp = token.codePointAt(i)
            word.add(String(Character.toChars(cp)))
            i += Character.charCount(cp)
        }
        word[word.size - 1] = word.last() + "</w>"

        while (word.size > 1) {
            // find the adjacent pair with the lowest merge rank
            var bestRank = Int.MAX_VALUE
            var bestFirst: String? = null
            var bestSecond: String? = null
            for (j in 0 until word.size - 1) {
                val rank = bpeRanks[pairKey(word[j], word[j + 1])] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestFirst = word[j]
                    bestSecond = word[j + 1]
                }
            }
            if (bestFirst == null || bestSecond == null) break

            // merge all non-overlapping occurrences, left to right
            val merged = ArrayList<String>(word.size)
            var j = 0
            while (j < word.size) {
                if (j < word.size - 1 && word[j] == bestFirst && word[j + 1] == bestSecond) {
                    merged.add(bestFirst + bestSecond)
                    j += 2
                } else {
                    merged.add(word[j])
                    j++
                }
            }
            word.clear()
            word.addAll(merged)
        }

        val ids = word.map { encoder[it] ?: throw IllegalStateException("unknown BPE symbol=$it") }
        synchronized(cache) { cache[token] = ids }
        return ids
    }

    companion object {
        private const val MERGE_COUNT = 49152 - 256 - 2
        private const val VOCAB_SIZE = 256 * 2 + MERGE_COUNT + 2
        private const val CACHE_SIZE = 1024
        private const val START_OF_TEXT = "<start_of_text>"
        private const val END_OF_TEXT = "<end_of_text>"

        private val TOKEN_PATTERN = Regex(
            "<start_of_text>|<end_of_text>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+",
            RegexOption.IGNORE_CASE,
        ).toPattern()

        private fun pairKey(first: String, second: String) = "$first $second"

        // reversible mapping from bytes to printable unicode characters,
        // returned as a byte-indexed table and as the symbols in reference insertion order
        fun bytesToUnicode(): Pair<Array<String>, List<String>> {
            val bs = ArrayList<Int>()
            bs.addAll('!'.code..'~'.code)
            bs.addAll('¡'.code..'¬'.code)
            bs.addAll('®'.code..'ÿ'.code)
            val cs = ArrayList(bs)
            var n = 0
            for (b in 0 until 256) {
                if (b !in bs) {
                    bs.add(b)
                    cs.add(256 + n)
                    n++
                }
            }
            val table = Array(256) { "" }
            val ordered = ArrayList<String>(256)
            bs.forEachIndexed { i, b ->
                val symbol = String(Character.toChars(cs[i]))
                table[b] = symbol
                ordered.add(symbol)
            }
            return Pair(table, ordered)
        }

        // C1 control characters, decoded as Windows-1252 like `ftfy.fixes.fix_c1_controls`
        private val C1_AS_CP1252 = mapOf(
            0x80 to '€', 0x82 to '‚', 0x83 to 'ƒ', 0x84 to '„', 0x85 to '…', 0x86 to '†', 0x87 to '‡', 0x88 to 'ˆ',
            0x89 to '‰', 0x8A to 'Š', 0x8B to '‹', 0x8C to 'Œ', 0x8E to 'Ž', 0x91 to '‘', 0x92 to '’', 0x93 to '“',
            0x94 to '”', 0x95 to '•', 0x96 to '–', 0x97 to '—', 0x98 to '˜', 0x99 to '™', 0x9A to 'š', 0x9B to '›',
            0x9C to 'œ', 0x9E to 'ž', 0x9F to 'Ÿ',
        )

        private fun ftfyLite(text: String): String {
            val out = StringBuilder(text.length)
            text.forEach { raw ->
                val c = if (raw.code in 0x80..0x9F) C1_AS_CP1252[raw.code] ?: raw else raw
                val code = c.code
                when {
                    // uncurl quotes
                    c == '‘' || c == '’' || c == '‚' || c == '‛' -> out.append('\'')
                    c == '“' || c == '”' || c == '„' || c == '‟' -> out.append('"')
                    // character width (ideographic space, fullwidth and halfwidth forms), Latin ligatures and digraphs
                    code == 0x3000 || code in 0xFF01..0xFFEF || code in 0xFB00..0xFB06 || code in 0x01C4..0x01CC || code in 0x01F1..0x01F3 ->
                        out.append(Normalizer.normalize(c.toString(), Normalizer.Form.NFKC))

                    else -> out.append(c)
                }
            }
            return out.toString()
        }

        fun clean(text: String): String {
            var s = Normalizer.normalize(text, Normalizer.Form.NFC)
            // `ftfy.fix_text` unescapes HTML entities before its other fixes (e.g. uncurling `&rsquo;`)
            s = ftfyLite(htmlUnescape(s))
            s = htmlUnescape(htmlUnescape(s))
            // Python `str.split()` splits on unicode whitespace, including no-break spaces
            val sb = StringBuilder()
            var pendingSpace = false
            s.forEach { c ->
                if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                    pendingSpace = sb.isNotEmpty()
                } else {
                    if (pendingSpace) sb.append(' ')
                    pendingSpace = false
                    sb.append(c)
                }
            }
            return sb.toString().lowercase(Locale.ROOT)
        }

        private val NAMED_ENTITIES = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
            "hellip" to "…", "mdash" to "—", "ndash" to "–", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
            "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°", "eacute" to "é", "egrave" to "è", "agrave" to "à",
            "ccedil" to "ç", "uuml" to "ü", "ouml" to "ö", "auml" to "ä", "szlig" to "ß", "ntilde" to "ñ", "euro" to "€",
        )

        private val ENTITY_PATTERN = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")

        private fun htmlUnescape(s: String): String {
            if (!s.contains('&')) return s
            return ENTITY_PATTERN.replace(s) { m ->
                val e = m.groupValues[1]
                when {
                    !e.startsWith("#") -> NAMED_ENTITIES[e] ?: m.value
                    e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { codePointString(it) } ?: m.value
                    else -> e.substring(1).toIntOrNull()?.let { codePointString(it) } ?: m.value
                }
            }
        }

        private fun codePointString(cp: Int): String? = if (Character.isValidCodePoint(cp) && cp != 0) String(Character.toChars(cp)) else null
    }
}
