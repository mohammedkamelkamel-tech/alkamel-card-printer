package com.alkamel.cardprinter.data.local

import com.alkamel.cardprinter.data.local.entity.CardTemplateEntity

object DatabaseSeeder {

    /**
     * Seeds only a single neutral A4 layout template if none exists.
     * No fake packages, no fake cards, no fake sales, and no fake networks are ever seeded.
     * All operational data is fetched directly from the connected MikroTik RouterOS server.
     */
    suspend fun seedIfEmpty(database: AlkamelDatabase) {
        val templateDao = database.cardTemplateDao()
        if (templateDao.getCount() == 0) {
            templateDao.insertTemplatesIgnore(
                listOf(
                    CardTemplateEntity(
                        name = "قالب الكامل القياسي (A4)",
                        packageName = "الكل",
                        cardWidth = 203f,
                        cardHeight = 61f,
                        columnsPerPage = 4,
                        rowsPerPage = 25,
                        showUsername = true,
                        showPassword = false,
                        showQrCode = false,
                        textColorHex = "#FFFFFF",
                        fontSizeSp = 16f,
                        isDefault = true
                    )
                )
            )
        }
    }
}
