-optimizationpasses 30
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-verbose
-printmapping mapping.txt
-printseeds seeds.txt
-printconfiguration configuration.txt
-printusage unused.txt

-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizations !code/allocation/enum
-optimizations !code/guardanalysis
-optimizations !field/*,!class/merging/*
-optimizations !code/simplification/string

-allowaccessmodification
-mergeinterfacesaggressively
-repackageclasses ''
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions,RuntimeVisibleAnnotations,EnclosingMethod,Deprecated,InnerClasses,Signature,SourceFile,LineNumberTable
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature

# Main activity and inner classes
-keep public class com.coara.pagedl.pagedl {
    public *;
    protected *;
    <init>(...);
    void onCreate(...);
    void onDestroy(...);
    void onRequestPermissionsResult(...);
    void onBackPressed(...);
}
-keep public class com.coara.pagedl.pagedl$CustomWebViewClient {
    public *;
    protected *;
    <init>(...);
    void onPageFinished(...);
    android.webkit.WebResourceResponse shouldInterceptRequest(...);
}
-keep public class com.coara.pagedl.pagedl$1 { *; }  # BroadcastReceiver inner class
-keepclassmembers class com.coara.pagedl.pagedl$CustomWebViewClient {
    <fields>;
    <methods>;
}

# DownloadService and its members
-keep public class com.coara.pagedl.pagedl$DownloadService {
    public *;
    protected *;
    <init>(...);
    int onStartCommand(...);
    void onCreate(...);
    void onDestroy(...);
    android.os.IBinder onBind(...);
}
-keepclassmembers class com.coara.pagedl.pagedl$DownloadService {
    <fields>;
    <methods>;
    void sendProgress(...);
    void sendComplete(...);
    void sendError(...);
    java.util.concurrent.Future currentTask;
    java.util.concurrent.ExecutorService executor;
}

# Static utility classes
-keep class com.coara.pagedl.Utils { *; }
-keepclassmembers class com.coara.pagedl.Utils {
    public static *;
    private static *;
}
-keep class com.coara.pagedl.MimeParser { *; }
-keepclassmembers class com.coara.pagedl.MimeParser {
    public static *;
    private static *;
}

# WebView and related classes
-keep class android.webkit.WebView { *; }
-keep class android.webkit.WebSettings { *; }
-keep class android.webkit.WebViewClient { *; }
-keepclassmembers class * extends android.webkit.WebViewClient {
    public *;
    protected *;
    void onPageFinished(...);
    android.webkit.WebResourceResponse shouldInterceptRequest(...);
}
-keep interface android.webkit.ValueCallback { *; }
-keepclassmembers class * implements android.webkit.ValueCallback {
    public void onReceiveValue(java.lang.Object);
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface public *;
}
-keep class android.webkit.CookieManager { *; }
-keep class android.webkit.WebResourceRequest { *; }
-keep class android.webkit.WebResourceResponse { *; }

# Notification and service related
-keep class androidx.core.app.NotificationCompat { *; }
-keep class androidx.core.app.NotificationCompat$* { *; }
-keep class android.app.Notification { *; }
-keep class android.app.NotificationChannel { *; }
-keep class android.app.NotificationManager { *; }
-keep class android.app.PendingIntent { *; }
-keep class android.app.Service { *; }
-keepclassmembers class * extends android.app.Service {
    public *;
    protected *;
    int onStartCommand(...);
    void onCreate(...);
    void onDestroy(...);
    android.os.IBinder onBind(...);
}
-keep class android.content.BroadcastReceiver { *; }
-keepclassmembers class * extends android.content.BroadcastReceiver {
    public void onReceive(...);
}
-keep class android.content.Intent { *; }
-keep class android.content.IntentFilter { *; }
-keep class android.content.Context { *; }

# AppCompat and UI components
-keep class androidx.appcompat.app.AppCompatActivity { *; }
-keepclassmembers class * extends androidx.appcompat.app.AppCompatActivity {
    public *;
    protected *;
}
-keep class android.widget.EditText { *; }
-keep class android.widget.Switch { *; }
-keep class android.widget.Button { *; }
-keep class android.widget.Toast { *; }
-keep class android.app.ProgressDialog { *; }
-keep class android.view.View { *; }
-keep class android.os.Bundle { *; }
-keep class android.content.pm.PackageManager { *; }
-keep class androidx.core.app.ActivityCompat { *; }
-keep class androidx.core.content.ContextCompat { *; }
-keep class android.Manifest { *; }

# Network and IO classes
-keep class java.net.HttpURLConnection { *; }
-keep class java.net.URL { *; }
-keep class java.net.URLConnection { *; }
-keep class java.net.InetAddress { *; }
-keep class java.net.Socket { *; }
-keep class java.net.InetSocketAddress { *; }
-keep class java.net.UnknownHostException { *; }
-keep class java.io.** { *; }
-keep class java.io.InputStream { *; }
-keep class java.io.OutputStream { *; }
-keep class java.io.File { *; }
-keep class java.io.FileInputStream { *; }
-keep class java.io.FileOutputStream { *; }
-keep class java.io.ByteArrayInputStream { *; }
-keep class java.io.ByteArrayOutputStream { *; }
-keep class java.io.IOException { *; }
-keep class java.nio.charset.StandardCharsets { *; }
-keep class java.nio.channels.ClosedByInterruptException { *; }
-keep class android.util.Base64 { *; }

# Concurrency and utilities
-keep class java.util.concurrent.atomic.AtomicBoolean { *; }
-keep class java.util.concurrent.ExecutorService { *; }
-keep class java.util.concurrent.Executors { *; }
-keep class java.util.concurrent.Future { *; }
-keep class java.util.concurrent.TimeUnit { *; }
-keep class java.util.concurrent.InterruptedException { *; }
-keep class android.os.Handler { *; }
-keep class android.os.Looper { *; }
-keep class android.os.IBinder { *; }
-keep class android.os.Binder { *; }
-keep class java.util.regex.Pattern { *; }
-keep class java.util.regex.Matcher { *; }
-keep class java.util.HashMap { *; }
-keep class java.util.HashSet { *; }
-keep class java.util.Map { *; }
-keep class java.util.Set { *; }
-keep class java.util.List { *; }
-keep class java.util.ArrayList { *; }
-keep class java.util.Locale { *; }
-keep class java.text.SimpleDateFormat { *; }
-keep class java.util.Date { *; }
-keep class android.os.Build { *; }
-keep class android.os.Environment { *; }
-keep class android.webkit.CookieManager { *; }

# Storage and permissions
-keep class android.Manifest$permission { *; }
-keep class android.content.pm.PackageManager { *; }

# JSON and other
-keep class org.json.JSONArray { *; }
-keep class org.json.JSONException { *; }

# Lifecycle and startup
-keep class androidx.startup.InitializationProvider { *; }
-keep class androidx.profileinstaller.ProfileInstallerInitializer { *; }
-keep class androidx.emoji2.text.EmojiCompatInitializer { *; }
-keep class androidx.lifecycle.ProcessLifecycleInitializer { *; }
-keep class androidx.lifecycle.Lifecycle { *; }

# General constructors and methods
-keepclassmembers class * {
    public <init>(...);
    void <init>(...);
}
-keepclassmembers class * {
    @androidx.annotation.Nullable <fields>;
    @androidx.annotation.NonNull <fields>;
}

# Obfuscation exclusions for reflection and serialization
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepclasseswithmembernames class * {
    void *(java.lang.String);
}
-keepclasseswithmembernames class * {
    void *(java.lang.String,java.lang.String);
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Warnings suppression
-dontwarn javax.**
-dontwarn com.google.**
-dontwarn android.**
-dontwarn java.nio.file.**
-dontwarn org.json.**
-dontwarn android.webkit.**
-dontwarn androidx.**
-dontwarn java.util.concurrent.**
-dontwarn java.net.**

# Renaming for security (obfuscation)
-renamesourcefileattribute SourceFile
-keepattributes Exceptions,InnerClasses,Signature,Deprecated,SourceFile,LineNumberTable,*Annotation*,EnclosingMethod

# Performance: Inline small methods and fields where possible
-optimizations !code/simplification/fieldpropagation
-optimizations !code/simplification/variable
-optimizations !code/simplification/branch
-optimizations !code/simplification/duplication

# Additional keep for potential reflection in WebView JS
-keepclassmembers class * {
    void evaluateJavascript(...);
    void saveWebArchive(...);
}

# Prevent optimizations on sensitive classes by using keep rules (which implicitly prevent optimizations)
-keep class com.coara.pagedl.pagedl { *; }
-keep class com.coara.pagedl.pagedl$DownloadService { *; }
-keep class com.coara.pagedl.Utils { *; }
-keep class com.coara.pagedl.MimeParser { *; }
