# JNI_OnLoad in libmeshlink_sodium.so registers the native methods of these classes by class and
# method name.
-keep class ch.trancee.meshlink.crypto.sodium.SodiumHashingJni {
    native <methods>;
}
-keep class ch.trancee.meshlink.crypto.sodium.SodiumClassicalJni {
    native <methods>;
}
-keep class ch.trancee.meshlink.crypto.sodium.SodiumKemsJni {
    native <methods>;
}
-keep class ch.trancee.meshlink.crypto.sodium.SodiumRandomJni {
    native <methods>;
}
