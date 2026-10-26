# Velum brand icons

Source masters (owner-approved):

- `velum-icon-3d-light.jpg` — default launcher (black glass V on white)
- `velum-icon-3d-dark.jpg` — splash / about in dark theme (white glass V on black)
- `velum-v.svg` — flat V silhouette → Android `VectorDrawable` monochrome / notification

Generated outputs:

- Android adaptive + legacy mipmaps under `composeApp/src/androidMain/res/mipmap-*`
- Play Store 512: `branding/play-store/icon-512.png`
- Compose Multiplatform: `composeApp/src/commonMain/composeResources/drawable/`
- Desktop: `composeApp/src/desktopMain/resources/icons/`
- iOS: `iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/`

Launcher stays on the **light** master (Android home-screen icons are not theme-switched).
