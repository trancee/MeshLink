# JNI_OnLoad in libmeshlink_sodium.so registers SodiumJni's native methods by class and method name.
-keep class ch.trancee.meshlink.crypto.sodium.SodiumJni {
    native <methods>;
}
