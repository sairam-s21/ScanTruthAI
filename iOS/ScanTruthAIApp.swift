import SwiftUI
import FirebaseCore
import FirebaseAppCheck
import FirebaseFirestore

class AppDelegate: NSObject, UIApplicationDelegate {
  func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    // 1. Configure App Check BEFORE FirebaseApp.configure() (required for Firebase AI Logic)
    #if DEBUG
    let providerFactory = AppCheckDebugProviderFactory()
    #else
    let providerFactory = AppAttestProviderFactory()
    #endif
    AppCheck.setAppCheckProviderFactory(providerFactory)

    // 2. Initialize Firebase
    FirebaseApp.configure()

    // 3. Enable offline Firestore persistence for store-aisle ingredient scans
    let settings = FirestoreSettings()
    settings.cacheSettings = PersistentCacheSettings()
    Firestore.firestore().settings = settings

    return true
  }
}

@main
struct ScanTruthAIApp: App {
  // Register app delegate for Firebase & App Check setup
  @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate

  var body: some Scene {
    WindowGroup {
      ContentView()
    }
  }
}