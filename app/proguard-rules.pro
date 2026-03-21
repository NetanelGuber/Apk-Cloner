# Keep pxb axml/arsc library classes (used via reflection in binary parsing)
-keep class pxb.android.** { *; }

# Keep Bouncy Castle
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep dexlib2
-keep class org.jf.dexlib2.** { *; }
-dontwarn org.jf.dexlib2.**

# Keep apksig
-keep class com.android.apksig.** { *; }
-dontwarn com.android.apksig.**
