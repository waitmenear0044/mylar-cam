// «Cores» / Dark (даркор): тёмный бирюзово-синий туман. Приглушённый цвет, чёрные приподняты
// в сине-зелёный, туман сильнее к верху итогового кадра (дальнее светлее и холоднее), холодное
// свечение, диагональные штрихи размытия (направление в координатах итогового кадра), виньетка.
// Шум и зерно добавляют общие проходы (post, film_output).
// Загрузчик подставляет #version и SAMPLER_TYPE (камера или обычная текстура).
precision highp float;

uniform SAMPLER_TYPE uInput;
uniform mat4 uTexMatrix;
uniform vec2 uFrameOrigin;
uniform vec2 uFrameScale;
uniform sampler2D uSoft;
uniform sampler2D uWide;
uniform mat4 uToQ;          // uv полного кадра -> q
uniform mat4 uToP;          // q -> uv полного кадра
uniform float uAspectQ;

uniform float uTeal;
uniform float uDarken;
uniform float uLift;
uniform float uSaturation;
uniform float uFog;
uniform float uSoftness;
uniform float uVignette;
uniform float uGlow;
uniform vec2 uStreakDir;    // направление и длина штрихов в q (половина длины)
uniform float uStreakStrength;
uniform float uStreakOpacity;

in vec2 vUv;
out vec4 fragColor;

const vec3 LUMA = vec3(0.299, 0.587, 0.114);

float vignette(vec2 q) {
    vec2 d = (q - 0.5) * vec2(uAspectQ, 1.0);
    float r = length(d) / (0.5 * sqrt(uAspectQ * uAspectQ + 1.0));
    return smoothstep(0.25, 1.0, r);
}

void main() {
    vec2 uv = uFrameOrigin + vUv * uFrameScale;
    vec2 q = (uToQ * vec4(uv, 0.0, 1.0)).xy;
    vec3 c = texture(uInput, (uTexMatrix * vec4(uv, 0.0, 1.0)).xy).rgb;
    vec3 soft = texture(uSoft, uv).rgb;
    vec3 wide = texture(uWide, uv).rgb;

    c = mix(c, soft, uSoftness * 0.75);

    // Штрихи: среднее размытой копии вдоль направления (12 выборок), света тянут яркие полосы
    if (uStreakOpacity > 0.0 && dot(uStreakDir, uStreakDir) > 0.0) {
        vec3 acc = vec3(0.0);
        for (int i = 0; i < 12; i++) {
            float t = (float(i) + 0.5) / 12.0 * 2.0 - 1.0;
            vec2 p = (uToP * vec4(q + uStreakDir * t, 0.0, 1.0)).xy;
            acc += texture(uSoft, p).rgb;
        }
        acc /= 12.0;
        vec3 streak = acc + uStreakStrength * max(acc - 0.3, 0.0) * 2.5;
        c = mix(c, max(c, streak), uStreakOpacity * 0.7);
        c = mix(c, streak, uStreakOpacity * 0.45);
    }

    // Приглушённый цвет и бирюзово-синий оттенок
    float l = dot(c, LUMA);
    c = mix(vec3(l), c, uSaturation);
    c = mix(c, c * vec3(0.5, 0.92, 0.98) + vec3(0.0, 0.03, 0.05), uTeal);

    // Затемнение: тени и середина темнеют, света держатся
    l = dot(c, LUMA);
    c *= 1.0 - uDarken * 0.75 * (1.0 - 0.45 * l);

    // Приподнятые чёрные — в сине-зелёный
    c = c * (1.0 - uLift * 0.12) + uLift * vec3(0.02, 0.085, 0.095);

    // Туман: сильнее к верху кадра (дальнее) и в размытом, светлее и холоднее
    float depth = smoothstep(0.05, 0.95, 1.0 - q.y);
    float fogW = uFog * (0.3 + 0.7 * depth);
    vec3 fogColor = mix(vec3(0.16, 0.27, 0.31), vec3(0.52, 0.66, 0.68), clamp(dot(soft, LUMA) * 1.1, 0.0, 1.0));
    c = mix(c, fogColor, fogW * 0.55);

    // Холодное свечение светлых мест
    float g = clamp((dot(wide, LUMA) - 0.4) * 2.0, 0.0, 1.0);
    c = 1.0 - (1.0 - clamp(c, 0.0, 1.0)) * (1.0 - g * uGlow * vec3(0.65, 0.92, 1.0));

    c *= 1.0 - uVignette * 0.8 * vignette(q);
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
