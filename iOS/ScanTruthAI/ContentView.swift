//
//  ContentView.swift
//  ScanTruthAI
//

import SwiftUI

enum AppTab: Hashable {
    case home
    case scan
    case myHealth
    case profile
}

struct ContentView: View {
    @State private var selectedTab: AppTab = .home
    /// Set by Home's "Analyse Medical Report"; My Health opens the report picker when it sees it.
    @State private var openReportPicker: Bool = false

    var body: some View {
        ZStack(alignment: .bottom) {
            Group {
                switch selectedTab {
                case .home:
                    HomeView(
                        onOpenScanner: { selectedTab = .scan },
                        onCompleteProfile: { selectedTab = .myHealth },
                        onAnalyseReport: {
                            openReportPicker = true
                            selectedTab = .myHealth
                        }
                    )
                case .scan:
                    ScanView(
                        onBack: { selectedTab = .home }
                    )
                case .myHealth:
                    MyHealthView(openReportPicker: $openReportPicker)
                case .profile:
                    ProfileView()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            // Custom bottom tab bar
            customTabBar
        }
        .ignoresSafeArea(.keyboard, edges: .bottom)
    }

    private var customTabBar: some View {
        HStack(spacing: 6) {
            tabButton(title: "Home", systemImage: "leaf", tab: .home)
            tabButton(title: "Scan", systemImage: "viewfinder", tab: .scan)
            tabButton(title: "My Health", systemImage: "heart.text.square", tab: .myHealth)
            tabButton(title: "Profile", systemImage: "person", tab: .profile)
        }
        .padding(.horizontal, 12)
        .padding(.top, 10)
        .padding(.bottom, 22)
        .background(Color.white.opacity(0.98))
        .overlay(alignment: .top) {
            Divider()
        }
    }

    /// The selected tab is a filled dark-green pill, like the Android bottom bar.
    private func tabButton(title: String, systemImage: String, tab: AppTab) -> some View {
        let isSelected = selectedTab == tab

        return Button(action: {
            selectedTab = tab
        }) {
            VStack(spacing: 4) {
                Image(systemName: systemImage)
                    .font(.system(size: 20, weight: .medium))

                Text(title)
                    .font(.system(size: 11, weight: .semibold))
            }
            .foregroundStyle(isSelected ? Color.white : Theme.textMuted)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 9)
            .background(isSelected ? Theme.primary : Color.clear)
            .clipShape(RoundedRectangle(cornerRadius: 22))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

#Preview {
    ContentView()
}
