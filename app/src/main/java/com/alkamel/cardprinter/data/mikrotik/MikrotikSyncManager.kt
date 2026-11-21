package com.alkamel.cardprinter.data.mikrotik

import com.alkamel.cardprinter.data.local.entity.CardEntity
import com.alkamel.cardprinter.data.local.entity.CardPackageEntity
import com.alkamel.cardprinter.data.local.model.CardStatus
import com.alkamel.cardprinter.data.local.model.NetworkLoadType
import com.alkamel.cardprinter.data.repository.CardPrinterRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Coordinates real RouterOS v6 / v7 API data fetching, User Manager & Hotspot synchronization,
 * and live card pushing directly into MikroTik and Room. Zero fake or placeholder data.
 */
object MikrotikSyncManager {

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

    suspend fun syncFromMikrotik(
        credentials: MikrotikCredentials,
        loadType: NetworkLoadType,
        repository: CardPrinterRepository,
        onProgress: (percent: Int, label: String) -> Unit
    ): Result<MikrotikSyncSummary> = withContext(Dispatchers.IO) {
        val client = MikrotikApiClient()
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
                if (fetchedPackages.isNotEmpty()) {
                    fetchedPackages.forEach { pkg -> repository.savePackage(pkg) }
                    syncedPackagesCount = fetchedPackages.size
                }

                onProgress(70, "جاري جلب الكروت والجلسات الفعلية...")
                val priceByPackage = fetchedPackages.associate { it.name to it.price }
                val fetchedCards = fetchCardsInternal(
                    client = client,
                    isV7 = isV7,
                    loadType = loadType,
                    serverAccount = credentials.username,
                    priceByPackage = priceByPackage
                )
                if (fetchedCards.isNotEmpty()) {
                    repository.syncCardsFromMikrotik(fetchedCards)
                    syncedCardsCount = fetchedCards.size
                }
            }

            onProgress(100, "تمت المزامنة الحقيقية مع المايكروتيك بنجاح")
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
        val activeHotspot = client.execute("/ip/hotspot/active/print").getOrNull()?.records.orEmpty()
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
            val umSessions = client.execute(umActiveCmd, listOf("?active=true")).getOrNull()?.records.orEmpty()
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
                    val price = r["price"]?.toDoubleOrNull()
                        ?: r["price"]?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull()
                        ?: 0.0
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
                        price = 0.0,
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
                val sessions = client.execute("/user-manager/session/print")
                    .getOrNull()?.takeIf { it.isSuccess }?.records.orEmpty()

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

                    result[uname] = CardEntity(
                        username = uname,
                        password = u["password"].orEmpty(),
                        packageName = pkgName,
                        price = priceByPackage[pkgName] ?: 0.0,
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

                    result[uname] = CardEntity(
                        username = uname,
                        password = u["password"].orEmpty(),
                        packageName = pkgName,
                        price = priceByPackage[pkgName] ?: 0.0,
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

                result[uname] = CardEntity(
                    username = uname,
                    password = u["password"].orEmpty(),
                    packageName = pkgName,
                    price = priceByPackage[pkgName] ?: 0.0,
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

    fun formatBytesCompact(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(java.util.Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
            else -> "${kb.toInt()} KB"
        }
    }
}
