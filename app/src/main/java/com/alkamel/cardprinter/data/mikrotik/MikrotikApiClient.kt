package com.alkamel.cardprinter.data.mikrotik

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

data class RouterOsSentence(
    val type: String,
    val attributes: Map<String, String>
)

data class RouterOsCommandResult(
    val records: List<Map<String, String>>,
    val doneAttributes: Map<String, String>,
    val trapMessage: String? = null
) {
    val isSuccess: Boolean get() = trapMessage == null
}

/**
 * Native pure-Kotlin MikroTik RouterOS Binary API Client (supports RouterOS v6 & v7,
 * including both modern plaintext /login and legacy MD5 challenge-response /login).
 * All I/O executes strictly on Dispatchers.IO with timeouts and safe resource cleanup.
 */
class MikrotikApiClient(
    private val connectTimeoutMs: Int = 6000,
    private val readTimeoutMs: Int = 10000
) : AutoCloseable {

    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null

    val isOpen: Boolean
        get() = socket?.let { it.isConnected && !it.isClosed } == true

    suspend fun connectAndLogin(credentials: MikrotikCredentials): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            close()
            val cleanHost = credentials.host.trim()
            if (cleanHost.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("عنوان السيرفر (IP / Host) فارغ."))
            }
            val cleanPort = credentials.port.coerceIn(1, 65535)
            val newSocket = Socket()
            newSocket.connect(InetSocketAddress(cleanHost, cleanPort), connectTimeoutMs)
            newSocket.soTimeout = readTimeoutMs

            socket = newSocket
            input = BufferedInputStream(newSocket.getInputStream())
            output = BufferedOutputStream(newSocket.getOutputStream())

            // Attempt modern RouterOS v6.43+ / v7 login first
            val firstRes = executeInternal(
                listOf(
                    "/login",
                    "=name=${credentials.username.trim()}",
                    "=password=${credentials.password}"
                )
            )
            if (!firstRes.isSuccess) {
                close()
                return@withContext Result.failure(
                    IOException("رفض المايكروتيك تسجيل الدخول: ${firstRes.trapMessage}")
                )
            }

            // Legacy RouterOS (< 6.43) returns a challenge token in =ret
            val challengeHex = firstRes.doneAttributes["ret"]
            if (!challengeHex.isNullOrBlank()) {
                val md5Response = computeLegacyLoginHash(credentials.password, challengeHex)
                val secondRes = executeInternal(
                    listOf(
                        "/login",
                        "=name=${credentials.username.trim()}",
                        "=response=00$md5Response"
                    )
                )
                if (!secondRes.isSuccess) {
                    close()
                    return@withContext Result.failure(
                        IOException("فشل التحقق من بيانات الدخول على المايكروتيك: ${secondRes.trapMessage}")
                    )
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            close()
            val readable = when {
                e is java.net.SocketTimeoutException -> "انتهت مهلة الاتصال بالمايكروتيك (${credentials.host}:${credentials.port}). تأكد من أن الهاتف على نفس الشبكة وأن خدمة API مفعلة."
                e is java.net.ConnectException -> "تعذر الوصول إلى المايكروتيك (${credentials.host}:${credentials.port}). تأكد من العنوان والمنفذ وتفعيل IP -> Services -> api."
                e is java.net.UnknownHostException -> "عنوان السيرفر غير معروف (${credentials.host})."
                else -> e.localizedMessage ?: "تعذر الاتصال بسيرفر المايكروتيك."
            }
            Result.failure(IOException(readable, e))
        }
    }

    suspend fun execute(command: String, params: List<String> = emptyList()): Result<RouterOsCommandResult> =
        withContext(Dispatchers.IO) {
            try {
                if (!isOpen) {
                    return@withContext Result.failure(IOException("الاتصال بالمايكروتيك مغلق."))
                }
                val words = ArrayList<String>(1 + params.size).apply {
                    add(command)
                    addAll(params)
                }
                Result.success(executeInternal(words))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun executeInternal(words: List<String>): RouterOsCommandResult {
        val out = output ?: throw IOException("قناة الإرسال غير متصلة")
        for (word in words) {
            writeWord(out, word)
        }
        // Empty zero-length word terminates the sentence
        out.write(0)
        out.flush()

        val records = mutableListOf<Map<String, String>>()
        val doneAttrs = mutableMapOf<String, String>()
        var trapMessage: String? = null

        while (true) {
            val sentence = readSentence() ?: throw EOFException("انقطع الاتصال بالمايكروتيك بشكل غير متوقع")
            when (sentence.type) {
                "!re" -> records.add(sentence.attributes)
                "!trap" -> {
                    trapMessage = sentence.attributes["message"] ?: "خطأ من المايكروتيك (!trap)"
                }
                "!fatal" -> {
                    val fatalMsg = sentence.attributes["message"] ?: "أغلق المايكروتيك الاتصال (!fatal)"
                    close()
                    return RouterOsCommandResult(records, doneAttrs, fatalMsg)
                }
                "!done" -> {
                    doneAttrs.putAll(sentence.attributes)
                    break
                }
            }
        }
        return RouterOsCommandResult(records = records, doneAttributes = doneAttrs, trapMessage = trapMessage)
    }

    private fun readSentence(): RouterOsSentence? {
        val inp = input ?: return null
        val words = mutableListOf<String>()
        while (true) {
            val word = readWord(inp) ?: return if (words.isEmpty()) null else parseSentence(words)
            if (word.isEmpty()) {
                if (words.isEmpty()) continue
                return parseSentence(words)
            }
            words.add(word)
        }
    }

    private fun parseSentence(words: List<String>): RouterOsSentence {
        val type = words.firstOrNull().orEmpty()
        val attrs = LinkedHashMap<String, String>()
        for (i in 1 until words.size) {
            val w = words[i]
            if (w.startsWith("=")) {
                val sep = w.indexOf('=', startIndex = 1)
                if (sep > 1) {
                    val key = w.substring(1, sep)
                    val value = w.substring(sep + 1)
                    attrs[key] = value
                } else {
                    attrs[w.substring(1)] = ""
                }
            }
        }
        return RouterOsSentence(type = type, attributes = attrs)
    }

    private fun writeWord(out: BufferedOutputStream, word: String) {
        val bytes = word.toByteArray(Charsets.UTF_8)
        writeLength(out, bytes.size)
        if (bytes.isNotEmpty()) {
            out.write(bytes)
        }
    }

    private fun writeLength(out: BufferedOutputStream, length: Int) {
        when {
            length < 0x80 -> {
                out.write(length)
            }
            length < 0x4000 -> {
                val v = length or 0x8000
                out.write((v shr 8) and 0xFF)
                out.write(v and 0xFF)
            }
            length < 0x200000 -> {
                val v = length or 0xC00000
                out.write((v shr 16) and 0xFF)
                out.write((v shr 8) and 0xFF)
                out.write(v and 0xFF)
            }
            length < 0x10000000 -> {
                val v = length or 0xE0000000.toInt()
                out.write((v shr 24) and 0xFF)
                out.write((v shr 16) and 0xFF)
                out.write((v shr 8) and 0xFF)
                out.write(v and 0xFF)
            }
            else -> {
                out.write(0xF0)
                out.write((length shr 24) and 0xFF)
                out.write((length shr 16) and 0xFF)
                out.write((length shr 8) and 0xFF)
                out.write(length and 0xFF)
            }
        }
    }

    private fun readWord(inp: BufferedInputStream): String? {
        val length = readLength(inp) ?: return null
        if (length == 0) return ""
        val buf = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = inp.read(buf, offset, length - offset)
            if (read == -1) throw EOFException("انقطع بث البيانات من المايكروتيك")
            offset += read
        }
        return String(buf, Charsets.UTF_8)
    }

    private fun readLength(inp: BufferedInputStream): Int? {
        val b0 = inp.read()
        if (b0 == -1) return null
        return when {
            (b0 and 0x80) == 0x00 -> b0
            (b0 and 0xC0) == 0x80 -> {
                val b1 = readByteOrThrow(inp)
                ((b0 and 0x3F) shl 8) or b1
            }
            (b0 and 0xE0) == 0xC0 -> {
                val b1 = readByteOrThrow(inp)
                val b2 = readByteOrThrow(inp)
                ((b0 and 0x1F) shl 16) or (b1 shl 8) or b2
            }
            (b0 and 0xF0) == 0xE0 -> {
                val b1 = readByteOrThrow(inp)
                val b2 = readByteOrThrow(inp)
                val b3 = readByteOrThrow(inp)
                ((b0 and 0x0F) shl 24) or (b1 shl 16) or (b2 shl 8) or b3
            }
            else -> {
                val b1 = readByteOrThrow(inp)
                val b2 = readByteOrThrow(inp)
                val b3 = readByteOrThrow(inp)
                val b4 = readByteOrThrow(inp)
                (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
            }
        }
    }

    private fun readByteOrThrow(inp: BufferedInputStream): Int {
        val v = inp.read()
        if (v == -1) throw EOFException("نهاية غير متوقعة في طول الحزمة")
        return v
    }

    private fun computeLegacyLoginHash(password: String, challengeHex: String): String {
        val challengeBytes = hexToBytes(challengeHex)
        val baos = ByteArrayOutputStream()
        baos.write(0)
        baos.write(password.toByteArray(Charsets.ISO_8859_1))
        baos.write(challengeBytes)
        val digest = MessageDigest.getInstance("MD5").digest(baos.toByteArray())
        return digest.joinToString("") { String.format(java.util.Locale.US, "%02x", it) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val len = clean.length / 2
        return ByteArray(len) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    override fun close() {
        runCatching { output?.close() }
        runCatching { input?.close() }
        runCatching { socket?.close() }
        output = null
        input = null
        socket = null
    }
}
