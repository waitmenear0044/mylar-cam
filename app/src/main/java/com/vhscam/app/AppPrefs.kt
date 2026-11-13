package com.vhscam.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

enum class GridMode(val label: String) { OFF("Выкл"), THIRDS("3×3"), DIAGONALS("3×3 с диагоналями") }

enum class VolumeAction(val label: String) { SHUTTER("Спуск"), ZOOM("Зум"), NONE("Ничего") }

enum class StartMode(val label: String) { LAST("Последний"), PHOTO("Фото"), VIDEO("Видео") }

enum class JpegQuality(val label: String, val value: Int) { MEDIUM("Среднее", 85), HIGH("Высокое", 93), MAX("Максимальное", 100) }

enum class StampFormat(val label: String, val pattern: String) {
    DMY("12.11.26", "dd.MM.yy"),
    YMD("26 11 12", "yy MM dd"),
    TEXT("12 NOV 2026", "dd MMM yyyy"),
}

enum class StampFont(val label: String) { SEGMENT("Цифровой"), MONO("Моноширинный") }

/**
 * Штамп даты, общий для всех режимов: рисуется поверх итогового кадра после эффектов.
 * [size] — высота цифр в долях короткой стороны итогового кадра; [corner]: 0 — справа внизу, 1 — слева внизу,
 * 2 — справа вверху, 3 — слева вверху. [custom] — своя дата [customMs] вместо текущей (у файла из галереи — даты файла).
 */
data class StampSettings(
    val enabled: Boolean = false,
    val custom: Boolean = false,
    val customMs: Long = 0L,
    val format: StampFormat = StampFormat.DMY,
    val showTime: Boolean = false,
    val font: StampFont = StampFont.SEGMENT,
    val color: Int = DEFAULT_COLOR,
    val size: Float = 0.04f,
    val opacity: Float = 0.9f,
    val corner: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled).put("custom", custom).put("customMs", customMs).put("format", format.name)
        .put("showTime", showTime).put("font", font.name).put("color", color).put("size", size.toDouble())
        .put("opacity", opacity.toDouble()).put("corner", corner)

    companion object {
        const val DEFAULT_COLOR = 0xFFFF8A1E.toInt()
        const val MIN_SIZE = 0.02f
        const val MAX_SIZE = 0.1f
        val COLORS = listOf(
            "Оранжевый" to DEFAULT_COLOR, "Белый" to 0xFFFFFFFF.toInt(), "Жёлтый" to 0xFFFFE23A.toInt(),
            "Красный" to 0xFFFF3B30.toInt(), "Зелёный" to 0xFF4CE06A.toInt(),
        )

        fun fromJson(o: JSONObject): StampSettings {
            val d = StampSettings()
            return StampSettings(
                enabled = o.optBoolean("enabled", d.enabled),
                custom = o.optBoolean("custom", d.custom),
                customMs = o.optLong("customMs", d.customMs),
                format = StampFormat.entries.firstOrNull { it.name == o.optString("format") } ?: d.format,
                showTime = o.optBoolean("showTime", d.showTime),
                font = StampFont.entries.firstOrNull { it.name == o.optString("font") } ?: d.font,
                color = o.optInt("color", d.color) or 0xFF000000.toInt(),
                size = o.optDouble("size", d.size.toDouble()).toFloat().coerceIn(MIN_SIZE, MAX_SIZE),
                opacity = o.optDouble("opacity", d.opacity.toDouble()).toFloat().coerceIn(0.1f, 1f),
                corner = o.optInt("corner", d.corner).coerceIn(0, 3),
            )
        }
    }
}

/**
 * Настройки приложения (экран настроек и штамп даты): запоминаются между запусками и применяются сразу —
 * значения лежат в состоянии Compose, читать можно с любого потока.
 */
class AppPrefs private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    private inline fun <reified E : Enum<E>> enumOf(key: String, default: E): E =
        prefs.getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    private var gridState by mutableStateOf(enumOf("grid", GridMode.OFF))
    var grid: GridMode
        get() = gridState
        set(v) { gridState = v; prefs.edit().putString("grid", v.name).apply() }

    private var levelState by mutableStateOf(prefs.getBoolean("level", false))
    var level: Boolean
        get() = levelState
        set(v) { levelState = v; prefs.edit().putBoolean("level", v).apply() }

    /** Таймер спуска в секундах (0 — выключен). */
    private var timerState by mutableStateOf(prefs.getInt("timer", 0))
    var timerSec: Int
        get() = timerState
        set(v) { timerState = v; prefs.edit().putInt("timer", v).apply() }

    private var volumeState by mutableStateOf(enumOf("volume", VolumeAction.SHUTTER))
    var volumeAction: VolumeAction
        get() = volumeState
        set(v) { volumeState = v; prefs.edit().putString("volume", v.name).apply() }

    private var jpegState by mutableStateOf(enumOf("jpeg", JpegQuality.HIGH))
    var jpegQuality: JpegQuality
        get() = jpegState
        set(v) { jpegState = v; prefs.edit().putString("jpeg", v.name).apply() }

    /** Папка внутри Pictures и Movies. Старые файлы в прежних папках не трогаем. */
    private var folderState by mutableStateOf(prefs.getString("folder", null)?.let(::cleanFolder) ?: DEFAULT_FOLDER)
    var folder: String
        get() = folderState
        set(v) { folderState = cleanFolder(v); prefs.edit().putString("folder", folderState).apply() }

    private var vibrationState by mutableStateOf(prefs.getBoolean("vibration", true))
    var vibration: Boolean
        get() = vibrationState
        set(v) { vibrationState = v; prefs.edit().putBoolean("vibration", v).apply() }

    private var startState by mutableStateOf(enumOf("startMode", StartMode.LAST))
    var startMode: StartMode
        get() = startState
        set(v) { startState = v; prefs.edit().putString("startMode", v.name).apply() }

    private var stampState by mutableStateOf(
        prefs.getString("stamp", null)?.let { runCatching { StampSettings.fromJson(JSONObject(it)) }.getOrNull() }
            ?: legacyVhsDate(context) ?: StampSettings(),
    )
    var stamp: StampSettings
        get() = stampState
        set(v) { stampState = v; prefs.edit().putString("stamp", v.toJson().toString()).apply() }

    companion object {
        const val DEFAULT_FOLDER = "Mylar"

        /**
         * До этапа 7 дата была частью «VHS» (showDate/dateCorner в vhs.json). Если штамп ещё ни разу не сохранялся,
         * включённая там дата переносится в общий штамп: тот же угол, время и дата, как было.
         */
        private fun legacyVhsDate(context: Context): StampSettings? = runCatching {
            val vhs = context.getSharedPreferences("effect_settings", Context.MODE_PRIVATE).getString("vhs.json", null)
                ?.let { JSONObject(it) } ?: return null
            if (!vhs.optBoolean("showDate", false)) return null
            StampSettings(enabled = true, showTime = true, corner = vhs.optInt("dateCorner", 0).coerceIn(0, 3))
        }.getOrNull()
        val TIMER_CHOICES = listOf(0, 3, 5, 10)

        @Volatile private var instance: AppPrefs? = null

        fun get(context: Context): AppPrefs =
            instance ?: synchronized(this) { instance ?: AppPrefs(context.applicationContext).also { instance = it } }

        /** Имя папки без символов, недопустимых в путях; пустое — имя по умолчанию. */
        fun cleanFolder(name: String): String =
            name.filter { it.isLetterOrDigit() || it in " _-" }.trim().take(32).ifBlank { DEFAULT_FOLDER }
    }
}
