# Room Database & Entities
-keep class com.alkamel.cardprinter.data.local.entity.** { *; }
-keep class com.alkamel.cardprinter.data.local.model.** { *; }
-keep class com.alkamel.cardprinter.data.local.converter.** { *; }
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Enums (Room TypeConverters & DataStore)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Kotlin Coroutines & Compose
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# ZXing QR Code & Biometric
-keep class com.google.zxing.** { *; }
-keep class androidx.biometric.** { *; }
