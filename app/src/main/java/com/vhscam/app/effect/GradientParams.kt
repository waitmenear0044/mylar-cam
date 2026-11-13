package com.vhscam.app.effect

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToInt

/** Точка градиента: положение 0..1 и цвет (RGB, без прозрачности). */
data class GradientStop(val pos: Float, val color: Int)

/** Именованный градиент из точек (2..[MAX_STOPS]). Готовые можно править и сбрасывать к исходным. */
data class Gradient(val id: String, val name: String, val stops: List<GradientStop>, val builtIn: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("builtIn", builtIn).put("stops", stopsToJson(stops))

    companion object {
        const val MAX_STOPS = 30

        fun stopsToJson(stops: List<GradientStop>): JSONArray = JSONArray().also { a ->
            stops.forEach { a.put(JSONObject().put("p", Math.round(it.pos * 10000.0) / 10000.0).put("c", String.format("%06X", it.color and 0xFFFFFF))) }
        }

        /** Точки из JSON: по положению, не больше [MAX_STOPS]; меньше двух — null. */
        fun stopsFromJson(a: JSONArray?): List<GradientStop>? {
            if (a == null) return null
            val stops = (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val c = o.optString("c").toIntOrNull(16) ?: return@mapNotNull null
                val p = o.optDouble("p", Double.NaN).toFloat()
                if (p.isNaN()) null else GradientStop(p.coerceIn(0f, 1f), c or 0xFF000000.toInt())
            }.sortedBy { it.pos }.take(MAX_STOPS)
            return stops.takeIf { it.size >= 2 }
        }

        fun fromJson(o: JSONObject): Gradient? {
            val stops = stopsFromJson(o.optJSONArray("stops")) ?: return null
            return Gradient(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = o.optString("name").ifBlank { "Градиент" }.take(40),
                stops = stops,
                builtIn = o.optBoolean("builtIn", false),
            )
        }
    }
}

private fun stops(vararg s: Pair<Float, Long>) = s.map { GradientStop(it.first, it.second.toInt() or 0xFF000000.toInt()) }

/** Восемь готовых градиентов, разных по настроению. */
object BuiltInGradients {
    val all: List<Gradient> = listOf(
        Gradient("builtin:sunset", "Закат", stops(0f to 0x1B0B3A, 0.3f to 0x7A1F6B, 0.55f to 0xE0453F, 0.8f to 0xFFA33C, 1f to 0xFFF1B5), true),
        Gradient("builtin:neon", "Неон", stops(0f to 0x05010F, 0.35f to 0x3D0DB8, 0.6f to 0xFF2BD6, 0.85f to 0x33F3FF, 1f to 0xFFFFFF), true),
        Gradient("builtin:thermal", "Тепловизор", stops(0f to 0x000014, 0.2f to 0x2B0A8C, 0.4f to 0xC81E78, 0.6f to 0xFF6A00, 0.8f to 0xFFE600, 1f to 0xFFFFFF), true),
        Gradient("builtin:sepia", "Сепия", stops(0f to 0x120A05, 0.45f to 0x7A5230, 0.8f to 0xD9B88A, 1f to 0xFFF4DE), true),
        Gradient("builtin:ice", "Лёд", stops(0f to 0x020B1A, 0.4f to 0x1F5A8F, 0.75f to 0x8FD6F0, 1f to 0xF5FDFF), true),
        Gradient("builtin:cyberpunk", "Киберпанк", stops(0f to 0x0A0018, 0.3f to 0x1A1F8A, 0.55f to 0x00C2B8, 0.78f to 0xF2E100, 1f to 0xFF3D8B), true),
        Gradient("builtin:forest", "Лес", stops(0f to 0x06120A, 0.4f to 0x1F5C3A, 0.7f to 0x9CB85A, 1f to 0xF4F0D0), true),
        Gradient("builtin:monitor", "Ретро-монитор", stops(0f to 0x000800, 0.5f to 0x0C7A1E, 0.85f to 0x45F05A, 1f to 0xD6FFD0), true),
    )

    fun original(id: String): Gradient? = all.firstOrNull { it.id == id }
}

/**
 * Параметры режима «Градиент»: кадр переводится в ЧБ по яркости и раскрашивается картой градиента.
 * Точки градиента лежат прямо в параметрах — пресеты и коды самодостаточны; [gradientId] — какой градиент
 * из набора выбран (для подсветки в ленте).
 */
data class GradientParams(
    val enabled: Boolean = true,
    /** Смешивание с оригиналом 0..1. */
    val strength: Float = 1f,
    /** Контраст яркости до карты −1..1. */
    val contrast: Float = 0f,
    /** Циклический сдвиг градиента 0..1. */
    val shift: Float = 0f,
    val invert: Boolean = false,
    val gradientId: String = BuiltInGradients.all.first().id,
    val stops: List<GradientStop> = BuiltInGradients.all.first().stops,
    val noise: NoiseParams = NoiseParams(),
) {
    fun with(gradient: Gradient) = copy(gradientId = gradient.id, stops = gradient.stops)
}

fun GradientParams.toJson(): JSONObject = JSONObject()
    .put("strength", strength.toDouble()).put("contrast", contrast.toDouble()).put("shift", shift.toDouble())
    .put("invert", invert).put("gradientId", gradientId).put("stops", Gradient.stopsToJson(stops))
    .put("noiseLuma", noise.luma.toDouble()).put("noiseChroma", noise.chroma.toDouble())
    .put("noiseSize", noise.size.toDouble()).put("noiseShadows", noise.shadows.toDouble())

fun gradientParamsFromJson(o: JSONObject): GradientParams {
    val d = GradientParams()
    fun f(key: String, def: Float, min: Float, max: Float) = o.optDouble(key, def.toDouble()).toFloat().let { if (it.isNaN()) def else it.coerceIn(min, max) }
    return GradientParams(
        strength = f("strength", d.strength, 0f, 1f),
        contrast = f("contrast", d.contrast, -1f, 1f),
        shift = f("shift", d.shift, 0f, 1f),
        invert = o.optBoolean("invert", false),
        gradientId = o.optString("gradientId", d.gradientId),
        stops = Gradient.stopsFromJson(o.optJSONArray("stops")) ?: d.stops,
        noise = NoiseParams(f("noiseLuma", 0f, 0f, 1f), f("noiseChroma", 0f, 0f, 1f), f("noiseSize", 0.3f, 0f, 1f), f("noiseShadows", 0.6f, 0f, 1f)),
    )
}

/**
 * Карта градиента [size] x 1 (RGBA): интерполяция между точками в линейном свете (без грязных середин),
 * обратно в sRGB. Полосы убирает дизеринг в шейдере.
 */
fun gradientLut(stops: List<GradientStop>, size: Int = 256): ByteArray {
    val sorted = stops.sortedBy { it.pos }
    val out = ByteArray(size * 4)
    fun lin(c: Int) = (c / 255.0).pow(2.2)
    fun srgb(v: Double) = (v.coerceIn(0.0, 1.0).pow(1 / 2.2) * 255).roundToInt()
    for (i in 0 until size) {
        val t = i / (size - 1f)
        val hi = sorted.indexOfFirst { it.pos >= t }.let { if (it < 0) sorted.lastIndex else it }
        val lo = (hi - 1).coerceAtLeast(0)
        val a = sorted[lo]
        val b = sorted[hi]
        val k = if (b.pos - a.pos < 1e-5f) (if (t >= b.pos) 1.0 else 0.0) else ((t - a.pos) / (b.pos - a.pos)).coerceIn(0f, 1f).toDouble()
        for (ch in 0..2) {
            val sh = 16 - ch * 8
            val v = lin(a.color shr sh and 0xFF) * (1 - k) + lin(b.color shr sh and 0xFF) * k
            out[i * 4 + ch] = srgb(v).toByte()
        }
        out[i * 4 + 3] = 255.toByte()
    }
    return out
}

/**
 * Набор градиентов пользователя: готовые (можно править и сбрасывать) и свои, в заданном порядке.
 * Хранится в JSON-файле; один экземпляр на приложение, список — состояние Compose.
 */
class GradientStore private constructor(context: Context) {
    private val file = File(context.filesDir, "gradients.json")

    var gradients by mutableStateOf(load())
        private set

    private fun load(): List<Gradient> {
        val saved = runCatching {
            if (!file.exists()) return@runCatching emptyList()
            val a = JSONObject(file.readText()).getJSONArray("gradients")
            (0 until a.length()).mapNotNull { Gradient.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        // Готовые, которых нет в файле (новая версия приложения), добавляются в конец готовых
        val missing = BuiltInGradients.all.filter { b -> saved.none { it.id == b.id } }
        return if (saved.isEmpty()) BuiltInGradients.all else saved + missing
    }

    private fun persist(list: List<Gradient>) {
        gradients = list
        runCatching {
            val a = JSONArray().also { arr -> list.forEach { arr.put(it.toJson()) } }
            val tmp = File(file.parentFile, "gradients.json.tmp")
            tmp.writeText(JSONObject().put("version", 1).put("gradients", a).toString())
            tmp.renameTo(file)
        }
    }

    fun find(id: String): Gradient? = gradients.firstOrNull { it.id == id }

    fun update(gradient: Gradient) = persist(gradients.map { if (it.id == gradient.id) gradient else it })

    fun add(name: String, stops: List<GradientStop>): Gradient {
        val g = Gradient(UUID.randomUUID().toString(), name.trim().ifBlank { "Мой градиент" }.take(40), stops.sortedBy { it.pos }.take(Gradient.MAX_STOPS))
        persist(gradients + g)
        return g
    }

    fun delete(gradient: Gradient) {
        if (!gradient.builtIn) persist(gradients.filterNot { it.id == gradient.id })
    }

    /** Готовый градиент — к исходным точкам и названию. */
    fun reset(gradient: Gradient): Gradient? {
        val original = BuiltInGradients.original(gradient.id) ?: return null
        update(original)
        return original
    }

    fun move(from: Int, to: Int) {
        if (from == to || from !in gradients.indices || to !in gradients.indices) return
        persist(gradients.toMutableList().also { it.add(to, it.removeAt(from)) })
    }

    /** Добавляет градиенты из кода (один или набор); пустой список — код некорректный. */
    fun import(code: String): List<Gradient> {
        val decoded = decode(code)
        if (decoded.isEmpty()) return emptyList()
        val added = decoded.map { it.copy(id = UUID.randomUUID().toString(), builtIn = false) }
        persist(gradients + added)
        return added
    }

    companion object {
        @Volatile private var instance: GradientStore? = null

        fun get(context: Context): GradientStore =
            instance ?: synchronized(this) { instance ?: GradientStore(context.applicationContext).also { instance = it } }

        fun encode(list: List<Gradient>): String {
            val json = JSONObject().put("v", 1).put("kind", "gradients")
                .put("gradients", JSONArray().also { a -> list.forEach { a.put(JSONObject().put("name", it.name).put("stops", Gradient.stopsToJson(it.stops))) } })
            return PresetRepository.CODE_PREFIX + Base64.encodeToString(json.toString().toByteArray(), Base64.NO_WRAP)
        }

        fun decode(code: String): List<Gradient> = runCatching {
            val body = code.trim().removePrefix(PresetRepository.CODE_PREFIX).filterNot { it.isWhitespace() }
            val json = JSONObject(String(Base64.decode(body, Base64.DEFAULT)))
            if (json.optString("kind") != "gradients") return emptyList()
            val a = json.getJSONArray("gradients")
            (0 until a.length()).mapNotNull { Gradient.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}
