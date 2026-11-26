package com.alkamel.cardprinter.data.local.model

import com.alkamel.cardprinter.data.local.entity.PrintSalesLogEntity
import com.alkamel.cardprinter.data.local.entity.effectiveOperationsCount
import com.alkamel.cardprinter.data.local.util.DateTimeUtils
import java.util.Locale

data class DailyPackageSalesItem(
    val rank: Int,
    val packageName: String,
    val cardsCount: Int,
    val totalAmount: Double,
    val operationsCount: Int,
    val shareOfDayFraction: Float,
    val shareOfDayPercent: Int
)

data class DailyTopPackagesGroup(
    val dateKey: String,
    val displayDate: String,
    val totalCards: Int,
    val totalAmount: Double,
    val totalOperations: Int,
    val topPackageName: String,
    val packages: List<DailyPackageSalesItem>
)

data class VlanDataUsageItem(
    val rank: Int,
    val vlanName: String,
    val totalDownloadMb: Double,
    val totalDownloadLabel: String,
    val cardsCount: Int,
    val sessionsOrOpsCount: Int,
    val totalSalesAmount: Double,
    val shareOfMaxFraction: Float,
    val shareOfTotalPercent: Int
)

data class UnassignedVlanUsageSummary(
    val totalDownloadMb: Double,
    val totalDownloadLabel: String,
    val cardsCount: Int,
    val sessionsOrOpsCount: Int
)

data class VlanDataUsageRankingResult(
    val items: List<VlanDataUsageItem> = emptyList(),
    val totalVlanDownloadMb: Double = 0.0,
    val totalVlanDownloadLabel: String = "0 MB",
    val totalVlanCards: Int = 0,
    val totalVlanSessionsOrOps: Int = 0,
    val unassignedSummary: UnassignedVlanUsageSummary? = null
)

/**
 * Pure analytics engine powering the User Manager «الأكثر مبيعات» section:
 * 1. Daily top-selling categories/packages grouped per day and ranked from highest to lowest.
 * 2. VLANs ranked progressively from highest data consumption down to lowest.
 */
object TopSalesAnalytics {

    private data class RawPkgAgg(
        val name: String,
        val cards: Int,
        val amount: Double,
        val ops: Int
    )

    private data class MutableVlanBucket(
        val displayName: String,
        var totalMb: Double = 0.0,
        var cards: Int = 0,
        var sessionsOrOps: Int = 0,
        var salesAmount: Double = 0.0
    )

    fun buildDailyTopPackages(logs: List<PrintSalesLogEntity>): List<DailyTopPackagesGroup> {
        if (logs.isEmpty()) return emptyList()

        val logsByDay = LinkedHashMap<String, MutableList<PrintSalesLogEntity>>()
        logs.forEach { log ->
            val key = log.dateKey.trim().ifBlank { DateTimeUtils.formatDateKey(log.createdAt) }
            logsByDay.getOrPut(key) { ArrayList() }.add(log)
        }

        return logsByDay.entries
            .sortedByDescending { it.key }
            .map { (dateKey, dayLogs) ->
                val dayTotalCards = dayLogs.sumOf { it.cardsCount.coerceAtLeast(0) }
                val dayTotalAmount = dayLogs.sumOf { it.totalAmount.coerceAtLeast(0.0) }
                val dayTotalOps = dayLogs.sumOf { it.effectiveOperationsCount().coerceAtLeast(0) }

                val groupedByPackage = LinkedHashMap<String, MutableList<PrintSalesLogEntity>>()
                val canonicalNameByKey = HashMap<String, String>()

                dayLogs.forEach { log ->
                    val rawPkg = log.packageName.trim().ifBlank { "يوزر منجر" }
                    val normKey = rawPkg.lowercase(Locale.US)
                    if (!canonicalNameByKey.containsKey(normKey)) {
                        canonicalNameByKey[normKey] = rawPkg
                    }
                    groupedByPackage.getOrPut(normKey) { ArrayList() }.add(log)
                }

                val sortedPackages = groupedByPackage.entries
                    .map { (normKey, pkgLogs) ->
                        RawPkgAgg(
                            name = canonicalNameByKey[normKey] ?: normKey,
                            cards = pkgLogs.sumOf { it.cardsCount.coerceAtLeast(0) },
                            amount = pkgLogs.sumOf { it.totalAmount.coerceAtLeast(0.0) },
                            ops = pkgLogs.sumOf { it.effectiveOperationsCount().coerceAtLeast(0) }
                        )
                    }
                    .sortedWith(
                        compareByDescending<RawPkgAgg> { it.cards }
                            .thenByDescending { it.amount }
                            .thenBy { it.name.lowercase(Locale.US) }
                    )

                val topCardsInDay = sortedPackages.firstOrNull()?.cards?.coerceAtLeast(1) ?: 1

                val rankedItems = sortedPackages.mapIndexed { index, agg ->
                    val shareFraction = if (topCardsInDay > 0) {
                        (agg.cards.toFloat() / topCardsInDay.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    val sharePercent = if (dayTotalCards > 0) {
                        ((agg.cards * 100.0) / dayTotalCards).toInt().coerceIn(0, 100)
                    } else {
                        0
                    }
                    DailyPackageSalesItem(
                        rank = index + 1,
                        packageName = agg.name,
                        cardsCount = agg.cards,
                        totalAmount = agg.amount,
                        operationsCount = agg.ops,
                        shareOfDayFraction = shareFraction,
                        shareOfDayPercent = sharePercent
                    )
                }

                DailyTopPackagesGroup(
                    dateKey = dateKey,
                    displayDate = dateKey.replace('-', '/'),
                    totalCards = dayTotalCards,
                    totalAmount = dayTotalAmount,
                    totalOperations = dayTotalOps,
                    topPackageName = rankedItems.firstOrNull()?.packageName ?: "-",
                    packages = rankedItems
                )
            }
    }

    fun buildVlanDataUsageRanking(logs: List<PrintSalesLogEntity>): VlanDataUsageRankingResult {
        if (logs.isEmpty()) return VlanDataUsageRankingResult()

        val buckets = LinkedHashMap<String, MutableVlanBucket>()
        var unassignedMb = 0.0
        var unassignedCards = 0
        var unassignedOps = 0

        logs.forEach { log ->
            val rawVlan = log.connectionVlan.trim()
            val mb = log.downloadMb.coerceAtLeast(0.0)
            val cards = log.cardsCount.coerceAtLeast(0)
            val ops = log.effectiveOperationsCount().coerceAtLeast(0)
            val amount = log.totalAmount.coerceAtLeast(0.0)

            if (rawVlan.isBlank() || rawVlan == "-") {
                unassignedMb += mb
                unassignedCards += cards
                unassignedOps += ops
            } else {
                val key = rawVlan.lowercase(Locale.US)
                val bucket = buckets.getOrPut(key) { MutableVlanBucket(displayName = rawVlan) }
                bucket.totalMb += mb
                bucket.cards += cards
                bucket.sessionsOrOps += ops
                bucket.salesAmount += amount
            }
        }

        val sortedBuckets = buckets.values.sortedWith(
            compareByDescending<MutableVlanBucket> { it.totalMb }
                .thenByDescending { it.cards }
                .thenByDescending { it.sessionsOrOps }
                .thenBy { it.displayName.lowercase(Locale.US) }
        )

        val totalVlanMb = sortedBuckets.sumOf { it.totalMb }
        val maxVlanMb = sortedBuckets.firstOrNull()?.totalMb ?: 0.0
        val maxCardsFallback = sortedBuckets.maxOfOrNull { it.cards }?.coerceAtLeast(1) ?: 1

        val rankedItems = sortedBuckets.mapIndexed { index, bucket ->
            val shareOfMax = when {
                maxVlanMb > 0.0 -> (bucket.totalMb / maxVlanMb).toFloat().coerceIn(0f, 1f)
                else -> (bucket.cards.toFloat() / maxCardsFallback.toFloat()).coerceIn(0f, 1f)
            }
            val shareOfTotalPercent = if (totalVlanMb > 0.0) {
                ((bucket.totalMb * 100.0) / totalVlanMb).toInt().coerceIn(0, 100)
            } else {
                0
            }
            VlanDataUsageItem(
                rank = index + 1,
                vlanName = bucket.displayName,
                totalDownloadMb = bucket.totalMb,
                totalDownloadLabel = formatDataMb(bucket.totalMb),
                cardsCount = bucket.cards,
                sessionsOrOpsCount = bucket.sessionsOrOps,
                totalSalesAmount = bucket.salesAmount,
                shareOfMaxFraction = shareOfMax,
                shareOfTotalPercent = shareOfTotalPercent
            )
        }

        val unassigned = if (unassignedCards > 0 || unassignedMb > 0.0) {
            UnassignedVlanUsageSummary(
                totalDownloadMb = unassignedMb,
                totalDownloadLabel = formatDataMb(unassignedMb),
                cardsCount = unassignedCards,
                sessionsOrOpsCount = unassignedOps
            )
        } else {
            null
        }

        return VlanDataUsageRankingResult(
            items = rankedItems,
            totalVlanDownloadMb = totalVlanMb,
            totalVlanDownloadLabel = formatDataMb(totalVlanMb),
            totalVlanCards = rankedItems.sumOf { it.cardsCount },
            totalVlanSessionsOrOps = rankedItems.sumOf { it.sessionsOrOpsCount },
            unassignedSummary = unassigned
        )
    }

    fun formatDataMb(mb: Double): String {
        if (mb <= 0.0) return "0 MB"
        val gb = mb / 1024.0
        return when {
            gb >= 1024.0 -> String.format(Locale.US, "%.2f TB", gb / 1024.0)
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            else -> "${(mb * 1024.0).toInt()} KB"
        }
    }
}
