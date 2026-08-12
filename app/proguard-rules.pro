# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the Android SDK. You can edit the include path and order by
# changing the proguardFiles directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep AccessibilityService
-keep class com.umassisted.app.UMAccessibilityService { *; }
