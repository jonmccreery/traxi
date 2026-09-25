package thru.taxi.traxi.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The five navigation-bar icons.
 *
 * Hand-authored rather than taken from a set. `material-icons-core` is already
 * on the classpath, but it carries one plausible glyph for these five tabs --
 * `Settings` for Config -- and then makes Dumps and Log fight over `List`. Two
 * tabs wearing the same icon is worse than no icons at all, which is what this
 * bar had: every item passed `icon = {}` while Material3 went on reserving the
 * slot, so five labels sat under five empty boxes.
 *
 * Drawn as strokes on the same 24dp grid Material uses, at a 2dp weight, so
 * they sit at the density of the rest of the app rather than looking pasted in.
 * Every shape is outline-only: this app's launcher icon is a stroked boot
 * print, and filled glyphs beside it read as a different program.
 *
 * Rendered and inspected at 24 px before being committed, because at that size
 * the difference between a stack of plates and a grey smear is about half a
 * pixel of gap. Re-render with tools/icon/nav.py rather than adjusting
 * coordinates blind.
 */
object NavIcons {

    /** The logger itself: a handheld body with its antenna stub. */
    val Device: ImageVector by lazy {
        icon(
            "Device",
            "M12,1.5 V4",
            "M8,4 h8 a2,2 0 0 1 2,2 v12 a2,2 0 0 1 -2,2 h-8 a2,2 0 0 1 -2,-2 v-12 a2,2 0 0 1 2,-2 z",
            "M9.5,8.5 h5",
            "M9.5,12 h5",
        )
    }

    /** A fix: crosshair and a solved centre. */
    val Live: ImageVector by lazy {
        icon(
            "Live",
            "M17,12 A5,5 0 1 1 7,12 A5,5 0 1 1 17,12",
            "M12,2 V5", "M12,19 V22", "M2,12 H5", "M19,12 H22",
            "M13.4,12 A1.4,1.4 0 1 1 10.6,12 A1.4,1.4 0 1 1 13.4,12",
        )
    }

    /** Saved flash images, stacked like plates seen edge-on. */
    val Dumps: ImageVector by lazy {
        icon(
            "Dumps",
            "M5.5,4.5 h13 a1.25,1.25 0 0 1 0,2.5 h-13 a1.25,1.25 0 0 1 0,-2.5 z",
            "M5.5,10.75 h13 a1.25,1.25 0 0 1 0,2.5 h-13 a1.25,1.25 0 0 1 0,-2.5 z",
            "M5.5,17 h13 a1.25,1.25 0 0 1 0,2.5 h-13 a1.25,1.25 0 0 1 0,-2.5 z",
        )
    }

    /** Settings: two sliders, which a gear does not distinguish from anything. */
    val Config: ImageVector by lazy {
        icon(
            "Config",
            "M4,8 H20", "M4,16 H20",
            "M11,8 A2,2 0 1 1 7,8 A2,2 0 1 1 11,8",
            "M17,16 A2,2 0 1 1 13,16 A2,2 0 1 1 17,16",
        )
    }

    /** The PMTK transcript: a terminal, prompt and cursor. */
    val Log: ImageVector by lazy {
        icon(
            "Log",
            "M5,4 h14 a2,2 0 0 1 2,2 v12 a2,2 0 0 1 -2,2 h-14 a2,2 0 0 1 -2,-2 v-12 a2,2 0 0 1 2,-2 z",
            "M7.5,9.5 L10,12 L7.5,14.5",
            "M12.5,15 h4",
        )
    }

    /**
     * Black is never seen: [androidx.compose.material3.Icon] tints the whole
     * vector with the current content colour, which is what lets one icon serve
     * both the selected and unselected states of a navigation item.
     */
    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
