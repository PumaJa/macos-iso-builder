package org.kineticos.app

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.kineticos.app.alerts.AlertNotifier
import org.kineticos.app.net.Alert
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AlertNotifierTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test fun postsOncePerAlertAndSuppressesOlderDuplicates() {
        Shadows.shadowOf(ctx as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val a1 = Alert("a1", 1000, "geofence_exit", "Left 'Home' (300 m)")
        assertTrue(AlertNotifier.show(ctx, "MX", "veh", a1))
        assertFalse(AlertNotifier.show(ctx, "MX", "veh", a1), "same alert twice must not notify again")
        assertFalse(AlertNotifier.show(ctx, "MX", "veh", a1.copy(id = "old", ts = 500)))
        assertTrue(AlertNotifier.show(ctx, "MX", "veh", Alert("a2", 2000, "low_battery", "SoC 12 %")))
        val posted = Shadows.shadowOf(nm).allNotifications
        assertEquals(2, posted.size)
        assertTrue(posted.any { it.extras.getString("android.title")!!.contains("left the geofence") })
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(AlertNotifier.CHANNEL).importance)
    }
}
