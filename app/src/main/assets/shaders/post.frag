// Финальный проход (общий для режимов): обрезка полного кадра 3:4 до выбранного формата
// и жёсткий цифровой шум. Эффект режима уже посчитан в координатах полного кадра.
// Размер шума задан в долях короткой стороны полного кадра — в фото и превью одинаковый.
precision highp float;

uniform sampler2D uImage;   // кадр режима в полном формате
uniform mat4 uCrop;         // uv выхода -> uv полного кадра
uniform vec2 uFrameOrigin; // окно кадра: uv полного кадра = uFrameOrigin + vUv * uFrameScale
uniform vec2 uFrameScale;  // (в живом конвейере 0 и 1; в тайлах фото — положение тайла)
uniform vec2 uOutSize;      // размер выхода (в тайлах — полного кадра) в пикселях
uniform float uFullShort;   // короткая сторона полного кадра в пикселях выхода
uniform float uLuma;        // яркостный шум 0..1
uniform float uChroma;      // цветной шум 0..1
uniform float uSize;        // крупность 0..1
uniform float uShadows;     // насколько сильнее в тенях 0..1
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
    return mix(mix(hash(ivec3(i, seed)), hash(ivec3(i + ivec2(1, 0), seed)), u.x),
               mix(hash(ivec3(i + ivec2(0, 1), seed)), hash(ivec3(i + ivec2(1, 1), seed)), u.x), u.y);
}

void main() {
    vec2 fullUv = (uCrop * vec4(vUv, 0.0, 1.0)).xy;
    vec3 c = texture(uImage, fullUv).rgb;

    if (uLuma > 0.0 || uChroma > 0.0) {
        // Ячейка шума: жёсткая (без сглаживания), как у матрицы, от ~0.1% до ~0.6% короткой стороны
        float cell = max(1.0, uFullShort * mix(0.001, 0.006, uSize));
        vec2 uvNoise = uFrameOrigin + vUv * uFrameScale;
        ivec2 cellId = ivec2(floor(uvNoise * uOutSize / cell));
        int seed = uFrame * 8;

        float l = dot(c, vec3(0.299, 0.587, 0.114));
        float w = mix(1.0, 2.2 * (1.0 - l) * (1.0 - l) + 0.15, uShadows);

        // Яркостный: сумма двух хешей — распределение ближе к гауссову
        float n = hash(ivec3(cellId, seed)) + hash(ivec3(cellId, seed + 1)) - 1.0;
        c += n * uLuma * 0.35 * w;

        // Цветной: крупные плавающие пятна + цветные точки, малиново-зелёные
        vec2 p = uvNoise * uOutSize / (cell * 7.0);
        float blobA = valueNoise(p, seed + 2) - 0.5;
        float blobB = valueNoise(p * 1.7 + 11.0, seed + 3) - 0.5;
        float dotA = hash(ivec3(cellId, seed + 4)) - 0.5;
        float dotB = hash(ivec3(cellId, seed + 5)) - 0.5;
        float a = blobA * 0.8 + dotA * 0.6;
        float b = blobB * 0.8 + dotB * 0.6;
        vec3 chroma = vec3(a + 0.3 * b, -0.55 * a - 0.35 * b, b - 0.2 * a);
        c += chroma * uChroma * 0.35 * w;
    }
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
