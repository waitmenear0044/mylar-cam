// «Плёнка», проход 3: мягкость, halation, плёночная цветопередача и смаз с предыдущим кадром.
// Результат (без зерна) сохраняется в ping-pong буфер: он же «предыдущий кадр» для следующего.
precision highp float;

uniform sampler2D uBase;   // кадр после расщепления цвета
uniform sampler2D uHalo;   // размытые света (1/4 разрешения)
uniform sampler2D uPrev;   // предыдущий итоговый кадр (без зерна)
uniform vec2 uFrameOrigin; // окно кадра: uv полного кадра = uFrameOrigin + vUv * uFrameScale
uniform vec2 uFrameScale;  // (в живом конвейере 0 и 1; в тайлах фото — положение тайла)
uniform vec2 uTexel;       // 1 / размер uBase

uniform float uSoftness;
uniform float uHalation;
uniform float uMotion;     // доля предыдущего кадра
uniform float uFilmColor;
uniform float uContrast;
uniform float uFade;
uniform float uSaturation;
uniform float uTemperature;
uniform vec3 uShadowTint;     // цвет тонировки теней (яркость ~1)
uniform float uShadowTone;    // сила 0..1
uniform vec3 uHighlightTint;  // цвет тонировки светов
uniform float uHighlightTone;

in vec2 vUv;
out vec4 fragColor;

const vec3 LUMA = vec3(0.299, 0.587, 0.114);

vec3 filmGrade(vec3 c) {
    float k = uFilmColor;

    // S-образная кривая
    c = mix(c, c * c * (3.0 - 2.0 * c), uContrast);

    // Раздельные кривые и кроссовер (сняты с референсов): тени чуть в пурпур,
    // света в холодный голубой, средние тона слегка бирюзовые.
    float l = dot(c, LUMA);
    float sh = (1.0 - l) * (1.0 - l);
    float hi = l * l;
    c *= mix(vec3(1.0), vec3(1.03, 0.96, 1.04), sh * k);
    c *= mix(vec3(1.0), vec3(0.92, 1.0, 1.06), hi * k);
    c = pow(max(c, 0.0), mix(vec3(1.0), vec3(1.03, 0.98, 0.97), k));

    // Мягкое плечо в светах вместо жёсткого клиппинга (белое уходит в ~0.92)
    const float knee = 0.68;
    vec3 shoulder = knee + (1.0 - knee) * tanh(max(c - knee, 0.0) / (1.0 - knee));
    c = mix(c, mix(c, shoulder, step(knee, c)), k);

    // Насыщенность, приглушённая в светах
    l = dot(c, LUMA);
    float sat = uSaturation * mix(1.0, 0.72, smoothstep(0.55, 1.0, l) * k);
    c = mix(vec3(l), c, sat);

    // Тёплый / холодный
    vec3 warm = vec3(1.06, 1.0, 0.90);
    vec3 cool = vec3(0.93, 0.99, 1.08);
    c *= mix(vec3(1.0), uTemperature >= 0.0 ? warm : cool, abs(uTemperature));

    // Раздельная тонировка теней и светов (при силе 0 ничего не меняет)
    l = dot(c, LUMA);
    float shadowW = (1.0 - l) * (1.0 - l);
    c *= mix(vec3(1.0), uShadowTint, uShadowTone * 0.45 * shadowW);
    c *= mix(vec3(1.0), uHighlightTint, uHighlightTone * 0.35 * l * l);

    // Выцветание: приподнятые чёрные, чуть притушенные белые
    c = uFade * 0.10 + c * (1.0 - uFade * 0.14);
    return clamp(c, 0.0, 1.0);
}

void main() {
    vec3 c = texture(uBase, vUv).rgb;

    // Мягкость: смешиваем с маленьким размытием (4 билинейные выборки ~ блок 3x3)
    vec3 blur = texture(uBase, vUv + uTexel * vec2(-0.75, -0.75)).rgb
              + texture(uBase, vUv + uTexel * vec2( 0.75, -0.75)).rgb
              + texture(uBase, vUv + uTexel * vec2(-0.75,  0.75)).rgb
              + texture(uBase, vUv + uTexel * vec2( 0.75,  0.75)).rgb;
    c = mix(c, blur * 0.25, uSoftness);

    // Halation: свет, отражённый от подложки плёнки, — красно-оранжевый ореол вокруг ярких мест.
    // Виден на более тёмном окружении, а сами большие светлые области не «выбивает» в белое.
    float halo = dot(texture(uHalo, uFrameOrigin + vUv * uFrameScale).rgb, LUMA); // света — по полному кадру
    float around = 1.0 - smoothstep(0.45, 0.95, dot(c, LUMA));
    c += vec3(1.0, 0.36, 0.14) * halo * uHalation * 1.6 * around;

    c = filmGrade(c);

    c = mix(c, texture(uPrev, vUv).rgb, uMotion);
    fragColor = vec4(c, 1.0);
}
