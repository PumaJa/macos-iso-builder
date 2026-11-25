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
        // an older but new alert (e.g. a theft alert delivered out of order) must still be shown – dedupe is by id
        assertTrue(AlertNotifier.show(ctx, "MX", "veh", Alert("t1", 500, "theft", "moved while locked")))
        assertTrue(AlertNotifier.show(ctx, "MX", "veh", Alert("a2", 2000, "low_battery", "SoC 12 %")))
        val posted = Shadows.shadowOf(nm).allNotifications
        assertEquals(3, posted.size)
        assertTrue(posted.any { it.extras.getString("android.title")!!.contains("LOCKED") })
        assertTrue(posted.any { it.extras.getString("android.title")!!.contains("left the geofence") })
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(AlertNotifier.CHANNEL).importance)
    }
}
