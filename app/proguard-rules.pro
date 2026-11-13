# The app does not use reflection. Keep the manifest-declared components
# explicit so an optimized build cannot rename them away from the manifest.
-keep class com.asciiwallpaper.SettingsActivity { *; }
-keep class com.asciiwallpaper.AsciiWallpaperService { *; }
-keep class com.asciiwallpaper.AsciiWallpaperService$* { *; }
