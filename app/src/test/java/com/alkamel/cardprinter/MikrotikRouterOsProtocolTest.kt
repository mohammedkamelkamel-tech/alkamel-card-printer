package com.alkamel.cardprinter

import com.alkamel.cardprinter.data.local.entity.PrintSalesLogEntity
import com.alkamel.cardprinter.data.local.entity.effectiveOperationsCount
import com.alkamel.cardprinter.data.local.model.SaleOperationType
import com.alkamel.cardprinter.data.mikrotik.MikrotikApiClient
import com.alkamel.cardprinter.data.mikrotik.MikrotikCredentials
import com.alkamel.cardprinter.data.mikrotik.MikrotikSyncManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real JVM socket-level protocol tests for MikroTik RouterOS Binary API (v6 & v7),
 * verifying:
 * 1. RouterOS v6.49+ plaintext login & fast live telemetry (/system/resource + /ip/hotspot/active)
 * 2. Legacy RouterOS (< 6.43) MD5 challenge-response login
 * 3. Persistent telemetry socket reuse across polls & automatic reconnect after abrupt disconnect
 * 4. Recovery after SocketTimeoutException + full non-truncated streaming of 2,500+ User Manager cards
 * 5. RouterOS v7 indexed active session telemetry (/user-manager/session/print?active=true)
 * 6. Clear Arabic diagnostics on wrong credentials, invalid host/port, and unreachable router
 */
class MikrotikRouterOsProtocolTest {

    @After
    fun tearDown() {
        MikrotikSyncManager.closeTelemetryConnection()
    }

    @Test
    fun testPlaintextLoginAndV6LiveTelemetryFastPath() = runBlocking {
        val seenCommands = CopyOnWriteArrayList<String>()
        val server = EmbeddedRouterOsServer(
            expectedUser = "admin",
            expectedPass = "secret123",
            routerOsVersion = "6.49.19 (long-term)",
            cpuLoad = 27,
            uptime = "4d14h7m40s",
            hotspotActiveCount = 192,
            hotspotBytesOutPerUser = 113_860_000L, // ~20.36 GB total across 192 users
            onCommand = { seenCommands.add(it) }
        )

        server.use { srv ->
            val creds = MikrotikCredentials("127.0.0.1", srv.port, "admin", "secret123")
            val result = MikrotikSyncManager.testConnectionAndFetchStatus(creds)

            assertTrue("Expected successful RouterOS v6 connection, got: ${result.errorMessage}", result.isConnected)
            val status = result.systemStatus
            assertNotNull(status)
            assertEquals(27, status!!.cpuLoadPercent)
            assertEquals("4d14h7m40s", status.uptimeDisplay)
            assertEquals("6.49.19 (long-term)", status.routerOsVersion)
            assertEquals(192, status.activeConnectionsCount)
            assertEquals(192L * (113_860_000L + 1024L), status.totalDownloadBytes)
            assertTrue("Download display should be in GB, got: ${status.totalDownloadDisplay}", status.totalDownloadDisplay.contains("GB"))

            // Critical regression guard: on RouterOS v6 when hotspot active sessions already exist,
            // we must NOT stall on unindexed /tool/user-manager/session/print?active=true!
            assertFalse(
                "RouterOS v6 telemetry should not query slow /tool/user-manager/session/print when hotspot active sessions exist",
                seenCommands.contains("/tool/user-manager/session/print")
            )
        }
    }

    @Test
    fun testLegacyMd5ChallengeLoginFlow() = runBlocking {
        val server = EmbeddedRouterOsServer(
            expectedUser = "admin",
            expectedPass = "legacyPass",
            requireLegacyMd5Login = true,
            routerOsVersion = "6.42.12 (long-term)",
            cpuLoad = 14,
            uptime = "1d2h3m4s"
        )

        server.use { srv ->
            val client = MikrotikApiClient(connectTimeoutMs = 3000, readTimeoutMs = 5000)
            client.use {
                val creds = MikrotikCredentials("127.0.0.1", srv.port, "admin", "legacyPass")
                val loginRes = it.connectAndLogin(creds)
                assertTrue("Legacy MD5 login should succeed: ${loginRes.exceptionOrNull()}", loginRes.isSuccess)
                assertTrue(it.isOpen)

                val res = it.execute("/system/resource/print")
                assertTrue(res.isSuccess)
                assertEquals("14", res.getOrThrow().records.firstOrNull()?.get("cpu-load"))
            }
        }
    }

    @Test
    fun testPersistentTelemetryReusesSingleTcpConnectionAndRecoversFromDrop() = runBlocking {
        val server = EmbeddedRouterOsServer(
            expectedUser = "admin",
            expectedPass = "pass",
            routerOsVersion = "6.49.19 (long-term)",
            cpuLoad = 22,
            uptime = "5d01h10m00s",
            hotspotActiveCount = 48,
            hotspotBytesOutPerUser = 50_000_000L
        )

        server.use { srv ->
            val creds = MikrotikCredentials("127.0.0.1", srv.port, "admin", "pass")

            // 3 consecutive live telemetry polls MUST reuse the exact same single TCP connection
            for (i in 1..3) {
                val poll = MikrotikSyncManager.fetchLiveTelemetry(creds)
                assertTrue("Poll #$i should succeed: ${poll.exceptionOrNull()}", poll.isSuccess)
                assertEquals(22, poll.getOrThrow().cpuLoadPercent)
                assertEquals(48, poll.getOrThrow().activeConnectionsCount)
            }
            assertEquals("Persistent telemetry must reuse 1 TCP socket across polls", 1, srv.acceptedConnections.get())

            // Simulate sudden network drop / router kicking the idle socket
            srv.forceCloseActiveClients()
            Thread.sleep(50)
            srv.cpuLoad = 35
            srv.hotspotActiveCount = 61

            // Next poll must transparently reconnect and fetch the updated metrics without error
            val recovered = MikrotikSyncManager.fetchLiveTelemetry(creds)
            assertTrue("Telemetry must auto-reconnect after drop: ${recovered.exceptionOrNull()}", recovered.isSuccess)
            assertEquals(35, recovered.getOrThrow().cpuLoadPercent)
            assertEquals(61, recovered.getOrThrow().activeConnectionsCount)
            assertEquals("Expected exactly 1 reconnect (2 total TCP sockets)", 2, srv.acceptedConnections.get())
        }
    }

    @Test
    fun testV6Streaming2500CardsAndAutoReconnectAfterTimeout() = runBlocking {
        val server = EmbeddedRouterOsServer(
            expectedUser = "admin",
            expectedPass = "pass",
            routerOsVersion = "6.49.19 (long-term)",
            stallCommand = "/tool/user-manager/session/print",
            stallDelayMs = 4500L,
            userManagerCardsCount = 2500
        )

        server.use { srv ->
            val client = MikrotikApiClient(connectTimeoutMs = 3000, readTimeoutMs = 5000)
            client.use {
                val creds = MikrotikCredentials("127.0.0.1", srv.port, "admin", "pass")
                assertTrue(it.connectAndLogin(creds).isSuccess)

                // Trigger a real SocketTimeoutException on a stalled v6 session query
                val stalled = it.execute(
                    command = "/tool/user-manager/session/print",
                    params = listOf("?active=true"),
                    timeoutMs = 3000,
                    autoReconnect = false
                )
                assertTrue("Stalled command should time out cleanly", stalled.isFailure)
                assertFalse("Socket should be closed after timeout", it.isOpen)

                // Immediately execute streaming card fetch on the SAME client:
                // autoReconnect MUST reopen & re-authenticate seamlessly and stream all 2,500 cards!
                val streamedUsernames = ArrayList<String>(2500)
                val streamRes = it.executeStreaming(
                    command = "/tool/user-manager/user/print",
                    params = listOf("=.proplist=.id,username,password,actual-profile"),
                    timeoutMs = 15_000,
                    autoReconnect = true
                ) { _, rec ->
                    streamedUsernames.add(rec["username"].orEmpty())
                }

                assertTrue("Streaming must auto-reconnect and succeed: ${streamRes.exceptionOrNull()}", streamRes.isSuccess)
                assertEquals(2500, streamRes.getOrThrow().recordCount)
                assertEquals(2500, streamedUsernames.size)
                assertEquals("card-0001", streamedUsernames.first())
                assertEquals("card-2500", streamedUsernames.last())
            }
        }
    }

    @Test
    fun testRouterOsV7IndexedActiveSessionDetection() = runBlocking {
        val server = EmbeddedRouterOsServer(
            expectedUser = "admin",
            expectedPass = "v7pass",
            routerOsVersion = "7.15.3 (stable)",
            cpuLoad = 19,
            uptime = "12d04h15m10s",
            v7ActiveSessionsCount = 45,
            v7DownloadBytesPerSession = 10_485_760L // 10 MB each -> 450 MB total
        )

        server.use { srv ->
            val creds = MikrotikCredentials("127.0.0.1", srv.port, "admin", "v7pass")
            val result = MikrotikSyncManager.testConnectionAndFetchStatus(creds)

            assertTrue("Expected RouterOS v7 connection success: ${result.errorMessage}", result.isConnected)
            val status = result.systemStatus!!
            assertEquals(19, status.cpuLoadPercent)
            assertEquals(45, status.activeConnectionsCount)
            assertEquals(45L * (10_485_760L + 1024L), status.totalDownloadBytes)
            assertEquals("7.15.3 (stable)", status.routerOsVersion)
        }
    }

    @Test
    fun testInvalidCredentialsAndUnreachablePortReturnClearArabicErrors() = runBlocking {
        val server = EmbeddedRouterOsServer(
            expectedUser = "admin",
            expectedPass = "correctPass"
        )

        server.use { srv ->
            val wrongPass = MikrotikSyncManager.testConnectionAndFetchStatus(
                MikrotikCredentials("127.0.0.1", srv.port, "admin", "wrongPass")
            )
            assertFalse(wrongPass.isConnected)
            assertTrue(
                "Expected clear Arabic login refusal, got: ${wrongPass.errorMessage}",
                wrongPass.errorMessage.orEmpty().contains("رفض المايكروتيك تسجيل الدخول")
            )
        }

        // Validation: blank host and invalid port
        val blankHost = MikrotikSyncManager.testConnectionAndFetchStatus(
            MikrotikCredentials("", 8728, "admin", "pass")
        )
        assertFalse(blankHost.isConnected)
        assertTrue(blankHost.errorMessage.orEmpty().contains("فارغ"))

        val invalidPort = MikrotikSyncManager.testConnectionAndFetchStatus(
            MikrotikCredentials("127.0.0.1", 0, "admin", "pass")
        )
        assertFalse(invalidPort.isConnected)
        assertTrue(invalidPort.errorMessage.orEmpty().contains("غير صالح"))

        // Closed TCP port (unreachable router)
        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val unreachable = MikrotikSyncManager.testConnectionAndFetchStatus(
            MikrotikCredentials("127.0.0.1", closedPort, "admin", "pass")
        )
        assertFalse(unreachable.isConnected)
        val msg = unreachable.errorMessage.orEmpty()
        assertFalse("Must never show stale closed-socket message on unreachable port", msg.contains("الاتصال بالمايكروتيك مغلق"))
        assertTrue("Expected clear unreachable error message, got: $msg", msg.contains("تعذر الوصول") || msg.contains("تعذر الاتصال"))
    }

    @Test
    fun testRouterOsBytesDurationAndTimestampParsersForExpiryDetection() {
        // Raw bytes & MikroTik unit suffixes
        assertEquals(2_418_016_256L, MikrotikSyncManager.parseRouterOsBytes("2418016256"))
        assertEquals(1200L * 1024L * 1024L, MikrotikSyncManager.parseRouterOsBytes("1200M"))
        assertEquals(2306L * 1024L * 1024L, MikrotikSyncManager.parseRouterOsBytes("2306MiB"))
        assertEquals(2L * 1024L * 1024L * 1024L, MikrotikSyncManager.parseRouterOsBytes("2G"))
        assertEquals(0L, MikrotikSyncManager.parseRouterOsBytes("0B"))
        assertEquals(0L, MikrotikSyncManager.parseRouterOsBytes("unlimited"))

        // RouterOS shorthand & colon-separated durations (HH:MM:SS and hybrid)
        assertEquals(6L * 3600L + 24L * 60L + 7L, MikrotikSyncManager.parseRouterOsDurationSeconds("6h24m7s"))
        assertEquals(13L * 3600L + 38L * 60L, MikrotikSyncManager.parseRouterOsDurationSeconds("13h38m"))
        assertEquals(12L * 3600L, MikrotikSyncManager.parseRouterOsDurationSeconds("12:00:00"))
        assertEquals(86400L + 6L * 3600L + 24L * 60L + 7L, MikrotikSyncManager.parseRouterOsDurationSeconds("1d 06:24:07"))

        // Timestamps
        assertNotNull(MikrotikSyncManager.parseMikrotikTimestampMillis("nov/26/2026 14:20:05"))
        assertNotNull(MikrotikSyncManager.parseMikrotikTimestampMillis("2026-11-26 14:20:05"))
        assertNull(MikrotikSyncManager.parseMikrotikTimestampMillis("never"))
    }

    @Test
    fun testSalesEffectiveOperationsCountDoesNotMiscountGroupedLogs() {
        val singleSale = PrintSalesLogEntity(
            cardUsername = "39400704022",
            packageName = "100-Plus",
            cardsCount = 1,
            unitPrice = 100.0,
            totalAmount = 100.0,
            operationType = SaleOperationType.DIRECT_SALE,
            dateKey = "2026-11-26"
        )
        assertEquals(1, singleSale.effectiveOperationsCount())

        val printBatch = PrintSalesLogEntity(
            cardUsername = "دفعة طباعة (72 كرت)",
            packageName = "100-Plus",
            cardsCount = 72,
            unitPrice = 100.0,
            totalAmount = 7200.0,
            operationType = SaleOperationType.PRINT_SALE,
            dateKey = "2026-11-26"
        )
        assertEquals(1, printBatch.effectiveOperationsCount())

        val groupedHistoricalDirectSales = PrintSalesLogEntity(
            cardUsername = "مدفوعات يوزر منجر • 100-Plus",
            packageName = "100-Plus",
            cardsCount = 5,
            unitPrice = 100.0,
            totalAmount = 500.0,
            operationType = SaleOperationType.DIRECT_SALE,
            dateKey = "2026-10-15"
        )
        assertEquals(5, groupedHistoricalDirectSales.effectiveOperationsCount())
    }

    @Test
    fun testVlanResolutionReplacesPhysicalPortAndFallsBackCleanly() {
        val subnetVlan30 = MikrotikSyncManager.parseIpv4Cidr("10.10.30.1/24", "vlan30")
        assertNotNull(subnetVlan30)

        val lookup = MikrotikSyncManager.RouterVlanLookup(
            vlanByInterfaceName = mapOf(
                "vlan10" to "vlan10 (VLAN 10)",
                "vlan20" to "vlan20 (VLAN 20)",
                "vlan30" to "vlan30 (VLAN 30)",
                "vlan-north" to "vlan-north (VLAN 110)"
            ),
            vlanById = mapOf(
                "10" to "vlan10 (VLAN 10)",
                "20" to "vlan20 (VLAN 20)",
                "30" to "vlan30 (VLAN 30)",
                "110" to "vlan-north (VLAN 110)"
            ),
            vlansByParentInterface = mapOf(
                "ether11" to listOf("vlan-north (VLAN 110)")
            ),
            interfaceByHotspotServer = mapOf(
                "hs-vlan10" to "vlan10"
            ),
            vlanIdByMac = mapOf(
                "aa:bb:cc:dd:ee:01" to "20",
                "aa:bb:cc:dd:ee:99" to "99"
            ),
            interfaceByMac = mapOf(
                "aa:bb:cc:dd:ee:02" to "vlan30"
            ),
            interfaceByIp = mapOf(
                "10.10.10.55" to "vlan10"
            ),
            subnets = listOfNotNull(subnetVlan30)
        )

        // 1. Direct VLAN nas-port-id or Hotspot server mapped to a VLAN
        assertEquals(
            "vlan10 (VLAN 10)",
            MikrotikSyncManager.resolveVlanOrPort("vlan10", "", "", lookup)
        )
        assertEquals(
            "vlan10 (VLAN 10)",
            MikrotikSyncManager.resolveVlanOrPort("hs-vlan10", "", "", lookup)
        )

        // 2. Physical port ether7 with Bridge MAC vid=20 -> returns VLAN 20 instead of ether7
        assertEquals(
            "vlan20 (VLAN 20)",
            MikrotikSyncManager.resolveVlanOrPort("ether7", "", "AA:BB:CC:DD:EE:01", lookup)
        )
        // Bridge vid not named in /interface/vlan -> returns "VLAN 99" instead of ether7
        assertEquals(
            "VLAN 99",
            MikrotikSyncManager.resolveVlanOrPort("ether7", "", "AA:BB:CC:DD:EE:99", lookup)
        )

        // 3. Physical port ether6 with client IP inside 10.10.30.0/24 -> returns vlan30 instead of ether6
        assertEquals(
            "vlan30 (VLAN 30)",
            MikrotikSyncManager.resolveVlanOrPort("ether6", "10.10.30.45", "", lookup)
        )

        // 4. Physical port ether11 hosting vlan-north -> returns vlan-north instead of ether11
        assertEquals(
            "vlan-north (VLAN 110)",
            MikrotikSyncManager.resolveVlanOrPort("ether11", "", "", lookup)
        )

        // 5. Physical port ether5 with NO VLAN anywhere -> keeps physical port strictly as fallback
        assertEquals(
            "ether5",
            MikrotikSyncManager.resolveVlanOrPort("ether5", "192.168.88.10", "11:22:33:44:55:66", lookup)
        )
    }

    @Test
    fun testV6ProplistTrapRetriesWithoutProplistAndNeverCallsV7Prefix() = runBlocking {
        val seenCommands = CopyOnWriteArrayList<String>()
        val server = EmbeddedRouterOsServer(
            expectedUser = "admin",
            expectedPass = "pass",
            routerOsVersion = "6.49.19 (long-term)",
            userManagerCardsCount = 120,
            rejectV6Proplist = true,
            rejectV7PrefixWithNoSuchCommandPrefix = true,
            onCommand = { seenCommands.add(it) }
        )

        server.use { srv ->
            val creds = MikrotikCredentials("127.0.0.1", srv.port, "admin", "pass")
            val client = MikrotikApiClient(connectTimeoutMs = 3000, readTimeoutMs = 10000)
            client.use { api ->
                assertTrue(api.connectAndLogin(creds).isSuccess)
                val cardsRes = MikrotikSyncManager.fetchAllUserManagerCardsStreaming(
                    client = api,
                    credentials = creds,
                    isV7 = false,
                    serverAccount = "admin",
                    priceByPackage = mapOf("200" to 200.0),
                    limitationsByProfile = emptyMap(),
                    onCountProgress = {}
                )
                assertTrue("Expected v6 cards fetch to succeed via no-proplist retry, got: ${cardsRes.exceptionOrNull()}", cardsRes.isSuccess)
                assertEquals(120, cardsRes.getOrThrow().size)
                assertFalse(
                    "RouterOS v6 must NEVER call v7 /user-manager/user/print (causes 'no such command prefix')",
                    seenCommands.any { it.startsWith("/user-manager/") }
                )
            }
        }
    }

    @Test
    fun testAmbiguousVersionAutoDetectsCorrectUserManagerPrefix() = runBlocking {
        val server = EmbeddedRouterOsServer(
            expectedUser = "admin",
            expectedPass = "pass",
            routerOsVersion = "", // Ambiguous/missing version string
            rejectV6PrefixWithNoSuchCommandPrefix = true,
            v7ProfilesCount = 26
        )

        server.use { srv ->
            val creds = MikrotikCredentials("127.0.0.1", srv.port, "admin", "pass")
            val client = MikrotikApiClient(connectTimeoutMs = 3000, readTimeoutMs = 10000)
            client.use { api ->
                assertTrue(api.connectAndLogin(creds).isSuccess)
                val (detectedV7, profiles) = MikrotikSyncManager.fetchRawProfileRecordsWithDetectedMode(
                    client = api,
                    credentials = creds,
                    guessedIsV7 = false
                )
                assertTrue("Expected auto-detection of RouterOS v7 /user-manager prefix", detectedV7)
                assertEquals(26, profiles.size)
            }
        }
    }

    /**
     * Minimal pure-JVM embedded RouterOS Binary API server for deterministic unit testing.
     */
    private class EmbeddedRouterOsServer(
        private val expectedUser: String,
        private val expectedPass: String,
        private val requireLegacyMd5Login: Boolean = false,
        private val routerOsVersion: String = "6.49.19 (long-term)",
        @Volatile var cpuLoad: Int = 20,
        private val uptime: String = "1d00h00m00s",
        @Volatile var hotspotActiveCount: Int = 0,
        private val hotspotBytesOutPerUser: Long = 0L,
        private val v7ActiveSessionsCount: Int = 0,
        private val v7DownloadBytesPerSession: Long = 0L,
        private val userManagerCardsCount: Int = 0,
        private val v7ProfilesCount: Int = 0,
        private val rejectV6Proplist: Boolean = false,
        private val rejectV7PrefixWithNoSuchCommandPrefix: Boolean = false,
        private val rejectV6PrefixWithNoSuchCommandPrefix: Boolean = false,
        private val stallCommand: String? = null,
        private val stallDelayMs: Long = 0L,
        private val onCommand: (String) -> Unit = {}
    ) : AutoCloseable {

        private val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val running = AtomicBoolean(true)
        private val activeSockets = CopyOnWriteArrayList<Socket>()
        val acceptedConnections = AtomicInteger(0)
        val port: Int get() = serverSocket.localPort

        private val acceptThread = Thread {
            while (running.get()) {
                try {
                    val client = serverSocket.accept()
                    acceptedConnections.incrementAndGet()
                    activeSockets.add(client)
                    Thread { handleClient(client) }.apply { isDaemon = true; start() }
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }.apply { isDaemon = true; start() }

        fun forceCloseActiveClients() {
            activeSockets.forEach { sock -> runCatching { sock.close() } }
            activeSockets.clear()
        }

        private fun handleClient(socket: Socket) {
            try {
                val inp = BufferedInputStream(socket.getInputStream())
                val out = BufferedOutputStream(socket.getOutputStream())
                var loggedIn = false
                val challengeHex = "0123456789abcdef0123456789abcdef"

                while (running.get() && !socket.isClosed) {
                    val words = readSentence(inp) ?: break
                    if (words.isEmpty()) continue
                    val cmd = words[0]
                    val attrs = parseAttrs(words)
                    onCommand(cmd)

                    if (cmd == "/login") {
                        val user = attrs["name"].orEmpty()
                        val pass = attrs["password"]
                        val response = attrs["response"]

                        if (requireLegacyMd5Login && response == null) {
                            writeSentence(out, listOf("!done", "=ret=$challengeHex"))
                            continue
                        }
                        if (requireLegacyMd5Login && response != null) {
                            val expectedResp = "00" + computeMd5(expectedPass, challengeHex)
                            if (user == expectedUser && response.equals(expectedResp, ignoreCase = true)) {
                                loggedIn = true
                                writeSentence(out, listOf("!done"))
                            } else {
                                writeSentence(out, listOf("!trap", "=message=invalid user name or password"))
                                writeSentence(out, listOf("!done"))
                            }
                            continue
                        }

                        if (user == expectedUser && pass == expectedPass) {
                            loggedIn = true
                            writeSentence(out, listOf("!done"))
                        } else {
                            writeSentence(out, listOf("!trap", "=message=invalid user name or password"))
                            writeSentence(out, listOf("!done"))
                        }
                        continue
                    }

                    if (!loggedIn) {
                        writeSentence(out, listOf("!trap", "=message=not logged in"))
                        writeSentence(out, listOf("!done"))
                        continue
                    }

                    if (stallCommand != null && cmd == stallCommand && stallDelayMs > 0L) {
                        Thread.sleep(stallDelayMs)
                    }

                    if (rejectV7PrefixWithNoSuchCommandPrefix && cmd.startsWith("/user-manager/")) {
                        writeSentence(out, listOf("!trap", "=message=no such command prefix"))
                        writeSentence(out, listOf("!done"))
                        continue
                    }
                    if (rejectV6PrefixWithNoSuchCommandPrefix && cmd.startsWith("/tool/user-manager/")) {
                        writeSentence(out, listOf("!trap", "=message=no such command prefix"))
                        writeSentence(out, listOf("!done"))
                        continue
                    }

                    when (cmd) {
                        "/system/resource/print" -> {
                            writeSentence(
                                out,
                                listOf(
                                    "!re",
                                    "=cpu-load=$cpuLoad",
                                    "=uptime=$uptime",
                                    "=version=$routerOsVersion",
                                    "=board-name=RB1100AHx4"
                                )
                            )
                            writeSentence(out, listOf("!done"))
                        }
                        "/ip/hotspot/active/print" -> {
                            for (i in 1..hotspotActiveCount) {
                                writeSentence(
                                    out,
                                    listOf(
                                        "!re",
                                        "=user=user-$i",
                                        "=bytes-out=$hotspotBytesOutPerUser",
                                        "=bytes-in=1024"
                                    )
                                )
                            }
                            writeSentence(out, listOf("!done"))
                        }
                        "/ppp/active/print" -> {
                            writeSentence(out, listOf("!done"))
                        }
                        "/user-manager/profile/print" -> {
                            for (i in 1..v7ProfilesCount) {
                                writeSentence(
                                    out,
                                    listOf(
                                        "!re",
                                        "=.id=*$i",
                                        "=name=pkg-$i",
                                        "=price=100"
                                    )
                                )
                            }
                            writeSentence(out, listOf("!done"))
                        }
                        "/user-manager/session/print" -> {
                            for (i in 1..v7ActiveSessionsCount) {
                                writeSentence(
                                    out,
                                    listOf(
                                        "!re",
                                        "=user=v7user-$i",
                                        "=active=true",
                                        "=download=$v7DownloadBytesPerSession",
                                        "=upload=1024"
                                    )
                                )
                            }
                            writeSentence(out, listOf("!done"))
                        }
                        "/tool/user-manager/user/print" -> {
                            if (rejectV6Proplist && attrs.containsKey(".proplist")) {
                                writeSentence(out, listOf("!trap", "=message=invalid proplist"))
                                writeSentence(out, listOf("!done"))
                            } else {
                                for (i in 1..userManagerCardsCount) {
                                    val num = String.format(Locale.US, "%04d", i)
                                    writeSentence(
                                        out,
                                        listOf(
                                            "!re",
                                            "=.id=*$i",
                                            "=username=card-$num",
                                            "=password=pass-$num",
                                            "=actual-profile=200"
                                        )
                                    )
                                }
                                writeSentence(out, listOf("!done"))
                            }
                        }
                        else -> {
                            writeSentence(out, listOf("!done"))
                        }
                    }
                }
            } catch (_: Exception) {
                // Client disconnected or timed out
            } finally {
                runCatching { socket.close() }
                activeSockets.remove(socket)
            }
        }

        private fun parseAttrs(words: List<String>): Map<String, String> {
            val map = LinkedHashMap<String, String>()
            for (i in 1 until words.size) {
                val w = words[i]
                if (w.startsWith("=")) {
                    val eq = w.indexOf('=', 1)
                    if (eq > 1) map[w.substring(1, eq)] = w.substring(eq + 1)
                }
            }
            return map
        }

        private fun readSentence(inp: BufferedInputStream): List<String>? {
            val words = mutableListOf<String>()
            while (true) {
                val w = readWord(inp) ?: return if (words.isEmpty()) null else words
                if (w.isEmpty()) {
                    if (words.isEmpty()) continue
                    return words
                }
                words.add(w)
            }
        }

        private fun readWord(inp: BufferedInputStream): String? {
            val len = readLength(inp) ?: return null
            if (len == 0) return ""
            val buf = ByteArray(len)
            var off = 0
            while (off < len) {
                val r = inp.read(buf, off, len - off)
                if (r == -1) throw EOFException()
                off += r
            }
            return String(buf, Charsets.UTF_8)
        }

        private fun readLength(inp: BufferedInputStream): Int? {
            val b0 = inp.read()
            if (b0 == -1) return null
            return when {
                (b0 and 0x80) == 0x00 -> b0
                (b0 and 0xC0) == 0x80 -> ((b0 and 0x3F) shl 8) or readByte(inp)
                (b0 and 0xE0) == 0xC0 -> ((b0 and 0x1F) shl 16) or (readByte(inp) shl 8) or readByte(inp)
                (b0 and 0xF0) == 0xE0 -> ((b0 and 0x0F) shl 24) or (readByte(inp) shl 16) or (readByte(inp) shl 8) or readByte(inp)
                else -> (readByte(inp) shl 24) or (readByte(inp) shl 16) or (readByte(inp) shl 8) or readByte(inp)
            }
        }

        private fun readByte(inp: BufferedInputStream): Int {
            val b = inp.read()
            if (b == -1) throw EOFException()
            return b
        }

        private fun writeSentence(out: BufferedOutputStream, words: List<String>) {
            for (word in words) {
                val bytes = word.toByteArray(Charsets.UTF_8)
                writeLength(out, bytes.size)
                if (bytes.isNotEmpty()) out.write(bytes)
            }
            out.write(0)
            out.flush()
        }

        private fun writeLength(out: BufferedOutputStream, length: Int) {
            when {
                length < 0x80 -> out.write(length)
                length < 0x4000 -> {
                    val v = length or 0x8000
                    out.write((v shr 8) and 0xFF)
                    out.write(v and 0xFF)
                }
                else -> {
                    val v = length or 0xC00000
                    out.write((v shr 16) and 0xFF)
                    out.write((v shr 8) and 0xFF)
                    out.write(v and 0xFF)
                }
            }
        }

        private fun computeMd5(password: String, challengeHex: String): String {
            val challengeBytes = ByteArray(challengeHex.length / 2) { i ->
                challengeHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            val baos = ByteArrayOutputStream()
            baos.write(0)
            baos.write(password.toByteArray(Charsets.ISO_8859_1))
            baos.write(challengeBytes)
            val digest = MessageDigest.getInstance("MD5").digest(baos.toByteArray())
            return digest.joinToString("") { String.format(Locale.US, "%02x", it) }
        }

        override fun close() {
            running.set(false)
            forceCloseActiveClients()
            runCatching { serverSocket.close() }
            runCatching { acceptThread.join(1000) }
        }
    }
}
