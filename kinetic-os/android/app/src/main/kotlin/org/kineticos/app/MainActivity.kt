package org.kineticos.app

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.kineticos.app.service.CockpitService
import org.kineticos.app.ui.KineticRoot

/** Handlebar / remote key actions (Bluetooth media remotes, keyboards, volume keys). */
enum class KeyAction { NEXT_SCREEN, PREV_SCREEN, MODE_UP, MODE_DOWN, LAP_MARK, WAKE }

class MainActivity : ComponentActivity() {
    val keyActions = MutableSharedFlow<KeyAction>(extraBufferCapacity = 8)
    val deepLinkJoin = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 1)

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startCockpit() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        handleIntent(intent)
        setContent { KineticRoot(this) }
        lifecycleScope.launch {
            graph.settings.collect { s ->
                if (s.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                applyImmersive(true)
                if (s.kiosk) tryLockTask() else runCatching { if (isInLockTask()) stopLockTask() }
            }
        }
        requestNeeded()
    }

    private fun isInLockTask() = getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    /** Full kiosk needs the app to be device owner / allow-listed (adb dpm set-device-owner); otherwise Android shows screen pinning. */
    private fun tryLockTask() {
        runCatching {
            val dpm = getSystemService(android.app.admin.DevicePolicyManager::class.java)
            if (dpm.isDeviceOwnerApp(packageName)) dpm.setLockTaskPackages(android.content.ComponentName(this, org.kineticos.app.service.KioskAdminReceiver::class.java), arrayOf(packageName))
            if (!isInLockTask()) startLockTask()
        }
    }

    fun applyImmersive(on: Boolean) {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (on) { c.hide(WindowInsetsCompat.Type.systemBars()); c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        else c.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun requestNeeded() {
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT) }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (perms.isEmpty()) startCockpit() else permLauncher.launch(perms.toTypedArray())
    }

    private fun startCockpit() {
        runCatching { CockpitService.start(this) }.onFailure { graph.startRuntime() }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }
    private fun handleIntent(i: Intent?) {
        i?.data?.takeIf { it.scheme == "kineticos" && it.host == "join" }?.lastPathSegment?.let { deepLinkJoin.tryEmit(it) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val a = when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN -> KeyAction.NEXT_SCREEN
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP -> KeyAction.PREV_SCREEN
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_DPAD_UP -> KeyAction.MODE_UP
            KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> KeyAction.MODE_DOWN
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> KeyAction.LAP_MARK
            else -> null
        } ?: return super.onKeyDown(keyCode, event)
        keyActions.tryEmit(a); return true
    }

    override fun onUserInteraction() { super.onUserInteraction(); keyActions.tryEmit(KeyAction.WAKE) }
}
