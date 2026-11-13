// «Выбор цвета»: до трёх диапазонов оттенка. Выбранное остаётся цветным, остальное ЧБ — или наоборот.
// ЧБ-часть проходит через кривую (LUT 256x1). Попиксельный — в тайлах фото швов нет.
precision highp float;

uniform SAMPLER_TYPE uInput;
uniform mat4 uTexMatrix;
uniform sampler2D uCurve;
uniform vec2 uFrameOrigin;
uniform vec2 uFrameScale;
uniform vec3 uHue;      // оттенки слотов, градусы
uniform vec3 uWidth;    // полуширина диапазона, градусы
uniform vec3 uFeather;  // мягкость границы 0..1 (доля ширины)
uniform vec3 uOn;       // слот включён
uniform float uSatMin;  // ниже этой насыщенности цвет не считается выбранным
uniform float uKeep;    // 1 — выбранный цвет остаётся, остальное ЧБ; 0 — выбранный становится ЧБ

in vec2 vUv;
out vec4 fragColor;

vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

float slot(float hue, float center, float width, float feather) {
    float d = abs(mod(hue - center + 540.0, 360.0) - 180.0);
    return 1.0 - smoothstep(width, width + feather * width + 1.0, d);
}

void main() {
    vec2 p = uFrameOrigin + vUv * uFrameScale;
    vec3 c = texture(uInput, (uTexMatrix * vec4(p, 0.0, 1.0)).xy).rgb;
    vec3 hsv = rgb2hsv(c);
    float hue = hsv.x * 360.0;
    float m = 0.0;
    m = max(m, uOn.x * slot(hue, uHue.x, uWidth.x, uFeather.x));
    m = max(m, uOn.y * slot(hue, uHue.y, uWidth.y, uFeather.y));
    m = max(m, uOn.z * slot(hue, uHue.z, uWidth.z, uFeather.z));
    // У серого и почти чёрного оттенок случайный — такие пиксели выбранными не считаем
    m *= smoothstep(uSatMin * 0.7, uSatMin + 0.06, hsv.y) * smoothstep(0.03, 0.1, hsv.z);

    float l = dot(c, vec3(0.299, 0.587, 0.114));
    float g = texture(uCurve, vec2(l * (255.0 / 256.0) + 0.5 / 256.0, 0.5)).r;
    float colorPart = uKeep > 0.5 ? m : 1.0 - m;
    fragColor = vec4(mix(vec3(g), c, colorPart), 1.0);
}
