# iOS host

SwiftUI host for the shared Compose app. The Xcode project is generated from `project.yml` with
[XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`), so no `.xcodeproj` is committed.

```
cd iosApp
xcodegen generate
xcodebuild -project iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

The pre-build phase runs `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode` (JDK 17 on `PATH` or
`JAVA_HOME`). Running on a device needs an Apple developer team and signing, which are not configured here.

`scripts/check-ios-plist.sh` fails if the generated Info.plist lacks `CADisableMinimumFrameDurationOnPhone`, which Compose Multiplatform requires at launch (a missing key aborts the app).
Built for the simulator; a successful launch is not yet confirmed here.
