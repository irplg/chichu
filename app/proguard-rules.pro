# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ViewModel 反射实例化（by viewModels() 默认工厂按类名 newInstance）
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keep class com.easytranslate.chichu.ui.QingYiViewModel { *; }

# Application / Activity（manifest 引用，AGP 通常自动处理，显式保留双保险）
-keep class com.easytranslate.chichu.QingYiApplication { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# kotlinx.serialization（R8 需保留 @Serializable 类的 serializer 生成成员）
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.easytranslate.chichu.data.** {
    *** Companion;
    <fields>;
}
-keepclasseswithmembers class com.easytranslate.chichu.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class com.easytranslate.chichu.**
-keepclassmembers class com.easytranslate.chichu.<1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
