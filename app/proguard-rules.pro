# Project ProGuard rules

# ML Kit Document Scanner & Google Play Services
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_document_scanner.** { *; }
-keep class com.google.android.gms.internal.mlkit_document_scanner.** { *; }
-keep class com.google.android.gms.internal.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# PDFBox Android & Security Providers
-keep class com.tom_roush.pdfbox.pdmodel.** { *; }
-keep class com.tom_roush.pdfbox.multipdf.** { *; }
-keep class com.tom_roush.pdfbox.rendering.** { *; }
-keep class org.bouncycastle.jcajce.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn org.bouncycastle.**
-dontwarn com.gemalto.jp2.**

# Room Database & App Data Models
-keep class * extends androidx.room.RoomDatabase
-keep class ua.com.devinsider.pdfscanner.data.model.** { *; }
-keep class ua.com.devinsider.pdfscanner.data.local.** { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# WorkManager Workers
-keep class * extends androidx.work.ListenableWorker { *; }

# Dependency Injection (Inject constructors)
-keepclassmembers class * {
    @javax.inject.Inject <init>(...);
}

# Preserve Annotations, Signatures and Stacktrace details
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable