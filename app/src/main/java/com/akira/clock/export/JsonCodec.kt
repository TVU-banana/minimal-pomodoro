package com.akira.clock.export

/** Minimal JSON codec used to keep the backup format deterministic and dependency-free. */
internal sealed interface JsonValue {
    data class Object(val fields: LinkedHashMap<String, JsonValue>) : JsonValue
    data class Array(val values: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val source: String) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object NullValue : JsonValue
}

internal object JsonCodec {
    fun parse(source: String): JsonValue = Parser(source).parse()

    fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else append(character)
            }
        }
        append('"')
    }

    private class Parser(private val source: String) {
        private var position = 0

        fun parse(): JsonValue {
            skipWhitespace()
            val value = readValue()
            skipWhitespace()
            if (position != source.length) fail("trailing content")
            return value
        }

        private fun readValue(): JsonValue {
            if (position >= source.length) fail("unexpected end")
            return when (source[position]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.StringValue(readString())
                't' -> readLiteral("true", JsonValue.BooleanValue(true))
                'f' -> readLiteral("false", JsonValue.BooleanValue(false))
                'n' -> readLiteral("null", JsonValue.NullValue)
                '-', in '0'..'9' -> readNumber()
                else -> fail("unexpected character")
            }
        }

        private fun readObject(): JsonValue.Object {
            position++
            skipWhitespace()
            val fields = linkedMapOf<String, JsonValue>()
            if (take('}')) return JsonValue.Object(fields)
            while (true) {
                if (position >= source.length || source[position] != '"') {
                    fail("object key expected")
                }
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                if (fields.put(key, readValue()) != null) fail("duplicate key: $key")
                skipWhitespace()
                if (take('}')) return JsonValue.Object(fields)
                expect(',')
                skipWhitespace()
            }
        }

        private fun readArray(): JsonValue.Array {
            position++
            skipWhitespace()
            val values = mutableListOf<JsonValue>()
            if (take(']')) return JsonValue.Array(values)
            while (true) {
                values += readValue()
                skipWhitespace()
                if (take(']')) return JsonValue.Array(values)
                expect(',')
                skipWhitespace()
            }
        }

        private fun readString(): String {
            expect('"')
            val result = StringBuilder()
            while (position < source.length) {
                val character = source[position++]
                when (character) {
                    '"' -> return result.toString()
                    '\\' -> {
                        if (position >= source.length) fail("unfinished escape")
                        when (val escaped = source[position++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000C')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                if (position + 4 > source.length) fail("unfinished unicode escape")
                                val code = source.substring(position, position + 4).toIntOrNull(16)
                                    ?: fail("invalid unicode escape")
                                result.append(code.toChar())
                                position += 4
                            }
                            else -> fail("invalid escape")
                        }
                    }
                    else -> {
                        if (character.code < 0x20) fail("control character in string")
                        result.append(character)
                    }
                }
            }
            fail("unterminated string")
        }

        private fun readNumber(): JsonValue.NumberValue {
            val start = position
            if (take('-') && position >= source.length) fail("invalid number")
            if (take('0')) {
                if (position < source.length && source[position].isDigit()) fail("leading zero")
            } else {
                if (position >= source.length || source[position] !in '1'..'9') {
                    fail("invalid number")
                }
                while (position < source.length && source[position].isDigit()) position++
            }
            if (take('.')) {
                if (position >= source.length || !source[position].isDigit()) {
                    fail("invalid fraction")
                }
                while (position < source.length && source[position].isDigit()) position++
            }
            if (position < source.length && source[position] in "eE") {
                position++
                if (position < source.length && source[position] in "+-") position++
                if (position >= source.length || !source[position].isDigit()) {
                    fail("invalid exponent")
                }
                while (position < source.length && source[position].isDigit()) position++
            }
            return JsonValue.NumberValue(source.substring(start, position))
        }

        private fun <T : JsonValue> readLiteral(text: String, value: T): T {
            if (!source.startsWith(text, position)) fail("invalid literal")
            position += text.length
            return value
        }

        private fun expect(character: Char) {
            if (!take(character)) fail("expected '$character'")
        }

        private fun take(character: Char): Boolean =
            if (position < source.length && source[position] == character) {
                position++
                true
            } else false

        private fun skipWhitespace() {
            while (position < source.length && source[position] in " \t\r\n") position++
        }

        private fun fail(reason: String): Nothing =
            throw BackupFormatException("Invalid JSON at $position: $reason")
    }
}

internal fun JsonValue.objectValue(name: String = "value"): LinkedHashMap<String, JsonValue> =
    (this as? JsonValue.Object)?.fields ?: throw BackupFormatException("$name must be an object")

internal fun JsonValue.arrayValue(name: String): List<JsonValue> =
    (this as? JsonValue.Array)?.values ?: throw BackupFormatException("$name must be an array")

internal fun Map<String, JsonValue>.requiredString(name: String): String =
    (this[name] as? JsonValue.StringValue)?.value
        ?: throw BackupFormatException("$name must be a string")

internal fun Map<String, JsonValue>.optionalString(name: String): String? =
    when (val value = this[name]) {
        null, JsonValue.NullValue -> null
        is JsonValue.StringValue -> value.value
        else -> throw BackupFormatException("$name must be a string or null")
    }

internal fun Map<String, JsonValue>.requiredLong(name: String): Long =
    (this[name] as? JsonValue.NumberValue)?.source?.toLongOrNull()
        ?: throw BackupFormatException("$name must be an integer")

internal fun Map<String, JsonValue>.requiredBoolean(name: String): Boolean =
    (this[name] as? JsonValue.BooleanValue)?.value
        ?: throw BackupFormatException("$name must be a boolean")
