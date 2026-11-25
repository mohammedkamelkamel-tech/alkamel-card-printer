package com.alkamel.cardprinter.data.mikrotik

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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

data class RouterOsStreamResult(
    val recordCount: Int,
    val doneAttributes: Map<String, String>,
    val trapMessage: String? = null
) {
    val isSuccess: Boolean get() = trapMessage == null
}

/**
 * Native pure-Kotlin MikroTik RouterOS Binary API Client (supports RouterOS v6 & v7,
 * including both modern plaintext /login and legacy MD5 challenge-response /login).
 *
 * Features:
 * - Persistent reusable socket with transparent auto-reconnect on idle drop / closed socket
 * - Streaming record consumption for 50,000+ User Manager cards/sessions without RAM blowup
 * - Clear Arabic diagnostic errors reflecting the actual transport or RouterOS failure
 */
class MikrotikApiClient(
    private val connectTimeoutMs: Int = 8000,
    private val readTimeoutMs: Int = 20000
) : AutoCloseable {

    @Volatile
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null

    @Volatile
    private var lastCredentials: MikrotikCredentials? = null

    val isOpen: Boolean
        get() = socket?.let {
            it.isConnected && !it.isClosed && !it.isInputShutdown && !it.isOutputShutdown
        } == true

    fun hasSameCredentials(credentials: MikrotikCredentials): Boolean {
        val current = lastCredentials ?: return false
        return current.host.trim().equals(credentials.host.trim(), ignoreCase = true) &&
            current.port == credentials.port &&
            current.username.trim() == credentials.username.trim() &&
            current.password == credentials.password
    }

    fun setReadTimeout(timeoutMs: Int) {
        val safeTimeout = timeoutMs.coerceIn(3000, 300000)
        runCatching { socket?.soTimeout = safeTimeout }
    }

    /**
     * Ensures the socket is connected and authenticated.
     * Reuses the active connection if already open with the same credentials,
     * otherwise cleanly reconnects and logs in.
     */
    suspend fun ensureConnected(credentials: MikrotikCredentials? = null): Result<Unit> {
        val target = credentials ?: lastCredentials
            ?: return Result.failure(IOException("لا توجد بيانات اتصال محفوظة للمايكروتيك."))
        if (isOpen && hasSameCredentials(target)) {
            return Result.success(Unit)
        }
        return connectAndLogin(target)
    }

    suspend fun connectAndLogin(credentials: MikrotikCredentials): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            close()
            val cleanHost = credentials.host.trim()
            if (cleanHost.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("عنوان السيرفر (IP / Host) فارغ."))
            }
            val cleanPort = credentials.port
            if (cleanPort !in 1..65535) {
                return@withContext Result.failure(IllegalArgumentException("رقم منفذ API غير صالح ($cleanPort)."))
            }
            val cleanUser = credentials.username.trim()
            if (cleanUser.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("اسم المستخدم للمايكروتيك فارغ."))
            }

            val newSocket = Socket()
            runCatching {
                newSocket.tcpNoDelay = true
                newSocket.keepAlive = true
                newSocket.receiveBufferSize = 256 * 1024
            }
            newSocket.connect(InetSocketAddress(cleanHost, cleanPort), connectTimeoutMs)
            newSocket.soTimeout = readTimeoutMs

            socket = newSocket
            input = BufferedInputStream(newSocket.getInputStream(), 64 * 1024)
            output = BufferedOutputStream(newSocket.getOutputStream(), 16 * 1024)

            // Attempt modern RouterOS v6.43+ / v7 login first
            val firstRes = executeInternal(
                listOf(
                    "/login",
                    "=name=$cleanUser",
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
                        "=name=$cleanUser",
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

            lastCredentials = credentials.copy(
                host = cleanHost,
                port = cleanPort,
                username = cleanUser
            )
            Result.success(Unit)
        } catch (e: Exception) {
            close()
            Result.failure(IOException(formatTransportError(e, credentials), e))
        }
    }

    suspend fun execute(
        command: String,
        params: List<String> = emptyList(),
        timeoutMs: Int? = null,
        autoReconnect: Boolean = true
    ): Result<RouterOsCommandResult> = withContext(Dispatchers.IO) {
        val words = ArrayList<String>(1 + params.size).apply {
            add(command)
            addAll(params)
        }
        val maxAttempts = if (autoReconnect && lastCredentials != null) 2 else 1

        for (attempt in 1..maxAttempts) {
            if (!isOpen) {
                val creds = lastCredentials
                if (autoReconnect && creds != null) {
                    val recon = connectAndLogin(creds)
                    if (recon.isFailure) {
                        return@withContext Result.failure(
                            recon.exceptionOrNull() ?: IOException("تعذر إعادة الاتصال بالمايكروتيك.")
                        )
                    }
                } else {
                    return@withContext Result.failure(
                        IOException("لا يوجد اتصال مفتوح بالمايكروتيك لتنفيذ ($command).")
                    )
                }
            }

            val previousTimeout = runCatching { socket?.soTimeout }.getOrNull()
            try {
                if (timeoutMs != null) {
                    setReadTimeout(timeoutMs)
                }
                return@withContext Result.success(executeInternal(words))
            } catch (e: SocketTimeoutException) {
                close()
                return@withContext Result.failure(
                    IOException(
                        "انتهت مهلة قراءة البيانات من المايكروتيك أثناء تنفيذ ($command). قد تكون البيانات كبيرة أو الشبكة بطيئة.",
                        e
                    )
                )
            } catch (e: Exception) {
                val isRecoverableDrop = e is EOFException || e is SocketException
                close()
                if (isRecoverableDrop && attempt < maxAttempts) {
                    continue
                }
                val readable = lastCredentials?.let { formatTransportError(e, it) }
                    ?: (e.localizedMessage ?: "انقطع الاتصال بالمايكروتيك أثناء تنفيذ ($command).")
                return@withContext Result.failure(IOException(readable, e))
            } finally {
                if (timeoutMs != null && previousTimeout != null && isOpen) {
                    runCatching { socket?.soTimeout = previousTimeout }
                }
            }
        }
        Result.failure(IOException("تعذر تنفيذ الأمر ($command) على المايكروتيك."))
    }

    /**
     * Streams every `!re` record directly to [onRecord] until `!done` is received,
     * ensuring 100% of records are consumed without holding tens of thousands of raw maps in RAM.
     * Automatically reconnects if the socket was closed or dropped before streaming started.
     */
    suspend fun executeStreaming(
        command: String,
        params: List<String> = emptyList(),
        timeoutMs: Int = 120_000,
        autoReconnect: Boolean = true,
        onRecord: (index: Int, record: Map<String, String>) -> Unit
    ): Result<RouterOsStreamResult> = withContext(Dispatchers.IO) {
        val words = ArrayList<String>(1 + params.size).apply {
            add(command)
            addAll(params)
        }
        val maxAttempts = if (autoReconnect && lastCredentials != null) 2 else 1

        for (attempt in 1..maxAttempts) {
            if (!isOpen) {
                val creds = lastCredentials
                if (autoReconnect && creds != null) {
                    val recon = connectAndLogin(creds)
                    if (recon.isFailure) {
                        return@withContext Result.failure(
                            recon.exceptionOrNull() ?: IOException("تعذر إعادة الاتصال بالمايكروتيك.")
                        )
                    }
                } else {
                    return@withContext Result.failure(
                        IOException("لا يوجد اتصال مفتوح بالمايكروتيك لتنفيذ ($command).")
                    )
                }
            }

            val previousTimeout = runCatching { socket?.soTimeout }.getOrNull()
            var emittedAny = false
            try {
                setReadTimeout(timeoutMs)
                val result = executeStreamingInternal(words) { idx, rec ->
                    emittedAny = true
                    onRecord(idx, rec)
                }
                return@withContext Result.success(result)
            } catch (e: SocketTimeoutException) {
                close()
                return@withContext Result.failure(
                    IOException(
                        "انتهت مهلة جلب البيانات من المايكروتيك ($command). أعد المحاولة عند استقرار الشبكة.",
                        e
                    )
                )
            } catch (e: Exception) {
                val isRecoverableDrop = !emittedAny && (e is EOFException || e is SocketException)
                close()
                if (isRecoverableDrop && attempt < maxAttempts) {
                    continue
                }
                val readable = lastCredentials?.let { formatTransportError(e, it) }
                    ?: (e.localizedMessage ?: "انقطع الاتصال بالمايكروتيك أثناء قراءة ($command).")
                return@withContext Result.failure(IOException(readable, e))
            } finally {
                if (previousTimeout != null && isOpen) {
                    runCatching { socket?.soTimeout = previousTimeout }
                }
            }
        }
        Result.failure(IOException("تعذر قراءة البيانات من المايكروتيك ($command)."))
    }

    private fun executeInternal(words: List<String>): RouterOsCommandResult {
        val records = mutableListOf<Map<String, String>>()
        val streamRes = executeStreamingInternal(words) { _, rec ->
            records.add(rec)
        }
        return RouterOsCommandResult(
            records = records,
            doneAttributes = streamRes.doneAttributes,
            trapMessage = streamRes.trapMessage
        )
    }

    private fun executeStreamingInternal(
        words: List<String>,
        onRecord: (index: Int, record: Map<String, String>) -> Unit
    ): RouterOsStreamResult {
        val out = output ?: throw SocketException("قناة الإرسال غير متصلة")
        for (word in words) {
            writeWord(out, word)
        }
        // Empty zero-length word terminates the sentence
        out.write(0)
        out.flush()

        var recordCount = 0
        val doneAttrs = mutableMapOf<String, String>()
        var trapMessage: String? = null

        while (true) {
            val sentence = readSentence() ?: throw EOFException("انقطع الاتصال بالمايكروتيك قبل اكتمال جلب البيانات")
            when (sentence.type) {
                "!re" -> {
                    onRecord(recordCount, sentence.attributes)
                    recordCount++
                }
                "!trap" -> {
                    trapMessage = sentence.attributes["message"] ?: "خطأ من المايكروتيك (!trap)"
                }
                "!fatal" -> {
                    val fatalMsg = sentence.attributes["message"] ?: "أغلق المايكروتيك الاتصال (!fatal)"
                    close()
                    return RouterOsStreamResult(recordCount, doneAttrs, fatalMsg)
                }
                "!done" -> {
                    doneAttrs.putAll(sentence.attributes)
                    break
                }
            }
        }
        return RouterOsStreamResult(
            recordCount = recordCount,
            doneAttributes = doneAttrs,
            trapMessage = trapMessage
        )
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
        val attrs = LinkedHashMap<String, String>(words.size)
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

    private fun formatTransportError(e: Exception, credentials: MikrotikCredentials): String {
        val endpoint = "${credentials.host.trim()}:${credentials.port}"
        return when (e) {
            is SocketTimeoutException ->
                "انتهت مهلة الاتصال بالمايكروتيك ($endpoint). تأكد من أن الهاتف على نفس الشبكة وأن خدمة API مفعلة."
            is ConnectException ->
                "تعذر الوصول إلى المايكروتيك ($endpoint). تأكد من عنوان IP والمنفذ وتفعيل IP -> Services -> api."
            is UnknownHostException ->
                "عنوان السيرفر غير معروف (${credentials.host.trim()})."
            is EOFException ->
                "انقطع الاتصال مع المايكروتيك ($endpoint) أثناء استلام البيانات."
            is SocketException ->
                "تعذر استمرار الاتصال الشبكي مع المايكروتيك ($endpoint): ${e.localizedMessage ?: "انقطع الاتصال"}"
            else ->
                e.localizedMessage ?: "تعذر الاتصال بسيرفر المايكروتيك ($endpoint)."
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
