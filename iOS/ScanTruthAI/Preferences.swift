//
//  Preferences.swift
//  ScanTruthAI
//

import Foundation
import UserNotifications

/// Settings from the Profile cards (same options as the web app). Stored on this device only.
enum AppPreferences {
    static let criticalAllergenAlerts = "pref.criticalAllergenAlerts"
    static let intoleranceAlerts = "pref.intoleranceAlerts"
    static let weeklyDigest = "pref.weeklyDigest"
    static let strictSafetyPenalties = "pref.strictSafetyPenalties"

    /// Reads a setting with the same default the Profile toggles use.
    static func bool(_ key: String, default defaultValue: Bool) -> Bool {
        UserDefaults.standard.object(forKey: key) as? Bool ?? defaultValue
    }
}

/// The "Weekly Health Scan Digest" setting: a weekly reminder notification (Sundays, 9:00).
enum DigestNotifier {
    private static let identifier = "weekly-scan-digest"

    /// Turns the weekly reminder on or off. Returns false if notifications are not allowed.
    @MainActor
    static func setEnabled(_ enabled: Bool) async -> Bool {
        let center = UNUserNotificationCenter.current()

        guard enabled else {
            center.removePendingNotificationRequests(withIdentifiers: [identifier])
            return true
        }

        let granted = (try? await center.requestAuthorization(options: [.alert, .sound])) ?? false
        guard granted else { return false }

        let content = UNMutableNotificationContent()
        content.title = "Your weekly scan digest"
        content.body = "Open ScanTruthAI to review this week's scanned products against your health goals."

        var when = DateComponents()
        when.weekday = 1   // Sunday
        when.hour = 9

        let request = UNNotificationRequest(
            identifier: identifier,
            content: content,
            trigger: UNCalendarNotificationTrigger(dateMatching: when, repeats: true)
        )

        do {
            try await center.add(request)
            return true
        } catch {
            return false
        }
    }
}
