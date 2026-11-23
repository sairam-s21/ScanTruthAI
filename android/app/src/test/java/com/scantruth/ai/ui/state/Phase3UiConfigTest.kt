package com.scantruth.ai.ui.state

import com.scantruth.ai.HybridAIService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Phase3UiConfigTest {

    @Test
    fun cloudModel_matchesSpecification() {
        // L-1: Verify configured Gemini model name matches the architecture specification
        assertEquals(
            "gemini-3.5-flash-lite",
            HybridAIService.CLOUD_MODEL
        )
    }

    @Test
    fun launcherIcons_existAndAreWellFormedXml() {
        // L-3: Verify adaptive icons and vector drawables exist with valid XML
        val resDir = File("src/main/res")
        val bgFile = File(resDir, "drawable/ic_launcher_background.xml")
        val fgFile = File(resDir, "drawable/ic_launcher_foreground.xml")
        val anyDpiIcon = File(resDir, "mipmap-anydpi-v26/ic_launcher.xml")
        val anyDpiRound = File(resDir, "mipmap-anydpi-v26/ic_launcher_round.xml")
        val mipmapIcon = File(resDir, "mipmap/ic_launcher.xml")
        val mipmapRound = File(resDir, "mipmap/ic_launcher_round.xml")

        assertTrue("Background drawable must exist", bgFile.exists())
        assertTrue("Foreground drawable must exist", fgFile.exists())
        assertTrue("Anydpi adaptive icon must exist", anyDpiIcon.exists())
        assertTrue("Anydpi round adaptive icon must exist", anyDpiRound.exists())
        assertTrue("Mipmap icon must exist", mipmapIcon.exists())
        assertTrue("Mipmap round icon must exist", mipmapRound.exists())

        // Verify root elements
        assertTrue(bgFile.readText().contains("<vector"))
        assertTrue(fgFile.readText().contains("<vector"))
        assertTrue(anyDpiIcon.readText().contains("<adaptive-icon"))
        assertTrue(anyDpiRound.readText().contains("<adaptive-icon"))
        assertTrue(mipmapIcon.readText().contains("<adaptive-icon"))
        assertTrue(mipmapRound.readText().contains("<adaptive-icon"))
    }

    @Test
    fun manifest_declaresLauncherIcons() {
        // L-3: Verify AndroidManifest.xml wires the launcher icons
        val manifestFile = File("src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml must exist", manifestFile.exists())
        val content = manifestFile.readText()
        assertTrue("Manifest must declare android:icon", content.contains("android:icon=\"@mipmap/ic_launcher\""))
        assertTrue("Manifest must declare android:roundIcon", content.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""))
    }

    @Test
    fun profileContainerColors_areDistinctForExpandedAndCollapsed() {
        // M-4: Verify theme tokens for SectionGroup animation are distinct
        // surface vs surfaceContainerHighest
        val lightSurface = com.scantruth.ai.ui.theme.Brand.Card
        val lightExpanded = com.scantruth.ai.ui.theme.Brand.SoftRaised
        assertNotEquals("Light theme expanded container must differ from collapsed surface", lightSurface, lightExpanded)

        val darkSurface = com.scantruth.ai.ui.theme.Brand.DarkSurface
        val darkExpanded = com.scantruth.ai.ui.theme.Brand.DarkSurfaceVariant
        assertNotEquals("Dark theme expanded container must differ from collapsed surface", darkSurface, darkExpanded)
    }
}
