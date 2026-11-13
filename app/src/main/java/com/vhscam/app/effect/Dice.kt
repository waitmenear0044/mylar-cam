package com.vhscam.app.effect

import android.graphics.Bitmap
import android.util.Base64
import com.vhscam.app.gl.EditLayer
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.random.Random
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Режим «Кубик» редактора: случайная цепочка из N слоёв. Пул — все режимы (каждое ядро «Cores» отдельно; «Гостинг» —
 * только для видео), один режим не больше двух раз. Значения ползунков — случайные в «безопасном окне» диапазона
 * (по умолчанию 10–90%, у параметров, которые легко «убивают» кадр, — уже; см. [SAFE]) с уклоном к середине
 * (треугольное распределение). Непрозрачность слоя 0.5–1, порядок случайный. Всё задаётся зерном: тот же seed и
 * та же попытка дают ту же цепочку.
 */
object Dice {
    val COUNTS = listOf(3, 5, 7, 10)
    const val MAX_LAYERS = 10
    const val MAX_SAME_MODE = 2

    private data class Entry(val mode: EffectMode, val core: CoreKind? = null)

    private fun pool(video: Boolean): List<Entry> =
        listOf(
            Entry(EffectMode.PIXEL), Entry(EffectMode.FILM), Entry(EffectMode.VHS),
            Entry(EffectMode.CORES, CoreKind.DREAM), Entry(EffectMode.CORES, CoreKind.SUN),
            Entry(EffectMode.CORES, CoreKind.DARK), Entry(EffectMode.CORES, CoreKind.CIRCUIT),
            Entry(EffectMode.GRADIENT), Entry(EffectMode.NEWSPAPER), Entry(EffectMode.COLOR),
        ) + if (video) listOf(Entry(EffectMode.GHOST)) else emptyList()

    /** Категории, которые не трогаются: шум и оверлей (в каждом слое шум или «REC» портили бы кадр). */
    private val SKIP_CATEGORIES = setOf("Шум", "Оверлей")

    /**
     * Безопасные окна (доли диапазона) у параметров, которые легко делают кадр чёрным, белым или кашей.
     * Ключ — режим и подпись ползунка; остальные — 10–90%.
     */
    private val SAFE: Map<String, ClosedFloatingPointRange<Float>> = mapOf(
        "PIXEL|Выцветшие чёрные" to 0f..0.4f,
        "FILM|Выцветание" to 0f..0.5f, "FILM|Halation" to 0f..0.6f, "FILM|Зерно" to 0f..0.6f, "FILM|Смаз" to 0f..0.4f,
        "VHS|Сила эффекта" to 0.5f..1f, "VHS|Выцветшие чёрные" to 0f..0.5f, "VHS|Шум ленты" to 0f..0.5f,
        "VHS|Головки" to 0f..0.5f, "VHS|Трекинг" to 0f..0.4f, "VHS|Дропауты" to 0f..0.4f, "VHS|Блочность" to 0f..0.4f,
        "VHS|Маска люминофора" to 0f..0.7f, "VHS|Кривизна экрана" to 0f..0.7f, "VHS|Цветные облака" to 0f..0.6f,
        "VHS|Муар/волна" to 0f..0.4f, "VHS|Смаз/шлейф" to 0f..0.6f,
        "CORES|Сила эффекта" to 0.5f..1f, "CORES|Экспозиция" to 0.35f..0.75f, "CORES|Контраст" to 0.3f..0.7f,
        "CORES|Затемнение" to 0f..0.5f, "CORES|Порог тёмного" to 0f..0.4f, "CORES|Постеризация" to 0f..0.5f,
        "CORES|Туман" to 0f..0.6f, "CORES|Соляризация" to 0f..0.6f, "CORES|Сдвиг блоков" to 0f..0.5f,
        "CORES|Залипание строк" to 0f..0.5f, "CORES|Частота всплесков" to 0f..0.5f, "CORES|Шум" to 0f..0.4f,
        "GRADIENT|Сила" to 0.4f..1f, "GRADIENT|Контраст" to 0.3f..0.7f,
        "NEWSPAPER|Размер ячейки" to 0.05f..0.4f, "NEWSPAPER|Контраст точек" to 0.1f..0.5f,
        "COLOR|Ширина диапазона" to 0.15f..0.6f, "COLOR|Насыщенность от" to 0.05f..0.4f,
        "GHOST|Сила" to 0.3f..0.9f,
    )

    /**
     * Новая цепочка: [count] слоёв; слои из [locked] (по их местам) остаются, остальные выбираются заново.
     * [attempt] — номер переброса при «убитом» кадре (входит в зерно).
     */
    fun roll(
        seed: Long,
        attempt: Int,
        count: Int,
        video: Boolean,
        base: EffectSettings,
        locked: List<EditLayer>,
    ): List<EditLayer> {
        val rnd = Random(seed * 31 + attempt)
        val keep = locked.filter { it.locked }
        val total = maxOf(count, keep.size).coerceAtMost(MAX_LAYERS)
        val used = HashMap<EffectMode, Int>()
        keep.forEach { used[it.settings.mode] = (used[it.settings.mode] ?: 0) + 1 }
        val fresh = ArrayList<EditLayer>()
        repeat(total - keep.size) {
            val options = pool(video).filter { (used[it.mode] ?: 0) < MAX_SAME_MODE }
            val entry = if (options.isEmpty()) pool(video).random(rnd) else options.random(rnd)
            used[entry.mode] = (used[entry.mode] ?: 0) + 1
            val settings = randomSettings(entry, base, rnd)
            fresh += EditLayer(UUID.randomUUID().toString(), settings, opacity = 0.5f + 0.5f * rnd.nextFloat())
        }
        fresh.shuffle(rnd)
        // Слои с замком — на своих местах, новые — в остальные
        val result = ArrayList<EditLayer>(total)
        val lockedAt = locked.withIndex().filter { it.value.locked }.associate { it.index to it.value }
        var next = 0
        for (i in 0 until total) {
            val l = lockedAt[i]
            if (l != null) result += l else if (next < fresh.size) result += fresh[next++]
        }
        // Замки за пределами нового числа слоёв — в конец
        lockedAt.filterKeys { it >= total }.values.forEach { if (result.size < MAX_LAYERS) result += it }
        while (next < fresh.size && result.size < total) result += fresh[next++]
        return result
    }

    private fun tri(rnd: Random) = (rnd.nextFloat() + rnd.nextFloat()) / 2f

    @Suppress("UNCHECKED_CAST")
    private fun randomSettings(entry: Entry, base: EffectSettings, rnd: Random): EffectSettings {
        val mode = entry.mode
        val spec = specFor(mode)
        var p: Any = spec.setEnabled(spec.defaults, true)
        // Параметры, от которых зависят категории и вид, — до ползунков
        p = when (mode) {
            EffectMode.CORES -> (p as CoresParams).copy(core = entry.core ?: CoreKind.DREAM)
            EffectMode.GHOST -> (p as GhostParams).copy(echo = rnd.nextBoolean())
            EffectMode.GRADIENT -> (p as GradientParams).let { g ->
                if (rnd.nextBoolean()) g.with(BuiltInGradients.all.random(rnd)) else g.copy(gradientId = "dice", stops = randomStops(rnd))
            }
            EffectMode.COLOR -> (p as ColorSelectParams).let { c ->
                c.copy(keep = rnd.nextFloat() < 0.75f).withSlot(0, c.slot(0).copy(on = true, hue = rnd.nextFloat() * 360f))
            }
            else -> p
        }
        for (category in spec.categoriesOf(p)) {
            if (category.title in SKIP_CATEGORIES) continue
            for (param in category.params) {
                val window = SAFE["${mode.name}|${param.label}"] ?: (0.1f..0.9f)
                val t = window.start + (window.endInclusive - window.start) * tri(rnd)
                val r = param.range
                var v = r.start + (r.endInclusive - r.start) * t
                if (param.steps > 0) {
                    val step = (r.endInclusive - r.start) / (param.steps + 1)
                    v = r.start + ((v - r.start) / step).roundToInt() * step
                }
                p = param.set(p, v.coerceIn(r.start, r.endInclusive))
            }
        }
        return spec.put(base.copy(mode = mode), p).withPresetStrength(mode, 1f)
    }

    private fun randomStops(rnd: Random): List<GradientStop> {
        val n = 2 + rnd.nextInt(7)
        return List(n) { i ->
            val pos = if (n == 1) 0f else i / (n - 1f)
            val hsv = floatArrayOf(rnd.nextFloat() * 360f, 0.3f + 0.7f * rnd.nextFloat(), (0.1f + 0.9f * (pos * 0.7f + 0.3f * rnd.nextFloat())).coerceIn(0f, 1f))
            GradientStop(pos, android.graphics.Color.HSVToColor(hsv))
        }
    }

    /** Кадр не «убит»: средняя яркость 0.10–0.90 и есть хоть какой-то контраст. */
    fun looksAlive(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        var sum = 0.0
        var sum2 = 0.0
        for (c in px) {
            val y = (0.299 * (c shr 16 and 255) + 0.587 * (c shr 8 and 255) + 0.114 * (c and 255)) / 255.0
            sum += y
            sum2 += y * y
        }
        val n = px.size.coerceAtLeast(1)
        val mean = sum / n
        val std = kotlin.math.sqrt((sum2 / n - mean * mean).coerceAtLeast(0.0))
        return mean in 0.10..0.90 && std > 0.03
    }
}

/**
 * Код набора слоёв (MYLAR1:): режим, параметры (с учётом силы пресета), непрозрачность и видимость каждого слоя и
 * seed броска «Кубика». Слои удалённых режимов и ядер при вставке пропускаются.
 */
object LayerSetCode {
    fun encode(layers: List<EditLayer>, seed: Long?, count: Int? = null): String {
        val a = JSONArray()
        for (layer in layers) {
            val s = layer.settings.effective()
            val spec = specFor(s.mode)
            a.put(
                JSONObject().put("mode", s.mode.name).put("params", paramsToJson(s.mode, spec.get(s)))
                    .put("enabled", s.enabled).put("opacity", layer.opacity.toDouble()).put("visible", layer.visible).put("locked", layer.locked),
            )
        }
        val json = JSONObject().put("v", 1).put("kind", "layers").put("layers", a)
        if (seed != null) json.put("seed", seed)
        if (count != null) json.put("count", count)
        return PresetRepository.CODE_PREFIX + Base64.encodeToString(json.toString().toByteArray(), Base64.NO_WRAP)
    }

    class Decoded(val layers: List<EditLayer>, val seed: Long?, val skipped: Int, val count: Int?)

    /** null — в строке нет кода набора слоёв. */
    fun decode(code: String, base: EffectSettings): Decoded? = runCatching {
        val body = code.trim().removePrefix(PresetRepository.CODE_PREFIX).removePrefix(PresetRepository.OLD_CODE_PREFIX).filterNot { it.isWhitespace() }
        val json = JSONObject(String(Base64.decode(body, Base64.DEFAULT)))
        if (json.optString("kind") != "layers") return null
        val a = json.getJSONArray("layers")
        var skipped = 0
        val layers = (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val params = o.optJSONObject("params")
            val modeName = o.optString("mode")
            val mode = EffectMode.entries.firstOrNull { it.name == modeName }
            if (mode == null || params == null || PresetRepository.rejection(modeName, params) != null) {
                skipped++
                return@mapNotNull null
            }
            val spec = specFor(mode)
            val p = spec.setEnabled(paramsFromJson(mode, params), o.optBoolean("enabled", true))
            EditLayer(
                UUID.randomUUID().toString(), spec.put(base.copy(mode = mode), p),
                opacity = o.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f),
                visible = o.optBoolean("visible", true),
                locked = o.optBoolean("locked", false),
            )
        }.take(Dice.MAX_LAYERS)
        Decoded(
            layers, if (json.has("seed")) json.optLong("seed") else null, skipped,
            json.optInt("count", 0).takeIf { it in Dice.COUNTS },
        )
    }.getOrNull()
}

/**
 * Сохранённые наборы слоёв «Кубика»: имя, число слоёв броска и сам набор кодом [LayerSetCode] (режим, параметры,
 * порядок, непрозрачность, видимость, seed). Лежат в files/dice_sets.json; ничего не привязано к устройству и фото.
 */
data class DiceSet(val id: String, val name: String, val count: Int, val code: String)

class DiceSetStore private constructor(context: android.content.Context) {
    private val file = java.io.File(context.filesDir, "dice_sets.json")

    var sets by androidx.compose.runtime.mutableStateOf(load())
        private set

    private fun load(): List<DiceSet> = runCatching {
        if (!file.exists()) return emptyList()
        val a = JSONObject(file.readText()).getJSONArray("sets")
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val code = o.optString("code").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            DiceSet(o.optString("id").ifBlank { UUID.randomUUID().toString() }, o.optString("name").ifBlank { "Кубик" }.take(40), o.optInt("count", 5), code)
        }
    }.getOrDefault(emptyList())

    private fun persist(list: List<DiceSet>) {
        sets = list
        runCatching {
            val a = JSONArray().also { arr -> list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("count", it.count).put("code", it.code)) } }
            val tmp = java.io.File(file.parentFile, "dice_sets.json.tmp")
            tmp.writeText(JSONObject().put("version", 1).put("sets", a).toString())
            tmp.renameTo(file)
        }
    }

    /** Имя по умолчанию: «Кубик N» с первым свободным номером. */
    fun nextName(): String {
        val taken = sets.map { it.name }.toSet()
        var n = 1
        while ("Кубик $n" in taken) n++
        return "Кубик $n"
    }

    fun add(name: String, count: Int, layers: List<EditLayer>, seed: Long?): DiceSet {
        val set = DiceSet(UUID.randomUUID().toString(), name.trim().ifBlank { nextName() }.take(40), count, LayerSetCode.encode(layers, seed, count))
        persist(sets + set)
        return set
    }

    fun rename(set: DiceSet, name: String) = persist(sets.map { if (it.id == set.id) it.copy(name = name.trim().ifBlank { it.name }.take(40)) else it })

    fun delete(set: DiceSet) = persist(sets.filterNot { it.id == set.id })

    companion object {
        @Volatile private var instance: DiceSetStore? = null

        fun get(context: android.content.Context): DiceSetStore =
            instance ?: synchronized(this) { instance ?: DiceSetStore(context.applicationContext).also { instance = it } }
    }
}
