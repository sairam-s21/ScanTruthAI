package com.scantruth.ai.ui.components

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Good morning" / "Good afternoon" / "Good evening" from a 0-23 hour. */
fun greetingForHour(hour: Int): String = when (hour) {
    in 0..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

/**
 * Human date for a Firestore timestamp. Returns an empty string when the server value
 * is missing, so callers can simply omit the line instead of printing "null".
 */
fun formatTimestamp(millis: Long?): String {
    if (millis == null) return ""
    return SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault()).format(Date(millis))
}

/** Short date used inside list rows. */
fun formatShortDate(millis: Long?): String {
    if (millis == null) return ""
    return SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(millis))
}

/**
 * Best available display name for a user-picked document: the provider's display name,
 * then the last path segment, then a neutral fallback. Never an empty string.
 */
fun documentDisplayName(
    context: Context,
    uri: Uri,
    fallback: String = "Selected document"
): String {
    val fromProvider = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }.getOrNull()

    return fromProvider?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: fallback
}
