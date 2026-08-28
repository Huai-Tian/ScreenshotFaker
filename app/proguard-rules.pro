-keep class com.android.apksig.** { *; }
-dontwarn com.android.apksig.**
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-keep,allowobfuscation class fake.screenshot.services.privileged.** { *; }