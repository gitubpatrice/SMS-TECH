# SMS Tech — R8/ProGuard rules
# Tightened per audit F19 (avoid keep-all-classes shotgun rules) + F20 (strip Timber payloads).

-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ----- Kotlin -----------------------------------------------------------------------------------
-dontwarn kotlin.**
-keepclassmembers class kotlin.Metadata { *; }

# ----- kotlinx.serialization --------------------------------------------------------------------
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers @kotlinx.serialization.Serializable class * {
    static **$* INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.filestech.sms.**$$serializer { *; }
-keepclassmembers class com.filestech.sms.** {
    *** Companion;
}
-keepclasseswithmembers class com.filestech.sms.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ----- Coroutines -------------------------------------------------------------------------------
-dontwarn kotlinx.coroutines.flow.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ----- Hilt / Dagger ----------------------------------------------------------------------------
# Only keep what the framework needs at runtime (entry-point classes + @HiltViewModel ctors).
-keep class dagger.hilt.android.internal.managers.** { *; }
-keep class * extends dagger.hilt.android.HiltAndroidApp
-keepclasseswithmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}
-keep class androidx.hilt.work.HiltWorkerFactory { *; }
-keepclasseswithmembers class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ----- Androidx ViewModel ----------------------------------------------------------------------
# Names only — implementation members can be obfuscated. (Was `-keep class *` shotgun.)
-keepnames class * extends androidx.lifecycle.ViewModel

# ----- Room ------------------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.paging.**

# ----- Compose ---------------------------------------------------------------------------------
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**

# ----- Timber ----------------------------------------------------------------------------------
-dontwarn org.jetbrains.annotations.**

# ----- SQLCipher -------------------------------------------------------------------------------
# Native methods reflected through JNI need to stay reachable.
-keepclasseswithmembernames class net.zetetic.database.sqlcipher.** {
    native <methods>;
}
-keep,includedescriptorclasses class net.zetetic.database.sqlcipher.SQLiteConnection { *; }
-keep,includedescriptorclasses class net.zetetic.database.sqlcipher.SQLiteDatabase { *; }
-keep class net.zetetic.database.sqlcipher.SQLiteDatabaseHook { *; }

# ----- DataStore (protobuf-free path) ----------------------------------------------------------
-keep class androidx.datastore.preferences.protobuf.** { *; }

# ----- SMS / Telephony provider columns referenced via reflection -----------------------------
-keepclassmembers class android.provider.Telephony$** { *; }

# ----- App-level reflective entry points (manifest-declared receivers / services / Activity) --
-keepnames class com.filestech.sms.MainApplication
-keepnames class com.filestech.sms.MainActivity
-keepclasseswithmembers class com.filestech.sms.system.receiver.* {
    <init>();
    void onReceive(android.content.Context, android.content.Intent);
}
-keepclasseswithmembers class com.filestech.sms.system.service.* {
    <init>();
}

# ----- Strip Timber in release (audit F20) -----------------------------------------------------
# Release builds set BuildConfig.LOG_ENABLED=false and plant NoOpReleaseTree (which drops INFO and
# below). On top of that, we make every Timber log call a no-op at the bytecode level so that no
# argument expression is ever evaluated. This stops accidental PII / PIN material from leaking
# into logcat via the toString of a captured local even if a developer slipped a Timber.w(secret).
-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}
-assumenosideeffects class timber.log.Timber$Tree {
    public *** v(...);
    public *** d(...);
    public *** i(...);
    public *** w(...);
    public *** e(...);
    public *** wtf(...);
}
