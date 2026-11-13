// «Плёнка», проход 4: зерно поверх итогового кадра и вывод на экран / в кодер / в фото.
// Зерно — сглаженный value noise (мягкие зёрна, а не попиксельный цифровой шум), новое на каждом
// кадре. Размер зерна задан в долях короткой стороны, поэтому в фото оно такое же, как в превью.
precision highp float;

uniform sampler2D uImage;
uniform vec2 uFrameOrigin; // окно кадра: uv полного кадра = uFrameOrigin + vUv * uFrameScale
uniform vec2 uFrameScale;  // (в живом конвейере 0 и 1; в тайлах фото — положение тайла)
uniform vec2 uOutSize;    // размер полного кадра в пикселях
uniform float uShort;     // короткая сторона выхода в пикселях
uniform float uGrain;     // сила
uniform float uGrainSize; // 0..1
uniform int uFrame;

in vec2 vUv;
out vec4 fragColor;

float hash(ivec3 p) {
    uvec3 q = uvec3(p) * uvec3(1597334673u, 3812015801u, 2798796415u);
    uint n = (q.x ^ q.y ^ q.z) * 1597334673u;
    return float(n) * (1.0 / 4294967295.0);
}

float valueNoise(vec2 p, int seed) {
    ivec2 i = ivec2(floor(p));
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(ivec3(i, seed));
    float b = hash(ivec3(i + ivec2(1, 0), seed));
    float c = hash(ivec3(i + ivec2(0, 1), seed));
    float d = hash(ivec3(i + ivec2(1, 1), seed));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

void main() {
    vec3 c = texture(uImage, vUv).rgb;
    if (uGrain > 0.0) {
        float cell = uShort * mix(0.0007, 0.0030, uGrainSize);
        vec2 p = (uFrameOrigin + vUv * uFrameScale) * uOutSize / cell;
        int seed = uFrame * 8;
        float n = valueNoise(p, seed) * 0.65 + valueNoise(p * 2.03 + 17.0, seed + 1) * 0.35;
        vec3 chroma = vec3(
            valueNoise(p * 1.3 + 31.0, seed + 2),
            valueNoise(p * 1.3 + 57.0, seed + 3),
            valueNoise(p * 1.3 + 83.0, seed + 4)) - 0.5;
        vec3 g = vec3(n - 0.5) + chroma * 0.5;

        // Сильнее в средних тонах и тенях, слабее в светах
        float l = dot(c, vec3(0.299, 0.587, 0.114));
        float w = clamp(4.0 * l * (1.0 - l) + 0.6 * (1.0 - l) * (1.0 - l), 0.0, 1.0);
        c += g * uGrain * 0.22 * w;
    }
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
