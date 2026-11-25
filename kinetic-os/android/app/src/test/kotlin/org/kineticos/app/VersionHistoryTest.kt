package org.kineticos.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.kineticos.app.data.VersionHistory
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class VersionHistoryTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test fun recordsOnlyChanges() {
        ctx.getSharedPreferences("version_history", 0).edit().clear().commit()
        assertTrue(VersionHistory.record(ctx, "0.8.0", null, null, 1))
        assertFalse(VersionHistory.record(ctx, "0.8.0", null, null, 2))                 // nothing new
        assertTrue(VersionHistory.record(ctx, "0.8.0", "6.06", "SIM_VESC", 3))           // first firmware seen
        assertFalse(VersionHistory.record(ctx, "0.8.0", "6.06", "SIM_VESC", 4))
        assertTrue(VersionHistory.record(ctx, "0.9.0", null, null, 5))                   // app update keeps last firmware
        assertTrue(VersionHistory.record(ctx, "0.9.0", "7.00", "HW|X\nY", 6))           // separators are sanitised
        val e = VersionHistory.entries(ctx)
        assertEquals(4, e.size)
        assertEquals("6.06", e[2].firmware); assertEquals("7.00", e[3].firmware); assertEquals("HW/X Y", e[3].hardware)
    }
}
