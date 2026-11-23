import SwiftUI

@main
struct AurnsApp: App {
    @StateObject private var app = AppCoordinator()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            MainTabView()
                .environmentObject(app)
                .environment(\.aurnsPalette, app.isLightTheme ? .light : .dark)
                .preferredColorScheme(app.isLightTheme ? .light : .dark)
                .onOpenURL { url in
                    app.handleIncomingURL(url)
                }
                .onChange(of: scenePhase) { newPhase in
                    if newPhase == .active {
                        app.friends.reconnectIfNeeded()
                        Task { await app.library.scanLibrary() }
                    }
                }
        }
    }
}
