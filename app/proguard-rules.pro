# ProGuard rules for ProVideoPlayer

# Keep ExoPlayer classes
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { *; }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** { **[] $VALUES; public *; }

# Keep GPUImage
-keep class jp.co.cyberagent.android.gpuimage.** { *; }

# Keep model classes
-keep class com.provideoplayer.model.** { *; }
