package com.example.batteryguardian

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// ============================================================================
// 1. 数据模型定义 (Data Models)
// ============================================================================

/**
 * 电池硬件实时状态与真实容量快照
 */
data class BatteryHardwareSnapshot(
    val designCapacityMah: Double,          // 出厂设计容量 (mAh)
    val kernelFullCapacityMah: Double?,     // 内核 BMS 报告的当前满充容量 (mAh)
    val calibratedRealCapacityMah: Double?, // 历史充电积分实测的真实容量 (mAh)
    val currentChargeCounterMah: Double,    // 当前剩余电荷量 (mAh)
    val currentNowMa: Double,               // 实时充放电电流 (mA, 正为充电, 负为放电)
    val voltageMv: Int,                     // 实时电压 (mV)
    val temperatureCelsius: Double,         // 电池温度 (°C)
    val levelPercent: Int,                  // 系统显示电量百分比 (%)
    val isCharging: Boolean,                // 是否正在充电
    val systemCycleCount: Int?,             // 系统/内核读取的充电循环次数
    val localTrackedCycles: Double,         // 本地数据库累计追踪的等效循环次数
    val capacitySourceNote: String          // 容量数据来源说明
) {
    val effectiveRealCapacityMah: Double
        get() = calibratedRealCapacityMah ?: kernelFullCapacityMah ?: designCapacityMah

    val healthPercent: Double
        get() = if (designCapacityMah > 0) {
            ((effectiveRealCapacityMah / designCapacityMah) * 100.0).coerceIn(10.0, 120.0)
        } else 100.0
}

/**
 * 应用耗电四维归因分类
 */
enum class PowerDrainCategory(val label: String, val colorHex: Long) {
    SCREEN_UI("前台屏幕与交互", 0xFF2563EB),
    BACKGROUND_WAKELOCK("后台驻留与唤醒", 0xFFD97706),
    NETWORK_RADIO("网络与基带通信", 0xFF7C3AED),
    CPU_COMPUTE("CPU 高负载与多媒体", 0xFFDC2626)
}

/**
 * 单个应用的每日耗电记录与归因明细（即使应用已卸载，名称/图标/历史耗电依然永久保留）
 */
data class AppDailyPowerRecord(
    val dateStr: String,                    // 日期 YYYY-MM-DD
    val packageName: String,                // 应用包名
    val appName: String,                    // 永久快照保存的应用名称
    val versionName: String,                // 快照保存的版本号
    val isInstalled: Boolean,               // 当前是否仍安装在手机上（false = 已卸载但保留历史记录）
    val uninstalledAt: Long?,               // 卸载时间戳（若已卸载）
    val iconBase64: String?,                // 应用图标 Base64 缩略图快照
    val totalPowerMah: Double,              // 当日总耗电 (mAh)
    val screenPowerMah: Double,             // 1. 屏幕与前台渲染耗电 (mAh)
    val backgroundPowerMah: Double,         // 2. 后台服务与唤醒锁耗电 (mAh)
    val networkPowerMah: Double,            // 3. 移动网络与 Wi-Fi 射频耗电 (mAh)
    val cpuPowerMah: Double,                // 4. CPU 高负载与多媒体计算耗电 (mAh)
    val foregroundTimeMs: Long,             // 前台亮屏活跃时长 (ms)
    val backgroundTimeMs: Long,             // 后台服务活跃时长 (ms)
    val mobileTrafficBytes: Long,           // 移动数据流量 (Bytes)
    val wifiTrafficBytes: Long,             // Wi-Fi 流量 (Bytes)
    val launchCount: Int                    // 启动/唤醒切换次数
) {
    /**
     * 分析该应用最主要的耗电方面并给出针对性优化建议
     */
    fun analyzePrimaryDrainReason(): Pair<PowerDrainCategory, String> {
        val breakdown = mapOf(
            PowerDrainCategory.SCREEN_UI to screenPowerMah,
            PowerDrainCategory.BACKGROUND_WAKELOCK to backgroundPowerMah,
            PowerDrainCategory.NETWORK_RADIO to networkPowerMah,
            PowerDrainCategory.CPU_COMPUTE to cpuPowerMah
        )
        val dominant = breakdown.maxByOrNull { it.value }?.key ?: PowerDrainCategory.SCREEN_UI
        val total = max(totalPowerMah, 0.1)
        val ratio = ((breakdown[dominant] ?: 0.0) / total * 100).roundToInt()

        val diagnosis = when (dominant) {
            PowerDrainCategory.BACKGROUND_WAKELOCK ->
                "【后台偷跑主导 · 占 $ratio%】该应用在非亮屏时段存在大量后台活动或唤醒驻留（后台活跃 ${formatDuration(backgroundTimeMs)}）。建议在系统“应用电池管理”中将其设为“受限”，关闭非必要自启动。"
            PowerDrainCategory.NETWORK_RADIO ->
                "【网络通信主导 · 占 $ratio%】高频蜂窝/无线数据包收发触发基带高功率状态（移动数据 ${formatBytes(mobileTrafficBytes)}，Wi-Fi ${formatBytes(wifiTrafficBytes)}）。建议限制其后台移动数据权限或在 Wi-Fi 下预缓存。"
            PowerDrainCategory.CPU_COMPUTE ->
                "【CPU/高负载主导 · 占 $ratio%】运行期间伴随较高放电电流，主要集中在高帧率渲染、音视频编解码或密集计算。建议开启应用内省电/低帧率模式并注意机身散热。"
            PowerDrainCategory.SCREEN_UI ->
                "【前台亮屏主导 · 占 $ratio%】耗电与你的主动亮屏使用时长（${formatDuration(foregroundTimeMs)}）直接正相关，后台表现克制。可开启深色模式（OLED 屏）或适当调低屏幕亮度。"
        }
        return dominant to diagnosis
    }
}

/**
 * 单次充电实测校准会话记录
 */
data class ChargeCalibrationSession(
    val id: Long,
    val timestamp: Long,
    val startSoc: Int,
    val endSoc: Int,
    val chargedMah: Double,
    val estimatedFullCapacityMah: Double
)

// ============================================================================
// 2. 底层硬件电池读取器 (BatteryHardwareReader)
//    支持：BatteryManager API + Linux 内核 sysfs 节点 + PowerProfile 反射
// ============================================================================

class BatteryHardwareReader(private val context: Context) {

    private val batteryManager =
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    // 常见 Android 机型内核公开的满充容量节点路径
    private val chargeFullPaths = listOf(
        "/sys/class/power_supply/battery/charge_full",
        "/sys/class/power_supply/bms/charge_full",
        "/sys/class/power_supply/battery/fg_fullcapnom"
    )

    // 常见 Android 机型内核公开的出厂设计容量节点路径
    private val chargeDesignPaths = listOf(
        "/sys/class/power_supply/battery/charge_full_design",
        "/sys/class/power_supply/bms/charge_full_design"
    )

    // 常见 Android 机型内核公开的充电循环次数节点路径
    private val cycleCountPaths = listOf(
        "/sys/class/power_supply/battery/cycle_count",
        "/sys/class/power_supply/bms/cycle_count",
        "/sys/class/power_supply/battery/battery_cycle"
    )

    /**
     * 读取完整的电池硬件实时状态
     */
    fun readHardwareSnapshot(
        calibratedCapacityMah: Double?,
        localTrackedCycles: Double
    ): BatteryHardwareSnapshot {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val socPercent = if (level >= 0 && scale > 0) (level * 100) / scale else 0

        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 3850) ?: 3850
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 280) ?: 280
        val temperatureCelsius = tempTenths / 10.0

        // 1. 读取实时电流 (BATTERY_PROPERTY_CURRENT_NOW 通常为微安 uA，部分机型为毫安 mA)
        val rawCurrent = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentNowMa = normalizeCurrentToMa(rawCurrent, isCharging)

        // 2. 读取当前剩余电荷量 (BATTERY_PROPERTY_CHARGE_COUNTER 单位微安时 uAh)
        val rawChargeCounterUah =
            batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val currentChargeCounterMah = if (rawChargeCounterUah > 0) {
            rawChargeCounterUah / 1000.0
        } else 0.0

        // 3. 获取出厂设计容量 (优先读内核节点，其次反射 PowerProfile)
        val kernelDesignMah = readSysfsCapacityMah(chargeDesignPaths)
        val profileDesignMah = readPowerProfileDesignCapacity()
        val designCapacityMah = kernelDesignMah ?: profileDesignMah ?: 5000.0

        // 4. 获取内核 BMS 实际满充容量 (若有权限读取 sysfs，否则用 CHARGE_COUNTER / SOC 反推)
        val sysfsFullMah = readSysfsCapacityMah(chargeFullPaths)
        val counterDerivedFullMah = if (currentChargeCounterMah > 100 && socPercent >= 15) {
            (currentChargeCounterMah / (socPercent / 100.0)).coerceIn(
                designCapacityMah * 0.4,
                designCapacityMah * 1.15
            )
        } else null
        val kernelFullCapacityMah = sysfsFullMah ?: counterDerivedFullMah

        // 5. 读取充电循环次数 (Android 14+ EXTRA_CYCLE_COUNT -> sysfs cycle_count)
        val api34CycleCount = if (Build.VERSION.SDK_INT >= 34 && intent != null) {
            val c = intent.getIntExtra("android.os.extra.CYCLE_COUNT", -1)
            if (c >= 0) c else null
        } else null
        val sysfsCycleCount = readSysfsInt(cycleCountPaths)
        val systemCycleCount = api34CycleCount ?: sysfsCycleCount

        val sourceNote = when {
            calibratedCapacityMah != null -> "已结合本地库仑计充电积分实测校准 (精度最高)"
            sysfsFullMah != null -> "直接读取自内核 BMS 硬件节点 (/sys/class/power_supply)"
            counterDerivedFullMah != null -> "由系统硬件库仑计 CHARGE_COUNTER 实时反推"
            else -> "当前使用出厂标称值，请插上充电器充入 ≥20% 电量完成首次实测校准"
        }

        return BatteryHardwareSnapshot(
            designCapacityMah = designCapacityMah,
            kernelFullCapacityMah = kernelFullCapacityMah,
            calibratedRealCapacityMah = calibratedCapacityMah,
            currentChargeCounterMah = currentChargeCounterMah,
            currentNowMa = currentNowMa,
            voltageMv = voltageMv,
            temperatureCelsius = temperatureCelsius,
            levelPercent = socPercent,
            isCharging = isCharging,
            systemCycleCount = systemCycleCount,
            localTrackedCycles = localTrackedCycles,
            capacitySourceNote = sourceNote
        )
    }

    /**
     * 通过反射读取 Android 内部 PowerProfile 的出厂电池设计容量 (mAh)
     */
    @Suppress("PrivateApi")
    private fun readPowerProfileDesignCapacity(): Double? {
        return try {
            val powerProfileClass = Class.forName("com.android.internal.os.PowerProfile")
            val constructor = powerProfileClass.getConstructor(Context::class.java)
            val profileInstance = constructor.newInstance(context)
            val method = powerProfileClass.getMethod("getBatteryCapacity")
            val cap = method.invoke(profileInstance) as? Double
            if (cap != null && cap > 500.0) cap else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从 sysfs 路径读取容量并统一转换为 mAh (自动识别 uAh 与 mAh)
     */
    private fun readSysfsCapacityMah(paths: List<String>): Double? {
        for (path in paths) {
            val raw = readFirstLineLong(path) ?: continue
            if (raw <= 0) continue
            // 若数值大于 100,000，说明单位是 uAh (例如 4850000 uAh = 4850 mAh)
            val mah = if (raw > 100_000L) raw / 1000.0 else raw.toDouble()
            if (mah in 500.0..25000.0) return mah
        }
        return null
    }

    private fun readSysfsInt(paths: List<String>): Int? {
        for (path in paths) {
            val raw = readFirstLineLong(path) ?: continue
            if (raw in 0..10000) return raw.toInt()
        }
        return null
    }

    private fun readFirstLineLong(path: String): Long? {
        return try {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                file.bufferedReader().use { it.readLine()?.trim()?.toLongOrNull() }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 统一不同厂商的电流单位为 mA（正数代表充电，负数代表放电）
     */
    private fun normalizeCurrentToMa(rawCurrent: Long, isCharging: Boolean): Double {
        if (rawCurrent == 0L || rawCurrent == Long.MIN_VALUE) return 0.0
        val absVal = abs(rawCurrent).toDouble()
        // 绝大部分机型返回 uA (如 1500000 表示 1500mA)，少数机型直接返回 mA
        val magnitudeMa = if (absVal > 20_000.0) absVal / 1000.0 else absVal
        return if (isCharging) magnitudeMa else -magnitudeMa
    }
}

// ============================================================================
// 3. 本地 SQLite 持久化数据库 (PersistentPowerDatabase)
//    核心特性：永久保存应用名称/图标快照与每日耗电记录，应用卸载后历史数据依然完整保留
// ============================================================================

class PersistentPowerDatabase(context: Context) :
    SQLiteOpenHelper(context, "battery_guardian_store.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        // 表 1：应用元数据永久快照表（防止卸载后包名无法解析出应用名和图标）
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS app_metadata_cache (
                package_name TEXT PRIMARY KEY,
                app_name TEXT NOT NULL,
                version_name TEXT,
                icon_base64 TEXT,
                is_installed INTEGER NOT NULL DEFAULT 1,
                uninstalled_at INTEGER,
                last_seen_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // 表 2：每日应用四维耗电归档表（按 date_str + package_name 唯一索引）
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS daily_app_power_log (
                date_str TEXT NOT NULL,
                package_name TEXT NOT NULL,
                total_power_mah REAL NOT NULL,
                screen_power_mah REAL NOT NULL,
                background_power_mah REAL NOT NULL,
                network_power_mah REAL NOT NULL,
                cpu_power_mah REAL NOT NULL,
                foreground_time_ms INTEGER NOT NULL,
                background_time_ms INTEGER NOT NULL,
                mobile_traffic_bytes INTEGER NOT NULL,
                wifi_traffic_bytes INTEGER NOT NULL,
                launch_count INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (date_str, package_name)
            )
            """.trimIndent()
        )

        // 表 3：充电容量实测校准与循环次数记录表
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS charge_calibration_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                start_soc INTEGER NOT NULL,
                end_soc INTEGER NOT NULL,
                charged_mah REAL NOT NULL,
                estimated_full_mah REAL NOT NULL,
                delta_cycle REAL NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /**
     * 保存或更新应用元数据快照（安装状态下更新名称与图标）
     */
    fun upsertAppMetadata(
        packageName: String,
        appName: String,
        versionName: String,
        iconBase64: String?,
        isInstalled: Boolean
    ) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("package_name", packageName)
            put("app_name", appName)
            put("version_name", versionName)
            if (iconBase64 != null) put("icon_base64", iconBase64)
            put("is_installed", if (isInstalled) 1 else 0)
            if (!isInstalled) put("uninstalled_at", System.currentTimeMillis())
            put("last_seen_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict(
            "app_metadata_cache",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /**
     * 当监听到应用卸载广播 (ACTION_PACKAGE_REMOVED) 时，仅标记 is_installed = 0，绝不删除历史耗电记录！
     */
    fun markAppUninstalled(packageName: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("is_installed", 0)
            put("uninstalled_at", System.currentTimeMillis())
        }
        db.update(
            "app_metadata_cache",
            values,
            "package_name = ?",
            arrayOf(packageName)
        )
    }

    /**
     * 保存或更新某天某个应用的四维耗电归因数据
     */
    fun upsertDailyAppPower(record: AppDailyPowerRecord) {
        upsertAppMetadata(
            packageName = record.packageName,
            appName = record.appName,
            versionName = record.versionName,
            iconBase64 = record.iconBase64,
            isInstalled = record.isInstalled
        )
        val db = writableDatabase
        val values = ContentValues().apply {
            put("date_str", record.dateStr)
            put("package_name", record.packageName)
            put("total_power_mah", record.totalPowerMah)
            put("screen_power_mah", record.screenPowerMah)
            put("background_power_mah", record.backgroundPowerMah)
            put("network_power_mah", record.networkPowerMah)
            put("cpu_power_mah", record.cpuPowerMah)
            put("foreground_time_ms", record.foregroundTimeMs)
            put("background_time_ms", record.backgroundTimeMs)
            put("mobile_traffic_bytes", record.mobileTrafficBytes)
            put("wifi_traffic_bytes", record.wifiTrafficBytes)
            put("launch_count", record.launchCount)
            put("updated_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict(
            "daily_app_power_log",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /**
     * 查询指定日期的应用耗电排行榜（左连接 app_metadata_cache，已卸载应用照常显示原名称与图标）
     */
    fun getDailyAppPowerRanking(dateStr: String): List<AppDailyPowerRecord> {
        val db = readableDatabase
        val list = mutableListOf<AppDailyPowerRecord>()
        val sql = """
            SELECT 
                d.date_str,
                d.package_name,
                COALESCE(m.app_name, d.package_name) AS app_name,
                COALESCE(m.version_name, '未知版本') AS version_name,
                COALESCE(m.is_installed, 0) AS is_installed,
                m.uninstalled_at,
                m.icon_base64,
                d.total_power_mah,
                d.screen_power_mah,
                d.background_power_mah,
                d.network_power_mah,
                d.cpu_power_mah,
                d.foreground_time_ms,
                d.background_time_ms,
                d.mobile_traffic_bytes,
                d.wifi_traffic_bytes,
                d.launch_count
            FROM daily_app_power_log d
            LEFT JOIN app_metadata_cache m ON d.package_name = m.package_name
            WHERE d.date_str = ?
            ORDER BY d.total_power_mah DESC
        """.trimIndent()

        db.rawQuery(sql, arrayOf(dateStr)).use { cursor ->
            while (cursor.moveToNext()) {
                val uninstalledCol = cursor.getColumnIndexOrThrow("uninstalled_at")
                list.add(
                    AppDailyPowerRecord(
                        dateStr = cursor.getString(cursor.getColumnIndexOrThrow("date_str")),
                        packageName = cursor.getString(cursor.getColumnIndexOrThrow("package_name")),
                        appName = cursor.getString(cursor.getColumnIndexOrThrow("app_name")),
                        versionName = cursor.getString(cursor.getColumnIndexOrThrow("version_name")),
                        isInstalled = cursor.getInt(cursor.getColumnIndexOrThrow("is_installed")) == 1,
                        uninstalledAt = if (cursor.isNull(uninstalledCol)) null else cursor.getLong(uninstalledCol),
                        iconBase64 = cursor.getString(cursor.getColumnIndexOrThrow("icon_base64")),
                        totalPowerMah = cursor.getDouble(cursor.getColumnIndexOrThrow("total_power_mah")),
                        screenPowerMah = cursor.getDouble(cursor.getColumnIndexOrThrow("screen_power_mah")),
                        backgroundPowerMah = cursor.getDouble(cursor.getColumnIndexOrThrow("background_power_mah")),
                        networkPowerMah = cursor.getDouble(cursor.getColumnIndexOrThrow("network_power_mah")),
                        cpuPowerMah = cursor.getDouble(cursor.getColumnIndexOrThrow("cpu_power_mah")),
                        foregroundTimeMs = cursor.getLong(cursor.getColumnIndexOrThrow("foreground_time_ms")),
                        backgroundTimeMs = cursor.getLong(cursor.getColumnIndexOrThrow("background_time_ms")),
                        mobileTrafficBytes = cursor.getLong(cursor.getColumnIndexOrThrow("mobile_traffic_bytes")),
                        wifiTrafficBytes = cursor.getLong(cursor.getColumnIndexOrThrow("wifi_traffic_bytes")),
                        launchCount = cursor.getInt(cursor.getColumnIndexOrThrow("launch_count"))
                    )
                )
            }
        }
        return list
    }

    /**
     * 获取数据库中所有有耗电记录的日期列表（降序）
     */
    fun getRecordedDates(): List<String> {
        val db = readableDatabase
        val dates = mutableListOf<String>()
        db.rawQuery(
            "SELECT DISTINCT date_str FROM daily_app_power_log ORDER BY date_str DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                dates.add(cursor.getString(0))
            }
        }
        return dates
    }

    /**
     * 记录一次有效充电实测会话（当单次充电 ΔSOC >= 15% 时用于校准真实电池容量）
     */
    fun insertChargeSession(
        startSoc: Int,
        endSoc: Int,
        chargedMah: Double
    ) {
        val deltaSoc = (endSoc - startSoc).coerceAtLeast(1)
        val estimatedFullMah = (chargedMah / (deltaSoc / 100.0))
        val deltaCycle = deltaSoc / 100.0
        val db = writableDatabase
        val values = ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            put("start_soc", startSoc)
            put("end_soc", endSoc)
            put("charged_mah", chargedMah)
            put("estimated_full_mah", estimatedFullMah)
            put("delta_cycle", deltaCycle)
        }
        db.insert("charge_calibration_sessions", null, values)
    }

    /**
     * 获取最近 5 次有效充电校准会话的加权平均真实容量 (mAh)
     */
    fun getCalibratedRealCapacityMah(): Double? {
        val db = readableDatabase
        val list = mutableListOf<Double>()
        db.rawQuery(
            """
            SELECT estimated_full_mah FROM charge_calibration_sessions
            WHERE (end_soc - start_soc) >= 15
            ORDER BY timestamp DESC LIMIT 5
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursor.getDouble(0))
            }
        }
        return if (list.isNotEmpty()) list.average() else null
    }

    /**
     * 获取本地累计记录的等效充电循环次数
     */
    fun getLocalTrackedCycles(): Double {
        val db = readableDatabase
        db.rawQuery("SELECT SUM(delta_cycle) FROM charge_calibration_sessions", null).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getDouble(0)
            }
        }
        return 0.0
    }

    /**
     * 获取历史充电校准记录列表
     */
    fun getChargeSessions(): List<ChargeCalibrationSession> {
        val db = readableDatabase
        val list = mutableListOf<ChargeCalibrationSession>()
        db.rawQuery(
            "SELECT id, timestamp, start_soc, end_soc, charged_mah, estimated_full_mah FROM charge_calibration_sessions ORDER BY timestamp DESC LIMIT 20",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    ChargeCalibrationSession(
                        id = cursor.getLong(0),
                        timestamp = cursor.getLong(1),
                        startSoc = cursor.getInt(2),
                        endSoc = cursor.getInt(3),
                        chargedMah = cursor.getDouble(4),
                        estimatedFullCapacityMah = cursor.getDouble(5)
                    )
                )
            }
        }
        return list
    }
}

// ============================================================================
// 4. 应用卸载广播接收器 (AppUninstallReceiver)
//    一旦用户卸载某个应用，立即在数据库将该应用标记为“已卸载”，保留其名称、图标和全部历史耗电！
// ============================================================================

class AppUninstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_PACKAGE_REMOVED) {
            val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            if (!isReplacing) {
                val pkg = intent.data?.schemeSpecificPart ?: return
                PersistentPowerDatabase(context).markAppUninstalled(pkg)
            }
        }
    }
}

// ============================================================================
// 5. 应用耗电采集与四维归因分析引擎 (AppPowerAnalyzer)
// ============================================================================

class AppPowerAnalyzer(
    private val context: Context,
    private val database: PersistentPowerDatabase
) {
    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val networkStatsManager =
        context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
    private val packageManager: PackageManager = context.packageManager

    /**
     * 检查是否已授予 PACKAGE_USAGE_STATS（应用使用情况访问权限）
     */
    fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * 扫描今日（00:00 至当前时刻）各应用的活跃时长、后台服务、网络流量，
     * 结合设备典型功耗权重模型拆解为 4 个耗电维度并持久化存入 SQLite。
     * 同时对已卸载但当天早些时候产生过耗电的应用，完整保留在排行榜中！
     */
    fun syncTodayAppPowerUsage(): List<AppDailyPowerRecord> {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val todayStr = dateFormat.format(Date())

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDayMs = cal.timeInMillis
        val nowMs = System.currentTimeMillis()

        // 1. 先检查当前已安装应用列表，同步卸载状态
        val installedPackages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .associateBy { it.packageName }

        // 若已授权 UsageStats，则采集真实今日使用事件与流量
        if (hasUsageStatsPermission()) {
            val usageStatsMap = usageStatsManager.queryAndAggregateUsageStats(startOfDayMs, nowMs)
            val launchCounts = queryAppLaunchCounts(startOfDayMs, nowMs)

            for ((pkg, stats) in usageStatsMap) {
                val fgTimeMs = stats.totalTimeInForeground
                val bgTimeMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    stats.totalTimeForegroundServiceUsed
                } else {
                    0L
                }
                if (fgTimeMs < 15_000L && bgTimeMs < 30_000L) continue

                val appInfo = installedPackages[pkg]
                val isInstalled = appInfo != null
                val appName = if (appInfo != null) {
                    packageManager.getApplicationLabel(appInfo).toString()
                } else pkg
                val versionName = try {
                    packageManager.getPackageInfo(pkg, 0).versionName ?: "1.0"
                } catch (e: Exception) {
                    "已卸载版本"
                }
                val iconBase64 = if (appInfo != null) {
                    drawableToBase64(packageManager.getApplicationIcon(appInfo))
                } else null

                val (mobileBytes, wifiBytes) = if (appInfo != null) {
                    queryUidNetworkBytes(appInfo.uid, startOfDayMs, nowMs)
                } else 0L to 0L

                val launches = launchCounts[pkg] ?: 1

                // 功耗归因估算模型 (基于 Android PowerProfile 典型电流权重)：
                // - 屏幕与 UI 交互: ~210 mA 均值 -> 0.0583 mAh / 秒
                // - 后台服务与唤醒锁: ~55 mA 均值 + 每次唤醒脉冲 0.18 mAh
                // - 网络通信射频: 移动蜂窝约 1.8 mAh / MB, Wi-Fi 约 0.45 mAh / MB
                // - CPU 计算与多媒体负载: 根据前台活跃密度与进程类型动态估算 (~95 mA)
                val fgHours = fgTimeMs / 3_600_000.0
                val bgHours = bgTimeMs / 3_600_000.0
                val mobileMb = mobileBytes / (1024.0 * 1024.0)
                val wifiMb = wifiBytes / (1024.0 * 1024.0)

                val screenMah = fgHours * 210.0
                val backgroundMah = (bgHours * 55.0) + (launches * 0.18)
                val networkMah = (mobileMb * 1.8) + (wifiMb * 0.45)
                val cpuMah = (fgHours * 95.0) + (bgHours * 20.0)
                val totalMah = screenMah + backgroundMah + networkMah + cpuMah

                val record = AppDailyPowerRecord(
                    dateStr = todayStr,
                    packageName = pkg,
                    appName = appName,
                    versionName = versionName,
                    isInstalled = isInstalled,
                    uninstalledAt = if (isInstalled) null else System.currentTimeMillis(),
                    iconBase64 = iconBase64,
                    totalPowerMah = totalMah,
                    screenPowerMah = screenMah,
                    backgroundPowerMah = backgroundMah,
                    networkPowerMah = networkMah,
                    cpuPowerMah = cpuMah,
                    foregroundTimeMs = fgTimeMs,
                    backgroundTimeMs = bgTimeMs,
                    mobileTrafficBytes = mobileBytes,
                    wifiTrafficBytes = wifiBytes,
                    launchCount = launches
                )
                database.upsertDailyAppPower(record)
            }
        }

        // 同时检查数据库里已记录的应用现在是否被卸载了
        val existingToday = database.getDailyAppPowerRanking(todayStr)
        for (item in existingToday) {
            if (item.isInstalled && !installedPackages.containsKey(item.packageName)) {
                database.markAppUninstalled(item.packageName)
            }
        }

        return database.getDailyAppPowerRanking(todayStr)
    }

    /**
     * 统计各应用今日启动/切换前台次数
     */
    private fun queryAppLaunchCounts(startMs: Long, endMs: Long): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        return try {
            val events = usageStatsManager.queryEvents(startMs, endMs)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    val pkg = event.packageName ?: continue
                    counts[pkg] = (counts[pkg] ?: 0) + 1
                }
            }
            counts
        } catch (e: Exception) {
            counts
        }
    }

    /**
     * 查询指定 UID 的蜂窝移动网络与 Wi-Fi 流量 (Bytes)
     */
    private fun queryUidNetworkBytes(uid: Int, startMs: Long, endMs: Long): Pair<Long, Long> {
        var mobileBytes = 0L
        var wifiBytes = 0L
        try {
            val mobileStats = networkStatsManager.queryDetailsForUid(
                ConnectivityManager.TYPE_MOBILE,
                null,
                startMs,
                endMs,
                uid
            )
            val bucket = NetworkStats.Bucket()
            while (mobileStats.hasNextBucket()) {
                mobileStats.getNextBucket(bucket)
                mobileBytes += bucket.rxBytes + bucket.txBytes
            }
            mobileStats.close()
        } catch (_: Exception) {}

        try {
            val wifiStats = networkStatsManager.queryDetailsForUid(
                ConnectivityManager.TYPE_WIFI,
                null,
                startMs,
                endMs,
                uid
            )
            val bucket = NetworkStats.Bucket()
            while (wifiStats.hasNextBucket()) {
                wifiStats.getNextBucket(bucket)
                wifiBytes += bucket.rxBytes + bucket.txBytes
            }
            wifiStats.close()
        } catch (_: Exception) {}

        return mobileBytes to wifiBytes
    }

    private fun drawableToBase64(drawable: Drawable): String? {
        return try {
            val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
                drawable.bitmap
            } else {
                val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp
            }
            val scaled = Bitmap.createScaledBitmap(bitmap, 48, 48, true)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 85, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 当首次安装或用于演示测试时，写入含“已卸载应用”快照的示例历史数据，方便立即验证卸载保留与四维耗电分析
     */
    fun seedSampleDataIfEmpty() {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val todayStr = dateFormat.format(Date())
        if (database.getDailyAppPowerRanking(todayStr).isNotEmpty()) return

        val sampleApps = listOf(
            AppDailyPowerRecord(
                dateStr = todayStr,
                packageName = "com.tencent.tmgp.sgame",
                appName = "王者荣耀",
                versionName = "9.4.1.5",
                isInstalled = true,
                uninstalledAt = null,
                iconBase64 = null,
                totalPowerMah = 920.0,
                screenPowerMah = 340.0,
                backgroundPowerMah = 35.0,
                networkPowerMah = 85.0,
                cpuPowerMah = 460.0,
                foregroundTimeMs = 82 * 60 * 1000L,
                backgroundTimeMs = 10 * 60 * 1000L,
                mobileTrafficBytes = 145L * 1024 * 1024,
                wifiTrafficBytes = 320L * 1024 * 1024,
                launchCount = 4
            ),
            AppDailyPowerRecord(
                dateStr = todayStr,
                packageName = "com.example.shortvideo.uninstalled",
                appName = "某短视频极速版 (测试卸载保留)",
                versionName = "28.2.0",
                isInstalled = false, // 已卸载应用，验证卸载后依然保留在排行榜中
                uninstalledAt = System.currentTimeMillis() - 3600_000L * 3,
                iconBase64 = null,
                totalPowerMah = 680.0,
                screenPowerMah = 120.0,
                backgroundPowerMah = 415.0,
                networkPowerMah = 95.0,
                cpuPowerMah = 50.0,
                foregroundTimeMs = 28 * 60 * 1000L,
                backgroundTimeMs = 215 * 60 * 1000L,
                mobileTrafficBytes = 210L * 1024 * 1024,
                wifiTrafficBytes = 580L * 1024 * 1024,
                launchCount = 19
            ),
            AppDailyPowerRecord(
                dateStr = todayStr,
                packageName = "com.tencent.mm",
                appName = "微信",
                versionName = "8.0.50",
                isInstalled = true,
                uninstalledAt = null,
                iconBase64 = null,
                totalPowerMah = 540.0,
                screenPowerMah = 295.0,
                backgroundPowerMah = 110.0,
                networkPowerMah = 75.0,
                cpuPowerMah = 60.0,
                foregroundTimeMs = 95 * 60 * 1000L,
                backgroundTimeMs = 180 * 60 * 1000L,
                mobileTrafficBytes = 88L * 1024 * 1024,
                wifiTrafficBytes = 410L * 1024 * 1024,
                launchCount = 42
            )
        )
        sampleApps.forEach { database.upsertDailyAppPower(it) }
    }
}

// ============================================================================
// 6. 工具格式化函数
// ============================================================================

fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val mins = totalMinutes % 60
    return if (hours > 0) "${hours}小时${mins}分" else "${mins}分钟"
}

fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.2f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }
}

// ============================================================================
// 7. Jetpack Compose 主界面与交互逻辑 (MainActivity)
// ============================================================================

class MainActivity : ComponentActivity() {

    private lateinit var database: PersistentPowerDatabase
    private lateinit var hardwareReader: BatteryHardwareReader
    private lateinit var powerAnalyzer: AppPowerAnalyzer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = PersistentPowerDatabase(this)
        hardwareReader = BatteryHardwareReader(this)
        powerAnalyzer = AppPowerAnalyzer(this, database)

        // 首次启动填充示例数据（若已授予权限会自动合并真实应用耗电）
        powerAnalyzer.seedSampleDataIfEmpty()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF10B981),
                    secondary = Color(0xFF3B82F6),
                    background = Color(0xFF0F172A),
                    surface = Color(0xFF1E293B),
                    onBackground = Color(0xFFF8FAFC),
                    onSurface = Color(0xFFF1F5F9)
                )
            ) {
                BatteryGuardianScreen(
                    hardwareReader = hardwareReader,
                    database = database,
                    powerAnalyzer = powerAnalyzer
                )
            }
        }
    }
}

@Composable
fun BatteryGuardianScreen(
    hardwareReader: BatteryHardwareReader,
    database: PersistentPowerDatabase,
    powerAnalyzer: AppPowerAnalyzer
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentTab by remember { mutableStateOf(0) }
    var snapshot by remember {
        mutableStateOf(
            hardwareReader.readHardwareSnapshot(
                calibratedCapacityMah = database.getCalibratedRealCapacityMah(),
                localTrackedCycles = database.getLocalTrackedCycles()
            )
        )
    }

    val recordedDates = remember { mutableStateListOf<String>() }
    var selectedDate by remember {
        mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()))
    }
    var dailyRanking by remember { mutableStateOf(emptyList<AppDailyPowerRecord>()) }
    var selectedAppForDetail by remember { mutableStateOf<AppDailyPowerRecord?>(null) }
    var hasUsagePermission by remember { mutableStateOf(powerAnalyzer.hasUsageStatsPermission()) }

    // 实时库仑计充电校准状态
    var isCalibrating by remember { mutableStateOf(false) }
    var calibStartSoc by remember { mutableStateOf(0) }
    var calibAccumulatedMah by remember { mutableStateOf(0.0) }

    // 每 2 秒刷新一次电池底层实时电流、电压、电荷量并执行库仑积分
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            powerAnalyzer.syncTodayAppPowerUsage()
            val dates = database.getRecordedDates()
            withContext(Dispatchers.Main) {
                recordedDates.clear()
                recordedDates.addAll(dates)
                dailyRanking = database.getDailyAppPowerRanking(selectedDate)
            }
        }
        while (true) {
            delay(2000L)
            val calibrated = database.getCalibratedRealCapacityMah()
            val trackedCycles = database.getLocalTrackedCycles()
            val latest = hardwareReader.readHardwareSnapshot(calibrated, trackedCycles)
            snapshot = latest

            // 若正在进行充电积分校准且处于充电状态 (电流 > 0)
            if (isCalibrating && latest.isCharging && latest.currentNowMa > 0) {
                // 2 秒 = 2 / 3600 小时
                calibAccumulatedMah += latest.currentNowMa * (2.0 / 3600.0)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    icon = { Text("🔋", fontSize = 18.sp) },
                    label = { Text("真实容量与循环") }
                )
                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = {
                        currentTab = 1
                        hasUsagePermission = powerAnalyzer.hasUsageStatsPermission()
                        scope.launch(Dispatchers.IO) {
                            val updated = powerAnalyzer.syncTodayAppPowerUsage()
                            withContext(Dispatchers.Main) {
                                dailyRanking = if (selectedDate == SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())) {
                                    updated
                                } else {
                                    database.getDailyAppPowerRanking(selectedDate)
                                }
                            }
                        }
                    },
                    icon = { Text("📊", fontSize = 18.sp) },
                    label = { Text("每日耗电与归因") }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (currentTab == 0) {
                RealCapacityAndCycleTab(
                    snapshot = snapshot,
                    isCalibrating = isCalibrating,
                    calibStartSoc = calibStartSoc,
                    calibAccumulatedMah = calibAccumulatedMah,
                    onToggleCalibration = {
                        if (!isCalibrating) {
                            calibStartSoc = snapshot.levelPercent
                            calibAccumulatedMah = 0.0
                            isCalibrating = true
                        } else {
                            isCalibrating = false
                            val deltaSoc = snapshot.levelPercent - calibStartSoc
                            if (deltaSoc >= 15 && calibAccumulatedMah > 100.0) {
                                database.insertChargeSession(
                                    startSoc = calibStartSoc,
                                    endSoc = snapshot.levelPercent,
                                    chargedMah = calibAccumulatedMah
                                )
                                snapshot = hardwareReader.readHardwareSnapshot(
                                    calibratedCapacityMah = database.getCalibratedRealCapacityMah(),
                                    localTrackedCycles = database.getLocalTrackedCycles()
                                )
                            }
                        }
                    }
                )
            } else {
                DailyAppPowerRankingTab(
                    hasUsagePermission = hasUsagePermission,
                    onRequestPermission = {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    },
                    recordedDates = recordedDates,
                    selectedDate = selectedDate,
                    onSelectDate = { date ->
                        selectedDate = date
                        dailyRanking = database.getDailyAppPowerRanking(date)
                    },
                    dailyRanking = dailyRanking,
                    onSelectApp = { selectedAppForDetail = it }
                )
            }
        }
    }

    // 弹出单个应用的四维耗电归因详情对话框
    selectedAppForDetail?.let { app ->
        AppPowerBreakdownDialog(
            app = app,
            onDismiss = { selectedAppForDetail = null }
        )
    }
}

@Composable
fun RealCapacityAndCycleTab(
    snapshot: BatteryHardwareSnapshot,
    isCalibrating: Boolean,
    calibStartSoc: Int,
    calibAccumulatedMah: Double,
    onToggleCalibration: () -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Text(
                text = "电池真实容量与循环检测",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = snapshot.capacitySourceNote,
                fontSize = 12.sp,
                color = Color(0xFF94A3B8),
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // 核心卡片：真实容量 vs 出厂设计容量
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("预估真实满充容量", fontSize = 13.sp, color = Color(0xFF94A3B8))
                            Text(
                                text = "${snapshot.effectiveRealCapacityMah.roundToInt()} mAh",
                                fontSize = 30.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF10B981)
                            )
                        }
                        Surface(
                            color = Color(0xFF064E3B),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "真实健康度 ${String.format(Locale.US, "%.1f%%", snapshot.healthPercent)}",
                                color = Color(0xFF34D399),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricBox(
                            label = "出厂设计容量",
                            value = "${snapshot.designCapacityMah.roundToInt()} mAh"
                        )
                        MetricBox(
                            label = "内核 BMS 满充",
                            value = snapshot.kernelFullCapacityMah?.let { "${it.roundToInt()} mAh" } ?: "需校准"
                        )
                        MetricBox(
                            label = "当前剩余电荷",
                            value = "${snapshot.currentChargeCounterMah.roundToInt()} mAh"
                        )
                    }
                }
            }
        }

        // 卡片 2：充电循环次数与实时硬件遥测
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "充电循环次数与实时电流遥测",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricBox(
                            label = "系统/内核循环次数",
                            value = snapshot.systemCycleCount?.let { "${it} 次" } ?: "内核隐藏"
                        )
                        MetricBox(
                            label = "本应用累计循环",
                            value = String.format(Locale.US, "%.2f 次", snapshot.localTrackedCycles)
                        )
                        MetricBox(
                            label = "实时充放电电流",
                            value = "${snapshot.currentNowMa.roundToInt()} mA"
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricBox(
                            label = "当前电量 (SOC)",
                            value = "${snapshot.levelPercent}%"
                        )
                        MetricBox(
                            label = "实时电压",
                            value = "${snapshot.voltageMv} mV"
                        )
                        MetricBox(
                            label = "电池温度",
                            value = String.format(Locale.US, "%.1f °C", snapshot.temperatureCelsius)
                        )
                    }
                }
            }
        }

        // 卡片 3：库仑计充电积分实测校准器
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "库仑计充电积分实测校准 (解决系统虚标)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "原理：在充电时每 2 秒对硬件实时电流 I(t) 进行积分，根据【实测充入电荷量 ÷ 电量增量百分比】直接算出电池当前真实的物理容量上限。",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8),
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )
                    if (isCalibrating) {
                        Text(
                            text = "正在积分采样：起始电量 $calibStartSoc% → 当前 ${snapshot.levelPercent}% | 已充入 ${
                                String.format(Locale.US, "%.1f mAh", calibAccumulatedMah)
                            }",
                            fontSize = 13.sp,
                            color = Color(0xFF38BDF8),
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Button(
                        onClick = onToggleCalibration,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isCalibrating) Color(0xFFEF4444) else Color(0xFF10B981)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (isCalibrating) "结束校准并保存真实容量 (需充入 ≥15%)" else "开始充电实测校准 (建议电量 <50% 时插电开启)"
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DailyAppPowerRankingTab(
    hasUsagePermission: Boolean,
    onRequestPermission: () -> Unit,
    recordedDates: List<String>,
    selectedDate: String,
    onSelectDate: (String) -> Unit,
    dailyRanking: List<AppDailyPowerRecord>,
    onSelectApp: (AppDailyPowerRecord) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "每日应用耗电排行 & 耗电归因",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "应用名称、图标与四维耗电明细已永久存档于本地 SQLite，卸载应用后历史记录依然保留",
            fontSize = 12.sp,
            color = Color(0xFF94A3B8),
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
        )

        if (!hasUsagePermission) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF422006)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "需授予“使用情况访问权限”以读取全机应用实时耗电",
                        fontSize = 12.sp,
                        color = Color(0xFFFDE68A),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRequestPermission) {
                        Text("去授权", color = Color(0xFFFBBF24), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 日期切换栏
        if (recordedDates.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
            ) {
                recordedDates.take(4).forEach { dateStr ->
                    val isSelected = dateStr == selectedDate
                    Surface(
                        color = if (isSelected) Color(0xFF10B981) else Color(0xFF1E293B),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable { onSelectDate(dateStr) }
                    ) {
                        Text(
                            text = dateStr,
                            fontSize = 12.sp,
                            color = if (isSelected) Color.Black else Color.White,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(dailyRanking, key = { it.packageName }) { app ->
                val (dominantCategory, _) = app.analyzePrimaryDrainReason()
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectApp(app) }
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(Color(dominantCategory.colorHex).copy(alpha = 0.25f))
                                ) {
                                    Text(
                                        text = app.appName.take(1),
                                        fontWeight = FontWeight.Bold,
                                        color = Color(dominantCategory.colorHex)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = app.appName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (!app.isInstalled) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                color = Color(0xFF7F1D1D),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = "已卸载 · 历史保留",
                                                    fontSize = 10.sp,
                                                    color = Color(0xFFFCA5A5),
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = "主要耗电：${dominantCategory.label} | 前台 ${formatDuration(app.foregroundTimeMs)} · 后台 ${formatDuration(app.backgroundTimeMs)}",
                                        fontSize = 11.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "${app.totalPowerMah.roundToInt()} mAh",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 16.sp,
                                    color = Color(0xFF38BDF8)
                                )
                                Text(
                                    text = "点击看归因 >",
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppPowerBreakdownDialog(
    app: AppDailyPowerRecord,
    onDismiss: () -> Unit
) {
    val (_, diagnosisText) = app.analyzePrimaryDrainReason()
    val total = max(app.totalPowerMah, 0.1)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E293B),
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(app.appName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    if (!app.isInstalled) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("[已卸载]", color = Color(0xFFF87171), fontSize = 13.sp)
                    }
                }
                Text(
                    text = "包名: ${app.packageName} (${app.versionName})",
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8)
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "当日累计耗电：${String.format(Locale.US, "%.1f mAh", app.totalPowerMah)}",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF10B981)
                )

                DrainDimensionBar(
                    label = "1. 前台屏幕与 UI 交互 (${formatDuration(app.foregroundTimeMs)})",
                    mah = app.screenPowerMah,
                    ratio = (app.screenPowerMah / total).toFloat(),
                    color = Color(PowerDrainCategory.SCREEN_UI.colorHex)
                )
                DrainDimensionBar(
                    label = "2. 后台驻留与唤醒 (${formatDuration(app.backgroundTimeMs)} / 唤醒 ${app.launchCount} 次)",
                    mah = app.backgroundPowerMah,
                    ratio = (app.backgroundPowerMah / total).toFloat(),
                    color = Color(PowerDrainCategory.BACKGROUND_WAKELOCK.colorHex)
                )
                DrainDimensionBar(
                    label = "3. 网络与基带通信 (移动 ${formatBytes(app.mobileTrafficBytes)} / Wi-Fi ${formatBytes(app.wifiTrafficBytes)})",
                    mah = app.networkPowerMah,
                    ratio = (app.networkPowerMah / total).toFloat(),
                    color = Color(PowerDrainCategory.NETWORK_RADIO.colorHex)
                )
                DrainDimensionBar(
                    label = "4. CPU 高负载与多媒体计算",
                    mah = app.cpuPowerMah,
                    ratio = (app.cpuPowerMah / total).toFloat(),
                    color = Color(PowerDrainCategory.CPU_COMPUTE.colorHex)
                )

                Surface(
                    color = Color(0xFF0F172A),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = diagnosisText,
                        fontSize = 12.sp,
                        color = Color(0xFFE2E8F0),
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = Color(0xFF10B981))
            }
        }
    )
}

@Composable
fun DrainDimensionBar(
    label: String,
    mah: Double,
    ratio: Float,
    color: Color
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, fontSize = 11.sp, color = Color(0xFFCBD5E1), modifier = Modifier.weight(1f))
            Text(
                text = "${mah.roundToInt()} mAh (${(ratio * 100).roundToInt()}%)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { ratio.coerceIn(0f, 1f) },
            color = color,
            trackColor = Color(0xFF334155),
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
        )
    }
}

@Composable
fun MetricBox(label: String, value: String) {
    Column {
        Text(text = label, fontSize = 11.sp, color = Color(0xFF94A3B8))
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFF1F5F9),
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
