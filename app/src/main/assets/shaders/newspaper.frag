// «Газета»: полутоновая печать. Три вида: ЧБ-растр (одна сетка), цветная «розетка» CMYK (углы 15/75/0/45°),
// RGB-каналы (у каждого своя смещённая сетка). Тон для точки берётся в центре ячейки из сглаженной копии всего
// кадра (uTone с мип-уровнями) — точки стабильны и в тайлах фото не дают швов. Размеры — в долях короткой стороны
// кадра; края точек сглажены по размеру пикселя, поэтому муара нет ни в превью, ни в полном размере.
// Бумага — только цвет подложки.
precision highp float;

uniform sampler2D uTone;
uniform vec2 uFrameOrigin;
uniform vec2 uFrameScale;
uniform vec2 uFullSize;     // полный кадр в пикселях выхода
uniform int uKind;          // 0 — ЧБ, 1 — CMYK, 2 — RGB
uniform float uCell;        // размер ячейки, доля короткой стороны
uniform float uGain;        // контраст точек
uniform float uOffset;      // смещение каналов, доля ячейки
uniform float uAngle;       // угол сетки ЧБ, радианы
uniform vec3 uInk;          // цвет чёрной краски
uniform vec3 uPaper;        // цвет бумаги
uniform int uSeed;


in vec2 vUv;
out vec4 fragColor;

float hash(ivec3 p) {
    uvec3 q = uvec3(p) * uvec3(1597334673u, 3812015801u, 2798796415u);
    uint n = (q.x ^ q.y ^ q.z) * 1597334673u;
    return float(n) * (1.0 / 4294967295.0);
}

float vnoise(vec2 p, int seed) {
    ivec2 i = ivec2(floor(p));
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(ivec3(i, seed)), hash(ivec3(i + ivec2(1, 0), seed)), u.x),
               mix(hash(ivec3(i + ivec2(0, 1), seed)), hash(ivec3(i + ivec2(1, 1), seed)), u.x), u.y);
}

// Размер кадра и размер пикселя выхода в единицах короткой стороны
vec2 fullQ() {
    return uFullSize / min(uFullSize.x, uFullSize.y);
}

float pixelQ() {
    return 1.0 / min(uFullSize.x, uFullSize.y);
}

vec3 toneAt(vec2 q) {
    vec2 uv = clamp(q / fullQ(), 0.0, 1.0);
    float lod = log2(max(1.0, uCell * float(min(textureSize(uTone, 0).x, textureSize(uTone, 0).y)) * 0.8));
    return textureLod(uTone, uv, lod).rgb;
}

float contrast(float v) {
    return clamp((v - 0.5) * uGain + 0.5, 0.0, 1.0);
}

// Доля краски канала ch в тоне t: 0..2 — канал CMY или RGB, 3 — K, 4 — яркость
float inkOf(vec3 t, int ch) {
    if (ch == 4) return 1.0 - dot(t, vec3(0.299, 0.587, 0.114));
    float v = ch == 0 ? t.r : (ch == 1 ? t.g : t.b);
    if (uKind == 2) return v;
    float k = 1.0 - max(t.r, max(t.g, t.b));
    if (ch == 3) return k;
    return (1.0 - v - k) / max(1e-3, 1.0 - k);
}

// Покрытие краской в точке q для сетки под углом a со сдвигом shift; ch: 0..2 — канал CMY или RGB, 3 — K, 4 — яркость
float screen(vec2 q, float a, vec2 shift, int ch) {
    float ca = cos(a), sa = sin(a);
    mat2 rot = mat2(ca, -sa, sa, ca);
    mat2 inv = mat2(ca, sa, -sa, ca);
    vec2 g = rot * (q + shift) / uCell;
    vec2 base = floor(g);
    float aa = 0.75 * pixelQ() / uCell;
    float cover = 0.0;
    // Соседние ячейки тоже: крупные точки в тенях заходят в чужие ячейки и сливаются
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            vec2 cell = base + vec2(float(i), float(j));
            vec2 center = inv * ((cell + 0.5) * uCell) - shift;
            float v = inkOf(toneAt(center), ch);
            v = contrast(v);
            // Площадь круга равна доле краски: r = sqrt(v / pi), при v > 0.78 точки сливаются
            float r = sqrt(v * 0.3183) * 1.05;
            float d = length(g - (cell + 0.5));
            cover = max(cover, 1.0 - smoothstep(r - aa, r + aa, d));
        }
    }
    // Совсем мелкая ячейка (меньше ~2.5 пикселей): вместо точек — ровный тон, без муара
    float cellPx = uCell / pixelQ();
    if (cellPx < 3.0) {
        cover = mix(contrast(inkOf(toneAt(q), ch)), cover, smoothstep(1.5, 3.0, cellPx));
    }
    return cover;
}

void main() {
    vec2 p = uFrameOrigin + vUv * uFrameScale;
    vec2 gFull = fullQ();
    float gPixel = pixelQ();
    vec2 q = p * gFull;
    float off = uOffset * uCell;

    vec3 paper = uKind == 2 ? vec3(0.04) : uPaper;
    vec3 c;
    if (uKind == 0) {
        c = mix(paper, uInk, screen(q, uAngle, vec2(off * 0.5, 0.0), 4));
    } else if (uKind == 1) {
        c = paper;
        c *= mix(vec3(1.0), vec3(0.0, 0.62, 0.89), screen(q, 0.2618, vec2(off, 0.0), 0));
        c *= mix(vec3(1.0), vec3(0.89, 0.0, 0.50), screen(q, 1.3090, vec2(-off * 0.6, off * 0.8), 1));
        c *= mix(vec3(1.0), vec3(1.0, 0.92, 0.0), screen(q, 0.0, vec2(0.0, -off), 2));
        c *= mix(vec3(1.0), uInk, screen(q, 0.7854, vec2(0.0), 3));
    } else {
        // Каналы светятся на тёмной подложке: у каждого своя сетка со своим смещением (минимум — треть ячейки)
        float o = uCell * 0.33 + off;
        c = paper;
        c.r += screen(q, uAngle, vec2(o, 0.0), 0);
        c.g += screen(q, uAngle, vec2(-o * 0.5, o * 0.87), 1);
        c.b += screen(q, uAngle, vec2(-o * 0.5, -o * 0.87), 2);
    }

    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
