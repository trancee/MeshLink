# MeshLink consumer ProGuard / R8 rules
# These rules are embedded in the AAR and applied automatically when library consumers shrink code.

# Keep all concrete subclasses of MeshLinkService so R8 does not rename or strip them.
-keep public class * extends ch.trancee.meshlink.transport.MeshLinkService

# Keep AndroidBleTransport — its internal callback classes are referenced by the BT stack via
# reflection-like JNI downcalls and must not be renamed.
-keep class ch.trancee.meshlink.transport.AndroidBleTransport { *; }

# Keep the BleTransport interface and all its members so callers via LocalBinder are not broken.
-keep interface ch.trancee.meshlink.transport.BleTransport { *; }
