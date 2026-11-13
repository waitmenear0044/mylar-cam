package com.vhscam.app.gl

import android.content.res.AssetManager
import android.opengl.GLES30

/** Шейдерная программа из файлов в assets/shaders. Строку #version и define'ы добавляет [header]. */
class GlProgram(assets: AssetManager, vertexFile: String, fragmentFile: String, header: String) {
    val id: Int
    private val uniforms = HashMap<String, Int>()

    init {
        val vsSource = BASE_HEADER + assets.readShader(vertexFile)
        val fsSource = header + assets.readShader(fragmentFile)
        val key = vsSource + "\u0000" + fsSource
        id = fromCache(key) ?: link(vsSource, fsSource, vertexFile, fragmentFile).also { store(key, it) }
    }

    private fun link(vsSource: String, fsSource: String, vertexFile: String, fragmentFile: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vsSource, vertexFile)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fsSource, fragmentFile)
        val id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vs)
        GLES30.glAttachShader(id, fs)
        GLES30.glLinkProgram(id)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        val status = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(id)
            GLES30.glDeleteProgram(id)
            error("Link failed ($vertexFile + $fragmentFile): $log")
        }
        return id
    }

    fun use() = GLES30.glUseProgram(id)

    /** -1, если uniform не используется в шейдере (компилятор его выбросил); GL такие вызовы игнорирует. */
    fun uniform(name: String): Int = uniforms.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }

    fun release() = GLES30.glDeleteProgram(id)

    private fun compile(type: Int, source: String, name: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            error("Compile failed ($name): $log")
        }
        return shader
    }

    companion object {
        /**
         * Готовые бинарники программ (glGetProgramBinary) на время жизни процесса: каждый новый GL-контекст
         * (сохранение фото, миниатюры пресетов, справка, экспорт видео) не компилирует шейдеры заново. Если драйвер
         * бинарники не поддерживает или отверг (другой GPU-контекст, обновление драйвера), — обычная компиляция.
         */
        private val binaries = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, ByteArray>>()

        private fun fromCache(key: String): Int? {
            val (format, bytes) = binaries[key] ?: return null
            val id = GLES30.glCreateProgram()
            val buffer = java.nio.ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { position(0) }
            GLES30.glProgramBinary(id, format, buffer, bytes.size)
            val status = IntArray(1)
            GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] != 0) return id
            GLES30.glDeleteProgram(id)
            binaries.remove(key)
            return null
        }

        private fun store(key: String, id: Int) {
            runCatching {
                val length = IntArray(1)
                GLES30.glGetProgramiv(id, GLES30.GL_PROGRAM_BINARY_LENGTH, length, 0)
                if (length[0] <= 0) return
                val buffer = java.nio.ByteBuffer.allocateDirect(length[0])
                val format = IntArray(1)
                GLES30.glGetProgramBinary(id, length[0], length, 0, format, 0, buffer)
                if (GLES30.glGetError() != GLES30.GL_NO_ERROR || length[0] <= 0) return
                val bytes = ByteArray(length[0])
                buffer.position(0)
                buffer.get(bytes)
                binaries[key] = format[0] to bytes
            }
        }

        const val BASE_HEADER = "#version 300 es\n"

        /** Вход — кадр камеры из SurfaceTexture. */
        const val OES_HEADER = BASE_HEADER +
            "#extension GL_OES_EGL_image_external_essl3 : require\n" +
            "#define SAMPLER_TYPE samplerExternalOES\n"

        /** Вход — обычная текстура (фото из ImageCapture, в будущем кадры готового видео). */
        const val TEXTURE_2D_HEADER = BASE_HEADER + "#define SAMPLER_TYPE sampler2D\n"

        /** Текст шейдера; строки `#include "файл"` заменяются содержимым файла из той же папки. */
        private fun AssetManager.readShader(file: String): String =
            open("shaders/$file").bufferedReader().use { it.readText() }.lines().joinToString("\n") { line ->
                val include = INCLUDE.matchEntire(line.trim())?.groupValues?.get(1)
                if (include != null) readShader(include) else line
            }

        private val INCLUDE = Regex("""#include\s+"([^"]+)"""")
    }
}
