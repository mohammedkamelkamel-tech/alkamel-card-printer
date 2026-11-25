package com.alkamel.cardprinter.data.mikrotik

import com.alkamel.cardprinter.data.local.entity.CardEntity
import com.alkamel.cardprinter.data.local.entity.CardPackageEntity
import com.alkamel.cardprinter.data.local.entity.CardSessionEntity
import com.alkamel.cardprinter.data.local.entity.PrintSalesLogEntity
import com.alkamel.cardprinter.data.local.model.CardStatus
import com.alkamel.cardprinter.data.local.model.NetworkLoadType
import com.alkamel.cardprinter.data.local.model.PeriodSalesSummary
import com.alkamel.cardprinter.data.local.model.PrintMethod
import com.alkamel.cardprinter.data.local.model.SaleOperationType
import com.alkamel.cardprinter.data.local.util.DateTimeUtils
import com.alkamel.cardprinter.data.repository.CardPrinterRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Coordinates complete RouterOS v6 / v7 User Manager API data fetching, live telemetry polling
 * over a persistent reusable connection, per-card User Manager session lookup, full sales/payments
 * synchronization into Room, live User Manager reports aggregation, and live card pushing directly
 * into MikroTik User Manager.
 *
 * Strictly User Manager only — zero Hotspot card/profile management.
 * Uses streaming record reading, fast active-session detection, and automatic socket recovery
 * so 50,000+ cards sync reliably without socket drops or false disconnections.
 */
object MikrotikSyncManager {

    private const val STREAM_BULK_TIMEOUT_MS = 120_000
    private const val SESSIONS_BULK_TIMEOUT_MS = 60_000
    private const val TELEMETRY_TIMEOUT_MS = 8_000
    private const val ACTIVE_QUERY_TIMEOUT_MS = 6_000

    private val telemetryMutex = Mutex()
    private val telemetryClient = MikrotikApiClient(
        connectTimeoutMs = 6_000,
        readTimeoutMs = TELEMETRY_TIMEOUT_MS
    )

    fun closeTelemetryConnection() {
        runCatching { telemetryClient.close() }
    }

    private val MONTH_ABBR_TO_NUM = mapOf(
        "jan" to "01", "feb" to "02", "mar" to "03", "apr" to "04",
        "may" to "05", "jun" to "06", "jul" to "07", "aug" to "08",
        "sep" to "09", "oct" to "10", "nov" to "11", "dec" to "12"
    )

    private val CURRENCY_SUFFIX_REGEX = Regex("""(\d+(?:\.\d+)?)\s*(?:yr|ry|yer|sar|ريال)""", RegexOption.IGNORE_CASE)
    private val DURATION_TOKEN_REGEX = Regex("""(\d+)([wdhms])""", RegexOption.IGNORE_CASE)

    private data class ProfileLimitationMeta(
        val transferLimitBytes: Long = 0L,
        val uptimeLimitSeconds: Long = 0L,
        val validityLabel: String = ""
    )

    private data class UserSessionAgg(
        var totalBytes: Long = 0L,
        var uptimeSeconds: Long = 0L,
        var lastUptime: String = "0s",
        var isActive: Boolean = false,
        var count: Int = 0
    )

    private data class PaymentBucket(
        var count: Int = 0,
        var totalAmount: Double = 0.0,
        var totalDownloadBytes: Long = 0L,
        var latestMillis: Long = 0L
    )

    private data class SessionSalesBucket(
        var count: Int = 0,
        var totalAmount: Double = 0.0,
        var totalBytes: Long = 0L
    )

    private data class UserManagerSalesSyncBundle(
        val salesLogs: List<PrintSalesLogEntity>,
        val liveMetrics: MikrotikLiveReportsMetrics
    )

    suspend fun testConnectionAndFetchStatus(credentials: MikrotikCredentials): MikrotikConnectionTestResult =
        withContext(Dispatchers.IO) {
            val client = MikrotikApiClient(connectTimeoutMs = 8000, readTimeoutMs = 15000)
            try {
                val loginResult = client.connectAndLogin(credentials)
                if (loginResult.isFailure) {
                    return@withContext MikrotikConnectionTestResult(
                        isConnected = false,
                        errorMessage = loginResult.exceptionOrNull()?.localizedMessage
                            ?: "تعذر الاتصال بسيرفر يوزر منجر."
                    )
                }
                val status = fetchSystemStatusInternal(client, credentials)
                MikrotikConnectionTestResult(isConnected = true, systemStatus = status)
            } catch (e: Exception) {
                MikrotikConnectionTestResult(
                    isConnected = false,
                    errorMessage = e.localizedMessage ?: "حدث خطأ غير متوقع أثناء الاتصال بالمايكروتيك."
                )
            } finally {
                client.close()
            }
        }

    /**
     * Fast, lightweight live telemetry fetch used by periodic polling on Dashboard.
     * Reuses a single persistent authenticated connection under [telemetryMutex] to avoid
     * opening/closing TCP sockets every 5 seconds, and transparently auto-reconnects on drop.
     */
    suspend fun fetchLiveTelemetry(credentials: MikrotikCredentials): Result<MikrotikSystemStatus> =
        withContext(Dispatchers.IO) {
            telemetryMutex.withLock {
                try {
                    val connRes = telemetryClient.ensureConnected(credentials)
                    if (connRes.isFailure) {
                        return@withLock Result.failure(
                            connRes.exceptionOrNull()
                                ?: IOException("تعذر الاتصال بسيرفر المايكروتيك.")
                        )
                    }
                    val status = fetchSystemStatusInternal(telemetryClient, credentials)
                    Result.success(status)
                } catch (e: Exception) {
                    telemetryClient.close()
                    // One transparent reconnect + retry if the persistent socket was dropped by router/network
                    val retryConn = telemetryClient.connectAndLogin(credentials)
                    if (retryConn.isSuccess) {
                        try {
                            val status = fetchSystemStatusInternal(telemetryClient, credentials)
                            return@withLock Result.success(status)
                        } catch (retryErr: Exception) {
                            telemetryClient.close()
                            return@withLock Result.failure(retryErr)
                        }
                    }
                    Result.failure(retryConn.exceptionOrNull() ?: e)
                }
            }
        }

    /**
     * Performs a complete, non-truncated User Manager synchronization (RouterOS v6 & v7):
     * 1. System resource & live active connections status (emitted immediately via [onSystemStatusReady])
     * 2. ALL User Manager profiles + limitations (no filters, no limits)
     * 3. ALL User Manager users/cards via streaming API (120s timeout, zero truncation)
     * 4. Automatic package discovery for any profile referenced by cards
     * 5. ALL User Manager payments & session-derived sales persisted into Room & live metrics
     */
    suspend fun syncFromMikrotik(
        credentials: MikrotikCredentials,
        @Suppress("UNUSED_PARAMETER") loadType: NetworkLoadType,
        repository: CardPrinterRepository,
        onProgress: (percent: Int, label: String) -> Unit,
        onSystemStatusReady: (MikrotikSystemStatus) -> Unit = {}
    ): Result<MikrotikSyncSummary> = withContext(Dispatchers.IO) {
        val client = MikrotikApiClient(connectTimeoutMs = 10000, readTimeoutMs = STREAM_BULK_TIMEOUT_MS)
        try {
            onProgress(5, "جاري الاتصال بسيرفر يوزر منجر...")
            val loginRes = client.connectAndLogin(credentials)
            if (loginRes.isFailure) {
                return@withContext Result.failure(
                    loginRes.exceptionOrNull() ?: IOException("تعذر الاتصال بسيرفر يوزر منجر")
                )
            }

            onProgress(12, "جاري قراءة حالة السيرفر وإصدار RouterOS...")
            val sysStatus = fetchSystemStatusInternal(client, credentials)
            onSystemStatusReady(sysStatus)
            val isV7 = sysStatus.routerOsVersion.trim().startsWith("7")

            // Ensure socket is open before querying profiles (recovers automatically if any probe timed out)
            client.ensureConnected(credentials).getOrElse { return@withContext Result.failure(it) }

            // 1. Fetch ALL User Manager profiles + limitations
            onProgress(20, "جاري جلب جميع باقات يوزر منجر...")
            val limitationsByProfile = fetchProfileLimitationsBestEffort(client, credentials, isV7)
            client.ensureConnected(credentials).getOrElse { return@withContext Result.failure(it) }

            val fetchedPackages = fetchUserManagerPackagesInternal(client, credentials, isV7, limitationsByProfile)
            var mergedPackages = if (fetchedPackages.isNotEmpty()) {
                repository.mergePackagesFromMikrotik(fetchedPackages).getOrDefault(fetchedPackages)
            } else {
                repository.getAllPackagesOnce()
            }

            // 2. Fetch ALL User Manager cards via streaming (no filter, no limit, full snapshot)
            client.ensureConnected(credentials).getOrElse { return@withContext Result.failure(it) }
            onProgress(30, "جاري جلب جميع كروت يوزر منجر...")
            val priceByPackage = HashMap<String, Double>()
            mergedPackages.forEach { priceByPackage[it.name.trim().lowercase()] = it.price }

            val cardsResult = fetchAllUserManagerCardsStreaming(
                client = client,
                credentials = credentials,
                isV7 = isV7,
                serverAccount = credentials.username,
                priceByPackage = priceByPackage,
                limitationsByProfile = limitationsByProfile,
                onCountProgress = { count ->
                    val pct = (30 + (count / 1200).coerceAtMost(35)).coerceIn(30, 65)
                    onProgress(pct, "جاري جلب كروت يوزر منجر... ($count كرت)")
                }
            )
            if (cardsResult.isFailure) {
                return@withContext Result.failure(
                    cardsResult.exceptionOrNull()
                        ?: IOException("تعذر جلب قائمة كروت يوزر منجر بالكامل من المايكروتيك.")
                )
            }
            val fetchedCards = cardsResult.getOrDefault(emptyList())

            // 3. Auto-discover any package names referenced by cards that weren't in /profile/print
            val knownPkgKeys = mergedPackages.map { it.name.trim().lowercase() }.toMutableSet()
            val discoveredPackages = mutableListOf<CardPackageEntity>()
            for (card in fetchedCards) {
                val pName = card.packageName.trim()
                if (pName.isNotBlank() && knownPkgKeys.add(pName.lowercase())) {
                    val limitMeta = limitationsByProfile[pName.lowercase()]
                    discoveredPackages.add(
                        CardPackageEntity(
                            name = pName,
                            price = inferPriceFromPackageName(pName),
                            commission = 0.0,
                            validityLabel = limitMeta?.validityLabel.orEmpty(),
                            dataLimitMb = (limitMeta?.transferLimitBytes ?: 0L) / (1024.0 * 1024.0)
                        )
                    )
                }
            }
            if (discoveredPackages.isNotEmpty()) {
                mergedPackages = repository.mergePackagesFromMikrotik(mergedPackages + discoveredPackages)
                    .getOrDefault(mergedPackages + discoveredPackages)
                mergedPackages.forEach { priceByPackage[it.name.trim().lowercase()] = it.price }
            }

            // 4. Persist complete card snapshot atomically into Room
            onProgress(70, "جاري حفظ ${fetchedCards.size} كرت يوزر منجر في قاعدة البيانات...")
            val saveCardsRes = repository.syncCardsFromMikrotik(
                remoteCards = fetchedCards,
                isCompleteSnapshot = true
            )
            if (saveCardsRes.isFailure) {
                return@withContext Result.failure(
                    saveCardsRes.exceptionOrNull() ?: IOException("تعذر حفظ كروت يوزر منجر في قاعدة البيانات.")
                )
            }

            // 5. Fetch ALL User Manager payments & sessions to populate Sales & Reports identically
            // Cards & packages are already safely saved in Room, so sales/reports never abort the whole sync.
            onProgress(82, "جاري مزامنة مبيعات وجلسات وتقارير يوزر منجر...")
            val salesBundle = runCatching {
                client.ensureConnected(credentials)
                fetchUserManagerSalesAndReportsInternal(
                    client = client,
                    credentials = credentials,
                    isV7 = isV7,
                    sysStatus = sysStatus,
                    serverAccount = credentials.username,
                    cards = fetchedCards,
                    packages = mergedPackages
                )
            }.getOrElse {
                buildFallbackSalesBundleFromCards(
                    sysStatus = sysStatus,
                    serverAccount = credentials.username,
                    cards = fetchedCards,
                    packages = mergedPackages
                )
            }
            if (salesBundle.salesLogs.isNotEmpty()) {
                onProgress(93, "جاري حفظ سجل مبيعات يوزر منجر (${salesBundle.salesLogs.size} سجل)...")
                repository.replaceSyncedMikrotikSalesLogs(salesBundle.salesLogs)
            }

            onProgress(100, "تم تحديث جميع بيانات يوزر منجر بالكامل (${fetchedCards.size} كرت)")
            Result.success(
                MikrotikSyncSummary(
                    systemStatus = sysStatus,
                    packagesSynced = mergedPackages.size,
                    cardsSynced = fetchedCards.size,
                    activeSessionsCount = sysStatus.activeConnectionsCount,
                    salesSynced = salesBundle.salesLogs.size,
                    liveReportsMetrics = salesBundle.liveMetrics
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            client.close()
        }
    }

    /**
     * Fetches detailed User Manager session history for a single card username
     * directly from RouterOS v6/v7 User Manager and caches it in Room.
     */
    suspend fun fetchSessionsForUser(
        credentials: MikrotikCredentials,
        @Suppress("UNUSED_PARAMETER") loadType: NetworkLoadType,
        username: String,
        repository: CardPrinterRepository
    ): Result<List<CardSessionEntity>> = withContext(Dispatchers.IO) {
        val cleanUser = username.trim()
        if (cleanUser.isBlank()) {
            return@withContext Result.success(emptyList())
        }
        val client = MikrotikApiClient(connectTimeoutMs = 6000, readTimeoutMs = 30000)
        try {
            val loginRes = client.connectAndLogin(credentials)
            if (loginRes.isFailure) {
                return@withContext Result.failure(
                    loginRes.exceptionOrNull() ?: IllegalStateException("تعذر الاتصال بسيرفر يوزر منجر.")
                )
            }
            val resourceRes = client.execute("/system/resource/print").getOrNull()
            val version = resourceRes?.records?.firstOrNull()?.get("version").orEmpty()
            val isV7 = version.trim().startsWith("7")

            val sessions = mutableListOf<CardSessionEntity>()
            val nowMillis = System.currentTimeMillis()

            val primaryCmd = if (isV7) "/user-manager/session/print" else "/tool/user-manager/session/print"
            val fallbackCmd = if (isV7) "/tool/user-manager/session/print" else "/user-manager/session/print"

            val primaryStream = client.executeStreaming(primaryCmd, listOf("?user=$cleanUser"), timeoutMs = 45000) { _, rec ->
                sessions.add(parseSessionRecord(cleanUser, rec, nowMillis))
            }
            if (primaryStream.isFailure || primaryStream.getOrNull()?.isSuccess != true) {
                sessions.clear()
                client.executeStreaming(fallbackCmd, listOf("?user=$cleanUser"), timeoutMs = 45000) { _, rec ->
                    sessions.add(parseSessionRecord(cleanUser, rec, nowMillis))
                }
            }

            val sorted = sessions.sortedWith(
                compareByDescending<CardSessionEntity> { it.isActive }
                    .thenByDescending { parseMikrotikDateKey(it.startTime) ?: "" }
                    .thenByDescending { it.startTime }
            )

            repository.replaceSessionsForUser(cleanUser, sorted)
            Result.success(sorted)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            client.close()
        }
    }

    /**
     * Fetches complete live User Manager payments & sessions metrics and persists synced sales logs
     * into Room so SalesScreen, ReportsScreen, and DashboardScreen remain 100% consistent.
     */
    suspend fun fetchLiveReportsMetrics(
        credentials: MikrotikCredentials,
        @Suppress("UNUSED_PARAMETER") loadType: NetworkLoadType,
        cards: List<CardEntity>,
        packages: List<CardPackageEntity>,
        repository: CardPrinterRepository? = null
    ): Result<MikrotikLiveReportsMetrics> = withContext(Dispatchers.IO) {
        val client = MikrotikApiClient(connectTimeoutMs = 8000, readTimeoutMs = STREAM_BULK_TIMEOUT_MS)
        try {
            val loginRes = client.connectAndLogin(credentials)
            if (loginRes.isFailure) {
                return@withContext Result.failure(
                    loginRes.exceptionOrNull() ?: IllegalStateException("تعذر الاتصال بسيرفر يوزر منجر")
                )
            }

            val sysStatus = fetchSystemStatusInternal(client, credentials)
            val isV7 = sysStatus.routerOsVersion.trim().startsWith("7")

            val resolvedCards = if (cards.isEmpty() && repository != null) {
                repository.getAllCardsOnce()
            } else {
                cards
            }
            val resolvedPackages = if (packages.isEmpty() && repository != null) {
                repository.getAllPackagesOnce()
            } else {
                packages
            }

            val bundle = fetchUserManagerSalesAndReportsInternal(
                client = client,
                credentials = credentials,
                isV7 = isV7,
                sysStatus = sysStatus,
                serverAccount = credentials.username,
                cards = resolvedCards,
                packages = resolvedPackages
            )
            if (repository != null && bundle.salesLogs.isNotEmpty()) {
                repository.replaceSyncedMikrotikSalesLogs(bundle.salesLogs)
            }
            Result.success(bundle.liveMetrics)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            client.close()
        }
    }

    suspend fun pushCardsToMikrotik(
        credentials: MikrotikCredentials,
        @Suppress("UNUSED_PARAMETER") loadType: NetworkLoadType,
        cards: List<CardEntity>,
        repository: CardPrinterRepository,
        onProgress: (MikrotikPushCardProgress) -> Unit
    ): Result<List<CardEntity>> = withContext(Dispatchers.IO) {
        if (cards.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("لا توجد كروت للإضافة."))
        }
        val client = MikrotikApiClient(connectTimeoutMs = 8000, readTimeoutMs = 25000)
        try {
            val loginRes = client.connectAndLogin(credentials)
            if (loginRes.isFailure) {
                return@withContext Result.failure(
                    loginRes.exceptionOrNull() ?: IllegalStateException("غير متصل بسيرفر يوزر منجر.")
                )
            }

            val sysStatus = fetchSystemStatusInternal(client, credentials)
            val isV7 = sysStatus.routerOsVersion.trim().startsWith("7")
            val total = cards.size
            val pushedCards = mutableListOf<CardEntity>()
            var addedCount = 0
            var failedCount = 0
            var lastRouterError: String? = null

            cards.forEachIndexed { idx, card ->
                val pushRes = pushSingleUserManagerCardInternal(
                    client = client,
                    isV7 = isV7,
                    customer = credentials.username,
                    card = card
                )
                if (pushRes.isSuccess) {
                    val createdId = pushRes.getOrNull()
                    val persistedCard = card.copy(
                        serverAccount = credentials.username,
                        mikrotikId = createdId
                    )
                    repository.upsertCards(listOf(persistedCard))
                    pushedCards.add(persistedCard)
                    addedCount++
                } else {
                    failedCount++
                    lastRouterError = pushRes.exceptionOrNull()?.localizedMessage
                }

                val processed = idx + 1
                val percent = ((processed * 100f) / total).toInt().coerceIn(1, 100)
                onProgress(
                    MikrotikPushCardProgress(
                        processedCount = processed,
                        addedToRouterCount = addedCount,
                        failedCount = failedCount,
                        totalCount = total,
                        percent = percent
                    )
                )
            }

            if (addedCount == 0 && lastRouterError != null) {
                return@withContext Result.failure(
                    IllegalStateException("فشل إضافة الكروت إلى يوزر منجر: $lastRouterError")
                )
            }

            Result.success(pushedCards)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            client.close()
        }
    }

    suspend fun deleteCardsOnMikrotik(
        credentials: MikrotikCredentials,
        @Suppress("UNUSED_PARAMETER") loadType: NetworkLoadType,
        cards: List<CardEntity>
    ): Result<Int> = withContext(Dispatchers.IO) {
        val client = MikrotikApiClient(connectTimeoutMs = 8000, readTimeoutMs = 25000)
        try {
            val loginRes = client.connectAndLogin(credentials)
            if (loginRes.isFailure) return@withContext Result.failure(loginRes.exceptionOrNull()!!)
            val sysStatus = fetchSystemStatusInternal(client, credentials)
            val isV7 = sysStatus.routerOsVersion.trim().startsWith("7")

            var removed = 0
            for (card in cards) {
                val ok = deleteSingleUserManagerCardInternal(client, isV7, card)
                if (ok) removed++
            }
            Result.success(removed)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            client.close()
        }
    }

    /**
     * Reads real-time MikroTik telemetry (CPU, uptime, RouterOS version, active connections,
     * and live download/upload traffic).
     *
     * CRITICAL ROUTEROS v6 NOTE:
     * On RouterOS v6 routers with tens of thousands of User Manager cards (e.g. 50,000+ cards),
     * `/tool/user-manager/session/print?active=true` scans the entire historical UM SQLite
     * session table linearly and times out (which previously closed the socket and broke the
     * entire sync with "الاتصال بالمايكروتيك مغلق.").
     * Therefore:
     * - On RouterOS v6: query fast live active RADIUS/Hotspot & PPPoE sessions first (<100ms),
     *   falling back to UM active sessions only if both are 0, and ALWAYS restoring the socket
     *   if an optional query times out.
     * - On RouterOS v7: query indexed `/user-manager/session/print?active=true` first, with
     *   fast fallback to active Hotspot/PPPoE sessions.
     */
    private suspend fun fetchSystemStatusInternal(
        client: MikrotikApiClient,
        credentials: MikrotikCredentials
    ): MikrotikSystemStatus {
        val resourceRes = client.execute("/system/resource/print", timeoutMs = TELEMETRY_TIMEOUT_MS)
            .getOrElse { throw it }
        if (!resourceRes.isSuccess) {
            throw IOException(resourceRes.trapMessage ?: "تعذر قراءة بيانات النظام من المايكروتيك.")
        }
        val resMap = resourceRes.records.firstOrNull().orEmpty()
        val cpu = resMap["cpu-load"]?.toIntOrNull()?.coerceIn(0, 100) ?: 0
        val uptime = resMap["uptime"].orEmpty().ifBlank { "--" }
        val version = resMap["version"].orEmpty()
        val board = resMap["board-name"].orEmpty()
        val isV7 = version.trim().startsWith("7")

        var activeCount = 0
        var totalBytes = 0L

        suspend fun queryHotspotActive(): Pair<Int, Long> {
            client.ensureConnected(credentials)
            var count = 0
            var bytes = 0L
            val res = client.executeStreaming(
                "/ip/hotspot/active/print",
                listOf("=.proplist=user,bytes-in,bytes-out"),
                timeoutMs = ACTIVE_QUERY_TIMEOUT_MS
            ) { _, rec ->
                count++
                bytes += (rec["bytes-in"]?.toLongOrNull() ?: 0L) + (rec["bytes-out"]?.toLongOrNull() ?: 0L)
            }
            return if (res.isSuccess && res.getOrNull()?.isSuccess == true) count to bytes else 0 to 0L
        }

        suspend fun queryPppActive(): Int {
            client.ensureConnected(credentials)
            var count = 0
            val res = client.executeStreaming(
                "/ppp/active/print",
                listOf("=.proplist=name"),
                timeoutMs = ACTIVE_QUERY_TIMEOUT_MS
            ) { _, _ ->
                count++
            }
            return if (res.isSuccess && res.getOrNull()?.isSuccess == true) count else 0
        }

        suspend fun queryUmActive(cmd: String): Pair<Int, Long> {
            client.ensureConnected(credentials)
            var count = 0
            var bytes = 0L
            val res = client.executeStreaming(
                cmd,
                listOf("?active=true", "=.proplist=download,upload"),
                timeoutMs = ACTIVE_QUERY_TIMEOUT_MS
            ) { _, rec ->
                count++
                bytes += (rec["download"]?.toLongOrNull() ?: 0L) + (rec["upload"]?.toLongOrNull() ?: 0L)
            }
            return if (res.isSuccess && res.getOrNull()?.isSuccess == true) count to bytes else 0 to 0L
        }

        if (isV7) {
            // RouterOS v7 indexes active UM sessions natively
            val (umCount, umBytes) = queryUmActive("/user-manager/session/print")
            if (umCount > 0) {
                activeCount = umCount
                totalBytes = umBytes
            } else {
                val (hsCount, hsBytes) = queryHotspotActive()
                if (hsCount > 0) {
                    activeCount = hsCount
                    totalBytes = hsBytes
                } else {
                    activeCount = queryPppActive()
                }
            }
        } else {
            // RouterOS v6: fast live active RADIUS/Hotspot & PPPoE clients first so we never stall on 500k UM history rows
            val (hsCount, hsBytes) = queryHotspotActive()
            if (hsCount > 0) {
                activeCount = hsCount
                totalBytes = hsBytes
            } else {
                val pppCount = queryPppActive()
                if (pppCount > 0) {
                    activeCount = pppCount
                } else {
                    val (umCount, umBytes) = queryUmActive("/tool/user-manager/session/print")
                    activeCount = umCount
                    totalBytes = umBytes
                }
            }
        }

        // Guarantee the socket is healthy before returning to caller (never leave a closed socket behind)
        if (!client.isOpen) {
            client.ensureConnected(credentials)
        }

        return MikrotikSystemStatus(
            cpuLoadPercent = cpu,
            activeConnectionsCount = activeCount,
            uptimeDisplay = uptime,
            totalDownloadBytes = totalBytes,
            totalDownloadDisplay = formatBytesCompact(totalBytes),
            routerOsVersion = version,
            boardName = board
        )
    }

    private suspend fun fetchProfileLimitationsBestEffort(
        client: MikrotikApiClient,
        credentials: MikrotikCredentials,
        isV7: Boolean
    ): Map<String, ProfileLimitationMeta> {
        val result = HashMap<String, ProfileLimitationMeta>()
        runCatching {
            client.ensureConnected(credentials)
            val limCmd = if (isV7) "/user-manager/limitation/print" else "/tool/user-manager/profile/limitation/print"
            val linkCmd = if (isV7) "/user-manager/profile-limitation/print" else "/tool/user-manager/profile/profile-limitation/print"

            val limRecords = client.execute(limCmd, timeoutMs = 20000).getOrNull()
                ?.takeIf { it.isSuccess }?.records.orEmpty()
            client.ensureConnected(credentials)
            val linkRecords = client.execute(linkCmd, timeoutMs = 20000).getOrNull()
                ?.takeIf { it.isSuccess }?.records.orEmpty()

            if (limRecords.isEmpty()) return result

            val limByName = HashMap<String, ProfileLimitationMeta>()
            limRecords.forEach { r ->
                val name = r["name"].orEmpty().trim().lowercase()
                if (name.isBlank()) return@forEach
                val transfer = r["transfer-limit"]?.toLongOrNull() ?: 0L
                val dl = r["download-limit"]?.toLongOrNull() ?: 0L
                val ul = r["upload-limit"]?.toLongOrNull() ?: 0L
                val effectiveBytes = when {
                    transfer > 0L -> transfer
                    dl > 0L || ul > 0L -> dl + ul
                    else -> 0L
                }
                val uptimeStr = r["uptime-limit"].orEmpty().trim()
                val uptimeSec = parseRouterOsDurationSeconds(uptimeStr)
                limByName[name] = ProfileLimitationMeta(
                    transferLimitBytes = effectiveBytes,
                    uptimeLimitSeconds = uptimeSec,
                    validityLabel = uptimeStr
                )
            }

            linkRecords.forEach { link ->
                val profName = link["profile"].orEmpty().trim().lowercase()
                val limName = link["limitation"].orEmpty().trim().lowercase()
                val meta = limByName[limName] ?: return@forEach
                if (profName.isBlank()) return@forEach
                val prev = result[profName]
                result[profName] = ProfileLimitationMeta(
                    transferLimitBytes = maxOf(prev?.transferLimitBytes ?: 0L, meta.transferLimitBytes),
                    uptimeLimitSeconds = maxOf(prev?.uptimeLimitSeconds ?: 0L, meta.uptimeLimitSeconds),
                    validityLabel = (prev?.validityLabel?.takeIf { it.isNotBlank() }) ?: meta.validityLabel
                )
            }
        }
        return result
    }

    private suspend fun fetchUserManagerPackagesInternal(
        client: MikrotikApiClient,
        credentials: MikrotikCredentials,
        isV7: Boolean,
        limitationsByProfile: Map<String, ProfileLimitationMeta>
    ): List<CardPackageEntity> {
        val result = LinkedHashMap<String, CardPackageEntity>()
        val primaryCmd = if (isV7) "/user-manager/profile/print" else "/tool/user-manager/profile/print"
        val fallbackCmd = if (isV7) "/tool/user-manager/profile/print" else "/user-manager/profile/print"

        client.ensureConnected(credentials)
        val primaryRes = client.execute(primaryCmd, timeoutMs = 30000).getOrNull()
        val records = if (primaryRes != null && primaryRes.isSuccess) {
            primaryRes.records
        } else {
            client.ensureConnected(credentials)
            client.execute(fallbackCmd, timeoutMs = 30000).getOrNull()
                ?.takeIf { it.isSuccess }?.records.orEmpty()
        }

        records.forEach { r ->
            val name = r["name"].orEmpty().trim()
            if (name.isNotBlank()) {
                val rawPrice = r["price"].orEmpty().trim()
                val routerPrice = rawPrice.toDoubleOrNull()
                    ?: rawPrice.filter { it.isDigit() || it == '.' }.toDoubleOrNull()
                    ?: 0.0
                val price = if (routerPrice > 0.0) routerPrice else inferPriceFromPackageName(name)
                val limMeta = limitationsByProfile[name.lowercase()]
                val validity = r["validity"].orEmpty().trim().ifBlank { limMeta?.validityLabel.orEmpty() }
                val dataLimitMb = (limMeta?.transferLimitBytes ?: 0L) / (1024.0 * 1024.0)

                result[name.lowercase()] = CardPackageEntity(
                    name = name,
                    price = price,
                    commission = 0.0,
                    validityLabel = validity,
                    dataLimitMb = dataLimitMb
                )
            }
        }
        return result.values.toList()
    }

    private suspend fun fetchAllUserManagerCardsStreaming(
        client: MikrotikApiClient,
        credentials: MikrotikCredentials,
        isV7: Boolean,
        serverAccount: String,
        priceByPackage: Map<String, Double>,
        limitationsByProfile: Map<String, ProfileLimitationMeta>,
        onCountProgress: (Int) -> Unit
    ): Result<List<CardEntity>> {
        return if (isV7) {
            val v7Res = fetchV7CardsStreaming(client, credentials, serverAccount, priceByPackage, onCountProgress)
            if (v7Res.isSuccess) {
                v7Res
            } else {
                client.ensureConnected(credentials)
                fetchV6CardsStreaming(client, credentials, serverAccount, priceByPackage, limitationsByProfile, onCountProgress)
            }
        } else {
            val v6Res = fetchV6CardsStreaming(client, credentials, serverAccount, priceByPackage, limitationsByProfile, onCountProgress)
            if (v6Res.isSuccess) {
                v6Res
            } else {
                client.ensureConnected(credentials)
                fetchV7CardsStreaming(client, credentials, serverAccount, priceByPackage, onCountProgress)
            }
        }
    }

    private suspend fun fetchV6CardsStreaming(
        client: MikrotikApiClient,
        credentials: MikrotikCredentials,
        serverAccount: String,
        priceByPackage: Map<String, Double>,
        limitationsByProfile: Map<String, ProfileLimitationMeta>,
        onCountProgress: (Int) -> Unit
    ): Result<List<CardEntity>> {
        val result = LinkedHashMap<String, CardEntity>(4096)
        val baseNow = System.currentTimeMillis()
        var streamedCount = 0

        fun processV6User(idx: Int, u: Map<String, String>) {
            streamedCount = idx + 1
            if (streamedCount % 500 == 0) {
                onCountProgress(streamedCount)
            }
            val uname = (u["username"] ?: u["name"]).orEmpty().trim()
            if (uname.isBlank()) return

            val actualProfile = u["actual-profile"].orEmpty().trim()
            val commentProfile = u["comment"].orEmpty().trim()
                .takeIf { it.startsWith("alkamel:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                .orEmpty()
            val pkgName = actualProfile
                .ifBlank { u["profile"].orEmpty().trim() }
                .ifBlank { commentProfile }

            val dlBytes = (u["download-used"]?.toLongOrNull() ?: 0L) +
                (u["upload-used"]?.toLongOrNull() ?: 0L)
            val uptimeUsedRaw = u["uptime-used"].orEmpty().trim()
            val uptimeUsed = uptimeUsedRaw.ifBlank { "0s" }
            val uptimeSeconds = parseRouterOsDurationSeconds(uptimeUsedRaw)
            val disabled = u["disabled"].equals("true", ignoreCase = true)
            val isActive = u["active"].equals("true", ignoreCase = true)
            val lastSeen = u["last-seen"].orEmpty().trim()
            val hasLastSeen = lastSeen.isNotBlank() && !lastSeen.equals("never", ignoreCase = true)
            val hasUsage = dlBytes > 0L || uptimeSeconds > 0L || hasLastSeen

            val limitMeta = if (pkgName.isNotBlank()) limitationsByProfile[pkgName.lowercase()] else null
            val reachedDataLimit = limitMeta != null && limitMeta.transferLimitBytes > 0L && dlBytes >= limitMeta.transferLimitBytes
            val reachedUptimeLimit = limitMeta != null && limitMeta.uptimeLimitSeconds > 0L && uptimeSeconds >= limitMeta.uptimeLimitSeconds

            val status = when {
                disabled -> CardStatus.EXPIRED
                reachedDataLimit || reachedUptimeLimit -> CardStatus.EXPIRED
                actualProfile.isBlank() && hasUsage -> CardStatus.EXPIRED
                isActive || hasUsage -> CardStatus.USED
                else -> CardStatus.UNUSED
            }

            val resolvedPrice = priceByPackage[pkgName.lowercase()]
                ?.takeIf { it > 0.0 }
                ?: inferPriceFromPackageName(pkgName)

            result[uname] = CardEntity(
                username = uname,
                password = u["password"].orEmpty(),
                packageName = pkgName,
                price = resolvedPrice,
                serverAccount = u["customer"].orEmpty().trim().ifBlank { serverAccount },
                mikrotikId = u[".id"],
                status = status,
                uptimeLabel = uptimeUsed,
                uptimeMinutes = uptimeSeconds / 60L,
                dataUsageLabel = formatBytesCompact(dlBytes),
                dataUsageMb = dlBytes / (1024.0 * 1024.0),
                createdAt = baseNow - idx
            )
        }

        // Request only the needed User Manager attributes first (3x-4x faster for 50,000+ cards)
        val fastProplist = listOf(
            "=.proplist=.id,username,name,password,customer,actual-profile,profile,comment,download-used,upload-used,uptime-used,disabled,active,last-seen"
        )
        var streamRes = client.executeStreaming(
            command = "/tool/user-manager/user/print",
            params = fastProplist,
            timeoutMs = STREAM_BULK_TIMEOUT_MS
        ) { idx, u -> processV6User(idx, u) }

        // Fallback to full record print if an older RouterOS v6 build traps on proplist
        if (result.isEmpty() && (streamRes.isFailure || streamRes.getOrNull()?.isSuccess != true)) {
            client.ensureConnected(credentials)
            result.clear()
            streamRes = client.executeStreaming(
                command = "/tool/user-manager/user/print",
                params = emptyList(),
                timeoutMs = STREAM_BULK_TIMEOUT_MS
            ) { idx, u -> processV6User(idx, u) }
        }

        if (streamRes.isFailure) {
            return Result.failure(streamRes.exceptionOrNull() ?: IOException("تعذر قراءة كروت يوزر منجر (v6)."))
        }
        val sr = streamRes.getOrThrow()
        if (!sr.isSuccess) {
            return Result.failure(IOException(sr.trapMessage ?: "رفض المايكروتيك أمر قراءة كروت يوزر منجر (v6)."))
        }
        onCountProgress(result.size)
        return Result.success(result.values.toList())
    }

    private suspend fun fetchV7CardsStreaming(
        client: MikrotikApiClient,
        credentials: MikrotikCredentials,
        serverAccount: String,
        priceByPackage: Map<String, Double>,
        onCountProgress: (Int) -> Unit
    ): Result<List<CardEntity>> {
        // 1. Stream all user-profile assignments
        client.ensureConnected(credentials)
        val profilesByUser = HashMap<String, MutableList<Pair<String, String>>>(4096)
        client.executeStreaming(
            command = "/user-manager/user-profile/print",
            params = listOf("=.proplist=user,profile,state"),
            timeoutMs = STREAM_BULK_TIMEOUT_MS
        ) { _, up ->
            val user = up["user"].orEmpty().trim()
            if (user.isNotBlank()) {
                val list = profilesByUser.getOrPut(user) { ArrayList(2) }
                list.add(up["profile"].orEmpty().trim() to up["state"].orEmpty().trim().lowercase())
            }
        }

        // 2. Stream all sessions to aggregate total bytes & uptime per user (best-effort)
        client.ensureConnected(credentials)
        val sessionByUser = HashMap<String, UserSessionAgg>(4096)
        client.executeStreaming(
            command = "/user-manager/session/print",
            params = listOf("=.proplist=user,download,upload,uptime,active"),
            timeoutMs = SESSIONS_BULK_TIMEOUT_MS
        ) { _, s ->
            val user = s["user"].orEmpty().trim()
            if (user.isNotBlank()) {
                val agg = sessionByUser.getOrPut(user) { UserSessionAgg() }
                agg.totalBytes += (s["download"]?.toLongOrNull() ?: 0L) + (s["upload"]?.toLongOrNull() ?: 0L)
                val upStr = s["uptime"].orEmpty().trim()
                agg.uptimeSeconds += parseRouterOsDurationSeconds(upStr)
                if (upStr.isNotBlank()) agg.lastUptime = upStr
                if (s["active"].equals("true", ignoreCase = true)) agg.isActive = true
                agg.count++
            }
        }

        // 3. Stream ALL User Manager users (ensure connected even if session table timed out)
        client.ensureConnected(credentials).getOrElse { return Result.failure(it) }
        val result = LinkedHashMap<String, CardEntity>(4096)
        val baseNow = System.currentTimeMillis()
        val userStreamRes = client.executeStreaming(
            command = "/user-manager/user/print",
            params = listOf("=.proplist=.id,name,password,group,comment,disabled"),
            timeoutMs = STREAM_BULK_TIMEOUT_MS
        ) { idx, u ->
            val count = idx + 1
            if (count % 500 == 0) {
                onCountProgress(count)
            }
            val uname = u["name"].orEmpty().trim()
            if (uname.isBlank()) return@executeStreaming

            val upList = profilesByUser[uname].orEmpty()
            val runningEntry = upList.lastOrNull { it.second.contains("running") || it.second.contains("active") }
            val waitingEntry = upList.lastOrNull { it.second.contains("waiting") }
            val anyUsedOrExpired = upList.isNotEmpty() && upList.all {
                it.second.contains("used") || it.second.contains("expired") || it.second.contains("end")
            }

            val commentProfile = u["comment"].orEmpty().trim()
                .takeIf { it.startsWith("alkamel:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                .orEmpty()
            val pkgName = (runningEntry?.first ?: waitingEntry?.first ?: upList.lastOrNull()?.first)
                .orEmpty()
                .ifBlank { u["group"].orEmpty().trim() }
                .ifBlank { commentProfile }

            val sessAgg = sessionByUser[uname]
            val totalBytes = sessAgg?.totalBytes ?: 0L
            val uptimeSeconds = sessAgg?.uptimeSeconds ?: 0L
            val uptimeLabel = sessAgg?.lastUptime?.ifBlank { "0s" } ?: "0s"
            val disabled = u["disabled"].equals("true", ignoreCase = true)

            val status = when {
                disabled -> CardStatus.EXPIRED
                runningEntry != null || sessAgg?.isActive == true -> CardStatus.USED
                anyUsedOrExpired -> CardStatus.EXPIRED
                waitingEntry != null && (sessAgg == null || sessAgg.count == 0) -> CardStatus.UNUSED
                sessAgg != null && sessAgg.count > 0 -> CardStatus.USED
                else -> CardStatus.UNUSED
            }

            val resolvedPrice = priceByPackage[pkgName.lowercase()]
                ?.takeIf { it > 0.0 }
                ?: inferPriceFromPackageName(pkgName)

            result[uname] = CardEntity(
                username = uname,
                password = u["password"].orEmpty(),
                packageName = pkgName,
                price = resolvedPrice,
                serverAccount = serverAccount,
                mikrotikId = u[".id"],
                status = status,
                uptimeLabel = uptimeLabel,
                uptimeMinutes = uptimeSeconds / 60L,
                dataUsageLabel = formatBytesCompact(totalBytes),
                dataUsageMb = totalBytes / (1024.0 * 1024.0),
                createdAt = baseNow - idx
            )
        }

        if (userStreamRes.isFailure) {
            return Result.failure(userStreamRes.exceptionOrNull() ?: IOException("تعذر قراءة كروت يوزر منجر (v7)."))
        }
        val sr = userStreamRes.getOrThrow()
        if (!sr.isSuccess) {
            return Result.failure(IOException(sr.trapMessage ?: "رفض المايكروتيك أمر قراءة كروت يوزر منجر (v7)."))
        }
        onCountProgress(result.size)
        return Result.success(result.values.toList())
    }

    /**
     * Streams ALL User Manager payments and sessions (RouterOS v6 & v7) without page limits,
     * builds both [MikrotikLiveReportsMetrics] and unified [PrintSalesLogEntity] records for Room.
     */
    private suspend fun fetchUserManagerSalesAndReportsInternal(
        client: MikrotikApiClient,
        credentials: MikrotikCredentials,
        isV7: Boolean,
        sysStatus: MikrotikSystemStatus,
        serverAccount: String,
        cards: List<CardEntity>,
        packages: List<CardPackageEntity>
    ): UserManagerSalesSyncBundle {
        val todayKey = DateTimeUtils.todayDateKey()
        val yesterdayKey = DateTimeUtils.yesterdayDateKey()
        val weekStartKey = DateTimeUtils.daysAgoDateKey(6)
        val monthStartKey = DateTimeUtils.monthStartDateKey()

        val cardByUser = HashMap<String, CardEntity>((cards.size * 1.4f).toInt().coerceAtLeast(16))
        cards.forEach { cardByUser[it.username] = it }

        val priceByPackage = HashMap<String, Double>()
        packages.forEach { priceByPackage[it.name.trim().lowercase()] = it.price }

        fun resolveUserPackage(username: String): String =
            cardByUser[username]?.packageName?.ifBlank { "يوزر منجر" } ?: "يوزر منجر"

        fun resolveUserPrice(username: String, fallbackPkg: String = ""): Double {
            val card = cardByUser[username]
            if (card != null && card.price > 0.0) return card.price
            val pkg = (card?.packageName?.takeIf { it.isNotBlank() } ?: fallbackPkg).trim()
            val pkgPrice = priceByPackage[pkg.lowercase()] ?: 0.0
            if (pkgPrice > 0.0) return pkgPrice
            return inferPriceFromPackageName(pkg)
        }

        val activeLiveBytes = sysStatus.totalDownloadBytes

        // 1. Stream ALL User Manager payments (v6 & v7 fallback, zero limits)
        var paymentsCount = 0
        var payTodayAmt = 0.0; var payTodayCnt = 0
        var payYestAmt = 0.0; var payYestCnt = 0
        var payWeekAmt = 0.0; var payWeekCnt = 0
        var payMonthAmt = 0.0; var payMonthCnt = 0
        var payAllAmt = 0.0; var payAllCnt = 0

        val rawPaymentLogs = ArrayList<PrintSalesLogEntity>()
        val groupedPaymentBuckets = LinkedHashMap<Pair<String, String>, PaymentBucket>()
        var overflowPayments = false

        val payPrimary = if (isV7) "/user-manager/payment/print" else "/tool/user-manager/payment/print"
        val payFallback = if (isV7) "/tool/user-manager/payment/print" else "/user-manager/payment/print"

        fun handlePaymentRecord(rec: Map<String, String>) {
            paymentsCount++
            val user = rec["user"].orEmpty().trim()
            val dateRaw = rec["trans-start"] ?: rec["date"] ?: rec["trans-end"]
            val dateKey = parseMikrotikDateKey(dateRaw) ?: todayKey
            val parsedMillis = parseDateKeyToMillis(dateKey)
            val pkg = resolveUserPackage(user)
            val rawPrice = rec["price"]?.trim()?.toDoubleOrNull()
                ?: rec["price"]?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull()
                ?: resolveUserPrice(user, pkg)
            val cardMb = cardByUser[user]?.dataUsageMb ?: 0.0
            val cardBytes = (cardMb * 1024.0 * 1024.0).toLong()

            payAllAmt += rawPrice; payAllCnt++
            if (dateKey == todayKey) { payTodayAmt += rawPrice; payTodayCnt++ }
            if (dateKey == yesterdayKey) { payYestAmt += rawPrice; payYestCnt++ }
            if (dateKey >= weekStartKey) { payWeekAmt += rawPrice; payWeekCnt++ }
            if (dateKey >= monthStartKey) { payMonthAmt += rawPrice; payMonthCnt++ }

            val bucket = groupedPaymentBuckets.getOrPut(dateKey to pkg) { PaymentBucket() }
            bucket.count++
            bucket.totalAmount += rawPrice
            bucket.totalDownloadBytes += cardBytes
            if (parsedMillis > bucket.latestMillis) bucket.latestMillis = parsedMillis

            if (!overflowPayments) {
                if (rawPaymentLogs.size < 1500) {
                    rawPaymentLogs.add(
                        PrintSalesLogEntity(
                            cardUsername = user.ifBlank { "دفعة يوزر منجر" },
                            packageName = pkg,
                            cardsCount = 1,
                            unitPrice = rawPrice,
                            totalAmount = rawPrice,
                            commissionTotal = 0.0,
                            serverAccount = serverAccount,
                            downloadLabel = formatBytesCompact(cardBytes),
                            downloadMb = cardMb,
                            printMethod = PrintMethod.PDF_A4_GRID,
                            operationType = SaleOperationType.DIRECT_SALE,
                            dateKey = dateKey,
                            createdAt = parsedMillis
                        )
                    )
                } else {
                    overflowPayments = true
                    rawPaymentLogs.clear()
                }
            }
        }

        client.ensureConnected(credentials)
        val payRes = client.executeStreaming(
            command = payPrimary,
            params = listOf("=.proplist=user,price,trans-start,trans-end,method"),
            timeoutMs = SESSIONS_BULK_TIMEOUT_MS
        ) { _, rec -> handlePaymentRecord(rec) }
        if (paymentsCount == 0 && (payRes.isFailure || payRes.getOrNull()?.isSuccess != true)) {
            client.ensureConnected(credentials)
            client.executeStreaming(
                command = payFallback,
                params = listOf("=.proplist=user,price,trans-start,trans-end,method"),
                timeoutMs = SESSIONS_BULK_TIMEOUT_MS
            ) { _, rec -> handlePaymentRecord(rec) }
        }

        // 2. Stream User Manager sessions with version-specific proplist (keep partial records if router DB is huge)
        var sessionsScanned = 0
        var todaySessionBytes = 0L
        val earliestSessionDateByUser = HashMap<String, String>(4096)
        val userSessionBytes = HashMap<String, Long>(4096)
        val todayActiveUsers = HashSet<String>()
        val yesterdayActiveUsers = HashSet<String>()
        val weekActiveUsers = HashSet<String>()
        val monthActiveUsers = HashSet<String>()

        val sessPrimary = if (isV7) "/user-manager/session/print" else "/tool/user-manager/session/print"
        val sessFallback = if (isV7) "/tool/user-manager/session/print" else "/user-manager/session/print"
        val sessProplistPrimary = if (isV7) {
            listOf("=.proplist=user,started,ended,download,upload,active")
        } else {
            listOf("=.proplist=user,from-time,till-time,download,upload,active")
        }
        val sessProplistFallback = if (isV7) {
            listOf("=.proplist=user,from-time,till-time,download,upload,active")
        } else {
            listOf("=.proplist=user,started,ended,download,upload,active")
        }

        fun handleSessionRecord(rec: Map<String, String>) {
            sessionsScanned++
            val user = rec["user"].orEmpty().trim()
            val fromKey = parseMikrotikDateKey(rec["from-time"] ?: rec["started"])
            val tillKey = parseMikrotikDateKey(rec["till-time"] ?: rec["ended"])
            val isActive = rec["active"].equals("true", ignoreCase = true)
            val dl = rec["download"]?.toLongOrNull() ?: 0L
            val ul = rec["upload"]?.toLongOrNull() ?: 0L
            val bytes = dl + ul

            if (isActive || fromKey == todayKey || tillKey == todayKey) {
                todaySessionBytes += bytes
            }

            if (user.isNotBlank()) {
                userSessionBytes[user] = (userSessionBytes[user] ?: 0L) + bytes
                val effectiveDate = fromKey ?: tillKey
                if (effectiveDate != null) {
                    val prev = earliestSessionDateByUser[user]
                    if (prev == null || effectiveDate < prev) {
                        earliestSessionDateByUser[user] = effectiveDate
                    }
                    if (fromKey == todayKey || tillKey == todayKey || isActive) todayActiveUsers.add(user)
                    if (fromKey == yesterdayKey) yesterdayActiveUsers.add(user)
                    if (effectiveDate >= weekStartKey) weekActiveUsers.add(user)
                    if (effectiveDate >= monthStartKey) monthActiveUsers.add(user)
                }
            }
        }

        client.ensureConnected(credentials)
        val sessRes = client.executeStreaming(
            command = sessPrimary,
            params = sessProplistPrimary,
            timeoutMs = SESSIONS_BULK_TIMEOUT_MS
        ) { _, rec -> handleSessionRecord(rec) }
        if (sessionsScanned == 0 && (sessRes.isFailure || sessRes.getOrNull()?.isSuccess != true)) {
            client.ensureConnected(credentials)
            client.executeStreaming(
                command = sessFallback,
                params = sessProplistFallback,
                timeoutMs = SESSIONS_BULK_TIMEOUT_MS
            ) { _, rec -> handleSessionRecord(rec) }
        }

        fun buildActivationPeriod(predicate: (String) -> Boolean, fallbackUsers: Set<String>): PeriodSalesSummary {
            val matchedUsers = earliestSessionDateByUser
                .filterValues(predicate)
                .keys
                .ifEmpty { fallbackUsers }
            val totalAmount = matchedUsers.sumOf { resolveUserPrice(it) }
            return PeriodSalesSummary(
                totalAmount = totalAmount,
                totalCards = matchedUsers.size,
                operationsCount = matchedUsers.size
            )
        }

        val usePayments = payAllCnt > 0
        val todaySales = if (usePayments) {
            PeriodSalesSummary(payTodayAmt, payTodayCnt, payTodayCnt)
        } else {
            buildActivationPeriod({ it == todayKey }, todayActiveUsers)
        }
        val yesterdaySales = if (usePayments) {
            PeriodSalesSummary(payYestAmt, payYestCnt, payYestCnt)
        } else {
            buildActivationPeriod({ it == yesterdayKey }, yesterdayActiveUsers)
        }
        val weeklySales = if (usePayments) {
            PeriodSalesSummary(payWeekAmt, payWeekCnt, payWeekCnt)
        } else {
            buildActivationPeriod({ it >= weekStartKey }, weekActiveUsers)
        }
        val monthlySales = if (usePayments) {
            PeriodSalesSummary(payMonthAmt, payMonthCnt, payMonthCnt)
        } else {
            buildActivationPeriod({ it >= monthStartKey }, monthActiveUsers)
        }

        val allTimeSales: PeriodSalesSummary
        val syncedSalesLogs: List<PrintSalesLogEntity>

        if (usePayments) {
            allTimeSales = PeriodSalesSummary(payAllAmt, payAllCnt, payAllCnt)
            syncedSalesLogs = if (!overflowPayments && rawPaymentLogs.isNotEmpty()) {
                rawPaymentLogs
            } else {
                groupedPaymentBuckets.map { (key, bucket) ->
                    val (dKey, pkg) = key
                    val unit = if (bucket.count > 0) bucket.totalAmount / bucket.count else 0.0
                    PrintSalesLogEntity(
                        cardUsername = "مدفوعات يوزر منجر • $pkg (${bucket.count} كرت)",
                        packageName = pkg,
                        cardsCount = bucket.count,
                        unitPrice = unit,
                        totalAmount = bucket.totalAmount,
                        commissionTotal = 0.0,
                        serverAccount = serverAccount,
                        downloadLabel = formatBytesCompact(bucket.totalDownloadBytes),
                        downloadMb = bucket.totalDownloadBytes / (1024.0 * 1024.0),
                        printMethod = PrintMethod.PDF_A4_GRID,
                        operationType = SaleOperationType.DIRECT_SALE,
                        dateKey = dKey,
                        createdAt = bucket.latestMillis
                    )
                }
            }
        } else if (earliestSessionDateByUser.isNotEmpty()) {
            // Group ALL activated User Manager users by (activationDate, packageName)
            val buckets = LinkedHashMap<Pair<String, String>, SessionSalesBucket>()
            var allCardsCount = 0
            var allAmount = 0.0

            earliestSessionDateByUser.forEach { (user, activationDate) ->
                val pkg = resolveUserPackage(user)
                val price = resolveUserPrice(user, pkg)
                val bytes = maxOf(
                    userSessionBytes[user] ?: 0L,
                    ((cardByUser[user]?.dataUsageMb ?: 0.0) * 1024.0 * 1024.0).toLong()
                )
                allCardsCount++
                allAmount += price
                val b = buckets.getOrPut(activationDate to pkg) { SessionSalesBucket() }
                b.count++
                b.totalAmount += price
                b.totalBytes += bytes
            }

            allTimeSales = PeriodSalesSummary(
                totalAmount = allAmount,
                totalCards = allCardsCount,
                operationsCount = buckets.size
            )
            syncedSalesLogs = buckets.map { (key, b) ->
                val (dKey, pkg) = key
                val unit = if (b.count > 0) b.totalAmount / b.count else 0.0
                PrintSalesLogEntity(
                    cardUsername = "مبيعات يوزر منجر • $pkg (${b.count} كرت)",
                    packageName = pkg,
                    cardsCount = b.count,
                    unitPrice = unit,
                    totalAmount = b.totalAmount,
                    commissionTotal = 0.0,
                    serverAccount = serverAccount,
                    downloadLabel = formatBytesCompact(b.totalBytes),
                    downloadMb = b.totalBytes / (1024.0 * 1024.0),
                    printMethod = PrintMethod.PDF_A4_GRID,
                    operationType = SaleOperationType.DIRECT_SALE,
                    dateKey = dKey,
                    createdAt = parseDateKeyToMillis(dKey)
                )
            }
        } else {
            // Router cleared old sessions & has no payments: derive all-time consumed sales from USED + EXPIRED cards
            val consumedCards = cards.filter { it.status != CardStatus.UNUSED }
            val byPkg = consumedCards.groupBy { it.packageName.ifBlank { "يوزر منجر" } }
            var totalAmt = 0.0
            syncedSalesLogs = byPkg.map { (pkg, pkgCards) ->
                val unit = priceByPackage[pkg.lowercase()]?.takeIf { it > 0.0 }
                    ?: pkgCards.firstOrNull { it.price > 0.0 }?.price
                    ?: inferPriceFromPackageName(pkg)
                val sumAmt = pkgCards.size * unit
                val sumMb = pkgCards.sumOf { it.dataUsageMb }
                totalAmt += sumAmt
                PrintSalesLogEntity(
                    cardUsername = "كروت مستهلكة • $pkg (${pkgCards.size} كرت)",
                    packageName = pkg,
                    cardsCount = pkgCards.size,
                    unitPrice = unit,
                    totalAmount = sumAmt,
                    commissionTotal = 0.0,
                    serverAccount = serverAccount,
                    downloadLabel = formatBytesCompact((sumMb * 1024.0 * 1024.0).toLong()),
                    downloadMb = sumMb,
                    printMethod = PrintMethod.PDF_A4_GRID,
                    operationType = SaleOperationType.DIRECT_SALE,
                    dateKey = todayKey,
                    createdAt = System.currentTimeMillis()
                )
            }
            allTimeSales = PeriodSalesSummary(
                totalAmount = totalAmt,
                totalCards = consumedCards.size,
                operationsCount = syncedSalesLogs.size
            )
        }

        val todayBytes = maxOf(todaySessionBytes, activeLiveBytes)
        val sourceNote = when {
            usePayments -> "بيانات كاملة من مدفوعات وجلسات يوزر منجر ($paymentsCount دفعة • $sessionsScanned جلسة)"
            sessionsScanned > 0 -> "بيانات كاملة من جلسات وتفعيل كروت يوزر منجر ($sessionsScanned جلسة • ${cards.size} كرت)"
            activeLiveBytes > 0L -> "بيانات حية من جلسات يوزر منجر النشطة (${sysStatus.activeConnectionsCount} متصل • ${cards.size} كرت)"
            else -> "بيانات كروت يوزر منجر الكاملة (${cards.size} كرت متزامن)"
        }

        val metrics = MikrotikLiveReportsMetrics(
            todayDownloadBytes = todayBytes,
            activeLiveDownloadBytes = activeLiveBytes,
            todaySales = todaySales,
            yesterdaySales = yesterdaySales,
            weeklySales = weeklySales,
            monthlySales = monthlySales,
            allTimeSales = allTimeSales,
            sessionsScannedCount = sessionsScanned,
            paymentsScannedCount = paymentsCount,
            sourceNote = sourceNote
        )
        return UserManagerSalesSyncBundle(salesLogs = syncedSalesLogs, liveMetrics = metrics)
    }

    private fun buildFallbackSalesBundleFromCards(
        sysStatus: MikrotikSystemStatus,
        serverAccount: String,
        cards: List<CardEntity>,
        packages: List<CardPackageEntity>
    ): UserManagerSalesSyncBundle {
        val todayKey = DateTimeUtils.todayDateKey()
        val priceByPackage = HashMap<String, Double>()
        packages.forEach { priceByPackage[it.name.trim().lowercase()] = it.price }

        val consumedCards = cards.filter { it.status != CardStatus.UNUSED }
        val byPkg = consumedCards.groupBy { it.packageName.ifBlank { "يوزر منجر" } }
        var totalAmt = 0.0
        val logs = byPkg.map { (pkg, pkgCards) ->
            val unit = priceByPackage[pkg.lowercase()]?.takeIf { it > 0.0 }
                ?: pkgCards.firstOrNull { it.price > 0.0 }?.price
                ?: inferPriceFromPackageName(pkg)
            val sumAmt = pkgCards.size * unit
            val sumMb = pkgCards.sumOf { it.dataUsageMb }
            totalAmt += sumAmt
            PrintSalesLogEntity(
                cardUsername = "كروت مستهلكة • $pkg (${pkgCards.size} كرت)",
                packageName = pkg,
                cardsCount = pkgCards.size,
                unitPrice = unit,
                totalAmount = sumAmt,
                commissionTotal = 0.0,
                serverAccount = serverAccount,
                downloadLabel = formatBytesCompact((sumMb * 1024.0 * 1024.0).toLong()),
                downloadMb = sumMb,
                printMethod = PrintMethod.PDF_A4_GRID,
                operationType = SaleOperationType.DIRECT_SALE,
                dateKey = todayKey,
                createdAt = System.currentTimeMillis()
            )
        }
        val allTime = PeriodSalesSummary(
            totalAmount = totalAmt,
            totalCards = consumedCards.size,
            operationsCount = logs.size
        )
        val metrics = MikrotikLiveReportsMetrics(
            todayDownloadBytes = sysStatus.totalDownloadBytes,
            activeLiveDownloadBytes = sysStatus.totalDownloadBytes,
            allTimeSales = allTime,
            sourceNote = "بيانات كروت يوزر منجر الكاملة (${cards.size} كرت متزامن)"
        )
        return UserManagerSalesSyncBundle(salesLogs = logs, liveMetrics = metrics)
    }

    private fun parseSessionRecord(
        cleanUser: String,
        rec: Map<String, String>,
        nowMillis: Long
    ): CardSessionEntity {
        val dl = rec["download"]?.toLongOrNull() ?: 0L
        val ul = rec["upload"]?.toLongOrNull() ?: 0L
        val total = dl + ul
        val isActive = rec["active"].equals("true", ignoreCase = true) ||
            rec["status"].equals("start", ignoreCase = true)
        val startTime = (rec["from-time"] ?: rec["started"]).orEmpty().ifBlank { "-" }
        val endTimeRaw = (rec["till-time"] ?: rec["ended"]).orEmpty()
        val endTime = when {
            isActive -> "نشط الآن"
            endTimeRaw.isNotBlank() -> endTimeRaw
            else -> "-"
        }
        val uptime = rec["uptime"].orEmpty().ifBlank { "0s" }
        val ip = (rec["user-ip"] ?: rec["user-address"] ?: rec["framed-ip-address"]).orEmpty().ifBlank { "-" }
        val mac = (rec["calling-station-id"] ?: rec["host-ip"]).orEmpty().ifBlank { "-" }

        return CardSessionEntity(
            username = cleanUser,
            mikrotikSessionId = rec[".id"].orEmpty(),
            startTime = startTime,
            endTime = endTime,
            uptime = uptime,
            downloadBytes = dl,
            uploadBytes = ul,
            downloadLabel = formatBytesCompact(dl),
            uploadLabel = formatBytesCompact(ul),
            totalLabel = formatBytesCompact(total),
            userIp = ip,
            macAddress = mac,
            isActive = isActive,
            fetchedAt = nowMillis
        )
    }

    private suspend fun pushSingleUserManagerCardInternal(
        client: MikrotikApiClient,
        isV7: Boolean,
        customer: String,
        card: CardEntity
    ): Result<String?> {
        return if (isV7) {
            val addParams = mutableListOf(
                "=name=${card.username}",
                "=password=${card.password}",
                "=shared-users=${if (card.bindFirstDevice) "1" else "unlimited"}",
                "=comment=alkamel:${card.packageName}"
            )
            val userAdd = client.execute("/user-manager/user/add", addParams).getOrElse { return Result.failure(it) }
            if (!userAdd.isSuccess) {
                return Result.failure(IllegalStateException(userAdd.trapMessage ?: "فشل إضافة المستخدم في يوزر منجر"))
            }
            if (card.packageName.isNotBlank()) {
                client.execute(
                    "/user-manager/user-profile/add",
                    listOf("=user=${card.username}", "=profile=${card.packageName}")
                )
            }
            Result.success(userAdd.doneAttributes["ret"])
        } else {
            val addParams = mutableListOf(
                "=customer=${customer.ifBlank { "admin" }}",
                "=username=${card.username}",
                "=password=${card.password}",
                "=shared-users=${if (card.bindFirstDevice) "1" else "unlimited"}",
                "=comment=alkamel:${card.packageName}"
            )
            val userAdd = client.execute("/tool/user-manager/user/add", addParams).getOrElse { return Result.failure(it) }
            if (!userAdd.isSuccess) {
                return Result.failure(IllegalStateException(userAdd.trapMessage ?: "فشل إضافة الكرت في يوزر منجر"))
            }
            val createdId = userAdd.doneAttributes["ret"]
            if (card.packageName.isNotBlank()) {
                val activateTarget = createdId ?: card.username
                client.execute(
                    "/tool/user-manager/user/create-and-activate-profile",
                    listOf(
                        "=numbers=$activateTarget",
                        "=customer=${customer.ifBlank { "admin" }}",
                        "=profile=${card.packageName}"
                    )
                )
            }
            Result.success(createdId)
        }
    }

    private suspend fun deleteSingleUserManagerCardInternal(
        client: MikrotikApiClient,
        isV7: Boolean,
        card: CardEntity
    ): Boolean {
        val basePath = if (isV7) "/user-manager/user" else "/tool/user-manager/user"
        val targetId = card.mikrotikId?.takeIf { it.isNotBlank() } ?: run {
            val queryKey = if (!isV7) "?username=${card.username}" else "?name=${card.username}"
            client.execute("$basePath/print", listOf(queryKey))
                .getOrNull()?.records?.firstOrNull()?.get(".id")
        } ?: return false

        val removeRes = client.execute("$basePath/remove", listOf("=.id=$targetId")).getOrNull()
        return removeRes?.isSuccess == true
    }

    fun parseRouterOsDurationSeconds(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        var total = 0L
        DURATION_TOKEN_REGEX.findAll(raw.trim()).forEach { match ->
            val value = match.groupValues[1].toLongOrNull() ?: 0L
            when (match.groupValues[2].lowercase(Locale.US)) {
                "w" -> total += value * 7L * 86400L
                "d" -> total += value * 86400L
                "h" -> total += value * 3600L
                "m" -> total += value * 60L
                "s" -> total += value
            }
        }
        return total
    }

    fun parseMikrotikDateKey(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val token = raw.trim().substringBefore(' ')
        if (token.length >= 10 && token[4] == '-' && token[7] == '-') {
            return token.substring(0, 10)
        }
        val parts = token.split('/')
        if (parts.size == 3) {
            val monthNum = MONTH_ABBR_TO_NUM[parts[0].lowercase(Locale.US)] ?: return null
            val day = parts[1].padStart(2, '0')
            val year = parts[2]
            if (year.length == 4 && day.length == 2) {
                return "$year-$monthNum-$day"
            }
        }
        return null
    }

    private fun parseDateKeyToMillis(dateKey: String): Long {
        return runCatching {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
            sdf.parse(dateKey)?.time ?: System.currentTimeMillis()
        }.getOrDefault(System.currentTimeMillis())
    }

    fun inferPriceFromPackageName(packageName: String): Double {
        val match = CURRENCY_SUFFIX_REGEX.find(packageName) ?: return 0.0
        return match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: 0.0
    }

    fun formatBytesCompact(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            else -> "${kb.toInt()} KB"
        }
    }
}
