package deckers.thibault.aves.smartsearch

import java.io.File
import java.io.Reader
import java.text.Normalizer
import java.util.Locale

/** OpenCLIP's `HFTokenizer(clean="canonicalize")` for timm/ViT-B-32-SigLIP2-256.
 * Reads the model's original tokenizer.json; no secondary, app-specific vocabulary is needed.
 */
class Siglip2Tokenizer(file: File) {
    private val vocabulary = HashMap<String, Int>(270_000)
    private val added = HashMap<Char, MutableList<Pair<String, Int>>>()
    // Primitive open-addressed table: a boxed Pair<String,String> for 580,604 merges exceeds Fire's heap.
    // The model vocabulary gives every merge component an integer ID; order in the JSON is BPE rank.
    private var mergeKeys = LongArray(1)
    private var mergeRanks = IntArray(1)
    private var mergeMask = 0
    private val byteIds = IntArray(256)

    init {
        file.bufferedReader(Charsets.UTF_8, 64 * 1024).use { reader ->
            val json = JsonStream(reader)
            json.obj { key ->
                when (key) {
                    "added_tokens" -> json.array {
                        var id = -1
                        var content = ""
                        json.obj { field ->
                            when (field) {
                                "id" -> id = json.number()
                                "content" -> content = json.string()
                                else -> json.skip()
                            }
                        }
                        if (id >= 0 && content.isNotEmpty()) {
                            added.getOrPut(content[0]) { ArrayList() }.add(content to id)
                        }
                    }
                    "model" -> json.obj { field ->
                        when (field) {
                            "vocab" -> json.obj { token -> vocabulary[token] = json.number() }
                            "merges" -> {
                                // 2^20 slots, ~55% occupancy: 12 MiB in primitive arrays.
                                mergeKeys = LongArray(1 shl 20)
                                mergeRanks = IntArray(mergeKeys.size)
                                mergeMask = mergeKeys.size - 1
                                var rank = 0
                                json.array {
                                    var component = 0
                                    var left = ""
                                    var right = ""
                                    json.array {
                                        if (component++ == 0) left = json.string() else right = json.string()
                                    }
                                    val a = requireNotNull(vocabulary[left]) { "Unknown BPE merge component: $left" }
                                    val b = requireNotNull(vocabulary[right]) { "Unknown BPE merge component: $right" }
                                    insert(pair(a, b), rank++)
                                }
                            }
                            else -> json.skip()
                        }
                    }
                    else -> json.skip()
                }
            }
        }
        require(mergeMask != 0 && vocabulary["<eos>"] == 1 && vocabulary["<pad>"] == 0) {
            "Not a Gemma tokenizer.json"
        }
        added.values.forEach { tokens ->
            tokens.sortWith(compareByDescending<Pair<String, Int>> { it.first.length }.thenBy { it.second })
        }
        for (byte in 0..255) {
            val digits = "0123456789ABCDEF"
            byteIds[byte] = vocabulary["<0x${digits[byte ushr 4]}${digits[byte and 15]}>"] ?: 3
        }
    }

    /** EOS-only (no BOS), right-padded with 0. Fast-tokenizer truncation reserves the
     * final position for EOS even when the content exceeds contextLength.
     */
    fun tokenize(text: String, contextLength: Int): LongArray {
        require(contextLength > 0) { "contextLength must be positive" }
        val result = LongArray(contextLength)
        val cleaned = canonicalize(text)
        var size = 0
        var start = 0
        var position = 0
        while (position < cleaned.length && size < contextLength - 1) {
            val special = added[cleaned[position]]?.firstOrNull { cleaned.startsWith(it.first, position) }
            if (special != null) {
                if (start < position) {
                    size = encodePiece(cleaned.substring(start, position), result, size, contextLength - 1)
                }
                if (size < contextLength - 1) result[size++] = special.second.toLong()
                position += special.first.length
                start = position
            } else {
                position++
            }
        }
        if (start < position && size < contextLength - 1) {
            size = encodePiece(cleaned.substring(start, position), result, size, contextLength - 1)
        }
        result[size] = 1L // tokenizer.json post_processor: A, <eos>
        return result
    }

    private fun encodePiece(text: String, output: LongArray, offset: Int, limit: Int): Int {
        // Gemma normalizer maps ASCII spaces to U+2581. Its Split(" ") pre-tokenizer sees
        // no remaining ASCII spaces, hence the complete piece is a single BPE word.
        val normalized = text.replace(' ', '▁')
        val symbols = ArrayList<String>(normalized.length)
        var index = 0
        while (index < normalized.length) {
            val cp = normalized.codePointAt(index)
            symbols.add(String(Character.toChars(cp)))
            index += Character.charCount(cp)
        }
        // BPE's initial alphabet is codepoints. Unknown codepoints fall back to UTF-8 bytes;
        // byte tokens are appended AFTER merging, not used as BPE merge components.
        while (symbols.size > 1) {
            var bestRank = Int.MAX_VALUE
            var best = -1
            for (i in 0 until symbols.size - 1) {
                val left = vocabulary[symbols[i]] ?: continue
                val right = vocabulary[symbols[i + 1]] ?: continue
                val rank = lookup(pair(left, right))
                if (rank < bestRank) {
                    bestRank = rank
                    best = i
                }
            }
            if (best < 0) break
            symbols[best] += symbols[best + 1]
            symbols.removeAt(best + 1)
        }
        var count = offset
        for (symbol in symbols) {
            if (count == limit) break
            val id = vocabulary[symbol]
            if (id != null) {
                output[count++] = id.toLong()
            } else {
                for (byte in symbol.toByteArray(Charsets.UTF_8)) {
                    if (count == limit) break
                    output[count++] = byteIds[byte.toInt() and 255].toLong()
                }
            }
        }
        return count
    }

    private fun insert(key: Long, rank: Int) {
        var slot = (key xor (key ushr 32)).toInt() * -0x7a143595 and mergeMask
        while (mergeKeys[slot] != 0L) slot = (slot + 1) and mergeMask
        mergeKeys[slot] = key + 1L
        mergeRanks[slot] = rank
    }

    private fun lookup(key: Long): Int {
        var slot = (key xor (key ushr 32)).toInt() * -0x7a143595 and mergeMask
        while (mergeKeys[slot] != 0L) {
            if (mergeKeys[slot] == key + 1L) return mergeRanks[slot]
            slot = (slot + 1) and mergeMask
        }
        return Int.MAX_VALUE
    }

    private fun pair(left: Int, right: Int) = (left.toLong() shl 32) or (right.toLong() and 0xffffffffL)

    private fun canonicalize(text: String): String {
        // OpenCLIP's basic_clean does ftfy, HTML unescape twice, then strip. NFC handles
        // ordinary text; a few frequent HTML entities are decoded without importing an
        // entire HTML parser. Exotic mojibake and HTML5 named entities aren't repaired.
        val normalized = Normalizer.normalize(unescape(unescape(text)), Normalizer.Form.NFC)
        val result = StringBuilder(normalized.length)
        var space = false
        for (ch in normalized) {
            when {
                ch == '_' || ch.isWhitespace() || Character.isSpaceChar(ch) ||
                    ch == '\u0085' || ch in '\u001c'..'\u001f' -> space = result.isNotEmpty()
                ch.code in 33..47 || ch.code in 58..64 || ch.code in 91..96 || ch.code in 123..126 -> Unit
                else -> {
                    if (space) result.append(' ')
                    result.append(ch)
                    space = false
                }
            }
        }
        return result.toString().lowercase(Locale.ROOT)
    }

    private fun unescape(text: String): String = ENTITY.replace(text) { match ->
        val entity = match.groupValues[1]
        val codepoint = when {
            entity.startsWith("#x", ignoreCase = true) -> entity.drop(2).toIntOrNull(16)
            entity.startsWith("#") -> entity.drop(1).toIntOrNull()
            else -> null
        }
        when {
            codepoint != null && Character.isValidCodePoint(codepoint) &&
                codepoint !in 0xD800..0xDFFF -> String(Character.toChars(codepoint))
            else -> when (entity) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "nbsp" -> "\u00a0"
                else -> match.value
            }
        }
    }

    private companion object {
        val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);?")
    }

    /** Incremental JSON tokenizer; never constructs the 33 MiB tree or the 580k merge strings. */
    private class JsonStream(private val reader: Reader) {
        private var next = reader.read()
        private fun take(): Int { val c = next; next = reader.read(); return c }
        private fun spaces() { while (next == 32 || next == 10 || next == 13 || next == 9) take() }
        private fun expect(ch: Char) { spaces(); require(take() == ch.code) { "Malformed tokenizer.json: expected $ch" } }
        fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                val c = take()
                when (c) {
                    -1 -> error("Unexpected end of tokenizer.json")
                    34 -> return out.toString()
                    92 -> when (val e = take()) {
                        34, 92, 47 -> out.append(e.toChar())
                        98 -> out.append('\b')
                        102 -> out.append('\u000c')
                        110 -> out.append('\n')
                        114 -> out.append('\r')
                        116 -> out.append('\t')
                        117 -> { var code = 0; repeat(4) { code = (code shl 4) + Character.digit(take().toChar(), 16) }; out.append(code.toChar()) }
                        else -> error("Invalid JSON escape $e")
                    }
                    else -> out.append(c.toChar())
                }
            }
        }
        fun number(): Int {
            spaces()
            var value = 0
            require(next in 48..57)
            while (next in 48..57) value = value * 10 + take() - 48
            return value
        }
        fun obj(consume: (String) -> Unit) {
            expect('{'); spaces()
            if (next == 125) { take(); return }
            while (true) {
                val key = string(); expect(':'); consume(key); spaces()
                if (next == 125) { take(); return }
                expect(',')
            }
        }
        fun array(consume: () -> Unit) {
            expect('['); spaces()
            if (next == 93) { take(); return }
            while (true) {
                consume(); spaces()
                if (next == 93) { take(); return }
                expect(',')
            }
        }
        fun skip() {
            spaces()
            when (next) {
                123 -> obj { skip() }
                91 -> array { skip() }
                34 -> { string() }
                else -> while (next != -1 && next != 44 && next != 93 && next != 125 && next != 32 && next != 10 && next != 13) take()
            }
        }
    }
}
