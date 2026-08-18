# Keep project model data classes and entities
-keep class com.example.zubflix.model.** { *; }
-keep class com.example.zubflix.database.** { *; }
-keep class com.example.zubflix.provider.sdk.models.** { *; }
-keep class com.example.zubflix.stremio.** { *; }
-keep class com.example.netflix.model.** { *; }
-keep class com.nuvio.app.features.plugins.** { *; }

# LibVLC
-keep class org.videolan.libvlc.** { *; }
-keepclassmembers class org.videolan.libvlc.** { *; }

# Rhino JS Engine
-keep class org.mozilla.javascript.** { *; }
-keepclassmembers class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn javax.swing.**
-dontwarn java.awt.**

# Retrofit, Gson, Moshi, OkHttp
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-dontwarn com.google.gson.**
-dontwarn com.squareup.moshi.**

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Glide
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep class com.bumptech.glide.GeneratedAppGlideModuleImpl { *; }

