# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

-keep class com.yubico.yubikit.android.** { *; }
-keep class com.yubico.yubikit.core.** { *; }
-keep class com.yubico.yubikit.management.** { *; }
#-keep class com.yubico.yubikit.oath.** { *; }
-keep class com.yubico.yubikit.openpgp.** { *; }
-keep class com.yubico.yubikit.fido.** { *; }
-keep class com.yubico.yubikit.yubiotp.** { *; }
-keep class com.yubico.yubikit.support.** { *; }

-keep class com.yubico.yubikit.core.Version { *; }
-keep class com.yubico.yubikit.core.application.Feature** { *; }

-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

-keep class org.slf4j.** { *; }
-keep class ch.qos.logback.** { *; }
-keep class com.github.tony19.** { *; }

-keep interface org.slf4j.** { *; }
-keep interface ch.qos.logback.** { *; }
-keep class ch.qos.logback.classic.android.** { *; }
-keep class ch.qos.logback.core.Appender { *; }

-keep class * implements org.slf4j.spi.SLF4JServiceProvider { *; }

-keep class cz.adaptech.tesseract4android.** { *; }
-keep class com.googlecode.tesseract.android.** { *; }
-keepclassmembers class com.googlecode.tesseract.android.** {
    native <methods>;
}

-keep class acab.naiveha.subrosa.ui.**Fragment { *; }
-keep class acab.naiveha.subrosa.ui.**ViewModel { *; }
-keep class acab.naiveha.subrosa.ui.openpgp.OpenPgpKeyInfo { *; }
-keep class acab.naiveha.subrosa.ui.openpgp.OpenPgpSubkeyInfo { *; }
-keep class acab.naiveha.subrosa.ui.openpgp.OpenPgpCardInfo** { *; }
-keepclassmembers class * {
    public static final java.lang.String TAG;
}

-keepattributes *Annotation*, EnclosingMethod, Signature, InnerClasses, SourceFile, LineNumberTable

-dontwarn ch.qos.logback.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**

-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}

-assumenosideeffects class org.slf4j.Logger {
    public void trace(...);
    public void debug(...);
    public void info(...);
    public void warn(...);
}

-assumenosideeffects class com.yubico.yubikit.core.internal.Logger {
    public static void trace(...);
    public static void debug(...);
    public static void info(...);
    public static void warn(...);
}
