// «VHS», проход 3: цвет после ленты в узком буфере (W/4 x L). Полоса цвета VHS в 7–10 раз уже
// яркости: широкий горизонтальный гауссиан; цвет запаздывает (сдвиг вправо); поверх —
// низкочастотные цветные пятна шума ленты, вытянутые вдоль строк.
precision highp float;

uniform sampler2D uSignal;  // Y, I, Q (I и Q со сдвигом 0.5), размер W x L
uniform vec2 uSrcSize;      // W, L
uniform float uSigma;       // радиус размытия цвета в отсчётах W
uniform float uShift;       // сдвиг цвета вправо, доля ширины
uniform float uNoise;       // шум ленты
uniform int uFrame;

in vec2 vUv;
out vec4 fragColor;

float hash(ivec3 p) {
    uvec3 q = uvec3(p) * uvec3(1597334673u, 3812015801u, 2798796415u);
    uint n = (q.x ^ q.y ^ q.z) * 1597334673u;
    return float(n) * (1.0 / 4294967295.0);
}

float vnoise2(vec2 p, int seed) {
    ivec2 i = ivec2(floor(p));
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(ivec3(i, seed)), hash(ivec3(i + ivec2(1, 0), seed)), u.x),
               mix(hash(ivec3(i + ivec2(0, 1), seed)), hash(ivec3(i + ivec2(1, 1), seed)), u.x), u.y);
}

void main() {
    float texel = 1.0 / uSrcSize.x;
    float s = max(uSigma, 0.5);
    float stepPx = s * 0.25;   // 21 выборка на ±2.5 радиуса
    vec2 acc = vec2(0.0);
    float wsum = 0.0;
    for (int i = -10; i <= 10; i++) {
        float g = exp(-0.5 * float(i * i) / 16.0);
        acc += (texture(uSignal, vec2(vUv.x - uShift + float(i) * stepPx * texel, vUv.y)).gb - 0.5) * g;
        wsum += g;
    }
    vec2 iq = acc / wsum / 0.8;

    if (uNoise > 0.0) {
        float line = floor(vUv.y * uSrcSize.y);
        vec2 p = vec2(vUv.x * 9.0, line * 0.35);
        int seed = uFrame * 4;
        iq += (vec2(vnoise2(p, seed), vnoise2(p + 17.0, seed + 1)) - 0.5) * uNoise * 0.14;
    }
    fragColor = vec4(clamp(iq * 0.8 + 0.5, 0.0, 1.0), 0.0, 1.0);
}
