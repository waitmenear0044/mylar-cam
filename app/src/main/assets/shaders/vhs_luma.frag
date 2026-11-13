// «VHS», проход 2 (буфер строк): яркость после ленты — горизонтальный lowpass (полоса яркости
// VHS ~2–3 МГц), «звон» от подчёркивания резкости с перерегулированием (светлые ореолы у контуров),
// блочность сжатия (для «кнопочного телефона») и дропауты — короткие светлые чёрточки.
precision highp float;

uniform sampler2D uSignal;  // Y, I, Q (I и Q со сдвигом 0.5)
uniform vec2 uSize;         // W, L
uniform float uSigma;       // радиус lowpass яркости в отсчётах
uniform float uRing;
uniform float uBlock;
uniform vec2 uBlockSize;    // размер блока в отсчётах (x) и строках (y)
uniform float uDropRate;    // вероятность дропаута на строку в кадре
uniform int uFrame;

in vec2 vUv;
out vec4 fragColor;

float hash(ivec3 p) {
    uvec3 q = uvec3(p) * uvec3(1597334673u, 3812015801u, 2798796415u);
    uint n = (q.x ^ q.y ^ q.z) * 1597334673u;
    return float(n) * (1.0 / 4294967295.0);
}

void main() {
    float texel = 1.0 / uSize.x;
    // Узкий (lowpass) и широкий гауссианы за один обход: разница даёт перерегулирование
    float s = max(uSigma, 0.35);
    float sw = s * 2.6;
    float stepPx = sw * 2.5 / 12.0;
    float lp = 0.0, wl = 0.0, wide = 0.0, ww = 0.0;
    for (int i = -12; i <= 12; i++) {
        float o = float(i) * stepPx;
        float v = texture(uSignal, vUv + vec2(o * texel, 0.0)).r;
        float g1 = exp(-0.5 * o * o / (s * s));
        float g2 = exp(-0.5 * o * o / (sw * sw));
        lp += v * g1; wl += g1;
        wide += v * g2; ww += g2;
    }
    lp /= wl;
    wide /= ww;
    float diff = lp - wide;
    float y = lp + uRing * (diff > 0.0 ? 1.5 : 0.8) * diff;

    // Блочность: в каждом блоке среднее (DC) квантуется, детали внутри блока приглушаются
    if (uBlock > 0.0) {
        vec2 cell = floor(vUv * uSize / uBlockSize);
        vec2 c0 = (cell + 0.5) * uBlockSize / uSize;
        float mean = 0.0;
        for (int j = 0; j < 4; j++) {
            for (int i = 0; i < 4; i++) {
                vec2 o = (vec2(float(i), float(j)) - 1.5) * 0.25 * uBlockSize / uSize;
                mean += texture(uSignal, c0 + o).r;
            }
        }
        mean /= 16.0;
        float levels = mix(64.0, 16.0, uBlock);
        float dc = floor(mean * levels + 0.5) / levels;
        y = mix(y, dc + (y - mean) * 0.5, uBlock);
    }

    // Дропауты: строка (и иногда следующая) получает светлую чёрточку 2–11% ширины
    float drop = 0.0;
    if (uDropRate > 0.0) {
        int line = int(floor(vUv.y * uSize.y));
        for (int k = 0; k < 2; k++) {
            int lj = line - k;
            if (hash(ivec3(lj, uFrame, 51)) >= uDropRate) continue;
            if (k == 1 && hash(ivec3(lj, uFrame, 52)) > 0.45) continue;
            float x0 = hash(ivec3(lj, uFrame, 53)) * 1.05 - 0.05;
            float len = mix(0.02, 0.11, hash(ivec3(lj, uFrame, 54)));
            float t = (vUv.x - x0) / len;
            if (t > 0.0 && t < 1.0) {
                float prof = smoothstep(0.0, 0.06, t) * (1.0 - smoothstep(0.6, 1.0, t));
                drop = max(drop, prof * (0.7 + 0.3 * hash(ivec3(lj, uFrame, 55))));
            }
        }
        float sparkle = hash(ivec3(int(vUv.x * uSize.x), line, uFrame + 3));
        y = mix(y, 0.88 + 0.12 * sparkle, drop);
    }

    fragColor = vec4(clamp(y, 0.0, 1.0), 0.0, 0.0, drop);
}
