//
//  Components.swift
//  ScanTruthAI
//
//  Shared building blocks, laid out like the Android app so both apps look the same.
//

import SwiftUI

// MARK: - Brand header (shield, app name, small-caps subtitle)

struct BrandHeader<Trailing: View>: View {
    let subtitle: String
    let trailing: Trailing

    init(subtitle: String, @ViewBuilder trailing: () -> Trailing) {
        self.subtitle = subtitle
        self.trailing = trailing()
    }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "checkmark.shield.fill")
                .font(.system(size: 20))
                .foregroundStyle(Color.white)
                .frame(width: 44, height: 44)
                .background(Theme.primary)
                .clipShape(RoundedRectangle(cornerRadius: 12))

            VStack(alignment: .leading, spacing: 3) {
                Text("ScanTruthAI")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(Theme.textPrimary)

                HStack(spacing: 5) {
                    Circle()
                        .fill(Theme.primary)
                        .frame(width: 5, height: 5)
                    Text(subtitle)
                        .font(.system(size: 10, weight: .semibold))
                        .tracking(0.8)
                        .foregroundStyle(Theme.textSecondary)
                }
            }

            Spacer()

            trailing
        }
    }
}

extension BrandHeader where Trailing == EmptyView {
    init(subtitle: String) {
        self.init(subtitle: subtitle) { EmptyView() }
    }
}

// MARK: - Section header ("Recent Analysis      View All >")

struct SectionHeader: View {
    let title: String
    var actionTitle: String? = nil
    var action: (() -> Void)? = nil
    /// Small icon after the action title; "chevron.right" for "View All", "pencil" for "Edit".
    var actionIcon: String? = "chevron.right"

    var body: some View {
        HStack {
            Text(title)
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(Theme.textPrimary)

            Spacer()

            if let actionTitle, let action {
                Button(action: action) {
                    HStack(spacing: 3) {
                        Text(actionTitle)
                        if let actionIcon {
                            Image(systemName: actionIcon)
                                .font(.system(size: 10, weight: .bold))
                        }
                    }
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Theme.primary)
                }
            }
        }
    }
}

// MARK: - Buttons

struct FilledActionButton: View {
    let title: String
    var systemImage: String? = nil
    var isEnabled: Bool = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let systemImage {
                    Image(systemName: systemImage)
                        .font(.system(size: 15, weight: .semibold))
                }
                Text(title)
                    .font(.system(size: 15, weight: .bold))
            }
            .foregroundStyle(Color.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 15)
            .background(Theme.primary.opacity(isEnabled ? 1 : 0.45))
            .clipShape(RoundedRectangle(cornerRadius: 14))
        }
        .disabled(!isEnabled)
    }
}

struct OutlinedActionButton: View {
    let title: String
    var systemImage: String? = nil
    var tint: Color = Theme.primary
    var fullWidth: Bool = false
    /// Taller button with larger text, e.g. Sign out.
    var isLarge: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let systemImage {
                    Image(systemName: systemImage)
                        .font(.system(size: 13, weight: .semibold))
                }
                Text(title)
                    .font(.system(size: isLarge ? 16 : 14, weight: isLarge ? .bold : .semibold))
            }
            .foregroundStyle(tint)
            .frame(maxWidth: fullWidth ? .infinity : nil)
            .padding(.horizontal, 16)
            .padding(.vertical, isLarge ? 16 : 11)
            .background(Color.white)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(tint.opacity(0.25), lineWidth: 1)
            )
        }
    }
}

// MARK: - Score pill ("● 95 /100")

struct ScorePill: View {
    let result: AnalysisResult

    var body: some View {
        HStack(spacing: 5) {
            Circle()
                .fill(result.scoreColor)
                .frame(width: 6, height: 6)

            Text(result.clampedScore.map(String.init) ?? "–")
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(Theme.textPrimary)

            Text("/100")
                .font(.system(size: 10))
                .foregroundStyle(Theme.textSecondary)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(result.scoreColor.opacity(0.12))
        .clipShape(Capsule())
        .overlay(
            Capsule().stroke(result.scoreColor.opacity(0.35), lineWidth: 1)
        )
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Score \(result.clampedScore.map(String.init) ?? "unknown") out of 100")
    }
}

// MARK: - Privacy note card

struct PrivacyNoteCard: View {
    let title: String
    let text: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "checkmark.shield")
                .font(.system(size: 16))
                .foregroundStyle(Theme.primary)
                .padding(.top, 2)

            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(Theme.textPrimary)

                Text(text)
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.accentMint.opacity(0.8))
        .clipShape(RoundedRectangle(cornerRadius: 18))
    }
}

// MARK: - Chips

struct ChipList: View {
    let items: [String]

    var body: some View {
        FlowLayout(spacing: 8) {
            ForEach(items, id: \.self) { item in
                Text(item)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(Theme.textPrimary)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Theme.accentMint)
                    .clipShape(Capsule())
            }
        }
    }
}

// MARK: - Icon tile (mint square with an icon)

struct IconTile: View {
    let systemImage: String
    var tint: Color = Theme.primary
    var background: Color = Theme.accentMint
    var size: CGFloat = 44

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: size * 0.42, weight: .medium))
            .foregroundStyle(tint)
            .frame(width: size, height: size)
            .background(background)
            .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}

// MARK: - Chips that can be removed (edit mode)

struct EditableChipList: View {
    let items: [String]
    var isEditing: Bool
    var onDelete: (String) -> Void

    var body: some View {
        FlowLayout(spacing: 8) {
            ForEach(items, id: \.self) { item in
                HStack(spacing: 6) {
                    Text(item)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(Theme.textPrimary)

                    if isEditing {
                        Button(action: {
                            onDelete(item)
                        }) {
                            Image(systemName: "xmark.circle.fill")
                                .font(.system(size: 15))
                                .foregroundStyle(Color.red.opacity(0.8))
                        }
                        .accessibilityLabel("Remove \(item)")
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(isEditing ? Color.red.opacity(0.07) : Theme.accentMint)
                .clipShape(Capsule())
            }
        }
    }
}
