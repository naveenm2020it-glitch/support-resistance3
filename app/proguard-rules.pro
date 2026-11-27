# Keep domain models and NSE CSV/parser structures intact
-keep class com.nifty.sellerfootprint.model.** { *; }
-keep class com.nifty.sellerfootprint.data.** { *; }

# OkHttp & Kotlin Coroutines
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
