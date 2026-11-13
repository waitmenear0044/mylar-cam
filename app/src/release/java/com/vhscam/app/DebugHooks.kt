package com.vhscam.app

import android.content.Intent

/** В release отладочных подмен нет (см. src/debug). */
object DebugHooks {
    val orientationOverride: Int? get() = null

    /** Отладочный источник движения (только debug). */
    val motion: Boolean get() = false

    /** Отладочное открытие редактора (только debug). */
    val editUri: String? get() = null
    val editRequest: Int get() = 0

    @Suppress("UNUSED_PARAMETER")
    fun read(intent: Intent?) = Unit
}
