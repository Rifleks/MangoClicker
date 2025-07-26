# Основные правила
-optimizationpasses 5
-dontusemixedcaseclassnames
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses

# Kotlin
-keep class kotlin.Metadata { *; }

# Сохраняем сериализуемые классы
-keep @kotlinx.serialization.Serializable class * { *; }

# Ваше приложение
-keep class rifleks.clicker.** { *; }
-keepclassmembers class rifleks.clicker.** { *; }

# Material Components
-keep class com.google.android.material.** { *; }

-keepnames class rifleks.clicker.databinding.** {
    static <fields>;
}

-keep class * implements androidx.viewbinding.ViewBinding {
    *;
}

# Сохраняем enum-классы
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}