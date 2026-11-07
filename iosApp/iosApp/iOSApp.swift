import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    init() {
        IosLocalInference.shared.bridge = LocalAiRuntime()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
