// «Градиент»: яркость кадра -> цвет из карты градиента (LUT 256x1). Попиксельный, без соседей — в тайлах фото швов нет.
// Дизеринг случайным шумом по пикселям полного кадра убирает полосы на плавных переходах.
precision highp float;

uniform SAMPLER_TYPE uInput;
uniform mat4 uTexMatrix;    // uv полного кадра -> uv входной текстуры
uniform sampler2D uLut;
uniform vec2 uFrameOrigin;  // окно кадра: uv полного кадра = uFrameOrigin + vUv * uFrameScale
uniform vec2 uFrameScale;
uniform vec2 uFullSize;     // полный кадр в пикселях
uniform float uStrength;
uniform float uContrast;    // -1..1
uniform float uShift;       // 0..1, циклический
uniform float uInvert;
uniform int uFrame;

in vec2 vUv;
out vec4 fragColor;

float hash(ivec3 p) {
    uvec3 q = uvec3(p) * uvec3(1597334673u, 3812015801u, 2798796415u);
    uint n = (q.x ^ q.y ^ q.z) * 1597334673u;
    return float(n) * (1.0 / 4294967295.0);
}

void main() {
    vec2 p = uFrameOrigin + vUv * uFrameScale;
    vec3 c = texture(uInput, (uTexMatrix * vec4(p, 0.0, 1.0)).xy).rgb;
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    float k = uContrast >= 0.0 ? 1.0 + 3.0 * uContrast : 1.0 + 0.85 * uContrast;
    l = clamp((l - 0.5) * k + 0.5, 0.0, 1.0);
    if (uInvert > 0.5) l = 1.0 - l;

    float d = hash(ivec3(ivec2(p * uFullSize), uFrame)) - 0.5;
    float t = l + d * (1.5 / 255.0);
    t = uShift > 0.0 ? fract(t + uShift) : clamp(t, 0.0, 1.0);
    vec3 g = texture(uLut, vec2(t * (255.0 / 256.0) + 0.5 / 256.0, 0.5)).rgb + d / 255.0;
    fragColor = vec4(clamp(mix(c, g, uStrength), 0.0, 1.0), 1.0);
}
