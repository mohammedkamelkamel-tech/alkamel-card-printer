package com.alkamel.cardprinter.data.mikrotik

import com.alkamel.cardprinter.data.local.entity.CardEntity
import com.alkamel.cardprinter.data.local.entity.CardPackageEntity
import com.alkamel.cardprinter.data.local.entity.CardSessionEntity
import com.alkamel.cardprinter.data.local.model.CardStatus
import com.alkamel.cardprinter.data.local.model.NetworkLoadType
import com.alkamel.cardprinter.data.local.model.PeriodSalesSummary
import com.alkamel.cardprinter.data.local.util.DateTimeUtils
import com.alkamel.cardprinter.data.repository.CardPrinterRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Coordinates real RouterOS v6 / v7 API data fetching, live telemetry polling,
 * per-card User Manager & Hotspot session lookup, live reports aggregation,
 * and live card pushing directly into MikroTik and Room. Zero fake or placeholder data.
 */
object MikrotikSyncManager {

    private val MONTH_ABBR_TO_NUM = mapOf(
        "jan" to "01", "feb" to "02", "mar" to "03", "apr" to "04",
        "may" to "05", "jun" to "06", "jul" to "07", "aug" to "08",
        "sep" to "09", "oct" to "10", "nov" to "11", "dec" to "12"
    )

    private val CURRENCY_SUFFIX_REGEX = Regex("""(\d+(?:\.\d+)?)\s*(?:yr|ry|yer|sar|ريال)""", RegexOption.IGNORE_CASE)

    suspend fun testConnectionAndFetchStatus(credentials: MikrotikCredentials): MikrotikConnectionTestResult =
        withContext(Dispatchers.IO) {
            val client = MikrotikApiClient()
            try {
                val loginResult = client.connectAndLogin(credentials)
                if (loginResult.isFailure) {
                    return@withContext MikrotikConnectionTestResult(
                        isConnected = false,
                        errorMessage = loginResult.exceptionOrNull()?.localizedMessage
                            ?: "تعذر الاتصال بسيرفر المايكروتيك."
                    )
                }
                val status = fetchSystemStatusInternal(client)
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
     * Fast, lightweight live telemetry fetch used by periodic polling on Dashboard
     * (reads only /system/resource/print + active connections without reloading all cards).
     */
    suspend fun fetchLiveTelemetry(credentials: MikrotikCredentials): Result<MikrotikSystemStatus> =
        withContext(Dispatchers.IO) {
            val client = MikrotikApiClient(connectTimeoutMs = 4000, readTimeoutMs = 5000)
            try {
                val loginRes = client.connectAndLogin(credentials)
                if (loginRes.isFailure) {
                    return@withContext Result.failure(
                        loginRes.exceptionOrNull() ?: IllegalStateException("تعذر الاتصال بالمايكروتيك")
                    )
                }
                val status = fetchSystemStatusInternal(client)
                Result.success(status)
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                client.close()
            }
        }

    suspend fun syncFromMikrotik(
        credentials: MikrotikCredentials,
        loadType: NetworkLoadType,
        repository: CardPrinterRepository,
        onProgress: (percent: Int, label: String) -> Unit
    ): Result<MikrotikSyncSummary> = withContext(Dispatchers.IO) {
        val client = MikrotikApiClient(connectTimeoutMs = 6000, readTimeoutMs = 20000)
        try {
            onProgress(10, "جاري الاتصال بسيرفر المايكروتيك...")
            val loginRes = client.connectAndLogin(credentials)
            if (loginRes.isFailure) {
                return@withContext Result.failure(
                    loginRes.exceptionOrNull() ?: IllegalStateException("تعذر الاتصال بالمايكروتيك")
                )
            }

            onProgress(25, "جاري جلب حالة النظام من المايكروتيك...")
            val sysStatus = fetchSystemStatusInternal(client)
            val isV7 = sysStatus.routerOsVersion.trim().startsWith("7")

            var syncedPackagesCount = 0
            var syncedCardsCount = 0

            if (loadType != NetworkLoadType.NO_LOAD) {
                onProgress(45, "جاري جلب الباقات الفعلية من المايكروتيك...")
                val fetchedPackages = fetchPackagesInternal(client, isV7, loadType)
                val mergedPackages = if (fetchedPackages.isNotEmpty()) {
                    syncedPackagesCount = fetchedPackages.size
                    repository.mergePackagesFromMikrotik(fetchedPackages).getOrDefault(fetchedPackages)
                } else {
                    repository.getAllPackagesOnce()
                }

                onProgress(70, "جاري جلب الكروت والجلسات الفعلية...")
                val priceByPackage = mergedPackages.associate { it.name.lowercase() to it.price }
                val fetchedCards = fetchCardsInternal(
                    client = client,
                    isV7 = isV7,
                    loadType = loadType,
                    serverAccount = credentials.username,
                    priceByPackage = priceByPackage
                )
                if (fetchedCards.isNotEmpty()) {
                    onProgress(88, "جاري حفظ ${fetchedCards.size} كرت في قاعدة البيانات...")
                    repository.syncCardsFromMikrotik(fetchedCards)
                    syncedCardsCount = fetchedCards.size
                }
            }

            onProgress(100, "تم تحديث الكروت والجلسات بالكامل")
            Result.success(
                MikrotikSyncSummary(
                    systemStatus = sysStatus,
                    packagesSynced = syncedPackagesCount,
                    cardsSynced = syncedCardsCount,
                    activeSessionsCount = sysStatus.activeConnectionsCount
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            client.close()
        }
    }

    /**
     * Fetches detailed session history + any currently active session for a single card username
     * directly from MikroTik (RouterOS v6/v7 User Manager & Hotspot) and caches them in Room.
     */
    suspend fun fetchSessionsForUser(
        credentials: MikrotikCredentials,
        loadType: NetworkLoadType,
        username: String,
        repository: CardPrinterRepository
    ): Result<List<CardSessionEntity>> = withContext(Dispatchers.IO) {
        val cleanUser = username.trim()
        if (cleanUser.isBlank()) {
            return@withContext Result.success(emptyList())
        }
        val client = MikrotikApiClient(connectTimeoutMs = 5000, readTimeoutMs = 12000)
        try {
            val loginRes = client.connectAndLogin(credentials)
            if (loginRes.isFailure) {
                return@withContext Result.failure(
                    loginRes.exceptionOrNull() ?: IllegalStateException("تعذر الاتصال بسيرفر المايكروتيك.")
                )
            }
            val resourceRes = client.execute("/system/resource/print").getOrNull()
            val version = resourceRes?.records?.firstOrNull()?.get("version").orEmpty()
            val isV7 = version.trim().startsWith("7")

            val sessions = mutableListOf<CardSessionEntity>()
            val nowMillis = System.currentTimeMillis()

            // 1. User Manager sessions filtered server-side by ?user=<username>
            if (loadType == NetworkLoadType.LOAD_USER_MANAGER || loadType == NetworkLoadType.LOAD_ALL) {
                val primaryCmd = if (isV7) "/user-manager/session/print" else "/tool/user-manager/session/print"
                val fallbackCmd = if (isV7) "/tool/user-manager/session/print" else "/user-manager/session/print"
                val umRecords = client.execute(primaryCmd, listOf("?user=$cleanUser"))
                    .getOrNull()?.takeIf { it.isSuccess }?.records
                    ?: client.execute(fallbackCmd, listOf("?user=$cleanUser"))
                        .getOrNull()?.takeIf { it.isSuccess }?.records
                    ?: emptyList()

                umRecords.forEach { rec ->
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

                    sessions.add(
                        CardSessionEntity(
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
                    )
                }
            }

            // 2. Also inspect live Hotspot active session for this user (fast server-side filter)
            val activeHotspot = client.execute("/ip/hotspot/active/print", listOf("?user=$cleanUser"))
                .getOrNull()?.takeIf { it.isSuccess }?.records.orEmpty()

            activeHotspot.forEach { rec ->
                val ip = rec["address"].orEmpty().ifBlank { "-" }
                val mac = rec["mac-address"].orEmpty().ifBlank { "-" }
                val dl = rec["bytes-out"]?.toLongOrNull() ?: 0L
                val ul = rec["bytes-in"]?.toLongOrNull() ?: 0L
                val total = dl + ul
                val uptime = rec["uptime"].orEmpty().ifBlank { "0s" }

                val existingActiveIdx = sessions.indexOfFirst { it.isActive && (it.userIp == ip || it.macAddress == mac || it.userIp == "-") }
                if (existingActiveIdx >= 0) {
                    val existing = sessions[existingActiveIdx]
                    sessions[existingActiveIdx] = existing.copy(
                        userIp = if (existing.userIp == "-" && ip != "-") ip else existing.userIp,
                        macAddress = if (existing.macAddress == "-" && mac != "-") mac else existing.macAddress,
                        uptime = if (uptime != "0s") uptime else existing.uptime,
                        downloadBytes = maxOf(existing.downloadBytes, dl),
                        uploadBytes = maxOf(existing.uploadBytes, ul),
                        downloadLabel = formatBytesCompact(maxOf(existing.downloadBytes, dl)),
                        uploadLabel = formatBytesCompact(maxOf(existing.uploadBytes, ul)),
                        totalLabel = formatBytesCompact(maxOf(existing.downloadBytes + existing.uploadBytes, total))
                    )
                } else {
                    sessions.add(
                        0,
                        CardSessionEntity(
                            username = cleanUser,
                            mikrotikSessionId = rec[".id"].orEmpty(),
                            startTime = "جلسة هوتسبوت نشطة",
                            endTime = "نشط الآن",
                            uptime = uptime,
                            downloadBytes = dl,
                            uploadBytes = ul,
                            downloadLabel = formatBytesCompact(dl),
                            uploadLabel = formatBytesCompact(ul),
                            totalLabel = formatBytesCompact(total),
                            userIp = ip,
                            macAddress = mac,
                            isActive = true,
                            fetchedAt = nowMillis
                        )
                    )
                }
            }

            // Sort newest/active first
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
     * Aggregates real today download & period sales directly from MikroTik active sessions,
     * User Manager sessions, and User Manager payments (with safe timeout & graceful fallback).
     */
    suspend fun fetchLiveReportsMetrics(
        credentials: MikrotikCredentials,
        loadType: NetworkLoadType,
        cards: List<CardEntity>,
        packages: List<CardPackageEntity>
    ): Result<MikrotikLiveReportsMetrics> = withContext(Dispatchers.IO) {
        val client = MikrotikApiClient(connectTimeoutMs = 5000, readTimeoutMs = 18000)
        try {
            val loginRes = client.connectAndLogin(credentials)
            if (loginRes.isFailure) {
                return@withContext Result.failure(
                    loginRes.exceptionOrNull() ?: IllegalStateException("تعذر الاتصال بالمايكروتيك")
                )
            }

            val sysStatus = fetchSystemStatusInternal(client)
            val isV7 = sysStatus.routerOsVersion.trim().startsWith("7")

            val todayKey = DateTimeUtils.todayDateKey()
            val yesterdayKey = DateTimeUtils.yesterdayDateKey()
            val weekStartKey = DateTimeUtils.daysAgoDateKey(6)
            val monthStartKey = DateTimeUtils.monthStartDateKey()

            val cardByUser = cards.associateBy { it.username }
            val priceByPackage = packages.associate { it.name.lowercase() to it.price }

            fun resolveUserPrice(username: String): Double {
                val card = cardByUser[username] ?: return 0.0
                if (card.price > 0.0) return card.price
                val pkgPrice = priceByPackage[card.packageName.lowercase()] ?: 0.0
                if (pkgPrice > 0.0) return pkgPrice
                return inferPriceFromPackageName(card.packageName)
            }

            // 1. Live active connections download bytes (always fast)
            val activeLiveBytes = sysStatus.totalDownloadBytes

            // 2. Query MikroTik User Manager payments if any exist
            var paymentsCount = 0
            var payTodayAmt = 0.0; var payTodayCnt = 0
            var payYestAmt = 0.0; var payYestCnt = 0
            var payWeekAmt = 0.0; var payWeekCnt = 0
            var payMonthAmt = 0.0; var payMonthCnt = 0

            if (loadType == NetworkLoadType.LOAD_USER_MANAGER || loadType == NetworkLoadType.LOAD_ALL) {
                val payCmd = if (isV7) "/user-manager/payment/print" else "/tool/user-manager/payment/print"
                val payments = client.execute(
                    payCmd,
                    listOf("=.proplist=user,price,trans-start,date,method")
                ).getOrNull()?.takeIf { it.isSuccess }?.records.orEmpty()

                paymentsCount = payments.size
                payments.forEach { rec ->
                    val dateKey = parseMikrotikDateKey(rec["trans-start"] ?: rec["date"]) ?: return@forEach
                    val rawPrice = rec["price"]?.toDoubleOrNull()
                        ?: rec["price"]?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull()
                        ?: resolveUserPrice(rec["user"].orEmpty())
                    if (dateKey == todayKey) {
                        payTodayAmt += rawPrice; payTodayCnt++
                    }
                    if (dateKey == yesterdayKey) {
                        payYestAmt += rawPrice; payYestCnt++
                    }
                    if (dateKey >= weekStartKey) {
                        payWeekAmt += rawPrice; payWeekCnt++
                    }
                    if (dateKey >= monthStartKey) {
                        payMonthAmt += rawPrice; payMonthCnt++
                    }
                }
            }

            // 3. Query User Manager sessions (lightweight proplist) for today's download + period activity
            var sessionsScanned = 0
            var todaySessionBytes = 0L
            val earliestSessionDateByUser = HashMap<String, String>()
            val todayActiveUsers = HashSet<String>()
            val yesterdayActiveUsers = HashSet<String>()
            val weekActiveUsers = HashSet<String>()
            val monthActiveUsers = HashSet<String>()

            if (loadType == NetworkLoadType.LOAD_USER_MANAGER || loadType == NetworkLoadType.LOAD_ALL) {
                val sessCmd = if (isV7) "/user-manager/session/print" else "/tool/user-manager/session/print"
                val sessRecords = client.execute(
                    sessCmd,
                    listOf("=.proplist=user,from-time,till-time,started,ended,download,upload,active")
                ).getOrNull()?.takeIf { it.isSuccess }?.records.orEmpty()

                sessionsScanned = sessRecords.size
                sessRecords.forEach { rec ->
                    val user = rec["user"].orEmpty().trim()
                    val fromKey = parseMikrotikDateKey(rec["from-time"] ?: rec["started"])
                    val tillKey = parseMikrotikDateKey(rec["till-time"] ?: rec["ended"])
                    val isActive = rec["active"].equals("true", ignoreCase = true)
                    val dl = rec["download"]?.toLongOrNull() ?: 0L
                    val ul = rec["upload"]?.toLongOrNull() ?: 0L

                    if (isActive || fromKey == todayKey || tillKey == todayKey) {
                        todaySessionBytes += (dl + ul)
                    }

                    if (user.isNotBlank() && fromKey != null) {
                        val prev = earliestSessionDateByUser[user]
                        if (prev == null || fromKey < prev) {
                            earliestSessionDateByUser[user] = fromKey
                        }
                        if (fromKey == todayKey || tillKey == todayKey || isActive) todayActiveUsers.add(user)
                        if (fromKey == yesterdayKey) yesterdayActiveUsers.add(user)
                        if (fromKey >= weekStartKey) weekActiveUsers.add(user)
                        if (fromKey >= monthStartKey) monthActiveUsers.add(user)
                    }
                }
            }

            // First-activation date per card gives true newly-activated cards per period
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

            val usePayments = (payTodayCnt + payYestCnt + payWeekCnt + payMonthCnt) > 0
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

            val todayBytes = maxOf(todaySessionBytes, activeLiveBytes)
            val sourceNote = when {
                usePayments -> "بيانات حية من مدفوعات وجلسات المايكروتيك ($paymentsCount دفعة • $sessionsScanned جلسة)"
                sessionsScanned > 0 -> "بيانات حية من جلسات وتفعيل الكروت على المايكروتيك ($sessionsScanned جلسة)"
                activeLiveBytes > 0L -> "بيانات حية من المتصلين الآن (${sysStatus.activeConnectionsCount} متصل) والكروت المتزامنة"
                else -> "بيانات الكروت المتزامنة من المايكروتيك"
            }

            Result.success(
                MikrotikLiveReportsMetrics(
                    todayDownloadBytes = todayBytes,
                    activeLiveDownloadBytes = activeLiveBytes,
                    todaySales = todaySales,
                    yesterdaySales = yesterdaySales,
                    weeklySales = weeklySales,
                    monthlySales = monthlySales,
                    sessionsScannedCount = sessionsScanned,
                    paymentsScannedCount = paymentsCount,
                    sourceNote = sourceNote
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            client.close()
        }
    }

    suspend fun pushCardsToMikrotik(
        credentials: MikrotikCredentials,
        loadType: NetworkLoadType,
        cards: List<CardEntity>,
        repository: CardPrinterRepository,
        onProgress: (MikrotikPushCardProgress) -> Unit
    ): Result<List<CardEntity>> = withContext(Dispatchers.IO) {
        if (cards.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("لا توجد كروت للإضافة."))
        }
        val client = MikrotikApiClient()
        try {
            val loginRes = client.connectAndLogin(credentials)
            if (loginRes.isFailure) {
                return@withContext Result.failure(
                    loginRes.exceptionOrNull() ?: IllegalStateException("غير متصل بسيرفر المايكروتيك.")
                )
            }

            val sysStatus = fetchSystemStatusInternal(client)
            val isV7 = sysStatus.routerOsVersion.trim().startsWith("7")
            val total = cards.size
            val pushedCards = mutableListOf<CardEntity>()
            var addedCount = 0
            var failedCount = 0
            var lastRouterError: String? = null

            cards.forEachIndexed { idx, card ->
                val pushRes = pushSingleCardInternal(
                    client = client,
                    isV7 = isV7,
                    loadType = loadType,
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
                    IllegalStateException("فشل إضافة الكروت إلى المايكروتيك: $lastRouterError")
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
        loadType: NetworkLoadType,
        cards: List<CardEntity>
    ): Result<Int> = withContext(Dispatchers.IO) {
        val client = MikrotikApiClient()
        try {
            val loginRes = client.connectAndLogin(credentials)
            if (loginRes.isFailure) return@withContext Result.failure(loginRes.exceptionOrNull()!!)
            val sysStatus = fetchSystemStatusInternal(client)
            val isV7 = sysStatus.routerOsVersion.trim().startsWith("7")

            var removed = 0
            for (card in cards) {
                val ok = deleteSingleCardInternal(client, isV7, loadType, card)
                if (ok) removed++
            }
            Result.success(removed)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            client.close()
        }
    }

    private suspend fun fetchSystemStatusInternal(client: MikrotikApiClient): MikrotikSystemStatus {
        val resourceRes = client.execute("/system/resource/print").getOrNull()
        val resMap = resourceRes?.records?.firstOrNull().orEmpty()
        val cpu = resMap["cpu-load"]?.toIntOrNull()?.coerceIn(0, 100) ?: 0
        val uptime = resMap["uptime"].orEmpty().ifBlank { "--" }
        val version = resMap["version"].orEmpty()
        val board = resMap["board-name"].orEmpty()

        // Active Hotspot connections + live bytes
        val activeHotspot = client.execute(
            "/ip/hotspot/active/print",
            listOf("=.proplist=user,bytes-in,bytes-out")
        ).getOrNull()?.records.orEmpty()
        var activeCount = activeHotspot.size
        var totalBytes = activeHotspot.sumOf { rec ->
            (rec["bytes-in"]?.toLongOrNull() ?: 0L) + (rec["bytes-out"]?.toLongOrNull() ?: 0L)
        }

        // If Hotspot active list is empty, inspect User Manager active sessions
        if (activeCount == 0) {
            val umActiveCmd = if (version.trim().startsWith("7")) {
                "/user-manager/session/print"
            } else {
                "/tool/user-manager/session/print"
            }
            val umSessions = client.execute(
                umActiveCmd,
                listOf("?active=true", "=.proplist=download,upload")
            ).getOrNull()?.records.orEmpty()
            if (umSessions.isNotEmpty()) {
                activeCount = umSessions.size
                totalBytes = umSessions.sumOf { rec ->
                    (rec["download"]?.toLongOrNull() ?: 0L) + (rec["upload"]?.toLongOrNull() ?: 0L)
                }
            }
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

    private suspend fun fetchPackagesInternal(
        client: MikrotikApiClient,
        isV7: Boolean,
        loadType: NetworkLoadType
    ): List<CardPackageEntity> {
        val result = LinkedHashMap<String, CardPackageEntity>()

        if (loadType == NetworkLoadType.LOAD_USER_MANAGER || loadType == NetworkLoadType.LOAD_ALL) {
            val primaryCmd = if (isV7) "/user-manager/profile/print" else "/tool/user-manager/profile/print"
            val fallbackCmd = if (isV7) "/tool/user-manager/profile/print" else "/user-manager/profile/print"
            val records = client.execute(primaryCmd).getOrNull()?.takeIf { it.isSuccess }?.records
                ?: client.execute(fallbackCmd).getOrNull()?.takeIf { it.isSuccess }?.records
                ?: emptyList()

            records.forEach { r ->
                val name = r["name"].orEmpty().trim()
                if (name.isNotBlank()) {
                    val routerPrice = r["price"]?.toDoubleOrNull()
                        ?: r["price"]?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull()
                        ?: 0.0
                    val price = if (routerPrice > 0.0) routerPrice else inferPriceFromPackageName(name)
                    val validity = r["validity"].orEmpty()
                    result[name] = CardPackageEntity(
                        name = name,
                        price = price,
                        commission = 0.0,
                        validityLabel = validity,
                        dataLimitMb = 0.0
                    )
                }
            }
        }

        if (loadType == NetworkLoadType.LOAD_HOTSPOT || loadType == NetworkLoadType.LOAD_ALL) {
            val hsProfiles = client.execute("/ip/hotspot/user/profile/print")
                .getOrNull()?.takeIf { it.isSuccess }?.records.orEmpty()
            hsProfiles.forEach { r ->
                val name = r["name"].orEmpty().trim()
                if (name.isNotBlank() && !result.containsKey(name)) {
                    result[name] = CardPackageEntity(
                        name = name,
                        price = inferPriceFromPackageName(name),
                        commission = 0.0,
                        validityLabel = r["session-timeout"].orEmpty(),
                        dataLimitMb = 0.0
                    )
                }
            }
        }

        return result.values.toList()
    }

    private suspend fun fetchCardsInternal(
        client: MikrotikApiClient,
        isV7: Boolean,
        loadType: NetworkLoadType,
        serverAccount: String,
        priceByPackage: Map<String, Double>
    ): List<CardEntity> {
        val result = LinkedHashMap<String, CardEntity>()

        if (loadType == NetworkLoadType.LOAD_USER_MANAGER || loadType == NetworkLoadType.LOAD_ALL) {
            if (isV7) {
                val users = client.execute("/user-manager/user/print")
                    .getOrNull()?.takeIf { it.isSuccess }?.records.orEmpty()
                val userProfiles = client.execute("/user-manager/user-profile/print")
                    .getOrNull()?.takeIf { it.isSuccess }?.records.orEmpty()
                val sessions = client.execute(
                    "/user-manager/session/print",
                    listOf("=.proplist=user,download,upload,uptime")
                ).getOrNull()?.takeIf { it.isSuccess }?.records.orEmpty()

                val profileMap = userProfiles.groupBy { it["user"].orEmpty() }
                val sessionMap = sessions.groupBy { it["user"].orEmpty() }

                users.forEach { u ->
                    val uname = u["name"].orEmpty().trim()
                    if (uname.isBlank()) return@forEach
                    val upList = profileMap[uname].orEmpty()
                    val latestProfile = upList.lastOrNull()
                    val pkgName = latestProfile?.get("profile").orEmpty().ifBlank { u["group"].orEmpty() }
                    val userSessions = sessionMap[uname].orEmpty()
                    val totalBytes = userSessions.sumOf {
                        (it["download"]?.toLongOrNull() ?: 0L) + (it["upload"]?.toLongOrNull() ?: 0L)
                    }
                    val totalMb = totalBytes / (1024.0 * 1024.0)
                    val uptimeLabel = userSessions.lastOrNull()?.get("uptime").orEmpty().ifBlank { "0m" }
                    val stateStr = latestProfile?.get("state").orEmpty().lowercase()
                    val disabled = u["disabled"] == "true"

                    val status = when {
                        disabled || stateStr.contains("used") || stateStr.contains("expired") || stateStr.contains("end") -> CardStatus.EXPIRED
                        userSessions.isNotEmpty() || stateStr.contains("running") || stateStr.contains("active") -> CardStatus.USED
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
                        dataUsageLabel = formatBytesCompact(totalBytes),
                        dataUsageMb = totalMb
                    )
                }
            } else {
                // RouterOS v6 User Manager
                val users = client.execute("/tool/user-manager/user/print")
                    .getOrNull()?.takeIf { it.isSuccess }?.records.orEmpty()
                users.forEach { u ->
                    val uname = (u["username"] ?: u["name"]).orEmpty().trim()
                    if (uname.isBlank()) return@forEach
                    val pkgName = u["actual-profile"].orEmpty()
                    val dlBytes = (u["download-used"]?.toLongOrNull() ?: 0L) + (u["upload-used"]?.toLongOrNull() ?: 0L)
                    val uptimeUsed = u["uptime-used"].orEmpty().ifBlank { "0m" }
                    val disabled = u["disabled"] == "true"
                    val isActive = u["active"] == "true"
                    val hasUsage = dlBytes > 0L || (uptimeUsed != "0m" && uptimeUsed != "0s" && uptimeUsed.isNotBlank())

                    val status = when {
                        disabled -> CardStatus.EXPIRED
                        isActive || (hasUsage && pkgName.isNotBlank()) -> CardStatus.USED
                        hasUsage && pkgName.isBlank() -> CardStatus.EXPIRED
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
                        serverAccount = u["customer"].orEmpty().ifBlank { serverAccount },
                        mikrotikId = u[".id"],
                        status = status,
                        uptimeLabel = uptimeUsed,
                        dataUsageLabel = formatBytesCompact(dlBytes),
                        dataUsageMb = dlBytes / (1024.0 * 1024.0)
                    )
                }
            }
        }

        if (loadType == NetworkLoadType.LOAD_HOTSPOT || loadType == NetworkLoadType.LOAD_ALL) {
            val hsUsers = client.execute("/ip/hotspot/user/print")
                .getOrNull()?.takeIf { it.isSuccess }?.records.orEmpty()
            hsUsers.forEach { u ->
                val uname = u["name"].orEmpty().trim()
                if (uname.isBlank() || uname.equals("default-trial", ignoreCase = true)) return@forEach
                if (result.containsKey(uname)) return@forEach

                val pkgName = u["profile"].orEmpty()
                val bytesTotal = (u["bytes-in"]?.toLongOrNull() ?: 0L) + (u["bytes-out"]?.toLongOrNull() ?: 0L)
                val limitBytes = u["limit-bytes-total"]?.toLongOrNull() ?: 0L
                val uptime = u["uptime"].orEmpty().ifBlank { "0s" }
                val disabled = u["disabled"] == "true"
                val quotaExhausted = limitBytes > 0L && bytesTotal >= limitBytes

                val status = when {
                    disabled || quotaExhausted -> CardStatus.EXPIRED
                    bytesTotal > 0L || (uptime != "0s" && uptime != "0m") -> CardStatus.USED
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
                    uptimeLabel = uptime,
                    dataUsageLabel = formatBytesCompact(bytesTotal),
                    dataUsageMb = bytesTotal / (1024.0 * 1024.0)
                )
            }
        }

        return result.values.toList()
    }

    private suspend fun pushSingleCardInternal(
        client: MikrotikApiClient,
        isV7: Boolean,
        loadType: NetworkLoadType,
        customer: String,
        card: CardEntity
    ): Result<String?> {
        if (loadType == NetworkLoadType.LOAD_HOTSPOT) {
            val params = mutableListOf(
                "=name=${card.username}",
                "=password=${card.password}",
                "=comment=alkamel:${card.packageName}"
            )
            if (card.packageName.isNotBlank()) params.add("=profile=${card.packageName}")
            val res = client.execute("/ip/hotspot/user/add", params)
            val cmdRes = res.getOrElse { return Result.failure(it) }
            return if (cmdRes.isSuccess) Result.success(cmdRes.doneAttributes["ret"])
            else Result.failure(IllegalStateException(cmdRes.trapMessage ?: "فشل إضافة كرت هوتسبوت"))
        }

        return if (isV7) {
            val addParams = mutableListOf(
                "=name=${card.username}",
                "=password=${card.password}",
                "=shared-users=${if (card.bindFirstDevice) "1" else "unlimited"}",
                "=comment=alkamel:${card.packageName}"
            )
            val userAdd = client.execute("/user-manager/user/add", addParams).getOrElse { return Result.failure(it) }
            if (!userAdd.isSuccess) {
                return Result.failure(IllegalStateException(userAdd.trapMessage ?: "فشل إضافة المستخدم"))
            }
            if (card.packageName.isNotBlank()) {
                client.execute(
                    "/user-manager/user-profile/add",
                    listOf("=user=${card.username}", "=profile=${card.packageName}")
                )
            }
            Result.success(userAdd.doneAttributes["ret"])
        } else {
            // RouterOS v6 User Manager
            val addParams = mutableListOf(
                "=customer=${customer.ifBlank { "admin" }}",
                "=username=${card.username}",
                "=password=${card.password}",
                "=shared-users=${if (card.bindFirstDevice) "1" else "unlimited"}",
                "=comment=alkamel:${card.packageName}"
            )
            val userAdd = client.execute("/tool/user-manager/user/add", addParams).getOrElse { return Result.failure(it) }
            if (!userAdd.isSuccess) {
                return Result.failure(IllegalStateException(userAdd.trapMessage ?: "فشل إضافة الكرت في يوزر مانجر"))
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

    private suspend fun deleteSingleCardInternal(
        client: MikrotikApiClient,
        isV7: Boolean,
        loadType: NetworkLoadType,
        card: CardEntity
    ): Boolean {
        val basePath = when {
            loadType == NetworkLoadType.LOAD_HOTSPOT -> "/ip/hotspot/user"
            isV7 -> "/user-manager/user"
            else -> "/tool/user-manager/user"
        }
        val targetId = card.mikrotikId?.takeIf { it.isNotBlank() } ?: run {
            val queryKey = if (!isV7 && loadType != NetworkLoadType.LOAD_HOTSPOT) "?username=${card.username}" else "?name=${card.username}"
            client.execute("$basePath/print", listOf(queryKey))
                .getOrNull()?.records?.firstOrNull()?.get(".id")
        } ?: return false

        val removeRes = client.execute("$basePath/remove", listOf("=.id=$targetId")).getOrNull()
        return removeRes?.isSuccess == true
    }

    /**
     * Parses RouterOS v6 ("nov/21/2026 14:22:01") and RouterOS v7 ("2026-11-21 14:22:01")
     * timestamps into standard SQL date key "yyyy-MM-dd".
     */
    fun parseMikrotikDateKey(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val token = raw.trim().substringBefore(' ')
        // ISO format: 2026-11-21
        if (token.length >= 10 && token[4] == '-' && token[7] == '-') {
            return token.substring(0, 10)
        }
        // RouterOS v6 format: nov/21/2026
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
