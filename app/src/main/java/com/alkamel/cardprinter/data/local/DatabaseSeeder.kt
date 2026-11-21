package com.alkamel.cardprinter.data.local

import com.alkamel.cardprinter.data.local.entity.CardPackageEntity
import com.alkamel.cardprinter.data.local.entity.CardTemplateEntity
import com.alkamel.cardprinter.data.local.entity.SavedNetworkEntity
import com.alkamel.cardprinter.data.local.model.NetworkLoadType

object DatabaseSeeder {

    suspend fun seedIfEmpty(database: AlkamelDatabase) {
        val packageDao = database.cardPackageDao()
        if (packageDao.getCount() == 0) {
            packageDao.insertPackagesIgnore(defaultPackages())
        }

        val templateDao = database.cardTemplateDao()
        if (templateDao.getCount() == 0) {
            templateDao.insertTemplatesIgnore(defaultTemplates())
        }

        val networkDao = database.savedNetworkDao()
        if (networkDao.getCount() == 0) {
            networkDao.upsertNetwork(
                SavedNetworkEntity(
                    networkName = "شبكة الكامل نت اللاسلكية",
                    hostAddress = "192.168.88.1",
                    port = 8728,
                    username = "admin",
                    password = "",
                    loadType = NetworkLoadType.LOAD_USER_MANAGER,
                    isDefault = true
                )
            )
        }
    }

    private fun defaultPackages(): List<CardPackageEntity> = listOf(
        CardPackageEntity(name = "50 ريال", price = 50.0, commission = 5.0, pointOfSale = "ether3", validityLabel = "1d", dataLimitMb = 250.0),
        CardPackageEntity(name = "80", price = 100.0, commission = 10.0, pointOfSale = "ether3", validityLabel = "3d", dataLimitMb = 500.0),
        CardPackageEntity(name = "99", price = 100.0, commission = 10.0, pointOfSale = "ether3", validityLabel = "3d", dataLimitMb = 600.0),
        CardPackageEntity(name = "100", price = 100.0, commission = 10.0, pointOfSale = "ether3", validityLabel = "5d", dataLimitMb = 700.0),
        CardPackageEntity(name = "100-Plus", price = 100.0, commission = 10.0, pointOfSale = "ether6", validityLabel = "5d", dataLimitMb = 800.0),
        CardPackageEntity(name = "150", price = 150.0, commission = 15.0, pointOfSale = "ether3", validityLabel = "7d", dataLimitMb = 1024.0),
        CardPackageEntity(name = "200", price = 200.0, commission = 20.0, pointOfSale = "ether3", validityLabel = "10d", dataLimitMb = 1500.0),
        CardPackageEntity(name = "200-Plus", price = 200.0, commission = 20.0, pointOfSale = "ether6", validityLabel = "10d", dataLimitMb = 1800.0),
        CardPackageEntity(name = "245", price = 250.0, commission = 25.0, pointOfSale = "ether3", validityLabel = "12d", dataLimitMb = 2000.0),
        CardPackageEntity(name = "250", price = 250.0, commission = 25.0, pointOfSale = "ether3", validityLabel = "15d", dataLimitMb = 2200.0),
        CardPackageEntity(name = "250-Plus", price = 250.0, commission = 25.0, pointOfSale = "ether6", validityLabel = "15d", dataLimitMb = 2500.0),
        CardPackageEntity(name = "OPEN 250", price = 250.0, commission = 25.0, pointOfSale = "ether3", validityLabel = "7d", dataLimitMb = 4096.0),
        CardPackageEntity(name = "300", price = 300.0, commission = 30.0, pointOfSale = "ether3", validityLabel = "15d", dataLimitMb = 3072.0),
        CardPackageEntity(name = "400", price = 400.0, commission = 40.0, pointOfSale = "ether6", validityLabel = "20d", dataLimitMb = 4096.0),
        CardPackageEntity(name = "500", price = 500.0, commission = 50.0, pointOfSale = "ether3", validityLabel = "30d", dataLimitMb = 6144.0),
        CardPackageEntity(name = "500-Plus", price = 500.0, commission = 50.0, pointOfSale = "ether6", validityLabel = "30d", dataLimitMb = 7168.0),
        CardPackageEntity(name = "1000", price = 1000.0, commission = 100.0, pointOfSale = "ether3", validityLabel = "30d", dataLimitMb = 14336.0),
        CardPackageEntity(name = "1500", price = 1500.0, commission = 150.0, pointOfSale = "ether6", validityLabel = "30d", dataLimitMb = 20480.0),
        CardPackageEntity(name = "2000", price = 2000.0, commission = 200.0, pointOfSale = "ether3", validityLabel = "30d", dataLimitMb = 30720.0),
        CardPackageEntity(name = "2500", price = 2500.0, commission = 250.0, pointOfSale = "ether6", validityLabel = "30d", dataLimitMb = 40960.0),
        CardPackageEntity(name = "3000", price = 3000.0, commission = 300.0, pointOfSale = "ether3", validityLabel = "30d", dataLimitMb = 51200.0),
        CardPackageEntity(name = "MY", price = 100.0, commission = 0.0, pointOfSale = "ether3", validityLabel = "30d", dataLimitMb = 10240.0)
    )

    private fun defaultTemplates(): List<CardTemplateEntity> = listOf(
        CardTemplateEntity(
            name = "قالب الكامل القياسي (100 كرت A4)",
            packageName = "80",
            cardWidth = 203f,
            cardHeight = 61f,
            columnsPerPage = 4,
            rowsPerPage = 25,
            showUsername = true,
            showPassword = false,
            showPointOfSale = false,
            showQrCode = false,
            textColorHex = "#FFFFFF",
            fontSizeSp = 16f,
            isDefault = true
        ),
        CardTemplateEntity(
            name = "قالب فئة 100 ريال (60 كرت A4)",
            packageName = "100",
            cardWidth = 203f,
            cardHeight = 76f,
            columnsPerPage = 4,
            rowsPerPage = 15,
            showUsername = true,
            showPassword = true,
            showPointOfSale = true,
            showQrCode = true,
            textColorHex = "#FFFFFF",
            fontSizeSp = 15f,
            isDefault = false
        ),
        CardTemplateEntity(
            name = "قالب فئة 250 و 500 ريال",
            packageName = "250",
            cardWidth = 203f,
            cardHeight = 82f,
            columnsPerPage = 3,
            rowsPerPage = 12,
            showUsername = true,
            showPassword = true,
            showPointOfSale = true,
            showQrCode = true,
            textColorHex = "#FFFFFF",
            fontSizeSp = 16f,
            isDefault = false
        )
    )
}
