package com.scantruth.ai.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * ScanTruthAI design tokens.
 *
 * Deep forest green is the identity colour: primary actions, the hero surface,
 * selected navigation and icon highlights. The canvas stays near-white so the
 * green reads as a deliberate accent rather than a wash.
 *
 * Screens must not hardcode colours; use [Brand] tokens or MaterialTheme.colorScheme.
 */
object Brand {
    /** Primary — deep forest green: #144d3d. */
    val Primary = Color(0xFF144D3D)

    /** Deep green for hero cards and dark accents: #0f382b. */
    val Deep = Color(0xFF0F382B)

    /** Secondary green for supporting highlights: #33a666. */
    val Secondary = Color(0xFF33A666)

    /** Mint soft green for tinted containers, chips and quiet banners: #e8f5ee. */
    val Soft = Color(0xFFE8F5EE)
    val Mint = Color(0xFFE8F5EE)
    val MintBorder = Color(0xFFBADECB)

    /** A slightly lighter soft green for nested surfaces on soft green: #f2f9f5. */
    val SoftRaised = Color(0xFFF1F8F4)

    /** Canvas background: #f7faf8. */
    val Background = Color(0xFFF7FAF8)
    val Card = Color(0xFFFFFFFF)

    val TextPrimary = Color(0xFF1A2921)
    val TextSecondary = Color(0xFF667A70)
    val TextTertiary = Color(0xFF99ADA3)
    val TextOnDeep = Color(0xFFF2F8F5)
    val TextOnDeepMuted = Color(0xFFB9D3C7)

    val Hairline = Color(0x0F000000)
    val Divider = Color(0x14000000)
    val Outline = Color(0xFFBADECB)

    /** Status / Rubric colors matching Web: good #33a666, care/amber #ff9500 / #f2a633, avoid #ff3b30, danger #d9363e. */
    val Good = Color(0xFF33A666)
    val Care = Color(0xFFFF9500)
    val Avoid = Color(0xFFFF3B30)
    val Amber = Color(0xFFF2A633)
    val Danger = Color(0xFFD9363E)
    val DangerSoft = Color(0xFFFBE9E7)
    val OnDangerSoft = Color(0xFF601410)

    val Warning = Color(0xFFFF9500)
    val WarningSoft = Color(0xFFFBF2E2)

    val Purple = Color(0xFF7347AD)
    val PurpleBg = Color(0xFFF2EBFA)
    val ScanGreen = Color(0xFF59D1A6)

    // Dark theme counterparts
    val DarkBackground = Color(0xFF08120F)
    val DarkSurface = Color(0xFF10201A)
    val DarkSurfaceVariant = Color(0xFF182C24)
    val DarkOutline = Color(0xFF2C4438)
    val DarkTextPrimary = Color(0xFFE8F2ED)
    val DarkTextSecondary = Color(0xFF9FB4A8)
    val DarkPrimary = Color(0xFF59D1A6)
    val DarkPrimaryContainer = Color(0xFF123A2D)
}
