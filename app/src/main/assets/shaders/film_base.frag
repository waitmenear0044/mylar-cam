// «Плёнка», проход 1: спектральное расщепление цвета (поперечная хроматическая аберрация).
// Вместо трёх жёстких копий R/G/B берём N выборок вдоль вектора смещения, каждую окрашиваем
// по «длине волны» (радужный вес) и нормируем по каналам: нейтральные места остаются нейтральными,
// а тёмный контур на светлом фоне получает плавную кайму — с одной стороны сине-фиолетовую,
// с другой жёлто-оранжевую (как на референсах).
// Загрузчик подставляет #version и SAMPLER_TYPE (камера или обычная текстура).
precision highp float;

uniform SAMPLER_TYPE uInput;
uniform mat4 uTexMatrix;
uniform vec2 uAspect;      // (ширина, высота) выхода в единицах короткой стороны
uniform vec2 uUniformDir;  // горизонталь итогового кадра в координатах выхода
uniform float uSplit;      // полный разброс спектра в единицах короткой стороны
uniform float uRadial;     // 0 = равномерный сдвиг, 1 = радиальный
uniform vec2 uFrameOrigin; // окно кадра: uv полного кадра = uFrameOrigin + vUv * uFrameScale
uniform vec2 uFrameScale;  // (в живом конвейере 0 и 1; в тайлах фото — положение тайла)
uniform int uSamples;      // считается в FilmRenderer: растёт с разбросом, чтобы шаг был ~1 пиксель

in vec2 vUv;
out vec4 fragColor;

vec3 sampleInput(vec2 uv) {
    return texture(uInput, (uTexMatrix * vec4(uv, 0.0, 1.0)).xy).rgb;
}

// t: 0 = фиолетовый/синий край спектра, 1 = красный
vec3 spectrum(float t) {
    vec3 w = vec3(
        1.0 - abs(t - 0.80) / 0.34,
        1.0 - abs(t - 0.50) / 0.30,
        1.0 - abs(t - 0.18) / 0.30);
    w = clamp(w, 0.0, 1.0);
    w.r += 0.3 * clamp(1.0 - t / 0.18, 0.0, 1.0); // фиолетовый хвост: немного красного у синего края
    return w;
}

void main() {
    vec2 uvFull = uFrameOrigin + vUv * uFrameScale;   // uv в полном кадре (uTexMatrix ждёт именно их)
    vec2 p = (uvFull - 0.5) * uAspect;               // от центра, в единицах короткой стороны
    vec2 shift = mix(uUniformDir * 0.5, p, uRadial) * uSplit;
    vec2 shiftUv = shift / uAspect;

    if (uSamples < 2 || uSplit <= 0.0) {
        fragColor = vec4(sampleInput(uvFull), 1.0);
        return;
    }
    // Стратифицированный сдвиг выборок на долю шага, свой у каждого пикселя: оставшиеся
    // промежутки между выборками превращаются в мелкий шум (его прячет зерно), а не в «призраков».
    float jitter = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
    vec3 acc = vec3(0.0);
    vec3 wsum = vec3(0.0);
    for (int i = 0; i < 64; i++) {
        if (i >= uSamples) break;
        float t = (float(i) + jitter) / float(uSamples);
        vec3 w = spectrum(t);
        acc += sampleInput(uvFull + shiftUv * (t - 0.5)) * w;
        wsum += w;
    }
    fragColor = vec4(acc / wsum, 1.0);
}
