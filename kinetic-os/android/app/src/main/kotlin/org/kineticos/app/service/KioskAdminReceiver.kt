package org.kineticos.app.service

import android.app.admin.DeviceAdminReceiver

/**
 * Enables full kiosk (lock task) when provisioned as device owner on a freshly reset cockpit phone:
 *   adb shell dpm set-device-owner org.kineticos.app/.service.KioskAdminReceiver
 * Remove with: adb shell dpm remove-active-admin org.kineticos.app/.service.KioskAdminReceiver (or factory reset).
 */
class KioskAdminReceiver : DeviceAdminReceiver()
