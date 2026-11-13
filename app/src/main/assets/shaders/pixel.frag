// VHS-эффект, проход 1: рендер в маленький внутренний буфер (uLowSize) и цветокоррекция.
// Загрузчик подставляет перед файлом #version 300 es и SAMPLER_TYPE:
// samplerExternalOES для кадров камеры, sampler2D для готового видео (Media3) в будущем.
precision highp float;

uniform SAMPLER_TYPE uInput;
uniform mat4 uTexMatrix;   // поворот/кроп/зеркало кадра камеры (от CameraX + SurfaceTexture)
uniform vec2 uLowSize;     // размер внутреннего буфера в пикселях
uniform float uTime;       // секунды; пригодится для шума и дрожания на следующих этапах
uniform float uSaturation; // 0..1
uniform float uBlackLevel; // 0..0.3
uniform float uTint;       // -1 зелёный .. +1 тёплый

in vec2 vUv;
out vec4 fragColor;

vec3 sampleInput(vec2 uv) {
    return texture(uInput, (uTexMatrix * vec4(uv, 0.0, 1.0)).xy).rgb;
}

void main() {
    // Каждый крупный пиксель = среднее 4 точек внутри него (простое усреднение,
    // чтобы уменьшение не мерцало сильнее, чем на настоящей плёнке).
    vec2 q = 0.25 / uLowSize;
    vec3 c = sampleInput(vUv + vec2(-q.x, -q.y))
           + sampleInput(vUv + vec2( q.x, -q.y))
           + sampleInput(vUv + vec2(-q.x,  q.y))
           + sampleInput(vUv + vec2( q.x,  q.y));
    c *= 0.25;

    // Насыщенность
    float luma = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(vec3(luma), c, uSaturation);

    // Тёплый / зелёный сдвиг оттенка
    vec3 warm = vec3(1.08, 1.02, 0.86);
    vec3 green = vec3(0.94, 1.07, 0.92);
    c *= mix(vec3(1.0), uTint >= 0.0 ? warm : green, abs(uTint));

    // Выцветшие чёрные: поднимаем нижнюю границу диапазона
    c = uBlackLevel + c * (1.0 - uBlackLevel);

    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
