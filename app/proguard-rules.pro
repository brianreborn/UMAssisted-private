# R8 / ProGuard rules for the release build.
#
# ML Kit ships its own consumer rules (kept native methods + proto field
# reflection), which AGP merges in automatically — we don't need to restate
# them here. What R8 can't infer is anything the *framework* instantiates or
# calls by name, so those are kept explicitly below.

# Instantiated by the system from AndroidManifest, and its methods are invoked
# as framework callbacks (onAccessibilityEvent/onServiceConnected/onInterrupt).
# MainActivity/UMAssistedApp are manifest-referenced and kept by AGP's generated
# rules, but the service is the one whose behavior is hardest to debug if R8
# gets it wrong, so keep it whole and explicit.
-keep class com.umassisted.app.UMAccessibilityService { *; }

# Referenced from the accessibility service config / manifest.
-keep class com.umassisted.app.MainActivity { *; }
-keep class com.umassisted.app.UMAssistedApp { *; }

# Keep the AccessibilityService callback surface generally — subclasses of
# framework classes whose methods are called reflectively by the system.
-keepclassmembers class * extends android.accessibilityservice.AccessibilityService {
    public *;
}

# Line numbers in crash reports stay useful; the mapping file is written to
# app/build/outputs/mapping/release/ for deobfuscation.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
