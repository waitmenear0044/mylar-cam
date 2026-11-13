// «VHS», проход 5: буфер строк -> выход (экран/кодер, полный кадр или тайл фото).
// Между строками — плавный переход (без ступенек и квадратных пикселей), тёмные промежутки строк,
// поверх — оверлей камкордера («● REC», дата и время) в углах видимой части кадра.
// ЭЛТ (если включено): кривизна экрана и волна меняют точку выборки, цветные облака (анизотропный шум со сдвигом
// строк), маска люминофора в пикселях итогового кадра (размер ячейки — доля короткой стороны; слишком мелкая для
// выхода ячейка плавно исчезает — без муара на уменьшенном превью). При нулевых ползунках ЭЛТ результат прежний.
precision highp float;

uniform sampler2D uImage;   // буфер строк W x L (координаты q: x вправо, y вниз)
uniform mat4 uToQ;          // uv полного кадра в цели -> q
uniform vec2 uFrameOrigin;  // окно кадра: uv полного кадра = uFrameOrigin + vUv * uFrameScale
uniform vec2 uFrameScale;   // (в живом конвейере 0 и 1; в тайлах фото — положение тайла)
uniform vec2 uSize;         // W, L
uniform float uScan;        // видимость строк (уже ослаблена, если на строку мало пикселей)
uniform float uScanGain;    // компенсация яркости за тёмные промежутки
uniform sampler2D uRecTex;
uniform sampler2D uDateTex;
uniform vec4 uRecRect;      // прямоугольник в q (x0, y0, x1, y1); x0 > x1 — выключено
uniform vec4 uDateRect;

uniform float uCrt;         // 1 — какой-то из ползунков ЭЛТ включён
uniform vec2 uPortrait;     // итоговый портретный кадр в пикселях выхода
uniform float uMask;        // сила маски (уже ослаблена для мелкой ячейки)
uniform int uMaskType;      // 1 — точки, 2 — решётка, 3 — щели
uniform float uMaskCell;    // ширина тройки RGB в пикселях выхода
uniform float uCurve;
uniform float uClouds;
uniform float uCloudPhase;  // время * скорость
uniform float uWave;
uniform float uTime;

in vec2 vUv;
out vec4 fragColor;

vec3 overlay(vec3 c, vec2 q, sampler2D tex, vec4 r) {
    if (q.x < r.x || q.x > r.z || q.y < r.y || q.y > r.w) return c;
    vec4 o = texture(tex, (q - r.xy) / (r.zw - r.xy)); // цвет уже умножен на альфу
    return c * (1.0 - o.a) + o.rgb;
}

float hash(ivec3 p) {
    uvec3 h = uvec3(p) * uvec3(1597334673u, 3812015801u, 2798796415u);
    uint n = (h.x ^ h.y ^ h.z) * 1597334673u;
    return float(n) * (1.0 / 4294967295.0);
}

float vnoise(vec2 p, int seed) {
    ivec2 i = ivec2(floor(p));
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(ivec3(i, seed)), hash(ivec3(i + ivec2(1, 0), seed)), u.x),
               mix(hash(ivec3(i + ivec2(0, 1), seed)), hash(ivec3(i + ivec2(1, 1), seed)), u.x), u.y);
}

// Три полоски R, G, B в ячейке: x — положение в ячейках
vec3 stripes(float x) {
    vec3 d = abs(fract(x - vec3(1.0 / 6.0, 0.5, 5.0 / 6.0) + 0.5) - 0.5) * 3.0; // расстояние в долях полоски
    return clamp(1.25 - d * 1.25, 0.0, 1.0);
}

vec3 phosphorMask(vec2 px) {
    float cell = uMaskCell;
    vec2 g = px / cell;
    vec3 w;
    if (uMaskType == 1) {
        // Точки: ряды троек, через ряд сдвиг на половину ячейки; точка — пятно по высоте ряда
        float rowH = 0.75;
        float row = floor(g.y / rowH);
        float x = g.x + 0.5 * mod(row, 2.0);
        float fy = fract(g.y / rowH) - 0.5;
        w = stripes(x) * clamp(1.4 - abs(fy) * 3.2, 0.0, 1.0);
    } else if (uMaskType == 3) {
        // Щели: полоски с короткими разрывами, разрывы соседних троек сдвинуты
        float col = floor(g.x);
        float fy = fract(g.y * 0.6 + 0.5 * mod(col, 2.0));
        float gap = smoothstep(0.0, 0.12, fy) * (1.0 - smoothstep(0.88, 1.0, fy));
        w = stripes(g.x) * gap;
    } else {
        w = stripes(g.x);
    }
    const float lo = 0.3;
    vec3 m = lo + (1.0 - lo) * w;
    // Средняя яркость маски ~0.55: компенсация, чтобы картинка не темнела
    return m * 1.75;
}

void main() {
    vec2 p = uFrameOrigin + vUv * uFrameScale;
    vec2 q = (uToQ * vec4(p, 0.0, 1.0)).xy;

    vec2 qs = q;
    float frame = 1.0;
    if (uCrt > 0.5) {
        float aspect = uPortrait.x / uPortrait.y;
        vec2 d = q * 2.0 - 1.0;
        if (uCurve > 0.0) {
            // Бочка: точка выборки уходит к краю тем сильнее, чем дальше от центра (с учётом пропорций кадра)
            vec2 da = d * vec2(aspect, 1.0);
            float r2 = dot(da, da) / (aspect * aspect + 1.0);
            d *= 1.0 + uCurve * 0.22 * r2;
            // Скруглённые углы и тёмная кромка: расстояние до скруглённого прямоугольника в пикселях
            vec2 halfPx = 0.5 * uPortrait;
            float radius = uCurve * 0.08 * min(uPortrait.x, uPortrait.y);
            vec2 e = abs(d) * halfPx - (halfPx - radius);
            float sd = length(max(e, 0.0)) + min(max(e.x, e.y), 0.0) - radius;
            frame = 1.0 - smoothstep(-1.5, 0.5, sd);
            // Виньетка к краю стекла
            frame *= 1.0 - uCurve * 0.55 * pow(clamp(r2 * 1.1, 0.0, 1.0), 1.6);
        }
        if (uWave > 0.0) {
            // Волна по горизонтали, сильнее к краям (как у кинескопа с плохой геометрией), медленно плывёт
            float edge = 0.25 + 0.75 * d.x * d.x;
            d.x += uWave * 0.018 * edge * sin(q.y * 6.2831853 * 23.0 + uTime * 1.7);
            d.y += uWave * 0.006 * edge * sin(q.x * 6.2831853 * 7.0 - uTime * 1.1);
        }
        qs = d * 0.5 + 0.5;
    }

    // Между строками: сглаженная (кубическая) интерполяция вместо линейной
    float fy = qs.y * uSize.y - 0.5;
    float i = floor(fy);
    float f = fy - i;
    f = f * f * (3.0 - 2.0 * f);
    vec3 c = texture(uImage, vec2(qs.x, (i + 0.5 + f) / uSize.y)).rgb;

    float fl = fract(qs.y * uSize.y);
    c *= (1.0 - uScan * (0.5 + 0.5 * cos(6.2831853 * fl))) * uScanGain;

    if (uCrt > 0.5) {
        if (uClouds > 0.0) {
            // Облака: шум растянут по горизонтали, каждая строка чуть сдвинута по шуму
            int line = int(floor(qs.y * uSize.y));
            float shift = (vnoise(vec2(float(line) * 0.15, uCloudPhase * 3.0), 41) - 0.5) * 0.06;
            vec2 cq = vec2(qs.x * 1.6 + shift + uCloudPhase * 0.35, qs.y * 7.0 + uCloudPhase * 0.2);
            float n1 = vnoise(cq, 11);
            float n2 = vnoise(cq * vec2(1.3, 0.8) + 5.1, 12);
            float n3 = vnoise(cq * vec2(0.8, 1.2) + 9.7, 13);
            vec3 tint = vec3(0.55, 0.92, 0.98) * n1 + vec3(1.0, 0.96, 0.55) * n2 + vec3(0.96, 0.52, 0.85) * n3;
            tint /= max(n1 + n2 + n3, 1e-3);
            float a = smoothstep(0.35, 0.85, max(n1, max(n2, n3)));
            // Пастель: подъём в сторону цвета облака
            vec3 washed = mix(c, 1.0 - (1.0 - c) * (1.0 - tint * 0.75), 0.85);
            c = mix(c, washed, uClouds * mix(0.35, 1.0, a));
        }
        if (uMask > 0.0) {
            vec2 px = qs * uPortrait;
            c = mix(c, c * phosphorMask(px), uMask);
        }
        c *= frame;
    }

    c = overlay(c, q, uRecTex, uRecRect);
    c = overlay(c, q, uDateTex, uDateRect);
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
