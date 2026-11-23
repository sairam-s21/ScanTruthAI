package com.scantruth.ai.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Small stroke icons drawn in code, so the app keeps the clean outline look of the
 * design without pulling in the whole material-icons-extended artifact.
 */
private fun strokeIcon(
    name: String,
    strokeWidth: Float = 1.8f,
    pathBuilder: PathBuilder.() -> Unit
): ImageVector {
    val builder = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    )
    builder.path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = strokeWidth,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = pathBuilder
    )
    return builder.build()
}

object AppIcons {
    val ShieldCheck: ImageVector by lazy {
        strokeIcon("ShieldCheck", 1.7f) {
            moveTo(12f, 3f)
            lineTo(20f, 6.3f)
            lineTo(20f, 11.8f)
            quadTo(20f, 18f, 12f, 21f)
            quadTo(4f, 18f, 4f, 11.8f)
            lineTo(4f, 6.3f)
            close()
            moveTo(8.7f, 11.8f)
            lineTo(11f, 14.1f)
            lineTo(15.4f, 9.7f)
        }
    }

    val Camera: ImageVector by lazy {
        strokeIcon("Camera") {
            moveTo(3f, 9.6f)
            quadTo(3f, 7.6f, 5f, 7.6f)
            lineTo(7.2f, 7.6f)
            lineTo(8.5f, 5.6f)
            lineTo(15.5f, 5.6f)
            lineTo(16.8f, 7.6f)
            lineTo(19f, 7.6f)
            quadTo(21f, 7.6f, 21f, 9.6f)
            lineTo(21f, 17.4f)
            quadTo(21f, 19.4f, 19f, 19.4f)
            lineTo(5f, 19.4f)
            quadTo(3f, 19.4f, 3f, 17.4f)
            close()
            moveTo(8.9f, 13.5f)
            arcTo(3.1f, 3.1f, 0f, false, true, 15.1f, 13.5f)
            arcTo(3.1f, 3.1f, 0f, false, true, 8.9f, 13.5f)
        }
    }

    val Document: ImageVector by lazy {
        strokeIcon("Document") {
            moveTo(6f, 3.5f)
            lineTo(14f, 3.5f)
            lineTo(19f, 8.5f)
            lineTo(19f, 20.5f)
            lineTo(6f, 20.5f)
            close()
            moveTo(14f, 3.5f)
            lineTo(14f, 8.5f)
            lineTo(19f, 8.5f)
            moveTo(9f, 13f)
            lineTo(16f, 13f)
            moveTo(9f, 16.5f)
            lineTo(16f, 16.5f)
        }
    }

    val ArrowRight: ImageVector by lazy {
        strokeIcon("ArrowRight", 2f) {
            moveTo(5f, 12f)
            lineTo(19f, 12f)
            moveTo(13f, 6f)
            lineTo(19f, 12f)
            lineTo(13f, 18f)
        }
    }

    val Gallery: ImageVector by lazy {
        strokeIcon("Gallery", 1.7f) {
            moveTo(4f, 7.5f)
            quadTo(4f, 6f, 5.5f, 6f)
            lineTo(18.5f, 6f)
            quadTo(20f, 6f, 20f, 7.5f)
            lineTo(20f, 16.5f)
            quadTo(20f, 18f, 18.5f, 18f)
            lineTo(5.5f, 18f)
            quadTo(4f, 18f, 4f, 16.5f)
            close()
            moveTo(4.6f, 16f)
            lineTo(9.2f, 11.4f)
            lineTo(12.4f, 14.6f)
            lineTo(14.8f, 12.2f)
            lineTo(19.4f, 16.8f)
        }
    }

    val Sparkle: ImageVector by lazy {
        strokeIcon("Sparkle", 1.7f) {
            moveTo(12f, 3.8f)
            lineTo(13.7f, 9.3f)
            lineTo(19.2f, 11f)
            lineTo(13.7f, 12.7f)
            lineTo(12f, 18.2f)
            lineTo(10.3f, 12.7f)
            lineTo(4.8f, 11f)
            lineTo(10.3f, 9.3f)
            close()
        }
    }

    val Bolt: ImageVector by lazy {
        strokeIcon("Bolt", 1.7f) {
            moveTo(13.2f, 3.5f)
            lineTo(6.8f, 13.2f)
            lineTo(11.6f, 13.2f)
            lineTo(10.8f, 20.5f)
            lineTo(17.2f, 10.8f)
            lineTo(12.4f, 10.8f)
            close()
        }
    }

    val HeartPulse: ImageVector by lazy {
        strokeIcon("HeartPulse", 1.7f) {
            moveTo(12f, 19.6f)
            quadTo(4f, 14.2f, 3.2f, 9.4f)
            quadTo(2.6f, 6f, 5.4f, 4.6f)
            quadTo(7.6f, 3.6f, 9.3f, 4.6f)
            quadTo(11.2f, 5.7f, 12f, 7.2f)
            quadTo(12.8f, 5.7f, 14.7f, 4.6f)
            quadTo(16.4f, 3.6f, 18.6f, 4.6f)
            quadTo(21.4f, 6f, 20.8f, 9.4f)
            quadTo(20f, 14.2f, 12f, 19.6f)
            close()
            moveTo(6.4f, 11.6f)
            lineTo(9.4f, 11.6f)
            lineTo(10.9f, 9f)
            lineTo(13f, 14.2f)
            lineTo(14.6f, 11.6f)
            lineTo(17.6f, 11.6f)
        }
    }

    val AlertTriangle: ImageVector by lazy {
        strokeIcon("AlertTriangle", 1.7f) {
            moveTo(12f, 4.2f)
            lineTo(20.8f, 19.4f)
            lineTo(3.2f, 19.4f)
            close()
            moveTo(12f, 9.6f)
            lineTo(12f, 14.2f)
            moveTo(12f, 16.9f)
            lineTo(12f, 17.1f)
        }
    }

    val Pill: ImageVector by lazy {
        strokeIcon("Pill", 1.7f) {
            moveTo(10.26f, 18.26f)
            lineTo(18.26f, 10.26f)
            arcTo(3.2f, 3.2f, 0f, false, false, 13.74f, 5.74f)
            lineTo(5.74f, 13.74f)
            arcTo(3.2f, 3.2f, 0f, false, false, 10.26f, 18.26f)
            close()
            moveTo(9.4f, 14.6f)
            lineTo(14.6f, 9.4f)
        }
    }

    val Leaf: ImageVector by lazy {
        strokeIcon("Leaf", 1.7f) {
            moveTo(5f, 19f)
            quadTo(4.4f, 8.6f, 10.4f, 6f)
            quadTo(15f, 4f, 19f, 5f)
            quadTo(20f, 9f, 18f, 13.6f)
            quadTo(15.4f, 19.6f, 5f, 19f)
            close()
            moveTo(5.4f, 18.6f)
            lineTo(13.2f, 10.8f)
        }
    }

    val Bell: ImageVector by lazy {
        strokeIcon("Bell", 1.7f) {
            moveTo(6.4f, 9.4f)
            quadTo(6.4f, 4.6f, 12f, 4.6f)
            quadTo(17.6f, 4.6f, 17.6f, 9.4f)
            lineTo(17.6f, 13.4f)
            lineTo(19.6f, 17.2f)
            lineTo(4.4f, 17.2f)
            lineTo(6.4f, 13.4f)
            close()
            moveTo(10f, 20f)
            quadTo(12f, 21.4f, 14f, 20f)
        }
    }

    val Plus: ImageVector by lazy {
        strokeIcon("Plus", 2f) {
            moveTo(12f, 5f)
            lineTo(12f, 19f)
            moveTo(5f, 12f)
            lineTo(19f, 12f)
        }
    }

    val ChevronRight: ImageVector by lazy {
        strokeIcon("ChevronRight", 2f) {
            moveTo(9.5f, 5.5f)
            lineTo(16f, 12f)
            lineTo(9.5f, 18.5f)
        }
    }

    val ChevronDown: ImageVector by lazy {
        strokeIcon("ChevronDown", 2f) {
            moveTo(5.5f, 9.5f)
            lineTo(12f, 16f)
            lineTo(18.5f, 9.5f)
        }
    }

    val Person: ImageVector by lazy {
        strokeIcon("Person", 1.7f) {
            moveTo(9.2f, 8.2f)
            arcTo(2.8f, 2.8f, 0f, false, true, 14.8f, 8.2f)
            arcTo(2.8f, 2.8f, 0f, false, true, 9.2f, 8.2f)
            moveTo(5f, 19.4f)
            quadTo(5f, 14.6f, 12f, 14.6f)
            quadTo(19f, 14.6f, 19f, 19.4f)
        }
    }

    val Pencil: ImageVector by lazy {
        strokeIcon("Pencil", 1.7f) {
            moveTo(15.6f, 4.9f)
            lineTo(19.1f, 8.4f)
            lineTo(8.6f, 18.9f)
            lineTo(4.4f, 19.6f)
            lineTo(5.1f, 15.4f)
            close()
        }
    }

    val Lock: ImageVector by lazy {
        strokeIcon("Lock", 1.7f) {
            moveTo(6f, 10.4f)
            lineTo(18f, 10.4f)
            lineTo(18f, 20f)
            lineTo(6f, 20f)
            close()
            moveTo(8.6f, 10.4f)
            lineTo(8.6f, 7.8f)
            quadTo(8.6f, 4.4f, 12f, 4.4f)
            quadTo(15.4f, 4.4f, 15.4f, 7.8f)
            lineTo(15.4f, 10.4f)
        }
    }

    val Check: ImageVector by lazy {
        strokeIcon("Check", 2f) {
            moveTo(5f, 12.6f)
            lineTo(9.8f, 17.4f)
            lineTo(19f, 6.8f)
        }
    }

    val Close: ImageVector by lazy {
        strokeIcon("Close", 2f) {
            moveTo(6.4f, 6.4f)
            lineTo(17.6f, 17.6f)
            moveTo(17.6f, 6.4f)
            lineTo(6.4f, 17.6f)
        }
    }

    val RemoveCircle: ImageVector by lazy {
        strokeIcon("RemoveCircle", 1.8f) {
            moveTo(12f, 3f)
            arcTo(9f, 9f, 0f, true, true, 12f, 21f)
            arcTo(9f, 9f, 0f, true, true, 12f, 3f)
            moveTo(7.5f, 12f)
            lineTo(16.5f, 12f)
        }
    }

    val Trash: ImageVector by lazy {
        strokeIcon("Trash", 1.8f) {
            moveTo(4f, 7f)
            lineTo(20f, 7f)
            moveTo(10f, 11f)
            lineTo(10f, 17f)
            moveTo(14f, 11f)
            lineTo(14f, 17f)
            moveTo(5f, 7f)
            lineTo(6.5f, 19.5f)
            quadTo(6.7f, 21f, 8.2f, 21f)
            lineTo(15.8f, 21f)
            quadTo(17.3f, 21f, 17.5f, 19.5f)
            lineTo(19f, 7f)
            moveTo(9f, 7f)
            lineTo(9.5f, 4f)
            lineTo(14.5f, 4f)
            lineTo(15f, 7f)
        }
    }

    val Chip: ImageVector by lazy {
        strokeIcon("Chip", 1.7f) {
            moveTo(8f, 8f)
            lineTo(16f, 8f)
            lineTo(16f, 16f)
            lineTo(8f, 16f)
            close()
            moveTo(10f, 4.5f)
            lineTo(10f, 8f)
            moveTo(14f, 4.5f)
            lineTo(14f, 8f)
            moveTo(10f, 16f)
            lineTo(10f, 19.5f)
            moveTo(14f, 16f)
            lineTo(14f, 19.5f)
            moveTo(4.5f, 10f)
            lineTo(8f, 10f)
            moveTo(4.5f, 14f)
            lineTo(8f, 14f)
            moveTo(16f, 10f)
            lineTo(19.5f, 10f)
            moveTo(16f, 14f)
            lineTo(19.5f, 14f)
        }
    }
}
