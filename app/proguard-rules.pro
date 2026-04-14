# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK directory.
# For more details, see
#   https://developer.android.com/build/shrink-code

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Photon parser classes
-keep class com.albionradar.parser.** { *; }
-keep class com.albionradar.network.** { *; }

# Keep entity classes
-keep class com.albionradar.android.Entity* { *; }
