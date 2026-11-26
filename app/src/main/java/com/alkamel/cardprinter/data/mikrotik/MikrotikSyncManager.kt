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

    private const val STREAM_BULK_TIMEOUT_MS = 240_000
    private const val SESSIONS_BULK_TIMEOUT_MS = 120_000
    private const val TELEMETRY_TIMEOUT_MS = 8_000
    private const val ACTIVE_QUERY_TIMEOUT_MS = 6_000

    internal fun isNoSuchCommandPrefixError(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val lower = message.lowercase(Locale.US)
        return lower.contains("no such command prefix") ||
            lower.contains("no such command") ||
            lower.contains("bad command name")
    }

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
    private val COLON_TIME_REGEX = Regex("""(?:^|\s)(\d{1,2}):(\d{2})(?::(\d{2}))?""")
    private val BYTE_SIZE_REGEX = Regex("""^\s*(\d+(?:\.\d+)?)\s*(tib|tb|t|gib|gb|g|mib|mb|m|kib|kb|k|b)?\s*$""", RegexOption.IGNORE_CASE)

    internal data class ProfileLimitationMeta(
        val transferLimitBytes: Long = 0L,
        val downloadLimitBytes: Long = 0L,
        val uploadLimitBytes: Long = 0L,
        val uptimeLimitSeconds: Long = 0L,
        val validitySeconds: Long = 0L,
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
        var totalBytes: Long = 0L,
        var latestMillis: Long = 0L
    )

    internal data class RouterCidrInterface(
        val networkInt: Int,
        val maskInt: Int,
        val prefixLength: Int,
        val interfaceName: String
    )

    internal data class RouterVlanLookup(
        val vlanByInterfaceName: Map<String, String> = emptyMap(),
        val vlanById: Map<String, String> = emptyMap(),
        val vlansByParentInterface: Map<String, List<String>> = emptyMap(),
        val interfaceByHotspotServer: Map<String, String> = emptyMap(),
        val vlanIdByMac: Map<String, String> = emptyMap(),
        val interfaceByMac: Map<String, String> = emptyMap(),
        val interfaceByIp: Map<String, String> = emptyMap(),
        val activeUserHint: Map<String, Triple<String, String, String>> = emptyMap(),
        val subnets: List<RouterCidrInterface> = emptyList()
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

            // 1. Fetch ALL User Manager profiles + detect authoritative v6 (/tool/user-manager) vs v7 (/user-manager) prefix
            onProgress(20, "جاري جلب جميع باقات يوزر منجر وحدود الصلاحية...")
            val (resolvedIsV7, rawProfileRecords) = fetchRawProfileRecordsWithDetectedMode(client, credentials, isV7)
            val limitationsByProfile = fetchProfileLimitationsBestEffort(
                client = client,
                credentials = credentials,
                isV7 = resolvedIsV7,
                rawProfileRecords = rawProfileRecords
            ).toMutableMap()
            client.ensureConnected(credentials).getOrElse { return@withContext Result.failure(it) }

            val fetchedPackages = parseUserManagerPackages(rawProfileRecords, limitationsByProfile)
            var mergedPackages = if (fetchedPackages.isNotEmpty()) {
                repository.mergePackagesFromMikrotik(fetchedPackages).getOrDefault(fetchedPackages)
            } else {
                repository.getAllPackagesOnce()
            }

            // Merge locally configured package limits from Room as fallback into limitationsByProfile
            mergedPackages.forEach { pkg ->
                val key = pkg.name.trim().lowercase()
                if (key.isBlank()) return@forEach
                val existing = limitationsByProfile[key]
                val localBytes = if (pkg.dataLimitMb > 0.0) (pkg.dataLimitMb * 1024.0 * 1024.0).toLong() else 0L
                val localUptimeSec = parseRouterOsDurationSeconds(pkg.validityLabel)
                if (existing == null) {
                    if (localBytes > 0L || localUptimeSec > 0L) {
                        limitationsByProfile[key] = ProfileLimitationMeta(
                            transferLimitBytes = localBytes,
                            uptimeLimitSeconds = localUptimeSec,
                            validitySeconds = localUptimeSec,
                            validityLabel = pkg.validityLabel
                        )
                    }
                } else {
                    limitationsByProfile[key] = existing.copy(
                        transferLimitBytes = maxOf(existing.transferLimitBytes, localBytes),
                        uptimeLimitSeconds = maxOf(existing.uptimeLimitSeconds, localUptimeSec),
                        validitySeconds = maxOf(existing.validitySeconds, localUptimeSec),
                        validityLabel = existing.validityLabel.ifBlank { pkg.validityLabel }
                    )
                }
            }

            // 2. Fetch ALL User Manager cards via streaming (no filter, no limit, full snapshot)
            client.ensureConnected(credentials).getOrElse { return@withContext Result.failure(it) }
            onProgress(30, "جاري جلب جميع كروت يوزر منجر...")
            val priceByPackage = HashMap<String, Double>()
            mergedPackages.forEach { priceByPackage[it.name.trim().lowercase()] = it.price }

            val cardsResult = fetchAllUserManagerCardsStreaming(
                client = client,
                credentials = credentials,
                isV7 = resolvedIsV7,
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
                    isV7 = resolvedIsV7,
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
            val primaryTrap = primaryStream.getOrNull()?.trapMessage ?: primaryStream.exceptionOrNull()?.message
            if (sessions.isEmpty() && isNoSuchCommandPrefixError(primaryTrap)) {
                sessions.clear()
                client.ensureConnected(credentials)
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
            var resolvedIsV7 = sysStatus.routerOsVersion.trim().startsWith("7")
            val total = cards.size
            val pushedCards = mutableListOf<CardEntity>()
            var addedCount = 0
            var failedCount = 0
            var lastRouterError: String? = null

            cards.forEachIndexed { idx, card ->
                var pushRes = pushSingleUserManagerCardInternal(
                    client = client,
                    isV7 = resolvedIsV7,
                    customer = credentials.username,
                    card = card
                )
                if (pushRes.isFailure && isNoSuchCommandPrefixError(pushRes.exceptionOrNull()?.message)) {
                    val altIsV7 = !resolvedIsV7
                    client.ensureConnected(credentials)
                    val altRes = pushSingleUserManagerCardInternal(
                        client = client,
                        isV7 = altIsV7,
                        customer = credentials.username,
                        card = card
                    )
                    if (altRes.isSuccess) {
                        resolvedIsV7 = altIsV7
                        pushRes = altRes
                    }
                }
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
            val guessedIsV7 = sysStatus.routerOsVersion.trim().startsWith("7")
            val (resolvedIsV7, _) = fetchRawProfileRecordsWithDetectedMode(client, credentials, guessedIsV7)

            var removed = 0
            for (card in cards) {
                val ok = deleteSingleUserManagerCardInternal(client, resolvedIsV7, card)
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

    internal suspend fun fetchRawProfileRecordsWithDetectedMode(
        client: MikrotikApiClient,
        credentials: MikrotikCredentials,
        guessedIsV7: Boolean
    ): Pair<Boolean, List<Map<String, String>>> {
        val primaryCmd = if (guessedIsV7) "/user-manager/profile/print" else "/tool/user-manager/profile/print"
        val fallbackCmd = if (guessedIsV7) "/tool/user-manager/profile/print" else "/user-manager/profile/print"

        client.ensureConnected(credentials)
        val primaryRes = client.execute(primaryCmd, timeoutMs = 30000).getOrNull()
        if (primaryRes != null && primaryRes.isSuccess) {
            return guessedIsV7 to primaryRes.records
        }
        // Only switch User Manager prefix when RouterOS reports command-prefix mismatch (or primary returned no result)
        if (primaryRes == null || isNoSuchCommandPrefixError(primaryRes.trapMessage)) {
            client.ensureConnected(credentials)
            val fallbackRes = client.execute(fallbackCmd, timeoutMs = 30000).getOrNull()
            if (fallbackRes != null && fallbackRes.isSuccess) {
                return (!guessedIsV7) to fallbackRes.records
            }
        }
        return guessedIsV7 to emptyList()
    }

    private suspend fun fetchProfileLimitationsBestEffort(
        client: MikrotikApiClient,
        credentials: MikrotikCredentials,
        isV7: Boolean,
        rawProfileRecords: List<Map<String, String>>
    ): Map<String, ProfileLimitationMeta> {
        val result = HashMap<String, ProfileLimitationMeta>()

        // Seed validity from profiles and map profile .id / name-for-users -> canonical name
        val profileAliasToKeys = HashMap<String, MutableSet<String>>()
        rawProfileRecords.forEach { r ->
            val name = r["name"].orEmpty().trim()
            val nameForUsers = r["name-for-users"].orEmpty().trim()
            val id = r[".id"].orEmpty().trim()
            val canonicalKeys = LinkedHashSet<String>()
            if (name.isNotBlank()) canonicalKeys.add(name.lowercase())
            if (nameForUsers.isNotBlank()) canonicalKeys.add(nameForUsers.lowercase())
            if (canonicalKeys.isEmpty()) return@forEach

            val validityRaw = r["validity"].orEmpty().trim()
            val validitySec = parseRouterOsDurationSeconds(validityRaw)
            canonicalKeys.forEach { key ->
                if (validitySec > 0L || validityRaw.isNotBlank()) {
                    val prev = result[key]
                    result[key] = ProfileLimitationMeta(
                        transferLimitBytes = prev?.transferLimitBytes ?: 0L,
                        downloadLimitBytes = prev?.downloadLimitBytes ?: 0L,
                        uploadLimitBytes = prev?.uploadLimitBytes ?: 0L,
                        uptimeLimitSeconds = prev?.uptimeLimitSeconds ?: 0L,
                        validitySeconds = maxOf(prev?.validitySeconds ?: 0L, validitySec),
                        validityLabel = prev?.validityLabel?.takeIf { it.isNotBlank() } ?: validityRaw
                    )
                }
                profileAliasToKeys.getOrPut(key) { LinkedHashSet() }.addAll(canonicalKeys)
            }
            if (id.isNotBlank()) {
                profileAliasToKeys.getOrPut(id.lowercase()) { LinkedHashSet() }.addAll(canonicalKeys)
            }
        }

        runCatching {
            val limCandidates = if (isV7) {
                listOf("/user-manager/limitation/print")
            } else {
                listOf("/tool/user-manager/profile/limitation/print", "/tool/user-manager/limitation/print")
            }
            val linkCandidates = if (isV7) {
                listOf("/user-manager/profile-limitation/print")
            } else {
                listOf("/tool/user-manager/profile/profile-limitation/print", "/tool/user-manager/profile-limitation/print")
            }

            var limRecords = emptyList<Map<String, String>>()
            for (cmd in limCandidates) {
                client.ensureConnected(credentials)
                val res = client.execute(cmd, timeoutMs = 20000).getOrNull()
                if (res != null && res.isSuccess) {
                    limRecords = res.records
                    break
                }
            }

            var linkRecords = emptyList<Map<String, String>>()
            for (cmd in linkCandidates) {
                client.ensureConnected(credentials)
                val res = client.execute(cmd, timeoutMs = 20000).getOrNull()
                if (res != null && res.isSuccess) {
                    linkRecords = res.records
                    break
                }
            }

            if (limRecords.isEmpty()) return result

            val limByKey = HashMap<String, ProfileLimitationMeta>()
            limRecords.forEach { r ->
                val name = r["name"].orEmpty().trim().lowercase()
                val id = r[".id"].orEmpty().trim().lowercase()
                if (name.isBlank() && id.isBlank()) return@forEach

                val transfer = parseRouterOsBytes(r["transfer-limit"])
                val dl = parseRouterOsBytes(r["download-limit"])
                val ul = parseRouterOsBytes(r["upload-limit"])
                val effectiveTransfer = when {
                    transfer > 0L -> transfer
                    dl > 0L && ul > 0L -> dl + ul
                    dl > 0L -> dl
                    ul > 0L -> ul
                    else -> 0L
                }
                val uptimeStr = r["uptime-limit"].orEmpty().trim()
                val uptimeSec = parseRouterOsDurationSeconds(uptimeStr)
                val meta = ProfileLimitationMeta(
                    transferLimitBytes = effectiveTransfer,
                    downloadLimitBytes = dl,
                    uploadLimitBytes = ul,
                    uptimeLimitSeconds = uptimeSec,
                    validityLabel = uptimeStr
                )
                if (name.isNotBlank()) limByKey[name] = meta
                if (id.isNotBlank()) limByKey[id] = meta
            }

            linkRecords.forEach { link ->
                val profRaw = link["profile"].orEmpty().trim().lowercase()
                val limRaw = link["limitation"].orEmpty().trim().lowercase()
                if (profRaw.isBlank() || limRaw.isBlank()) return@forEach
                val meta = limByKey[limRaw] ?: return@forEach

                val targetProfileKeys = profileAliasToKeys[profRaw] ?: setOf(profRaw)
                targetProfileKeys.forEach { profKey ->
                    val prev = result[profKey]
                    result[profKey] = ProfileLimitationMeta(
                        transferLimitBytes = maxOf(prev?.transferLimitBytes ?: 0L, meta.transferLimitBytes),
                        downloadLimitBytes = maxOf(prev?.downloadLimitBytes ?: 0L, meta.downloadLimitBytes),
                        uploadLimitBytes = maxOf(prev?.uploadLimitBytes ?: 0L, meta.uploadLimitBytes),
                        uptimeLimitSeconds = maxOf(prev?.uptimeLimitSeconds ?: 0L, meta.uptimeLimitSeconds),
                        validitySeconds = prev?.validitySeconds ?: 0L,
                        validityLabel = prev?.validityLabel?.takeIf { it.isNotBlank() } ?: meta.validityLabel
                    )
                }
            }
        }
        return result
    }

    private fun parseUserManagerPackages(
        records: List<Map<String, String>>,
        limitationsByProfile: Map<String, ProfileLimitationMeta>
    ): List<CardPackageEntity> {
        val result = LinkedHashMap<String, CardPackageEntity>()
        records.forEach { r ->
            val name = r["name"].orEmpty().trim()
            if (name.isNotBlank()) {
                val rawPrice = r["price"].orEmpty().trim()
                val routerPrice = rawPrice.toDoubleOrNull()
                    ?: rawPrice.filter { it.isDigit() || it == '.' }.toDoubleOrNull()
                    ?: 0.0
                val price = if (routerPrice > 0.0) routerPrice else inferPriceFromPackageName(name)
                val limMeta = limitationsByProfile[name.lowercase()]
                    ?: r["name-for-users"]?.trim()?.lowercase()?.let { limitationsByProfile[it] }
                val validity = r["validity"].orEmpty().trim().ifBlank { limMeta?.validityLabel.orEmpty() }
                val dataBytes = maxOf(
                    limMeta?.transferLimitBytes ?: 0L,
                    limMeta?.downloadLimitBytes ?: 0L
                )
                val dataLimitMb = dataBytes / (1024.0 * 1024.0)

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

    internal suspend fun fetchAllUserManagerCardsStreaming(
        client: MikrotikApiClient,
        credentials: MikrotikCredentials,
        isV7: Boolean,
        serverAccount: String,
        priceByPackage: Map<String, Double>,
        limitationsByProfile: Map<String, ProfileLimitationMeta>,
        onCountProgress: (Int) -> Unit
    ): Result<List<CardEntity>> {
        val primaryRes = if (isV7) {
            fetchV7CardsStreaming(client, credentials, serverAccount, priceByPackage, limitationsByProfile, onCountProgress)
        } else {
            fetchV6CardsStreaming(client, credentials, serverAccount, priceByPackage, limitationsByProfile, onCountProgress)
        }
        if (primaryRes.isSuccess) return primaryRes

        // Only try the opposite RouterOS prefix when the router explicitly reported a command-prefix mismatch.
        // Never overwrite a real v6/v7 transport or timeout error with a bogus "no such command prefix" from the wrong version!
        val primaryErrMsg = primaryRes.exceptionOrNull()?.message
        if (isNoSuchCommandPrefixError(primaryErrMsg)) {
            client.ensureConnected(credentials)
            val fallbackRes = if (isV7) {
                fetchV6CardsStreaming(client, credentials, serverAccount, priceByPackage, limitationsByProfile, onCountProgress)
            } else {
                fetchV7CardsStreaming(client, credentials, serverAccount, priceByPackage, limitationsByProfile, onCountProgress)
            }
            if (fallbackRes.isSuccess) return fallbackRes
        }
        return primaryRes
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

            val actualProfileRaw = u["actual-profile"].orEmpty().trim()
            val actualProfile = if (actualProfileRaw.equals("none", ignoreCase = true)) "" else actualProfileRaw
            val profileFieldRaw = u["profile"].orEmpty().trim()
            val profileField = if (profileFieldRaw.equals("none", ignoreCase = true)) "" else profileFieldRaw
            val commentProfile = u["comment"].orEmpty().trim()
                .takeIf { it.startsWith("alkamel:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                .orEmpty()
            val pkgName = actualProfile
                .ifBlank { profileField }
                .ifBlank { commentProfile }

            val dlOnlyBytes = parseRouterOsBytes(u["download-used"])
            val ulOnlyBytes = parseRouterOsBytes(u["upload-used"])
            val totalBytes = dlOnlyBytes + ulOnlyBytes
            val uptimeUsedRaw = u["uptime-used"].orEmpty().trim()
            val uptimeUsed = uptimeUsedRaw.ifBlank { "0s" }
            val uptimeSeconds = parseRouterOsDurationSeconds(uptimeUsedRaw)
            val disabled = u["disabled"].equals("true", ignoreCase = true) ||
                u["disabled"].equals("yes", ignoreCase = true)
            val isActive = u["active"].equals("true", ignoreCase = true) ||
                u["active"].equals("yes", ignoreCase = true)
            val lastSeen = u["last-seen"].orEmpty().trim()
            val hasLastSeen = lastSeen.isNotBlank() && !lastSeen.equals("never", ignoreCase = true)
            val lastSeenMillis = if (hasLastSeen) parseMikrotikTimestampMillis(lastSeen) else null
            val endTimeMillis = parseMikrotikTimestampMillis(u["end-time"] ?: u["till-time"])
            val hasUsage = totalBytes > 0L || uptimeSeconds > 0L || hasLastSeen

            val limitMeta = if (pkgName.isNotBlank()) limitationsByProfile[pkgName.lowercase()] else null
            val reachedTransferLimit = limitMeta != null &&
                limitMeta.transferLimitBytes > 0L &&
                totalBytes >= (limitMeta.transferLimitBytes * 0.99).toLong()
            val reachedDownloadLimit = limitMeta != null &&
                limitMeta.downloadLimitBytes > 0L &&
                dlOnlyBytes >= (limitMeta.downloadLimitBytes * 0.99).toLong()
            val reachedUploadLimit = limitMeta != null &&
                limitMeta.uploadLimitBytes > 0L &&
                ulOnlyBytes >= (limitMeta.uploadLimitBytes * 0.99).toLong()
            val reachedUptimeLimit = limitMeta != null &&
                limitMeta.uptimeLimitSeconds > 0L &&
                uptimeSeconds >= (limitMeta.uptimeLimitSeconds - 5L).coerceAtLeast(1L)
            val reachedValidityLimit = limitMeta != null &&
                limitMeta.validitySeconds > 0L &&
                !isActive &&
                lastSeenMillis != null &&
                (baseNow - lastSeenMillis) >= limitMeta.validitySeconds * 1000L
            val passedEndTime = endTimeMillis != null && endTimeMillis > 0L && endTimeMillis < baseNow
            val explicitExpiredState = u["state"].orEmpty().trim().lowercase().let { st ->
                st.contains("expired") || st.contains("used") || st.contains("end")
            }

            val status = when {
                disabled -> CardStatus.EXPIRED
                passedEndTime || explicitExpiredState -> CardStatus.EXPIRED
                reachedTransferLimit || reachedDownloadLimit || reachedUploadLimit || reachedUptimeLimit || reachedValidityLimit -> CardStatus.EXPIRED
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
                dataUsageLabel = formatBytesCompact(totalBytes),
                dataUsageMb = totalBytes / (1024.0 * 1024.0),
                createdAt = baseNow - idx
            )
        }

        // Use strictly valid RouterOS v6 User Manager user attributes for fast streaming (3x-4x faster for 50,000+ cards)
        val fastProplist = listOf(
            "=.proplist=.id,username,password,customer,actual-profile,comment,download-used,upload-used,uptime-used,disabled,active,last-seen"
        )
        var streamRes = client.executeStreaming(
            command = "/tool/user-manager/user/print",
            params = fastProplist,
            timeoutMs = STREAM_BULK_TIMEOUT_MS
        ) { idx, u -> processV6User(idx, u) }

        val firstTrap = streamRes.getOrNull()?.trapMessage ?: streamRes.exceptionOrNull()?.message
        // Retry without .proplist on any non-prefix error or empty proplist output (handles older RouterOS v6 builds safely)
        if ((streamRes.isFailure || streamRes.getOrNull()?.isSuccess != true || result.isEmpty()) && !isNoSuchCommandPrefixError(firstTrap)) {
            client.connectAndLogin(credentials)
            result.clear()
            streamRes = client.executeStreaming(
                command = "/tool/user-manager/user/print",
                params = emptyList(),
                timeoutMs = STREAM_BULK_TIMEOUT_MS
            ) { idx, u -> processV6User(idx, u) }
        }

        if (streamRes.isFailure) {
            if (result.isNotEmpty()) {
                onCountProgress(result.size)
                return Result.success(result.values.toList())
            }
            return Result.failure(streamRes.exceptionOrNull() ?: IOException("تعذر قراءة كروت يوزر منجر (v6)."))
        }
        val sr = streamRes.getOrThrow()
        if (!sr.isSuccess) {
            if (result.isNotEmpty()) {
                onCountProgress(result.size)
                return Result.success(result.values.toList())
            }
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
        limitationsByProfile: Map<String, ProfileLimitationMeta>,
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
                agg.totalBytes += parseRouterOsBytes(s["download"]) + parseRouterOsBytes(s["upload"])
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

        fun processV7User(idx: Int, u: Map<String, String>) {
            val count = idx + 1
            if (count % 500 == 0) {
                onCountProgress(count)
            }
            val uname = (u["name"] ?: u["username"]).orEmpty().trim()
            if (uname.isBlank()) return

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
            val disabled = u["disabled"].equals("true", ignoreCase = true) ||
                u["disabled"].equals("yes", ignoreCase = true)

            val limitMeta = if (pkgName.isNotBlank()) limitationsByProfile[pkgName.lowercase()] else null
            val reachedDataLimit = limitMeta != null &&
                limitMeta.transferLimitBytes > 0L &&
                totalBytes >= (limitMeta.transferLimitBytes * 0.99).toLong()
            val reachedUptimeLimit = limitMeta != null &&
                limitMeta.uptimeLimitSeconds > 0L &&
                uptimeSeconds >= (limitMeta.uptimeLimitSeconds - 5L).coerceAtLeast(1L)

            val status = when {
                disabled -> CardStatus.EXPIRED
                anyUsedOrExpired -> CardStatus.EXPIRED
                reachedDataLimit || reachedUptimeLimit -> CardStatus.EXPIRED
                runningEntry != null || sessAgg?.isActive == true -> CardStatus.USED
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

        var userStreamRes = client.executeStreaming(
            command = "/user-manager/user/print",
            params = listOf("=.proplist=.id,name,password,group,comment,disabled"),
            timeoutMs = STREAM_BULK_TIMEOUT_MS
        ) { idx, u -> processV7User(idx, u) }

        val firstV7Trap = userStreamRes.getOrNull()?.trapMessage ?: userStreamRes.exceptionOrNull()?.message
        if ((userStreamRes.isFailure || userStreamRes.getOrNull()?.isSuccess != true || result.isEmpty()) && !isNoSuchCommandPrefixError(firstV7Trap)) {
            client.connectAndLogin(credentials)
            result.clear()
            userStreamRes = client.executeStreaming(
                command = "/user-manager/user/print",
                params = emptyList(),
                timeoutMs = STREAM_BULK_TIMEOUT_MS
            ) { idx, u -> processV7User(idx, u) }
        }

        if (userStreamRes.isFailure) {
            if (result.isNotEmpty()) {
                onCountProgress(result.size)
                return Result.success(result.values.toList())
            }
            return Result.failure(userStreamRes.exceptionOrNull() ?: IOException("تعذر قراءة كروت يوزر منجر (v7)."))
        }
        val sr = userStreamRes.getOrThrow()
        if (!sr.isSuccess) {
            if (result.isNotEmpty()) {
                onCountProgress(result.size)
                return Result.success(result.values.toList())
            }
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

        // 0. Fetch lightweight RouterOS VLAN / Hotspot / IP / Bridge / ARP lookup tables
        // so every sales card displays the real connected VLAN (with physical port ONLY as fallback).
        val vlanLookup = fetchRouterVlanLookupBestEffort(client, credentials)

        // 1. Stream User Manager sessions (with nas-port-id, user-ip, calling-station-id for VLAN resolution)
        var sessionsScanned = 0
        var todaySessionBytes = 0L
        val earliestSessionDateByUser = HashMap<String, String>(4096)
        val earliestSessionMillisByUser = HashMap<String, Long>(4096)
        val userSessionBytes = HashMap<String, Long>(4096)
        val userConnectionVlan = HashMap<String, String>(4096)
        val todayActiveUsers = HashSet<String>()
        val yesterdayActiveUsers = HashSet<String>()
        val weekActiveUsers = HashSet<String>()
        val monthActiveUsers = HashSet<String>()

        val sessPrimary = if (isV7) "/user-manager/session/print" else "/tool/user-manager/session/print"
        val sessFallback = if (isV7) "/tool/user-manager/session/print" else "/user-manager/session/print"
        val sessProplistPrimary = if (isV7) {
            listOf("=.proplist=user,started,ended,download,upload,active,nas-port-id,user-address,calling-station-id")
        } else {
            listOf("=.proplist=user,from-time,till-time,download,upload,active,nas-port-id,user-ip,calling-station-id")
        }
        val sessProplistFallback = if (isV7) {
            listOf("=.proplist=user,from-time,till-time,download,upload,active,nas-port-id,user-ip,calling-station-id")
        } else {
            listOf("=.proplist=user,started,ended,download,upload,active,nas-port-id,user-address,calling-station-id")
        }

        fun handleSessionRecord(rec: Map<String, String>) {
            sessionsScanned++
            val user = rec["user"].orEmpty().trim()
            val fromRaw = rec["from-time"] ?: rec["started"]
            val tillRaw = rec["till-time"] ?: rec["ended"]
            val fromKey = parseMikrotikDateKey(fromRaw)
            val tillKey = parseMikrotikDateKey(tillRaw)
            val isActive = rec["active"].equals("true", ignoreCase = true)
            val dl = parseRouterOsBytes(rec["download"])
            val ul = parseRouterOsBytes(rec["upload"])
            val bytes = dl + ul

            if (isActive || fromKey == todayKey || tillKey == todayKey) {
                todaySessionBytes += bytes
            }

            if (user.isNotBlank()) {
                userSessionBytes[user] = (userSessionBytes[user] ?: 0L) + bytes
                val effectiveDate = fromKey ?: tillKey
                val effectiveMillis = parseMikrotikTimestampMillis(fromRaw ?: tillRaw)
                    ?: effectiveDate?.let { parseDateKeyToMillis(it) }
                    ?: System.currentTimeMillis()
                if (effectiveDate != null) {
                    val prev = earliestSessionDateByUser[user]
                    if (prev == null || effectiveDate < prev) {
                        earliestSessionDateByUser[user] = effectiveDate
                        earliestSessionMillisByUser[user] = effectiveMillis
                    } else if (effectiveDate == prev) {
                        val prevMs = earliestSessionMillisByUser[user] ?: Long.MAX_VALUE
                        if (effectiveMillis < prevMs) {
                            earliestSessionMillisByUser[user] = effectiveMillis
                        }
                    }
                    if (fromKey == todayKey || tillKey == todayKey || isActive) todayActiveUsers.add(user)
                    if (fromKey == yesterdayKey) yesterdayActiveUsers.add(user)
                    if (effectiveDate >= weekStartKey) weekActiveUsers.add(user)
                    if (effectiveDate >= monthStartKey) monthActiveUsers.add(user)
                }

                val nasPortId = rec["nas-port-id"].orEmpty().trim()
                val userIp = (rec["user-ip"] ?: rec["user-address"] ?: rec["framed-ip-address"]).orEmpty().trim()
                val callingMac = rec["calling-station-id"].orEmpty().trim()
                val activeHint = vlanLookup.activeUserHint[user]
                val resolvedVlanOrPort = resolveVlanOrPort(
                    nasPortId = nasPortId.ifBlank { activeHint?.first.orEmpty() },
                    userIp = userIp.ifBlank { activeHint?.second.orEmpty() },
                    callingStationId = callingMac.ifBlank { activeHint?.third.orEmpty() },
                    lookup = vlanLookup
                )
                if (resolvedVlanOrPort.isNotBlank() && resolvedVlanOrPort != "-") {
                    val existingVlan = userConnectionVlan[user].orEmpty()
                    val existingIsVlan = existingVlan.contains("vlan", ignoreCase = true)
                    val candidateIsVlan = resolvedVlanOrPort.contains("vlan", ignoreCase = true)
                    if (existingVlan.isBlank() || (!existingIsVlan && candidateIsVlan)) {
                        userConnectionVlan[user] = resolvedVlanOrPort
                    }
                }
            }
        }

        client.ensureConnected(credentials)
        val sessRes = client.executeStreaming(
            command = sessPrimary,
            params = sessProplistPrimary,
            timeoutMs = SESSIONS_BULK_TIMEOUT_MS
        ) { _, rec -> handleSessionRecord(rec) }
        val sessTrap = sessRes.getOrNull()?.trapMessage ?: sessRes.exceptionOrNull()?.message
        if (sessionsScanned == 0 && (sessRes.isFailure || sessRes.getOrNull()?.isSuccess != true)) {
            if (isNoSuchCommandPrefixError(sessTrap)) {
                client.ensureConnected(credentials)
                client.executeStreaming(
                    command = sessFallback,
                    params = sessProplistFallback,
                    timeoutMs = SESSIONS_BULK_TIMEOUT_MS
                ) { _, rec -> handleSessionRecord(rec) }
            } else {
                client.ensureConnected(credentials)
                client.executeStreaming(
                    command = sessPrimary,
                    params = emptyList(),
                    timeoutMs = SESSIONS_BULK_TIMEOUT_MS
                ) { _, rec -> handleSessionRecord(rec) }
            }
        }

        // 2. Stream User Manager payments as secondary fallback when sessions are unavailable
        var paymentsCount = 0
        var payTodayAmt = 0.0; var payTodayCnt = 0
        var payYestAmt = 0.0; var payYestCnt = 0
        var payWeekAmt = 0.0; var payWeekCnt = 0
        var payMonthAmt = 0.0; var payMonthCnt = 0
        var payAllAmt = 0.0; var payAllCnt = 0

        val recentIndividualPaymentLogs = ArrayList<PrintSalesLogEntity>()
        val historicalPaymentBuckets = LinkedHashMap<Pair<String, String>, PaymentBucket>()

        val payPrimary = if (isV7) "/user-manager/payment/print" else "/tool/user-manager/payment/print"
        val payFallback = if (isV7) "/tool/user-manager/payment/print" else "/user-manager/payment/print"

        fun handlePaymentRecord(rec: Map<String, String>) {
            paymentsCount++
            val user = (rec["user"] ?: rec["user-orig"]).orEmpty().trim()
            val dateRaw = rec["trans-start"] ?: rec["date"] ?: rec["trans-end"]
            val dateKey = parseMikrotikDateKey(dateRaw) ?: todayKey
            val parsedMillis = parseMikrotikTimestampMillis(dateRaw) ?: parseDateKeyToMillis(dateKey)
            val explicitProfile = rec["profile"].orEmpty().trim()
            val pkg = explicitProfile
                .ifBlank { cardByUser[user]?.packageName.orEmpty().trim() }
                .ifBlank { cardByUser[rec["user-orig"].orEmpty().trim()]?.packageName.orEmpty().trim() }
                .ifBlank { "يوزر منجر" }
            val parsedRouterPrice = rec["price"]?.trim()?.toDoubleOrNull()
                ?: rec["price"]?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull()
            val rawPrice = parsedRouterPrice?.takeIf { it > 0.0 } ?: resolveUserPrice(user, pkg)
            val cardMb = cardByUser[user]?.dataUsageMb ?: 0.0
            val cardBytes = (cardMb * 1024.0 * 1024.0).toLong()
            val accountLabel = rec["customer"].orEmpty().trim().ifBlank { serverAccount }
            val activeHint = vlanLookup.activeUserHint[user]
            val resolvedVlan = userConnectionVlan[user].orEmpty().ifBlank {
                resolveVlanOrPort(
                    nasPortId = activeHint?.first.orEmpty(),
                    userIp = activeHint?.second.orEmpty(),
                    callingStationId = activeHint?.third.orEmpty(),
                    lookup = vlanLookup
                )
            }

            payAllAmt += rawPrice; payAllCnt++
            if (dateKey == todayKey) { payTodayAmt += rawPrice; payTodayCnt++ }
            if (dateKey == yesterdayKey) { payYestAmt += rawPrice; payYestCnt++ }
            if (dateKey >= weekStartKey) { payWeekAmt += rawPrice; payWeekCnt++ }
            if (dateKey >= monthStartKey) { payMonthAmt += rawPrice; payMonthCnt++ }

            if (dateKey >= monthStartKey && recentIndividualPaymentLogs.size < 1500) {
                recentIndividualPaymentLogs.add(
                    PrintSalesLogEntity(
                        cardUsername = user.ifBlank { "دفعة يوزر منجر #$paymentsCount" },
                        packageName = pkg,
                        cardsCount = 1,
                        unitPrice = rawPrice,
                        totalAmount = rawPrice,
                        commissionTotal = 0.0,
                        serverAccount = accountLabel,
                        connectionVlan = resolvedVlan,
                        downloadLabel = formatBytesCompact(cardBytes),
                        downloadMb = cardMb,
                        printMethod = PrintMethod.PDF_A4_GRID,
                        operationType = SaleOperationType.DIRECT_SALE,
                        dateKey = dateKey,
                        createdAt = parsedMillis
                    )
                )
            } else {
                val bucket = historicalPaymentBuckets.getOrPut(dateKey to pkg) { PaymentBucket() }
                bucket.count++
                bucket.totalAmount += rawPrice
                bucket.totalDownloadBytes += cardBytes
                if (parsedMillis > bucket.latestMillis) bucket.latestMillis = parsedMillis
            }
        }

        if (earliestSessionDateByUser.isEmpty()) {
            val payProplist = listOf("=.proplist=user,user-orig,customer,profile,price,trans-start,trans-end,method")
            client.ensureConnected(credentials)
            val payRes = client.executeStreaming(
                command = payPrimary,
                params = payProplist,
                timeoutMs = SESSIONS_BULK_TIMEOUT_MS
            ) { _, rec -> handlePaymentRecord(rec) }
            val payTrap = payRes.getOrNull()?.trapMessage ?: payRes.exceptionOrNull()?.message
            if (paymentsCount == 0 && (payRes.isFailure || payRes.getOrNull()?.isSuccess != true)) {
                client.ensureConnected(credentials)
                if (isNoSuchCommandPrefixError(payTrap)) {
                    client.executeStreaming(
                        command = payFallback,
                        params = payProplist,
                        timeoutMs = SESSIONS_BULK_TIMEOUT_MS
                    ) { _, rec -> handlePaymentRecord(rec) }
                } else {
                    client.executeStreaming(
                        command = payPrimary,
                        params = emptyList(),
                        timeoutMs = SESSIONS_BULK_TIMEOUT_MS
                    ) { _, rec -> handlePaymentRecord(rec) }
                }
            }
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

        val useSessionsFirst = earliestSessionDateByUser.isNotEmpty()
        val usePayments = !useSessionsFirst && payAllCnt > 0
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

        if (useSessionsFirst) {
            // Preserve individual sold card rows for recent 30 days (up to 3,000 most recent cards)
            // with each card's exact username, price, package, date, and resolved VLAN (or port fallback).
            val recentCardLogs = ArrayList<PrintSalesLogEntity>(1024)
            val olderBuckets = LinkedHashMap<Triple<String, String, String>, SessionSalesBucket>()
            var allCardsCount = 0
            var allAmount = 0.0

            val sortedEntries = earliestSessionDateByUser.entries
                .sortedByDescending { earliestSessionMillisByUser[it.key] ?: parseDateKeyToMillis(it.value) }

            sortedEntries.forEach { (user, activationDate) ->
                val pkg = resolveUserPackage(user)
                val price = resolveUserPrice(user, pkg)
                val bytes = maxOf(
                    userSessionBytes[user] ?: 0L,
                    ((cardByUser[user]?.dataUsageMb ?: 0.0) * 1024.0 * 1024.0).toLong()
                )
                val vlanLabel = userConnectionVlan[user].orEmpty().ifBlank { "-" }
                val millis = earliestSessionMillisByUser[user] ?: parseDateKeyToMillis(activationDate)
                val acct = cardByUser[user]?.serverAccount?.ifBlank { serverAccount } ?: serverAccount

                allCardsCount++
                allAmount += price

                if (activationDate >= monthStartKey && recentCardLogs.size < 3000) {
                    recentCardLogs.add(
                        PrintSalesLogEntity(
                            cardUsername = user,
                            packageName = pkg,
                            cardsCount = 1,
                            unitPrice = price,
                            totalAmount = price,
                            commissionTotal = 0.0,
                            serverAccount = acct,
                            connectionVlan = vlanLabel,
                            downloadLabel = formatBytesCompact(bytes),
                            downloadMb = bytes / (1024.0 * 1024.0),
                            printMethod = PrintMethod.PDF_A4_GRID,
                            operationType = SaleOperationType.DIRECT_SALE,
                            dateKey = activationDate,
                            createdAt = millis
                        )
                    )
                } else {
                    val key = Triple(activationDate, pkg, vlanLabel)
                    val b = olderBuckets.getOrPut(key) { SessionSalesBucket() }
                    b.count++
                    b.totalAmount += price
                    b.totalBytes += bytes
                    if (millis > b.latestMillis) b.latestMillis = millis
                }
            }

            allTimeSales = PeriodSalesSummary(
                totalAmount = allAmount,
                totalCards = allCardsCount,
                operationsCount = allCardsCount
            )
            val groupedOlderLogs = olderBuckets.map { (key, b) ->
                val (dKey, pkg, vlan) = key
                val unit = if (b.count > 0) b.totalAmount / b.count else 0.0
                PrintSalesLogEntity(
                    cardUsername = "مبيعات يوزر منجر • $pkg",
                    packageName = pkg,
                    cardsCount = b.count,
                    unitPrice = unit,
                    totalAmount = b.totalAmount,
                    commissionTotal = 0.0,
                    serverAccount = serverAccount,
                    connectionVlan = vlan,
                    downloadLabel = formatBytesCompact(b.totalBytes),
                    downloadMb = b.totalBytes / (1024.0 * 1024.0),
                    printMethod = PrintMethod.PDF_A4_GRID,
                    operationType = SaleOperationType.DIRECT_SALE,
                    dateKey = dKey,
                    createdAt = b.latestMillis.takeIf { it > 0L } ?: parseDateKeyToMillis(dKey)
                )
            }
            syncedSalesLogs = recentCardLogs + groupedOlderLogs
        } else if (usePayments) {
            allTimeSales = PeriodSalesSummary(payAllAmt, payAllCnt, payAllCnt)
            val groupedOlderLogs = historicalPaymentBuckets.map { (key, bucket) ->
                val (dKey, pkg) = key
                val unit = if (bucket.count > 0) bucket.totalAmount / bucket.count else 0.0
                PrintSalesLogEntity(
                    cardUsername = "مدفوعات يوزر منجر • $pkg",
                    packageName = pkg,
                    cardsCount = bucket.count,
                    unitPrice = unit,
                    totalAmount = bucket.totalAmount,
                    commissionTotal = 0.0,
                    serverAccount = serverAccount,
                    connectionVlan = "-",
                    downloadLabel = formatBytesCompact(bucket.totalDownloadBytes),
                    downloadMb = bucket.totalDownloadBytes / (1024.0 * 1024.0),
                    printMethod = PrintMethod.PDF_A4_GRID,
                    operationType = SaleOperationType.DIRECT_SALE,
                    dateKey = dKey,
                    createdAt = bucket.latestMillis
                )
            }
            syncedSalesLogs = recentIndividualPaymentLogs + groupedOlderLogs
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
                    cardUsername = "كروت مستهلكة • $pkg",
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
                operationsCount = consumedCards.size
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

    /**
     * Parses MikroTik byte values in both raw integer form (`2418016256`) and unit-suffixed form
     * (`1200M`, `2306MiB`, `2G`, `500K`, `0B`). Returns 0L for blank/unlimited values.
     */
    fun parseRouterOsBytes(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val clean = raw.trim()
        if (clean.equals("unlimited", ignoreCase = true) || clean.equals("none", ignoreCase = true)) {
            return 0L
        }
        clean.toLongOrNull()?.let { return it.coerceAtLeast(0L) }
        val match = BYTE_SIZE_REGEX.matchEntire(clean) ?: return 0L
        val number = match.groupValues[1].toDoubleOrNull() ?: return 0L
        val unit = match.groupValues[2].lowercase(Locale.US)
        val multiplier = when (unit) {
            "k", "kb", "kib" -> 1024.0
            "m", "mb", "mib" -> 1024.0 * 1024.0
            "g", "gb", "gib" -> 1024.0 * 1024.0 * 1024.0
            "t", "tb", "tib" -> 1024.0 * 1024.0 * 1024.0 * 1024.0
            else -> 1.0
        }
        return (number * multiplier).toLong().coerceAtLeast(0L)
    }

    /**
     * Parses MikroTik RouterOS durations in BOTH shorthand (`1w2d6h24m7s`, `13h38m`)
     * AND colon-separated formats (`12:00:00`, `1d 06:24:07`, `00:30:00`).
     */
    fun parseRouterOsDurationSeconds(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val clean = raw.trim()
        if (clean.equals("unlimited", ignoreCase = true) || clean.equals("none", ignoreCase = true)) {
            return 0L
        }
        var total = 0L
        DURATION_TOKEN_REGEX.findAll(clean).forEach { match ->
            val value = match.groupValues[1].toLongOrNull() ?: 0L
            when (match.groupValues[2].lowercase(Locale.US)) {
                "w" -> total += value * 7L * 86400L
                "d" -> total += value * 86400L
                "h" -> total += value * 3600L
                "m" -> total += value * 60L
                "s" -> total += value
            }
        }
        COLON_TIME_REGEX.find(clean)?.let { cm ->
            val p1 = cm.groupValues[1].toLongOrNull() ?: 0L
            val p2 = cm.groupValues[2].toLongOrNull() ?: 0L
            val p3Raw = cm.groupValues.getOrNull(3).orEmpty()
            if (p3Raw.isNotBlank()) {
                val p3 = p3Raw.toLongOrNull() ?: 0L
                total += p1 * 3600L + p2 * 60L + p3
            } else {
                total += p1 * 60L + p2
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

    fun parseMikrotikTimestampMillis(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val clean = raw.trim()
        if (clean.equals("never", ignoreCase = true) || clean.equals("none", ignoreCase = true)) {
            return null
        }
        val dateKey = parseMikrotikDateKey(clean) ?: return null
        val timePart = clean.substringAfter(' ', missingDelimiterValue = "").trim()
        val pattern = if (timePart.length >= 8 && timePart[2] == ':' && timePart[5] == ':') {
            "yyyy-MM-dd HH:mm:ss"
        } else {
            "yyyy-MM-dd"
        }
        val input = if (pattern.contains("HH")) "$dateKey ${timePart.substring(0, 8)}" else dateKey
        return runCatching {
            SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }.parse(input)?.time
        }.getOrNull()
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

    /**
     * Fetches lightweight MikroTik RouterOS VLAN, Hotspot server, IP subnet, Bridge Host, and ARP tables
     * so every sold card resolves to its connected VLAN name/ID (with physical port ONLY as fallback).
     */
    private suspend fun fetchRouterVlanLookupBestEffort(
        client: MikrotikApiClient,
        credentials: MikrotikCredentials
    ): RouterVlanLookup {
        val vlanByName = HashMap<String, String>()
        val vlanById = HashMap<String, String>()
        val vlansByParent = HashMap<String, MutableList<String>>()
        val ifaceByHotspotServer = HashMap<String, String>()
        val vlanIdByMac = HashMap<String, String>()
        val ifaceByMac = HashMap<String, String>()
        val ifaceByIp = HashMap<String, String>()
        val activeUserHint = HashMap<String, Triple<String, String, String>>()
        val subnets = ArrayList<RouterCidrInterface>()

        // 1. /interface/vlan/print
        runCatching {
            client.ensureConnected(credentials)
            val res = client.execute(
                "/interface/vlan/print",
                listOf("=.proplist=name,vlan-id,interface"),
                timeoutMs = 8_000
            ).getOrNull()
            res?.takeIf { it.isSuccess }?.records?.forEach { r ->
                val name = r["name"].orEmpty().trim()
                val vid = r["vlan-id"].orEmpty().trim()
                val parent = r["interface"].orEmpty().trim().lowercase()
                if (name.isBlank() && vid.isBlank()) return@forEach
                val displayLabel = formatVlanDisplayLabel(name, vid)
                if (name.isNotBlank()) vlanByName[name.lowercase()] = displayLabel
                if (vid.isNotBlank()) vlanById[vid] = displayLabel
                if (parent.isNotBlank()) {
                    val list = vlansByParent.getOrPut(parent) { ArrayList() }
                    if (displayLabel !in list) list.add(displayLabel)
                }
            }
        }

        // 2. /ip/hotspot/print (maps hotspot server name -> interface)
        runCatching {
            client.ensureConnected(credentials)
            val res = client.execute(
                "/ip/hotspot/print",
                listOf("=.proplist=name,interface"),
                timeoutMs = 6_000
            ).getOrNull()
            res?.takeIf { it.isSuccess }?.records?.forEach { r ->
                val srv = r["name"].orEmpty().trim().lowercase()
                val iface = r["interface"].orEmpty().trim().lowercase()
                if (srv.isNotBlank() && iface.isNotBlank()) {
                    ifaceByHotspotServer[srv] = iface
                }
            }
        }

        // 3. /ip/address/print (maps IPv4 CIDR subnet -> interface)
        runCatching {
            client.ensureConnected(credentials)
            val res = client.execute(
                "/ip/address/print",
                listOf("=.proplist=address,interface"),
                timeoutMs = 6_000
            ).getOrNull()
            res?.takeIf { it.isSuccess }?.records?.forEach { r ->
                val addr = r["address"].orEmpty().trim()
                val iface = r["interface"].orEmpty().trim().lowercase()
                if (addr.isNotBlank() && iface.isNotBlank()) {
                    parseIpv4Cidr(addr, iface)?.let { subnets.add(it) }
                }
            }
            subnets.sortByDescending { it.prefixLength }
        }

        // 4. /interface/bridge/host/print (maps client MAC -> bridge VLAN ID & interface)
        runCatching {
            client.ensureConnected(credentials)
            client.executeStreaming(
                "/interface/bridge/host/print",
                listOf("=.proplist=mac-address,on-interface,vid"),
                timeoutMs = 6_000
            ) { _, r ->
                val mac = r["mac-address"].orEmpty().trim().lowercase()
                if (mac.isBlank()) return@executeStreaming
                val vid = r["vid"].orEmpty().trim()
                val onIface = r["on-interface"].orEmpty().trim().lowercase()
                if (vid.isNotBlank() && vid != "0" && vid != "1") {
                    vlanIdByMac[mac] = vid
                } else if (vid == "1" && vlanById.containsKey("1")) {
                    vlanIdByMac[mac] = vid
                }
                if (onIface.isNotBlank()) {
                    ifaceByMac[mac] = onIface
                }
            }
        }

        // 5. /ip/arp/print (maps client IP & MAC -> interface, e.g. vlan10)
        runCatching {
            client.ensureConnected(credentials)
            client.executeStreaming(
                "/ip/arp/print",
                listOf("=.proplist=address,mac-address,interface"),
                timeoutMs = 6_000
            ) { _, r ->
                val ip = r["address"].orEmpty().trim()
                val mac = r["mac-address"].orEmpty().trim().lowercase()
                val iface = r["interface"].orEmpty().trim().lowercase()
                if (iface.isBlank()) return@executeStreaming
                if (ip.isNotBlank()) ifaceByIp[ip] = iface
                if (mac.isNotBlank() && (!ifaceByMac.containsKey(mac) || vlanByName.containsKey(iface))) {
                    ifaceByMac[mac] = iface
                }
            }
        }

        // 6. /ip/hotspot/active/print (active user -> server/ip/mac hints)
        runCatching {
            client.ensureConnected(credentials)
            client.executeStreaming(
                "/ip/hotspot/active/print",
                listOf("=.proplist=user,server,address,mac-address"),
                timeoutMs = 6_000
            ) { _, r ->
                val u = r["user"].orEmpty().trim()
                if (u.isNotBlank()) {
                    activeUserHint[u] = Triple(
                        r["server"].orEmpty().trim(),
                        r["address"].orEmpty().trim(),
                        r["mac-address"].orEmpty().trim()
                    )
                }
            }
        }

        client.ensureConnected(credentials)
        return RouterVlanLookup(
            vlanByInterfaceName = vlanByName,
            vlanById = vlanById,
            vlansByParentInterface = vlansByParent,
            interfaceByHotspotServer = ifaceByHotspotServer,
            vlanIdByMac = vlanIdByMac,
            interfaceByMac = ifaceByMac,
            interfaceByIp = ifaceByIp,
            activeUserHint = activeUserHint,
            subnets = subnets
        )
    }

    private fun formatVlanDisplayLabel(name: String, vlanId: String): String {
        val cleanName = name.trim()
        val cleanVid = vlanId.trim()
        return when {
            cleanName.isNotBlank() && cleanVid.isNotBlank() -> {
                if (cleanName.contains(cleanVid, ignoreCase = true) && cleanName.contains("vlan", ignoreCase = true)) {
                    cleanName
                } else {
                    "$cleanName (VLAN $cleanVid)"
                }
            }
            cleanName.isNotBlank() -> cleanName
            cleanVid.isNotBlank() -> "VLAN $cleanVid"
            else -> "-"
        }
    }

    /**
     * Resolves the connected VLAN name/ID for a User Manager session/sale record.
     * Falls back to the physical port (e.g. ether7) ONLY when no VLAN exists for the record.
     */
    internal fun resolveVlanOrPort(
        nasPortId: String,
        userIp: String,
        callingStationId: String,
        lookup: RouterVlanLookup
    ): String {
        val cleanNas = nasPortId.trim()
        val cleanIp = userIp.trim()
        val cleanMac = callingStationId.trim().lowercase()

        fun interfaceToVlan(ifaceRaw: String?): String? {
            val raw = ifaceRaw?.trim().orEmpty()
            if (raw.isBlank()) return null
            val key = raw.lowercase()
            lookup.vlanByInterfaceName[key]?.let { return it }
            lookup.interfaceByHotspotServer[key]?.let { mapped ->
                lookup.vlanByInterfaceName[mapped]?.let { return it }
                if (mapped.contains("vlan", ignoreCase = true)) return mapped
            }
            if (key.contains("vlan")) return raw
            return null
        }

        // 1. Direct nas-port-id is a VLAN or Hotspot server bound to a VLAN
        interfaceToVlan(cleanNas)?.let { return it }

        // 2. Client MAC has an explicit Bridge VLAN ID (vid)
        if (cleanMac.isNotBlank()) {
            lookup.vlanIdByMac[cleanMac]?.let { vid ->
                return lookup.vlanById[vid] ?: "VLAN $vid"
            }
            // 3. Client MAC in ARP / Bridge maps to a VLAN interface
            interfaceToVlan(lookup.interfaceByMac[cleanMac])?.let { return it }
        }

        // 4. Client IP in ARP maps to a VLAN interface
        if (cleanIp.isNotBlank()) {
            interfaceToVlan(lookup.interfaceByIp[cleanIp])?.let { return it }
            // 5. Client IP matches a RouterOS /ip/address subnet bound to a VLAN interface
            val ipInt = parseIpv4ToInt(cleanIp)
            if (ipInt != null) {
                for (subnet in lookup.subnets) {
                    if ((ipInt and subnet.maskInt) == subnet.networkInt) {
                        interfaceToVlan(subnet.interfaceName)?.let { return it }
                    }
                }
            }
        }

        // 6. Physical port (or Hotspot server's parent port) hosts VLAN(s) in /interface/vlan/print
        val physicalPort = (lookup.interfaceByHotspotServer[cleanNas.lowercase()] ?: cleanNas.lowercase())
            .ifBlank { lookup.interfaceByIp[cleanIp].orEmpty() }
            .ifBlank { lookup.interfaceByMac[cleanMac].orEmpty() }
        if (physicalPort.isNotBlank()) {
            val portVlans = lookup.vlansByParentInterface[physicalPort]
            if (!portVlans.isNullOrEmpty()) {
                return if (portVlans.size == 1) portVlans.first() else portVlans.take(2).joinToString(" / ")
            }
        }

        // 7. Fallback ONLY when no VLAN exists for this record: physical port name
        return cleanNas
            .ifBlank { lookup.interfaceByIp[cleanIp].orEmpty() }
            .ifBlank { lookup.interfaceByMac[cleanMac].orEmpty() }
            .ifBlank { "-" }
    }

    internal fun parseIpv4Cidr(cidr: String, interfaceName: String): RouterCidrInterface? {
        val parts = cidr.trim().split('/')
        if (parts.size != 2) return null
        val ipInt = parseIpv4ToInt(parts[0]) ?: return null
        val prefix = parts[1].toIntOrNull()?.takeIf { it in 1..32 } ?: return null
        val maskInt = if (prefix == 32) -1 else (-1 shl (32 - prefix))
        return RouterCidrInterface(
            networkInt = ipInt and maskInt,
            maskInt = maskInt,
            prefixLength = prefix,
            interfaceName = interfaceName.trim().lowercase()
        )
    }

    private fun parseIpv4ToInt(ip: String): Int? {
        val octets = ip.trim().split('.')
        if (octets.size != 4) return null
        var acc = 0
        for (part in octets) {
            val v = part.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            acc = (acc shl 8) or v
        }
        return acc
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
