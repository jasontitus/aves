package deckers.thibault.aves.smartsearch

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

// Decides whether background indexing may run now, and how much memory it may use.
object ResourceGate {
    private const val MIN_BATTERY_PERCENT = 20
    private const val MAX_BATTERY_TEMPERATURE_TENTHS_C = 420
    private const val LOW_MEMORY_TOTAL_BYTES = 4_000_000_000L
    private const val CHARGING_ONLY_DEFAULT_TOTAL_BYTES = 3_000_000_000L

    enum class Block { NOT_CHARGING, LOW_BATTERY, THERMAL }

    fun check(context: Context, chargingOnly: Boolean): Block? {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (chargingOnly && !charging) return Block.NOT_CHARGING

        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (!charging && level >= 0 && scale > 0 && level * 100 / scale < MIN_BATTERY_PERCENT) return Block.LOW_BATTERY

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(PowerManager::class.java)
            if (pm != null && pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) return Block.THERMAL
        } else {
            // no thermal API before Android 10 (e.g. Fire OS 7): use battery temperature as a proxy
            val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (temperature > MAX_BATTERY_TEMPERATURE_TENTHS_C) return Block.THERMAL
        }
        return null
    }

    fun totalMemory(context: Context): Long {
        val am = context.getSystemService(ActivityManager::class.java) ?: return 0
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem
    }

    fun isLowMemoryDevice(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java)
        return am?.isLowRamDevice == true || totalMemory(context) < LOW_MEMORY_TOTAL_BYTES
    }

    // default for the "index only while charging" setting
    fun defaultChargingOnly(context: Context) = totalMemory(context) < CHARGING_ONLY_DEFAULT_TOTAL_BYTES

    val is64Bit: Boolean get() = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() && android.os.Process.is64Bit()

    fun isSupported(context: Context, spec: ModelSpec): Boolean {
        if (spec.requires64Bit && !is64Bit) return false
        return totalMemory(context) >= spec.minRamBytes
    }
}
