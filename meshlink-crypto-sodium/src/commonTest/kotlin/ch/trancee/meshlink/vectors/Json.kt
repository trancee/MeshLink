package ch.trancee.meshlink.vectors

/**
 * A parsed JSON value, read by [Json.parse]. Test-only: it reads the embedded vector files, so the
 * runner needs no JSON library (ticket 19, ADR 0005).
 */
internal sealed interface Json {
    class Object(
        val members: Map<String, Json>,
    ) : Json

    class Array(
        val items: List<Json>,
    ) : Json

    class Text(
        val value: String,
    ) : Json

    /** A number kept as its source text, so a u64 such as a nonce stays exact. */
    class Number(
        val text: String,
    ) : Json

    class Bool(
        val value: Boolean,
    ) : Json

    object Null : Json

    operator fun get(key: String): Json {
        val member = (this as? Object)?.members?.get(key)
        return member ?: throw AssertionError("JSON has no member \"$key\"")
    }

    fun has(key: String): Boolean = (this as? Object)?.members?.containsKey(key) == true

    val string: String get() = (this as? Text)?.value ?: throw AssertionError("JSON value is not a string")

    val list: List<Json> get() = (this as? Array)?.items ?: throw AssertionError("JSON value is not an array")

    val bool: Boolean get() = (this as? Bool)?.value ?: throw AssertionError("JSON value is not a boolean")

    val int: Int get() = number.toInt()

    val uInt: UInt get() = number.toUInt()

    val long: Long get() = number.toLong()

    val uLong: ULong get() = number.toULong()

    private val number: String get() = (this as? Number)?.text ?: throw AssertionError("JSON value is not a number")

    companion object {
        fun parse(text: String): Json = Parser(text).document()
    }
}

private class Parser(
    private val text: String,
) {
    private var at = 0

    fun document(): Json {
        val value = value()
        skipWhitespace()
        if (at != text.length) fail("trailing characters")
        return value
    }

    private fun value(): Json {
        skipWhitespace()
        return when (peek()) {
            '{' -> obj()
            '[' -> array()
            '"' -> Json.Text(string())
            't' -> literal("true", Json.Bool(true))
            'f' -> literal("false", Json.Bool(false))
            'n' -> literal("null", Json.Null)
            else -> number()
        }
    }

    private fun obj(): Json {
        expect('{')
        val members = LinkedHashMap<String, Json>()
        skipWhitespace()
        if (peek() == '}') {
            at++
            return Json.Object(members)
        }
        do {
            skipWhitespace()
            val key = string()
            skipWhitespace()
            expect(':')
            if (members.put(key, value()) != null) fail("duplicate member \"$key\"")
            skipWhitespace()
        } while (next() == ',')
        if (text[at - 1] != '}') fail("expected '}'")
        return Json.Object(members)
    }

    private fun array(): Json {
        expect('[')
        val items = ArrayList<Json>()
        skipWhitespace()
        if (peek() == ']') {
            at++
            return Json.Array(items)
        }
        do {
            items += value()
            skipWhitespace()
        } while (next() == ',')
        if (text[at - 1] != ']') fail("expected ']'")
        return Json.Array(items)
    }

    private fun string(): String {
        expect('"')
        return buildString {
            while (true) {
                when (val char = next()) {
                    '"' -> return@buildString
                    '\\' -> append(escape())
                    else -> if (char < ' ') fail("control character in string") else append(char)
                }
            }
        }
    }

    private fun escape(): Char =
        when (val char = next()) {
            '"', '\\', '/' -> {
                char
            }

            'b' -> {
                '\b'
            }

            'f' -> {
                '\u000c'
            }

            'n' -> {
                '\n'
            }

            'r' -> {
                '\r'
            }

            't' -> {
                '\t'
            }

            'u' -> {
                if (at + HEX_DIGITS > text.length) fail("short \\u escape")
                text
                    .substring(at, at + HEX_DIGITS)
                    .toInt(HEX)
                    .toChar()
                    .also { at += HEX_DIGITS }
            }

            else -> {
                fail("bad escape \\$char")
            }
        }

    private fun number(): Json {
        val start = at
        while (at < text.length && text[at] in NUMBER_CHARS) at++
        val number = text.substring(start, at)
        if (number.isEmpty() || number.toDoubleOrNull() == null) fail("bad value")
        return Json.Number(number)
    }

    private fun literal(
        word: String,
        value: Json,
    ): Json {
        if (!text.startsWith(word, at)) fail("bad literal")
        at += word.length
        return value
    }

    private fun skipWhitespace() {
        while (at < text.length && text[at] in " \t\r\n") at++
    }

    private fun expect(char: Char) {
        if (next() != char) fail("expected '$char'")
    }

    private fun peek(): Char = if (at < text.length) text[at] else fail("unexpected end")

    private fun next(): Char = peek().also { at++ }

    private fun fail(reason: String): Nothing = throw IllegalArgumentException("JSON at $at: $reason")

    private companion object {
        const val HEX = 16
        const val HEX_DIGITS = 4
        const val NUMBER_CHARS = "+-0123456789.eE"
    }
}
