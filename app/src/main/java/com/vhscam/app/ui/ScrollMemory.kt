package com.vhscam.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/**
 * Положение прокрутки списков выбора на время жизни процесса: ключ — список и режим (и слой редактора, см.
 * [LocalScrollScope]). Повторно открытый список (после закрытия окна, сворачивания, смены режима или экрана)
 * оказывается там же; список, которого ещё не было, при открытии прокручивается к выбранному элементу.
 */
object ScrollMemory {
    private val lazy = HashMap<String, Pair<Int, Int>>()
    private val plain = HashMap<String, Int>()

    fun lazyPosition(key: String): Pair<Int, Int>? = lazy[key]
    fun saveLazy(key: String, index: Int, offset: Int) { lazy[key] = index to offset }
    fun plainPosition(key: String): Int? = plain[key]
    fun savePlain(key: String, value: Int) { plain[key] = value }
}

/** Приставка ключей прокрутки: в редакторе — id слоя, в камере — пусто. */
val LocalScrollScope = compositionLocalOf { "" }

/**
 * Состояние ленивого списка, которое помнит прокрутку под ключом [key]. Если списка ещё не было, при первом показе
 * он прокручивается так, чтобы элемент [selectedIndex] оказался примерно по центру (без анимации — без «прыжка»).
 */
@Composable
fun rememberRememberedListState(key: String, selectedIndex: Int, centerOffsetPx: Int = 0): LazyListState {
    val fullKey = LocalScrollScope.current + "|" + key
    val saved = remember(fullKey) { ScrollMemory.lazyPosition(fullKey) }
    val state = remember(fullKey) {
        if (saved != null) LazyListState(saved.first, saved.second) else LazyListState((selectedIndex).coerceAtLeast(0), -centerOffsetPx)
    }
    LaunchedEffect(state, fullKey) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (i, o) -> ScrollMemory.saveLazy(fullKey, i, o) }
    }
    return state
}

/**
 * [ScrollState] обычной прокрутки (ряд значков), которая помнит положение под ключом [key]. [initial] вызывается,
 * если списка ещё не было: возвращает положение, при котором выбранный элемент виден (или null — с начала).
 */
@Composable
fun rememberRememberedScroll(key: String, initial: () -> Int? = { null }): ScrollState {
    val fullKey = LocalScrollScope.current + "|" + key
    val known = remember(fullKey) { ScrollMemory.plainPosition(fullKey) }
    val state = remember(fullKey) { ScrollState(known ?: 0) }
    LaunchedEffect(state, fullKey) {
        if (known == null) initial()?.let { target ->
            // Размер содержимого становится известен после первой раскладки (если всё помещается — прокрутки нет)
            val max = snapshotFlow { state.maxValue }.first { it in 1 until Int.MAX_VALUE }
            state.scrollTo(target.coerceIn(0, max))
        }
    }
    LaunchedEffect(state, fullKey) { snapshotFlow { state.value }.collect { ScrollMemory.savePlain(fullKey, it) } }
    DisposableEffect(fullKey) { onDispose { ScrollMemory.savePlain(fullKey, state.value) } }
    return state
}
