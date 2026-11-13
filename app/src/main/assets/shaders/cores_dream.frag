// «Cores» / Dream (дримкор): молочная пастель, раздельная тонировка (тени сине-фиолетовые, света
// розово-оранжевые, в светлом небе — зелёно-фиолетовая игра), низкий контраст, дымка, мягкость,
// свечение, лёгкая постеризация, слабая виньетка. Зерно добавляет следующий проход (film_output).
// Загрузчик подставляет #version и SAMPLER_TYPE (камера или обычная текстура).
precision highp float;

uniform SAMPLER_TYPE uInput;
uniform mat4 uTexMatrix;    // uv полного кадра -> uv входной текстуры
uniform vec2 uFrameOrigin;  // окно кадра: uv полного кадра = uFrameOrigin + vUv * uFrameScale
uniform vec2 uFrameScale;
uniform sampler2D uSoft;    // размытая копия всего кадра (1/4), небольшое размытие
uniform sampler2D uWide;    // она же, сильно размытая
uniform mat4 uToQ;          // uv полного кадра -> q (итоговый портретный кадр, y вниз)
uniform float uAspectQ;     // ширина/высота итогового кадра

uniform float uPastel;
uniform float uShadowTone;
uniform float uHighlightTone;
uniform float uContrast;
uniform float uHaze;
uniform float uSoftness;
uniform float uGlow;
uniform float uVignette;
uniform float uPosterize;

in vec2 vUv;
out vec4 fragColor;

const vec3 LUMA = vec3(0.299, 0.587, 0.114);

float vignette(vec2 q) {
    vec2 d = (q - 0.5) * vec2(uAspectQ, 1.0);
    float r = length(d) / (0.5 * sqrt(uAspectQ * uAspectQ + 1.0));
    return smoothstep(0.35, 1.0, r);
}

void main() {
    vec2 uv = uFrameOrigin + vUv * uFrameScale;
    vec2 q = (uToQ * vec4(uv, 0.0, 1.0)).xy;
    vec3 c = texture(uInput, (uTexMatrix * vec4(uv, 0.0, 1.0)).xy).rgb;
    vec3 soft = texture(uSoft, uv).rgb;
    vec3 wide = texture(uWide, uv).rgb;

    c = mix(c, soft, uSoftness * 0.8);

    // Низкий контраст / S-кривая
    if (uContrast >= 0.0) c = mix(c, c * c * (3.0 - 2.0 * c), uContrast);
    else c = mix(c, 0.5 + (c - 0.5) * 0.55, -uContrast);

    // Пастель: лёгкий молочный подъём, но цвет заметно насыщеннее; зелень уходит в бирюзу,
    // как трава и лужайки на референсах
    float green = clamp((c.g - max(c.r, c.b)) * 4.0, 0.0, 1.0);
    c.b += green * uPastel * 0.45 * c.g;
    c.r *= 1.0 - green * uPastel * 0.25;
    c = mix(c, 0.1 + c * 0.86, uPastel * 0.8);
    float l = dot(c, LUMA);
    c = mix(vec3(l), c, 1.0 + uPastel * 1.1);

    // Раздельная тонировка
    l = clamp(dot(c, LUMA), 0.0, 1.0);
    float sh = (1.0 - l) * (1.0 - l);
    float hi = l * l;
    c *= mix(vec3(1.0), vec3(0.78, 0.76, 1.32), uShadowTone * (sh * 0.9 + 0.25));
    c *= mix(vec3(1.0), vec3(1.20, 0.93, 0.96), uHighlightTone * (hi * 0.85 + 0.15));

    // Зелёно-фиолетовая игра в светлом малонасыщенном (небо, облака): переливы по яркости
    float chroma = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
    float sky = smoothstep(0.45, 0.8, l) * (1.0 - smoothstep(0.12, 0.4, chroma));
    float phase = l * 11.0 + dot(wide, LUMA) * 5.0 + q.y * 4.0;
    vec3 irid = mix(vec3(0.84, 1.08, 0.96), vec3(1.08, 0.86, 1.14), 0.5 + 0.5 * sin(phase));
    c *= mix(vec3(1.0), irid, uHighlightTone * sky);

    // Дымка: светлая лавандовая, детали тонут
    c = mix(c, mix(soft, vec3(0.90, 0.87, 0.97), 0.55), uHaze * 0.5);

    // Свечение: «экранное» сложение сильно размытых светов, розоватое
    float g = clamp((dot(wide, LUMA) - 0.45) * 2.2, 0.0, 1.0);
    c = 1.0 - (1.0 - clamp(c, 0.0, 1.0)) * (1.0 - g * uGlow * vec3(1.0, 0.86, 0.92));

    // Лёгкая постеризация цвета
    if (uPosterize > 0.0) {
        float levels = mix(40.0, 5.0, uPosterize);
        c = floor(c * levels + 0.5) / levels;
    }

    c *= 1.0 - uVignette * 0.6 * vignette(q);
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
