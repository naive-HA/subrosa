package acab.naiveha.subrosa.ui.nitrokey3updater

internal class MiniJsonException(message: String) : Exception(message)

internal object MiniJson {
    private const val MAX_DEPTH = 32

    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue(0)
        reader.skipWhitespace()
        if (!reader.atEnd()) reader.fail("Unexpected data after the JSON value")
        return value
    }

    private class Reader(private val text: String) {
        private var pos = 0

        fun atEnd() = pos >= text.length

        fun fail(message: String): Nothing = throw MiniJsonException("$message at position $pos")

        fun skipWhitespace() {
            while (pos < text.length && text[pos].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) pos++
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("JSON is nested too deeply")
            if (atEnd()) fail("Unexpected end of JSON")
            return when (val c = text[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (c == '-' || c in '0'..'9') readNumber() else fail("Unexpected character '$c'")
            }
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            pos++ // {
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return result
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("Expected a string as object key")
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                result[key] = readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return result
                    }
                    else -> fail("Expected ',' or '}'")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            pos++ // [
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return result
            }
            while (true) {
                skipWhitespace()
                result.add(readValue(depth + 1))
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return result
                    }
                    else -> fail("Expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            pos++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (atEnd()) fail("Unterminated string")
                val c = text[pos++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> out.append(readEscape())
                    c < ' ' -> fail("Control character in string")
                    else -> out.append(c)
                }
            }
        }

        private fun readEscape(): Char {
            if (atEnd()) fail("Unterminated escape sequence")
            return when (val e = text[pos++]) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (pos + 4 > text.length) fail("Truncated \\u escape")
                    val hex = text.substring(pos, pos + 4)
                    val code = hex.toIntOrNull(16)
                    if (code == null || hex.any { it == '+' || it == '-' }) fail("Invalid \\u escape")
                    pos += 4
                    code.toChar()
                }
                else -> fail("Invalid escape '\\$e'")
            }
        }

        private fun readNumber(): Double {
            val start = pos
            while (pos < text.length && (text[pos] in '0'..'9' || text[pos] in "+-.eE")) pos++
            return text.substring(start, pos).toDoubleOrNull() ?: run {
                pos = start
                fail("Invalid number")
            }
        }

        private fun readLiteral(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) fail("Unexpected token")
            pos += word.length
            return value
        }

        private fun peek(): Char = if (atEnd()) fail("Unexpected end of JSON") else text[pos]

        private fun expect(c: Char) {
            if (peek() != c) fail("Expected '$c'")
            pos++
        }
    }
}
