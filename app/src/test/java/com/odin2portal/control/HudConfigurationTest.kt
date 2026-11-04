package com.odin2portal.control

import com.odin2portal.control.monitor.HudDisplayMode
import com.odin2portal.control.monitor.HudDock
import com.odin2portal.control.monitor.HudGeometry
import com.odin2portal.control.monitor.HudPreferences
import com.odin2portal.control.monitor.Metric
import org.junit.Assert.*
import org.junit.Test

class HudConfigurationTest {
    @Test fun freeDragCannotMoveBeyondBounds() {
        val p = HudGeometry.position(HudDock.FREE, 9000, -400, 1920, 1080, 280, 140, 16)
        assertEquals(1624, p.x)
        assertEquals(16, p.y)
    }

    @Test fun docksAtAllRequestedEdges() {
        assertEquals(16, HudGeometry.position(HudDock.TOP, 450, 400, 1920, 1080, 280, 100, 16).y)
        assertEquals(964, HudGeometry.position(HudDock.BOTTOM, 450, 400, 1920, 1080, 280, 100, 16).y)
        assertEquals(16, HudGeometry.position(HudDock.LEFT, 450, 400, 1920, 1080, 280, 100, 16).x)
        assertEquals(1624, HudGeometry.position(HudDock.RIGHT, 450, 400, 1920, 1080, 280, 100, 16).x)
    }

    @Test fun handlesWindowSmallerThanOverlayWithoutCrashing() {
        val p = HudGeometry.position(HudDock.BOTTOM, 100, 100, 200, 80, 260, 200, 16)
        assertEquals(0, p.x)
        assertEquals(0, p.y)
    }

    @Test fun selectedFieldsDropUnknownOrDuplicatedItems() {
        val out = HudPreferences.parseMetrics("GPU_TEMP,GPU_TEMP,NOT_A_REAL_METRIC,CPU_LOAD")
        assertEquals(setOf(Metric.GPU_TEMP, Metric.CPU_LOAD), out)
        assertTrue(HudPreferences.parseMetrics("").isEmpty())
    }
}
