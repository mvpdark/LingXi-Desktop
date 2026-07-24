# 灵犀 Android ProGuard 规则

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }

# Koin
-keep class org.koin.** { *; }
-dontwarn org.koin.**

# Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# 项目模型类（序列化）
-keep class top.mvpdark.lingxi.data.model.** { *; }

# ONNX Runtime (native reflection)
-keep class ai.onnxruntime.** { *; }

# SAM service classes
-keep class top.mvpdark.lingxi.sam.** { *; }

# WebView @JavascriptInterface（全景查看器 JS 桥）
# release 包 R8 会重命名/移除未显式保留的方法，导致 JS 调用 AndroidBridge.* 失败；
# WebView 在运行时通过反射读取该注解，注解本身也必须保留。
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class top.mvpdark.lingxi.ui.components.PanoramaJsBridge { *; }
