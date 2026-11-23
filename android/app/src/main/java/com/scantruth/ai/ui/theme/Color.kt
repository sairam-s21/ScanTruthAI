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
    /** Primary — deep forest green. */
    val Primary = Color(0xFF145B46)

    /** Deep green for hero cards and the selected navigation pill. */
    val Deep = Color(0xFF0B3F32)

    /** Secondary green for supporting highlights. */
    val Secondary = Color(0xFF218568)

    /** Soft green for tinted containers, chips and quiet banners. */
    val Soft = Color(0xFFE8F2ED)

    /** A slightly lighter soft green for nested surfaces on soft green. */
    val SoftRaised = Color(0xFFF1F8F4)

    val Background = Color(0xFFF8F8F8)
    val Card = Color(0xFFFFFFFF)

    val TextPrimary = Color(0xFF172A24)
    val TextSecondary = Color(0xFF71847B)
    val TextOnDeep = Color(0xFFF2F8F5)
    val TextOnDeepMuted = Color(0xFFB9D3C7)

    val Divider = Color(0xFFE5EDE8)
    val Outline = Color(0xFFD8E4DC)

    /** Caution band for scores and warnings. */
    val Warning = Color(0xFF9A6414)
    val WarningSoft = Color(0xFFFBF2E2)

    val Danger = Color(0xFFB3261E)
    val DangerSoft = Color(0xFFFBE9E7)
    val OnDangerSoft = Color(0xFF601410)

    // Dark theme counterparts
    val DarkBackground = Color(0xFF08120F)
    val DarkSurface = Color(0xFF10201A)
    val DarkSurfaceVariant = Color(0xFF182C24)
    val DarkOutline = Color(0xFF2C4438)
    val DarkTextPrimary = Color(0xFFE8F2ED)
    val DarkTextSecondary = Color(0xFF9FB4A8)
    val DarkPrimary = Color(0xFF6FCDA9)
    val DarkPrimaryContainer = Color(0xFF123A2D)
}
