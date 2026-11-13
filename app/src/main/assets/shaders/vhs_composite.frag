// «VHS», проход 4 (буфер строк): сборка сигнала обратно в RGB. Яркость после ленты + узкий цвет,
// усреднённый с предыдущей строкой (как в видеомагнитофоне), свечение, цвет записи (насыщенность,
// контраст, оттенок, выцветшие чёрные), затем шлейф и чересстрочность через предыдущий кадр.
// Результат хранится в ping-pong буфере: он же «предыдущий кадр» для следующего.
precision highp float;

uniform sampler2D uLuma;    // Y после фильтров, a — маска дропаутов
uniform sampler2D uChroma;  // I, Q (со сдвигом 0.5), W/4 x L
uniform sampler2D uBloom;   // свечение, 1/4 x 1/4
uniform sampler2D uPrev;    // предыдущий кадр (буфер строк)
uniform vec2 uSize;         // W, L
uniform float uVertBlend;   // усреднение цвета соседних строк 0..1
uniform float uBlock;
uniform vec2 uBlockSize;
uniform float uSaturation;
uniform float uFade;
uniform float uTint;
uniform float uContrast;
uniform float uBloomK;
uniform float uTrail;       // 0, если предыдущего кадра нет
uniform float uInterlace;   // 0, если предыдущего кадра нет
uniform float uHistoryScale; // длительность кадра относительно 30 fps (доля шлейфа a -> a^scale)
uniform int uFrame;

in vec2 vUv;
out vec4 fragColor;

const vec3 LUMA = vec3(0.299, 0.587, 0.114);

vec3 yiq2rgb(vec3 c) {
    return vec3(
        c.x + 0.956 * c.y + 0.621 * c.z,
        c.x - 0.272 * c.y - 0.647 * c.z,
        c.x - 1.106 * c.y + 1.703 * c.z);
}

void main() {
    vec4 f = texture(uLuma, vUv);
    float drop = f.a;

    // Цвет: при блочности — пятнами по блокам сжатия
    vec2 cuv = vUv;
    if (uBlock > 0.0) {
        vec2 c0 = (floor(vUv * uSize / uBlockSize) + 0.5) * uBlockSize / uSize;
        cuv = mix(vUv, c0, uBlock * 0.6);
    }
    vec2 iq = (texture(uChroma, cuv).rg - 0.5) / 0.8;
    vec2 iqUp = (texture(uChroma, cuv - vec2(0.0, 1.0 / uSize.y)).rg - 0.5) / 0.8;
    iq = mix(iq, 0.5 * (iq + iqUp), uVertBlend);
    iq *= (1.0 - drop) * uSaturation;
    vec3 c = clamp(yiq2rgb(vec3(f.r, iq)), 0.0, 1.0);

    // Свечение: «экранное» сложение, чтобы света не выбивало в плоское белое
    float b = clamp(texture(uBloom, vUv).r * uBloomK, 0.0, 1.0);
    c = 1.0 - (1.0 - c) * (1.0 - b * vec3(1.0, 0.95, 0.85));

    // Контраст: S-кривая или «плоская» картинка
    if (uContrast >= 0.0) c = mix(c, c * c * (3.0 - 2.0 * c), uContrast);
    else c = mix(c, 0.5 + (c - 0.5) * 0.6, -uContrast);
    vec3 warm = vec3(1.07, 1.0, 0.86);
    vec3 green = vec3(0.94, 1.06, 0.92);
    c *= mix(vec3(1.0), uTint >= 0.0 ? warm : green, abs(uTint));
    c = uFade * 0.16 + c * (1.0 - uFade * 0.22);
    c = clamp(c, 0.0, 1.0);

    // Шлейф (сильнее в тенях) и чересстрочность: каждая вторая строка, через кадр — другая
    vec3 prev = texture(uPrev, vUv).rgb;
    float l = dot(c, LUMA);
    // Доля шлейфа подобрана для 30 fps; при другой частоте — a^(dt/dt30): то же затухание во времени
    float trail = pow(uTrail * mix(0.22, 0.45, (1.0 - l) * (1.0 - l)), uHistoryScale);
    int line = int(floor(vUv.y * uSize.y));
    float field = float((line + uFrame) & 1);
    float k = 1.0 - (1.0 - trail) * (1.0 - uInterlace * 0.6 * field);
    c = mix(c, prev, k);
    fragColor = vec4(c, 1.0);
}
