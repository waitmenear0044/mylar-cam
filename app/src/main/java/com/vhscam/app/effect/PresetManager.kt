package com.vhscam.app.effect

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID

/**
 * Состояние пресетов для интерфейса: список (готовые + свои), активный пресет каждого режима
 * (запоминается между запусками), маркер «изменён». Сами параметры камеры меняет вызывающий код
 * через возвращаемые [EffectSettings].
 */
@Stable
class PresetManager(context: Context) {
    private val repository = PresetRepository(context)
    private val store = SettingsStore(context)

    var userPresets by mutableStateOf(repository.loadUser())
        private set

    /** Активный пресет каждой группы: ключ — режим, у «Cores» — режим и ядро (см. [ModeSpec.groupOf]). */
    private val activeIds = mutableStateMapOf<String, String?>()

    private fun keyOf(mode: EffectMode, params: Any): String =
        specFor(mode).groupOf(params)?.let { "${mode.name}:$it" } ?: mode.name

    private fun keyOf(settings: EffectSettings): String = keyOf(settings.mode, specFor(settings.mode).get(settings))

    private fun activeId(key: String): String? = activeIds.getOrPut(key) { store.activePresetId(key) }

    fun presetsFor(mode: EffectMode): List<Preset> =
        BuiltInPresets.all.filter { it.mode == mode } + userPresets.filter { it.mode == mode }

    /** Группа пресета для заголовков в списке (у «Cores» — ядро). */
    fun groupOf(preset: Preset): String? = specFor(preset.mode).groupOf(preset.params)

    fun active(settings: EffectSettings): Preset? =
        activeId(keyOf(settings))?.let { id -> presetsFor(settings.mode).firstOrNull { it.id == id } }

    /** Параметры режима отличаются от активного пресета. */
    fun isModified(settings: EffectSettings): Boolean {
        val preset = active(settings) ?: return false
        return !sameLook(settings.mode, preset.params, specFor(settings.mode).get(settings))
    }

    /**
     * Применить пресет: параметры режима заменяются, флаг «эффект включён» (и оверлей «VHS») сохраняется,
     * сила пресета возвращается к 100%.
     */
    fun apply(preset: Preset, settings: EffectSettings): EffectSettings {
        setActive(keyOf(preset.mode, preset.params), preset.id)
        val spec = specFor(preset.mode)
        val current = spec.get(settings)
        val enabled = spec.isEnabled(current)
        return spec.put(settings, spec.keep(current, spec.setEnabled(preset.params, enabled))).withPresetStrength(preset.mode, 1f)
    }

    /** Настройки, какими они станут с этим пресетом, без смены активного пресета (для миниатюр). */
    fun preview(preset: Preset, settings: EffectSettings): EffectSettings {
        val spec = specFor(preset.mode)
        val current = spec.get(settings)
        return spec.put(settings.copy(mode = preset.mode), spec.keep(current, spec.setEnabled(preset.params, true)))
            .withPresetStrength(preset.mode, 1f)
    }

    /** Код текущих параметров режима (для «Скопировать код» без сохранения пресета). */
    fun codeOfCurrent(settings: EffectSettings): String {
        val spec = specFor(settings.mode)
        val name = active(settings)?.name ?: "Мой пресет"
        return PresetRepository.encode(Preset("", name, settings.mode, spec.setEnabled(spec.get(settings), true)))
    }

    /**
     * «×»: снять пресет и сбросить параметры к нейтральным (эффект почти не виден). У «Градиента» — только снять пресет:
     * карта и параметры остаются применёнными, как были (нейтральная сила «Градиента» — 0, сброс выключил бы эффект).
     */
    fun clear(settings: EffectSettings): EffectSettings {
        setActive(keyOf(settings), null)
        val spec = specFor(settings.mode)
        if (settings.mode == EffectMode.GRADIENT) {
            // Сила пресета «запекается» в параметры: вид не меняется
            return spec.put(settings, spec.get(settings.effective())).withPresetStrength(settings.mode, 1f)
        }
        return spec.put(settings, spec.toNeutral(spec.get(settings))).withPresetStrength(settings.mode, 1f)
    }

    fun revert(settings: EffectSettings): EffectSettings =
        active(settings)?.let { apply(it, settings) } ?: settings

    fun saveCurrent(name: String, settings: EffectSettings): Preset {
        val spec = specFor(settings.mode)
        val preset = Preset(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Мой пресет" }.take(40),
            mode = settings.mode,
            params = spec.setEnabled(spec.get(settings), true),
        )
        persist(userPresets + preset)
        setActive(keyOf(settings), preset.id)
        return preset
    }

    fun rename(preset: Preset, name: String) {
        if (preset.builtIn) return
        persist(userPresets.map { if (it.id == preset.id) it.copy(name = name.trim().ifBlank { it.name }.take(40)) else it })
    }

    fun delete(preset: Preset) {
        if (preset.builtIn) return
        persist(userPresets.filterNot { it.id == preset.id })
        val key = keyOf(preset.mode, preset.params)
        if (activeId(key) == preset.id) setActive(key, null)
    }

    /** Добавляет пресет из кода; null, если код некорректный. */
    fun import(code: String): Preset? {
        val preset = PresetRepository.decode(code) ?: return null
        persist(userPresets + preset)
        return preset
    }

    fun code(preset: Preset): String = PresetRepository.encode(preset)

    private fun setActive(key: String, id: String?) {
        activeIds[key] = id
        store.setActivePresetId(key, id)
    }

    private fun persist(list: List<Preset>) {
        userPresets = list
        repository.saveUser(list)
    }
}
