package com.alkamel.cardprinter

import com.alkamel.cardprinter.data.local.entity.PrintSalesLogEntity
import com.alkamel.cardprinter.data.local.model.SaleOperationType
import com.alkamel.cardprinter.data.local.model.TopSalesAnalytics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopSalesAnalyticsTest {

    @Test
    fun testDailyTopPackagesGroupsByDayAndRanksDescending() {
        val logs = listOf(
            // Day 2026-11-26: pkg "200" -> 3 cards (600), pkg "500" -> 2 cards (1000), pkg "100" -> 3 cards (300)
            PrintSalesLogEntity(
                cardUsername = "u1",
                packageName = "200",
                cardsCount = 1,
                unitPrice = 200.0,
                totalAmount = 200.0,
                dateKey = "2026-11-26",
                operationType = SaleOperationType.DIRECT_SALE
            ),
            PrintSalesLogEntity(
                cardUsername = "u2",
                packageName = "200",
                cardsCount = 2,
                unitPrice = 200.0,
                totalAmount = 400.0,
                dateKey = "2026-11-26",
                operationType = SaleOperationType.DIRECT_SALE
            ),
            PrintSalesLogEntity(
                cardUsername = "u3",
                packageName = "500",
                cardsCount = 2,
                unitPrice = 500.0,
                totalAmount = 1000.0,
                dateKey = "2026-11-26",
                operationType = SaleOperationType.DIRECT_SALE
            ),
            PrintSalesLogEntity(
                cardUsername = "u4",
                packageName = "100",
                cardsCount = 3,
                unitPrice = 100.0,
                totalAmount = 300.0,
                dateKey = "2026-11-26",
                operationType = SaleOperationType.DIRECT_SALE
            ),
            // Day 2026-11-25: pkg "1000" -> 5 cards (5000), pkg "200" -> 1 card (200)
            PrintSalesLogEntity(
                cardUsername = "مبيعات يوزر منجر • 1000",
                packageName = "1000",
                cardsCount = 5,
                unitPrice = 1000.0,
                totalAmount = 5000.0,
                dateKey = "2026-11-25",
                operationType = SaleOperationType.DIRECT_SALE
            ),
            PrintSalesLogEntity(
                cardUsername = "u6",
                packageName = "200",
                cardsCount = 1,
                unitPrice = 200.0,
                totalAmount = 200.0,
                dateKey = "2026-11-25",
                operationType = SaleOperationType.DIRECT_SALE
            )
        )

        val groups = TopSalesAnalytics.buildDailyTopPackages(logs)
        assertEquals(2, groups.size)

        // Newest day first
        val day1 = groups[0]
        assertEquals("2026-11-26", day1.dateKey)
        assertEquals("2026/11/26", day1.displayDate)
        assertEquals(8, day1.totalCards)
        assertEquals(1900.0, day1.totalAmount, 0.01)
        assertEquals("200", day1.topPackageName)

        // Inside 2026-11-26: "200" (3 cards, 600) beats "100" (3 cards, 300) by amount tie-breaker, then "500" (2 cards)
        assertEquals(3, day1.packages.size)
        assertEquals("200", day1.packages[0].packageName)
        assertEquals(1, day1.packages[0].rank)
        assertEquals(3, day1.packages[0].cardsCount)
        assertEquals(600.0, day1.packages[0].totalAmount, 0.01)

        assertEquals("100", day1.packages[1].packageName)
        assertEquals(2, day1.packages[1].rank)
        assertEquals(3, day1.packages[1].cardsCount)
        assertEquals(300.0, day1.packages[1].totalAmount, 0.01)

        assertEquals("500", day1.packages[2].packageName)
        assertEquals(3, day1.packages[2].rank)
        assertEquals(2, day1.packages[2].cardsCount)
        assertEquals(1000.0, day1.packages[2].totalAmount, 0.01)

        val day2 = groups[1]
        assertEquals("2026-11-25", day2.dateKey)
        assertEquals(6, day2.totalCards)
        assertEquals("1000", day2.packages[0].packageName)
        assertEquals(5, day2.packages[0].cardsCount)
        assertEquals("200", day2.packages[1].packageName)
        assertEquals(1, day2.packages[1].cardsCount)
    }

    @Test
    fun testVlanDataUsageRankingOrdersProgressivelyFromHighestToLowest() {
        val logs = listOf(
            PrintSalesLogEntity(
                cardUsername = "c1",
                packageName = "200",
                cardsCount = 1,
                totalAmount = 200.0,
                connectionVlan = "vlan10 (VLAN 10)",
                downloadMb = 1500.0,
                dateKey = "2026-11-26",
                operationType = SaleOperationType.DIRECT_SALE
            ),
            PrintSalesLogEntity(
                cardUsername = "c2",
                packageName = "500",
                cardsCount = 1,
                totalAmount = 500.0,
                connectionVlan = "VLAN10 (VLAN 10)", // Case-insensitive merge with vlan10
                downloadMb = 2500.0,
                dateKey = "2026-11-26",
                operationType = SaleOperationType.DIRECT_SALE
            ),
            PrintSalesLogEntity(
                cardUsername = "c3",
                packageName = "1000",
                cardsCount = 4,
                totalAmount = 4000.0,
                connectionVlan = "vlan20 (VLAN 20)",
                downloadMb = 10240.0, // 10 GB -> must be #1
                dateKey = "2026-11-26",
                operationType = SaleOperationType.DIRECT_SALE
            ),
            PrintSalesLogEntity(
                cardUsername = "c4",
                packageName = "200",
                cardsCount = 1,
                totalAmount = 200.0,
                connectionVlan = "vlan30 (VLAN 30)",
                downloadMb = 512.0, // 0.5 GB -> must be #3
                dateKey = "2026-11-25",
                operationType = SaleOperationType.DIRECT_SALE
            ),
            PrintSalesLogEntity(
                cardUsername = "c5",
                packageName = "200",
                cardsCount = 2,
                totalAmount = 400.0,
                connectionVlan = "-", // Unassigned must not pollute VLAN rank #1
                downloadMb = 20000.0,
                dateKey = "2026-11-24",
                operationType = SaleOperationType.DIRECT_SALE
            )
        )

        val ranking = TopSalesAnalytics.buildVlanDataUsageRanking(logs)
        assertEquals(3, ranking.items.size)

        // Progressive descending order: vlan20 (10240 MB) > vlan10 (4000 MB) > vlan30 (512 MB)
        assertEquals("vlan20 (VLAN 20)", ranking.items[0].vlanName)
        assertEquals(1, ranking.items[0].rank)
        assertEquals(10240.0, ranking.items[0].totalDownloadMb, 0.01)
        assertEquals("10.00 GB", ranking.items[0].totalDownloadLabel)
        assertEquals(1.0f, ranking.items[0].shareOfMaxFraction, 0.001f)
        assertEquals(4, ranking.items[0].cardsCount)

        assertEquals("vlan10 (VLAN 10)", ranking.items[1].vlanName)
        assertEquals(2, ranking.items[1].rank)
        assertEquals(4000.0, ranking.items[1].totalDownloadMb, 0.01)
        assertEquals(2, ranking.items[1].cardsCount)
        assertTrue(ranking.items[1].shareOfMaxFraction < ranking.items[0].shareOfMaxFraction)

        assertEquals("vlan30 (VLAN 30)", ranking.items[2].vlanName)
        assertEquals(3, ranking.items[2].rank)
        assertEquals(512.0, ranking.items[2].totalDownloadMb, 0.01)
        assertEquals("512.0 MB", ranking.items[2].totalDownloadLabel)
        assertTrue(ranking.items[2].shareOfMaxFraction < ranking.items[1].shareOfMaxFraction)

        assertNotNull(ranking.unassignedSummary)
        assertEquals(2, ranking.unassignedSummary!!.cardsCount)
        assertEquals(20000.0, ranking.unassignedSummary!!.totalDownloadMb, 0.01)
    }

    @Test
    fun testEmptyInputAndDataFormatting() {
        assertTrue(TopSalesAnalytics.buildDailyTopPackages(emptyList()).isEmpty())
        val emptyVlan = TopSalesAnalytics.buildVlanDataUsageRanking(emptyList())
        assertTrue(emptyVlan.items.isEmpty())
        assertNull(emptyVlan.unassignedSummary)

        assertEquals("0 MB", TopSalesAnalytics.formatDataMb(0.0))
        assertEquals("512 KB", TopSalesAnalytics.formatDataMb(0.5))
        assertEquals("250.5 MB", TopSalesAnalytics.formatDataMb(250.5))
        assertEquals("2.50 GB", TopSalesAnalytics.formatDataMb(2560.0))
    }
}
