package com.alkamel.cardprinter.data.local.model

enum class NetworkLoadType(val arabicLabel: String) {
    LOAD_USER_MANAGER("تحميل كروت وباقات يوزر منجر"),
    NO_LOAD("عدم التحميل الآن")
}

enum class CardStatus(val arabicLabel: String) {
    UNUSED("غير مستخدم"),
    USED("مستخدم"),
    EXPIRED("منتهي")
}

enum class PrintMethod(val arabicLabel: String) {
    PDF_A4_GRID("PDF شبكة كروت A4"),
    ANDROID_PRINT_A4("طباعة مباشرة A4"),
    PDF_REPORT_A4("تقرير مبيعات PDF A4")
}

enum class SaleOperationType(val arabicLabel: String) {
    PRINT_SALE("طباعة كروت A4"),
    DIRECT_SALE("بيع مباشر"),
    BATCH_EXPORT("تصدير دفعة PDF")
}
