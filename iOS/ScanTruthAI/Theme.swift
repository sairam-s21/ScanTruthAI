//
//  Theme.swift
//  ScanTruthAI
//

import SwiftUI

enum Theme {
    // MARK: - Colors matching Figma
    static let primary = Color(red: 0.08, green: 0.30, blue: 0.24)       // #144D3D Forest Green
    static let primaryDark = Color(red: 0.06, green: 0.22, blue: 0.17)   // #0F382B
    static let accentMint = Color(red: 0.91, green: 0.96, blue: 0.93)    // #E8F5EE Light Mint
    static let mintBorder = Color(red: 0.73, green: 0.88, blue: 0.80)    // #BADECB
    static let background = Color(red: 0.97, green: 0.98, blue: 0.97)    // #F7FAF8 Canvas
    static let cardBackground = Color.white
    
    // Status & Accent
    static let textPrimary = Color(red: 0.10, green: 0.16, blue: 0.13)   // Deep charcoal
    static let textSecondary = Color(red: 0.40, green: 0.48, blue: 0.44) // Muted slate
    static let textMuted = Color(red: 0.60, green: 0.68, blue: 0.64)     // Light muted
    
    static let aiPurple = Color(red: 0.45, green: 0.28, blue: 0.68)      // Sparkle purple
    static let aiPurpleBg = Color(red: 0.95, green: 0.92, blue: 0.98)    // Light purple
    
    static let coralAlert = Color(red: 0.92, green: 0.40, blue: 0.33)    // Alert tone
    static let amberWarning = Color(red: 0.95, green: 0.65, blue: 0.20)  // Warning tone
}

// MARK: - View Modifiers & Helpers

struct DashedCardBorder: ViewModifier {
    var cornerRadius: CGFloat = 16
    var color: Color = Theme.mintBorder
    
    func body(content: Content) -> some View {
        content
            .background(
                RoundedRectangle(cornerRadius: cornerRadius)
                    .strokeBorder(
                        color,
                        style: StrokeStyle(lineWidth: 1.2, dash: [5, 4])
                    )
            )
    }
}

struct PrimaryCardStyle: ViewModifier {
    var cornerRadius: CGFloat = 20
    
    func body(content: Content) -> some View {
        content
            .background(Theme.cardBackground)
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius)
                    .stroke(Color.black.opacity(0.04), lineWidth: 1)
            )
            .shadow(color: Color.black.opacity(0.03), radius: 8, x: 0, y: 2)
    }
}

extension View {
    func dashedCard(cornerRadius: CGFloat = 16, color: Color = Theme.mintBorder) -> some View {
        modifier(DashedCardBorder(cornerRadius: cornerRadius, color: color))
    }
    
    func primaryCard(cornerRadius: CGFloat = 20) -> some View {
        modifier(PrimaryCardStyle(cornerRadius: cornerRadius))
    }
}
