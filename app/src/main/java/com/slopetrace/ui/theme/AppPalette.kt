package com.slopetrace.ui.theme

import androidx.compose.ui.graphics.Color
import com.slopetrace.data.model.SegmentType

object AppPalette {
    val Background = Color(0xFF05060D)
    val Surface = Color(0xFF11162A)
    val SurfaceAlt = Color(0xFF1A1F33)
    val Outline = Color(0xFF2B3655)
    val TextPrimary = Color(0xFFF6F9FF)
    val TextSecondary = Color(0xB3F6F9FF)
    val Accent = Color(0xFFF97316)
    val Downhill = Color(0xFF22C55E)
    val Other = Color(0xFF7D8797)

    private val liftColors = listOf(
        Color(0xFF60A5FA),
        Color(0xFFF97316),
        Color(0xFFFBBF24),
        Color(0xFF14B8A6),
        Color(0xFFE879F9),
        Color(0xFFFB7185),
        Color(0xFFA3E635)
    )

    fun liftColor(physicalLiftId: String): Color {
        val index = (physicalLiftId.hashCode().toUInt().toInt() and Int.MAX_VALUE) % liftColors.size
        return liftColors[index]
    }

    fun segmentColor(segmentType: SegmentType, physicalLiftId: String?): Color {
        return when (segmentType) {
            SegmentType.DOWNHILL -> Downhill
            SegmentType.LIFT -> {
                if (physicalLiftId.isNullOrBlank()) Color(0xFF3B82F6) else liftColor(physicalLiftId)
            }
            SegmentType.UNKNOWN -> Other
        }
    }

    fun toGlRgba(color: Color): FloatArray {
        return floatArrayOf(color.red, color.green, color.blue, color.alpha)
    }
}
