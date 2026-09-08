# EndoCam logo and Android icons

The final logo uses a black USB-C cable and a red center dot. The wordmark has black “Endo” and red “Cam”. No dot appears beside the wordmark.

## Files

- `logo.png`: full logo on white, 1254 × 1254 pixels.
- `symbol-transparent.png`: standalone symbol with true alpha transparency.
- `app-icon-1024.png`: square app icon on white.
- `store-icon-512.png`: 512 × 512 RGBA PNG for the store listing.
- `android/res/mipmap-*`: legacy icons at 48, 72, 96, 144, and 192 pixels.
- `android/res/mipmap-anydpi-v26`: adaptive launcher icon for Android 8 and later.
- `android/res/mipmap-anydpi-v33`: adaptive icon with a monochrome layer for Android 13 and later.
- `android/res/drawable*`: transparent foreground artwork and inset definition.
- `prompts.txt`: source prompts for the image generator.

The assets are raster images. The full logo has a white background. The standalone symbol has a transparent background.

## Android integration

The project already uses the new adaptive icon. Both `android:icon` and `android:roundIcon` refer to `@mipmap/ic_launcher`.

For another Android project, copy the contents of `android/res` into its resource directory. Set both manifest icon attributes to `@mipmap/ic_launcher`.

The foreground centers the red lens within the launcher mask. Its insets are 17% left, 31% right, 23.6% top, and 24.4% bottom. These values compensate for the USB-C tail. The artwork fits within the 66 dp circular safe area in a 108 dp adaptive layer.

Android applies the launcher mask for circular and rounded-square icons. The themed icon uses the foreground alpha mask and the system palette.

## Checks

Android `aapt2` compiled and linked the application resources. The export dimensions and foreground alpha channel passed checks.

The red lens center is within 0.01 dp of the adaptive layer center. All opaque artwork stays within a 32.13 dp radius; the safe radius is 33 dp. `circular-alignment-preview.png` compares the old and corrected layouts.

The full `:app:assembleDebug` build passed with JDK 21. The APK is at `app/build/outputs/apk/debug/app-debug.apk` in the project.
