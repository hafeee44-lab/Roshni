package io.github.hafeee44lab.solargrid.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * A small hand-drawn icon set — one consistent 1.6px rounded stroke — instead of pulling in
 * the multi-megabyte extended Material icon library for a dozen glyphs.
 */
object Icons {

    /** [mirrored] flips the icon in right-to-left languages, for arrows that mean "back" or "forward". */
    private fun icon(name: String, vararg paths: String, mirrored: Boolean = false): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = mirrored
        )
        paths.forEach {
            builder.addPath(
                pathData = addPathNodes(it),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
        return builder.build()
    }

    val Live = icon("live", "M13 2.5L4.8 13.4H11L10.2 21.5L19.2 10.4H13Z")
    val Insights = icon("insights", "M4 20H20", "M6.5 16.5V11", "M11.5 16.5V5.5", "M16.5 16.5V9")
    val Settings = icon(
        "settings",
        "M4 6.5H13", "M17 6.5H20", "M15 4.5V8.5",
        "M4 12H6.5", "M10.5 12H20", "M8.5 10V14",
        "M4 17.5H14.5", "M18.5 17.5H20", "M16.5 15.5V19.5"
    )
    val Refresh = icon("refresh", "M19.5 12A7.5 7.5 0 1 1 17.3 6.7", "M19.5 4V8.5H15")
    val Back = icon("back", "M15 5L8 12L15 19", mirrored = true)
    val Chevron = icon("chevron", "M9.5 6L15.5 12L9.5 18", mirrored = true)
    val ChevronDown = icon("chevron_down", "M6 9.5L12 15.5L18 9.5")
    val Check = icon("check", "M5 12.5L9.5 17L19 7.5")
    val Warning = icon("warning", "M12 3.5L2.8 19.5H21.2Z", "M12 10V14", "M12 16.9V17")
    val Bell = icon("bell", "M6 16.5V11A6 6 0 0 1 18 11V16.5L19.5 18.5H4.5Z", "M10 20.8A2.2 2.2 0 0 0 14 20.8")
    val Export = icon("export", "M12 3.5V15", "M7.5 8L12 3.5L16.5 8", "M5 14V20H19V14")
    val Close = icon("close", "M6.5 6.5L17.5 17.5", "M17.5 6.5L6.5 17.5")
    val Clock = icon("clock", "M12 3A9 9 0 1 0 12.01 3Z", "M12 7.5V12L15 14")
    val Sun = icon(
        "sun",
        "M12 8A4 4 0 1 0 12.01 8Z",
        "M12 2.5V4.5", "M12 19.5V21.5", "M2.5 12H4.5", "M19.5 12H21.5",
        "M5.3 5.3L6.7 6.7", "M17.3 17.3L18.7 18.7", "M5.3 18.7L6.7 17.3", "M17.3 6.7L18.7 5.3"
    )
    val Battery = icon("battery", "M7.5 5.5H16.5A2 2 0 0 1 18.5 7.5V19A2 2 0 0 1 16.5 21H7.5A2 2 0 0 1 5.5 19V7.5A2 2 0 0 1 7.5 5.5Z", "M10 3H14", "M9 14H15")
    /** Small sizes (flow nodes, tiles, lists): legs, one cross-arm with insulators, one tie. */
    val Pylon = icon(
        "pylon",
        "M10.5 3H13.5", "M10.5 3L7 21", "M13.5 3L17 21",
        "M4.5 8H19.5", "M5.5 8V10.5", "M18.5 8V10.5",
        "M8.4 14H15.6"
    )
    /** Large sizes only (the alarm screen): the full lattice tower. */
    val Tower = icon(
        "tower",
        "M10.4 6L12 3L13.6 6", "M10.4 6L7.5 21.5", "M13.6 6L16.5 21.5",
        "M5 8.5H19", "M6.5 13H17.5",
        "M5.5 8.5V10.3", "M18.5 8.5V10.3", "M7 13V14.8", "M17 13V14.8",
        "M10 8.5L14.8 13", "M14 8.5L9.2 13", "M9.2 13L15.8 19", "M14.8 13L8.2 19"
    )
    val Home = icon("home", "M3.5 11.5L12 4.5L20.5 11.5", "M6 9.6V20H18V9.6", "M10 20V15H14V20")
    val Info = icon("info", "M12 3A9 9 0 1 0 12.01 3Z", "M12 11V16.5", "M12 7.8V8")
    val Moon = icon("moon", "M19.5 14.5A7.8 7.8 0 1 1 9.5 4.5A6.2 6.2 0 0 0 19.5 14.5Z")
    val Message = icon("message", "M4 5H20V16H10L5 20V16H4Z")
    val Shield = icon("shield", "M12 3L19.5 6V12C19.5 16.3 16.4 19.7 12 21C7.6 19.7 4.5 16.3 4.5 12V6Z", "M8.8 12L11 14.2L15.2 9.8")
    val Trash = icon("trash", "M4.5 7H19.5", "M9.5 7V4H14.5V7", "M6.5 7L7.4 20H16.6L17.5 7")
    val Flask = icon("flask", "M9 3H15", "M10 3V9.5L5 18.3A1.8 1.8 0 0 0 6.6 21H17.4A1.8 1.8 0 0 0 19 18.3L14 9.5V3", "M7.6 15H16.4")
    val Code = icon("code", "M8.5 7L3.5 12L8.5 17", "M15.5 7L20.5 12L15.5 17")
    val Thermometer = icon("thermo", "M10 14.2V5A2 2 0 0 1 14 5V14.2A4 4 0 1 1 10 14.2Z", "M12 11V16.5")
    val Wave = icon("wave", "M2.5 12C4.5 6.5 7 6.5 9 12S13.5 17.5 15.5 12S19.5 8 21.5 10")
    val Account = icon("account", "M12 4A4 4 0 1 0 12.01 4Z", "M4.5 20.5C5.5 16.5 8.5 14.5 12 14.5S18.5 16.5 19.5 20.5")
    val Calendar = icon("calendar", "M5 5.5H19V20H5Z", "M5 10H19", "M9 3.5V7", "M15 3.5V7")
    val Inverter = icon(
        "inverter",
        "M5 3.5H19A1.5 1.5 0 0 1 20.5 5V15.5A1.5 1.5 0 0 1 19 17H5A1.5 1.5 0 0 1 3.5 15.5V5A1.5 1.5 0 0 1 5 3.5Z",
        "M7 7H13V11H7Z", "M16.5 7.5V7.6", "M16.5 10V10.1",
        "M8 17V20.5", "M12 17V20.5", "M16 17V20.5"
    )
    val Globe = icon("globe", "M12 3A9 9 0 1 0 12.01 3Z", "M3.5 12H20.5", "M12 3C9.2 6 9.2 18 12 21", "M12 3C14.8 6 14.8 18 12 21")
    val Sparkle = icon("sparkle", "M12 3L13.8 10.2L21 12L13.8 13.8L12 21L10.2 13.8L3 12L10.2 10.2Z")
}
