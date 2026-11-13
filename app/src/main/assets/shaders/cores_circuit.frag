// «Cores» / Circuit (circuit bend): нарочито «сломанный» тракт. Жёсткая постеризация и пороги,
// психоделическая палитра по яркости (фиолетовый, маджента, оранжевый, лайм, циан), соляризация,
// инверсия полос, смена каналов, дизеринг точками вместо градиентов, тёмное — в тёмно-синий,
// плюс цифровой сбой всплесками: сдвиг блоков и залипание строк. Палитра и узор точек медленно
// «ползут» во времени. Всё, что зависит от координат, — в координатах итогового кадра q:
// у фото тайлы стыкуются без швов, а результат детерминирован (время и всплеск — на момент съёмки).
// Загрузчик подставляет #version и SAMPLER_TYPE (камера или обычная текстура).
precision highp float;

uniform SAMPLER_TYPE uInput;
uniform mat4 uTexQ;         // q -> uv входной текстуры
uniform mat4 uToQ;          // uv полного кадра -> q
uniform vec2 uFrameOrigin;
uniform vec2 uFrameScale;
uniform vec2 uPortrait;     // размер итогового кадра в пикселях (ширина, высота)
uniform float uTime;

uniform float uLevels;      // 2..16
uniform float uShift;       // поворот палитры 0..1
uniform float uSolarize;
uniform float uPalette;
uniform float uDither;
uniform float uDotSize;     // 0..1
uniform int uDitherType;    // 0 — Байер, 1 — «диффузия ошибки» (IGN)
uniform float uThreshold;
uniform float uContrast;
uniform float uBandInvert;
uniform float uSwap;
uniform float uBlock;
uniform float uLineStick;
uniform float uSpeed;
uniform float uBurst;       // 1 — сейчас всплеск сбоя (решает CPU по времени)
uniform int uBurstSeed;

in vec2 vUv;
out vec4 fragColor;

const vec3 LUMA = vec3(0.299, 0.587, 0.114);
const vec3 NAVY = vec3(0.03, 0.02, 0.2);

float hash(ivec3 p) {
    uvec3 q = uvec3(p) * uvec3(1597334673u, 3812015801u, 2798796415u);
    uint n = (q.x ^ q.y ^ q.z) * 1597334673u;
    return float(n) * (1.0 / 4294967295.0);
}

// Упорядоченный дизеринг Байера 8x8 (значения 0..1)
float bayer2(vec2 a) { a = floor(a); return fract(dot(a, vec2(0.5, a.y * 0.75))); }
float bayer4(vec2 a) { return bayer2(0.5 * a) * 0.25 + bayer2(a); }
float bayer8(vec2 a) { return bayer4(0.5 * a) * 0.25 + bayer2(a); }

// Interleaved gradient noise: россыпь точек, похожая на диффузию ошибки
float ign(vec2 p) { return fract(52.9829189 * fract(dot(floor(p), vec2(0.06711056, 0.00583715)))); }

vec3 paletteColor(int i) {
    if (i == 0) return vec3(0.45, 0.10, 0.95);   // фиолетовый
    if (i == 1) return vec3(1.00, 0.10, 0.85);   // маджента
    if (i == 2) return vec3(1.00, 0.50, 0.05);   // оранжевый
    if (i == 3) return vec3(0.60, 1.00, 0.10);   // лайм
    return vec3(0.05, 0.90, 1.00);               // циан
}

// Оттенок 0..1 (для разведения разноцветных объектов по разным цветам палитры)
float hueOf(vec3 c) {
    float mx = max(c.r, max(c.g, c.b));
    float mn = min(c.r, min(c.g, c.b));
    float d = mx - mn;
    if (d < 1e-4) return 0.0;
    float h = mx == c.r ? mod((c.g - c.b) / d, 6.0) : (mx == c.g ? (c.b - c.r) / d + 2.0 : (c.r - c.g) / d + 4.0);
    return h / 6.0;
}

// Циклическая палитра: почти плоские полосы с узким переходом
vec3 palette(float t) {
    float x = fract(t) * 5.0;
    float i = floor(x);
    float f = smoothstep(0.82, 1.0, x - i);
    return mix(paletteColor(int(i)), paletteColor(int(mod(i + 1.0, 5.0))), f);
}

void main() {
    vec2 uv = uFrameOrigin + vUv * uFrameScale;
    vec2 q = (uToQ * vec4(uv, 0.0, 1.0)).xy;

    // Сбой (только во время всплеска): залипание строк — полоса повторяет свою первую строку;
    // сдвиг блоков — прямоугольники съезжают по горизонтали итогового кадра
    if (uBurst > 0.0) {
        float bandH = 0.035;
        float band = floor(q.y / bandH);
        if (hash(ivec3(int(band), uBurstSeed, 1)) < uLineStick * 0.45) q.y = band * bandH;
        vec2 cell = floor(q / vec2(0.12, 0.05));
        if (hash(ivec3(ivec2(cell), uBurstSeed * 7 + 2)) < uBlock * 0.5) {
            q.x += (hash(ivec3(ivec2(cell), uBurstSeed * 7 + 3)) - 0.5) * 0.2 * uBlock;
        }
    }
    vec3 c = texture(uInput, (uTexQ * vec4(q, 0.0, 1.0)).xy).rgb;

    c = clamp((c - 0.5) * (1.0 + uContrast * 2.0) + 0.5, 0.0, 1.0);
    c = mix(c, c.brg, uSwap);
    c = mix(c, 1.0 - abs(1.0 - 2.0 * c), uSolarize);
    float l = dot(c, LUMA);
    // Насыщенные места получают цвет палитры и по своему оттенку, серые — только по яркости
    float sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
    float hueTerm = hueOf(c) * smoothstep(0.05, 0.25, sat);

    // Дизеринг в пикселях итогового кадра; узор медленно ползёт во времени
    float shortSide = min(uPortrait.x, uPortrait.y);
    float cellPx = max(1.0, shortSide * mix(0.0012, 0.008, uDotSize));
    vec2 dp = floor(q * uPortrait / cellPx) + floor(uTime * uSpeed * 3.0);
    float d = (uDitherType == 0 ? bayer8(dp) : ign(dp)) - 0.5;
    float dd = d * uDither;

    // Постеризация и палитра по яркости
    float steps = max(uLevels - 1.0, 1.0);
    vec3 post = floor(c * steps + 0.5 + dd) / steps;
    float band = floor(l * steps + 0.5 + dd);
    float lq = band / steps;
    vec3 pal = palette(lq * 1.6 + hueTerm * 0.8 + uShift + uTime * uSpeed * 0.05);
    c = mix(post, pal * (0.75 + 0.35 * lq), uPalette);

    // Инверсия каждой второй ступеньки яркости
    if (mod(band, 2.0) > 0.5) c = mix(c, 1.0 - c, uBandInvert);

    // Тёмное схлопывается в тёмно-синий (с дизерингом по краю порога)
    float dark = 1.0 - smoothstep(uThreshold - 0.02, uThreshold + 0.02, l + dd * 0.15);
    c = mix(c, NAVY, dark * step(0.001, uThreshold));

    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
