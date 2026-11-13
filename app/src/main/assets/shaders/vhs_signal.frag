// «VHS», проход 1: кадр камеры -> буфер строк (W отсчётов x L строк) в YIQ.
// Буфер строк в координатах итогового портретного кадра q (x вправо, y вниз), поэтому всё
// «горизонтальное» идёт вдоль строк итогового кадра, а не сенсора. Здесь же — то, что в ntsc-rs
// делается до фильтров ленты: дрожание строк (edge wave), переключение головок внизу кадра,
// полоса трекинга и шум ленты. Шум добавляется до lowpass яркости и потом размазывается вдоль строки.
// Загрузчик подставляет #version и SAMPLER_TYPE (камера или обычная текстура).
precision highp float;

uniform SAMPLER_TYPE uInput;
uniform mat4 uTexMatrix;   // q -> uv входной текстуры
uniform vec2 uSize;        // W, L
uniform float uTime;       // секунды
uniform int uFrame;
uniform float uJitter;
uniform float uHeads;
uniform float uTracking;
uniform float uTapeNoise;

in vec2 vUv;
out vec4 fragColor;

float hash(ivec3 p) {
    uvec3 q = uvec3(p) * uvec3(1597334673u, 3812015801u, 2798796415u);
    uint n = (q.x ^ q.y ^ q.z) * 1597334673u;
    return float(n) * (1.0 / 4294967295.0);
}

// Одномерный плавный шум с «семенем» (a, b)
float vnoise(float x, int a, int b) {
    float i = floor(x);
    float f = x - i;
    f = f * f * (3.0 - 2.0 * f);
    return mix(hash(ivec3(int(i), a, b)), hash(ivec3(int(i) + 1, a, b)), f);
}

vec3 sampleQ(vec2 q) {
    // За краем строки — чёрное: так выглядят сбитые и сдвинутые строки на настоящей кассете
    if (q.x < 0.0 || q.x > 1.0) return vec3(0.0);
    return texture(uInput, (uTexMatrix * vec4(q, 0.0, 1.0)).xy).rgb;
}

void main() {
    float line = floor(vUv.y * uSize.y);
    int li = int(line);
    float y = (line + 0.5) / uSize.y;   // вся строка сдвигается целиком
    float shift = 0.0;                  // в долях ширины кадра, + вправо
    float extra = 0.0;                  // дополнительный шум (головки, трекинг)

    // Дрожание строк: медленное «плавание» (две октавы вдоль высоты, плывут во времени),
    // мелкий случайный сдвиг каждой строки и редкие всплески в полосе строк
    if (uJitter > 0.0) {
        float wave = (vnoise(y * 7.0 + uTime * 0.45, 11, 0) - 0.5) * 0.65
                   + (vnoise(y * 29.0 - uTime * 1.3, 12, 0) - 0.5) * 0.35;
        float perLine = hash(ivec3(li, uFrame, 13)) - 0.5;
        shift += uJitter * (wave * 0.011 + perLine * 0.0014);
        int slot = int(floor(uTime * 4.0));
        if (hash(ivec3(slot, 14, 1)) < 0.01 + 0.035 * uJitter) {
            float center = hash(ivec3(slot, 15, 1));
            float band = 1.0 - smoothstep(0.0, 0.12, abs(y - center));
            float env = sin(3.14159 * fract(uTime * 4.0));
            shift += uJitter * band * env * (vnoise(y * 70.0, slot, 16) - 0.5) * 0.05;
        }
    }

    // Головки: нижние 5–8% кадра — строки сбиты вправо всё сильнее к низу и шумят
    float hh = mix(0.05, 0.08, uHeads);
    float hs = y - (1.0 - hh);
    if (uHeads > 0.0 && hs > 0.0) {
        float k = hs / hh;
        float jit = hash(ivec3(li, uFrame, 21)) - 0.5;
        float frameAmp = 0.8 + 0.4 * hash(ivec3(uFrame, 22, 0));
        shift += uHeads * (0.075 * pow(k, 1.5) * frameAmp + 0.006 * jit);
        extra += uHeads * (0.25 + 0.6 * k);
    }

    // Трекинг: время от времени снизу вверх проходит полоса со сдвигом строк и «снегом»
    if (uTracking > 0.0) {
        float period = mix(16.0, 4.5, uTracking);
        float duration = 2.2;
        float tc = mod(uTime, period);
        int cycle = int(floor(uTime / period));
        if (tc < duration) {
            float yc = 1.15 - (tc / duration) * 1.3;
            float hb = mix(0.03, 0.075, uTracking);
            float w = 1.0 - smoothstep(0.0, hb, abs(y - yc));
            float wave = vnoise(y * 90.0 + uTime * 3.0, 31, cycle) - 0.5;
            shift += w * uTracking * (0.04 * wave + 0.012);
            extra += w * uTracking * 1.3;
        }
    }

    // Отсчёт строки: среднее 2x2 точек (уменьшение без мерцания), со сдвигом строки
    vec2 q = vec2(vUv.x - shift, vUv.y);
    vec2 d = 0.25 / uSize;
    vec3 c = (sampleQ(q + vec2(-d.x, -d.y)) + sampleQ(q + vec2(d.x, -d.y))
            + sampleQ(q + vec2(-d.x, d.y)) + sampleQ(q + vec2(d.x, d.y))) * 0.25;

    vec3 yiq = vec3(
        dot(c, vec3(0.299, 0.587, 0.114)),
        dot(c, vec3(0.596, -0.274, -0.322)),
        dot(c, vec3(0.211, -0.523, 0.312)));

    // Шум ленты: у каждой строки свой (и своя сила), сильнее в тенях; изредка яркая строка.
    // Период ~3 отсчёта: после lowpass яркости получаются короткие штрихи вдоль строки.
    if (uTapeNoise > 0.0 || extra > 0.0) {
        float n = vnoise(vUv.x * uSize.x / 3.0, li, uFrame) - 0.5;
        float lineGain = 0.4 + hash(ivec3(li, uFrame, 41));
        float streak = step(0.985, hash(ivec3(li, uFrame, 42))) * 2.0;
        float shadow = 0.35 + 1.4 * (1.0 - yiq.x) * (1.0 - yiq.x);
        yiq.x += n * (uTapeNoise * 0.16 * shadow * (lineGain + streak) + extra * 0.35);
        // «Снег» в полосах головок и трекинга: светлые крапинки, вытянутые вдоль строки
        float sp = hash(ivec3(int(vUv.x * uSize.x / 2.0), li, uFrame + 7));
        yiq.x += step(1.0 - 0.05 * extra, sp) * 0.6;
    }

    fragColor = vec4(clamp(yiq.x, 0.0, 1.0), clamp(yiq.yz * 0.8 + 0.5, 0.0, 1.0), 1.0);
}
