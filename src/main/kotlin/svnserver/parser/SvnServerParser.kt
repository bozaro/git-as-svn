/*
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.parser

import svnserver.parser.token.*
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * Интерфейс для чтения токенов из потока.
 *
 *
 * http://svn.apache.org/repos/asf/subversion/trunk/subversion/libsvn_ra_svn/protocol
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
class SvnServerParser @JvmOverloads constructor(private val stream: InputStream, bufferSize: Int = DEFAULT_BUFFER_SIZE) {
    var depth: Int = 0
        private set
    private val buffer: ByteArray
    private var offset: Int = 0
    private var limit: Int = 0

    @Throws(IOException::class)
    fun readText(): String {
        return readToken(TextToken::class.java).text
    }

    @Throws(IOException::class)
    fun readNumber(): Int {
        return readToken(NumberToken::class.java).number
    }

    /**
     * Чтение элемента указанного типа из потока.
     *
     * @param tokenType Тип элемента.
     * @param <T>       Тип элемента.
     * @return Прочитанный элемент.
    </T> */
    @Throws(IOException::class)
    fun <T : SvnServerToken> readToken(tokenType: Class<T>): T {
        val token: SvnServerToken = readToken()
        if (!tokenType.isInstance(token)) {
            throw IOException("Unexpected token: " + token + " (expected: " + tokenType.name + ')')
        }
        return token as T
    }

    /**
     * Чтение элемента списка из потока.
     *
     * @param tokenType Тип элемента.
     * @param <T>       Тип элемента.
     * @return Прочитанный элемент.
    </T> */
    @Throws(IOException::class)
    fun <T : SvnServerToken> readItem(tokenType: Class<T>): T? {
        val token: SvnServerToken = readToken()
        if ((ListEndToken.instance == token)) {
            return null
        }
        if (!tokenType.isInstance(token)) {
            throw IOException("Unexpected token: " + token + " (expected: " + tokenType.name + ')')
        }
        return token as T
    }

    /**
     * Чтение элемента из потока.
     *
     * @return Возвращает элемент из потока. Если элемента нет - возвращает null.
     */
    @Throws(IOException::class)
    fun readToken(): SvnServerToken {
        val read: Byte = skipSpaces()
        if (read == '('.toByte()) {
            depth++
            return ListBeginToken.instance
        }
        if (read == ')'.toByte()) {
            depth--
            if (depth < 0) {
                throw IOException("Unexpect end of list token.")
            }
            return ListEndToken.instance
        }
        // Чтение чисел и строк.
        if (isDigit(read.toInt())) {
            return readNumberToken(read)
        }
        // Обычная строчка.
        if (isAlpha(read.toInt())) {
            return readWord()
        }
        throw IOException("Unexpected character in stream: " + read + " (need 'a'..'z', 'A'..'Z', '0'..'9', ' ' or '\\n')")
    }

    @Throws(IOException::class)
    private fun readNumberToken(first: Byte): SvnServerToken {
        var result: Int = first - '0'.toByte()
        while (true) {
            while (offset < limit) {
                val data: Byte = buffer.get(offset)
                offset++
                if ((data < '0'.toByte()) || (data > '9'.toByte())) {
                    if (data == ':'.toByte()) {
                        return readString(result)
                    }
                    if (isSpace(data.toInt())) {
                        return NumberToken(result)
                    }
                    throw IOException("Unexpected character in stream: " + data + " (need ' ', '\\n' or ':')")
                }
                result = result * 10 + (data - '0'.toByte())
            }
            if (limit < 0) {
                throw EOFException()
            }
            offset = 0
            limit = stream.read(buffer)
        }
    }

    @Throws(IOException::class)
    private fun skipSpaces(): Byte {
        while (true) {
            while (offset < limit) {
                val data: Byte = buffer.get(offset)
                offset++
                if (!isSpace(data.toInt())) {
                    return data
                }
            }
            if (limit < 0) {
                throw EOFException()
            }
            offset = 0
            limit = stream.read(buffer)
        }
    }

    @Throws(IOException::class)
    private fun readString(length: Int): StringToken {
        if (length >= MAX_BUFFER_SIZE) {
            throw IOException("Data is too long. Buffer overflow: " + buffer.size)
        }
        if (limit < 0) {
            throw EOFException()
        }
        val token = ByteArray(length)
        if (length <= limit - offset) {
            System.arraycopy(buffer, offset, token, 0, length)
            offset += length
        } else {
            var position: Int = limit - offset
            System.arraycopy(buffer, offset, token, 0, position)
            limit = 0
            offset = 0
            while (position < length) {
                val size: Int = stream.read(token, position, length - position)
                if (size < 0) {
                    limit = -1
                    throw EOFException()
                }
                position += size
            }
        }
        return StringToken(Arrays.copyOf(token, length))
    }

    @Throws(IOException::class)
    private fun readWord(): WordToken {
        val begin: Int = offset - 1
        while (offset < limit) {
            val data: Byte = buffer.get(offset)
            offset++
            if (isSpace(data.toInt())) {
                return WordToken(String(buffer, begin, offset - begin - 1, StandardCharsets.US_ASCII))
            }
            if (!(isAlpha(data.toInt()) || isDigit(data.toInt()) || (data == '-'.toByte()))) {
                throw IOException("Unexpected character in stream: " + data + " (need 'a'..'z', 'A'..'Z', '0'..'9' or '-')")
            }
        }
        System.arraycopy(buffer, begin, buffer, 0, limit - begin)
        limit = offset - begin
        offset = limit
        while (limit < buffer.size) {
            val size: Int = stream.read(buffer, limit, buffer.size - limit)
            if (size < 0) {
                throw EOFException()
            }
            limit += size
            while (offset < limit) {
                val data: Byte = buffer.get(offset)
                offset++
                if (isSpace(data.toInt())) {
                    return WordToken(String(buffer, 0, offset - 1, StandardCharsets.US_ASCII))
                }
                if (!(isAlpha(data.toInt()) || isDigit(data.toInt()) || (data == '-'.toByte()))) {
                    throw IOException("Unexpected character in stream: " + data + " (need 'a'..'z', 'A'..'Z', '0'..'9' or '-')")
                }
            }
        }
        throw IOException("Data is too long. Buffer overflow: " + buffer.size)
    }

    @Throws(IOException::class)
    fun skipItems() {
        var depth = 0
        while (depth >= 0) {
            val token: SvnServerToken = readToken(SvnServerToken::class.java)
            if ((ListBeginToken.instance == token)) {
                depth++
            }
            if ((ListEndToken.instance == token)) {
                depth--
            }
        }
    }

    companion object {
        private val DEFAULT_BUFFER_SIZE: Int = 32 * 1024

        // Buffer size limit for out-of-memory prevention.
        private val MAX_BUFFER_SIZE: Int = 10 * 1024 * 1024
        private fun isSpace(data: Int): Boolean {
            return ((data == ' '.toInt())
                    || (data == '\n'.toInt()))
        }

        private fun isDigit(data: Int): Boolean {
            return (data >= '0'.toInt() && data <= '9'.toInt())
        }

        private fun isAlpha(data: Int): Boolean {
            return ((data >= 'a'.toInt() && data <= 'z'.toInt())
                    || (data >= 'A'.toInt() && data <= 'Z'.toInt()))
        }
    }

    init {
        buffer = ByteArray(Math.max(1, bufferSize))
    }
}
