# MeshLink Core - Consumer ProGuard Rules
# These rules are automatically included when consuming this library.

# Keep all public API classes
-keep public class io.meshlink.MeshLink { *; }
-keep public class io.meshlink.MeshLinkApi { *; }
-keep public class io.meshlink.config.** { *; }
-keep public class io.meshlink.model.** { *; }

# Keep all enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Don't warn about kotlinx.coroutines internals
-dontwarn kotlinx.coroutines.internal.**
