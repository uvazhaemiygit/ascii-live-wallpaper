package com.asciiwallpaper

import androidx.multidex.MultiDexApplication

/**
 * Installs the secondary DEX on Android 4.4. Compose is still present in the
 * APK for Android 5.0+, but the legacy settings screen never loads it.
 */
class AsciiApplication : MultiDexApplication()
