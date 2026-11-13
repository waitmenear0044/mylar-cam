// «Cores» / Sun (санкор): яркий тёплый свет с эффектом Orton. Экспозиция с мягким плечом (света
// уходят в кремовый, а не в белое), золотые света, жёлто-зелёная зелень, насыщенные оранжево-красные
// акценты, низкий контраст, сильное мягкое свечение, тёплая дымка и засветы по краям кадра.
// Загрузчик подставляет #version и SAMPLER_TYPE (камера или обычная текстура).
precision highp float;

uniform SAMPLER_TYPE uInput;
uniform mat4 uTexMatrix;
uniform vec2 uFrameOrigin;
uniform vec2 uFrameScale;
uniform sampler2D uSoft;
uniform sampler2D uWide;
uniform mat4 uToQ;
uniform float uAspectQ;
uniform float uTime;        // секунды (засветы медленно плывут)

uniform float uExposure;
uniform float uGlow;
uniform float uLeak;
uniform float uSoftness;
uniform float uWarmth;
uniform float uSaturation;
uniform float uYellowGreen;
uniform float uContrast;
uniform float uHaze;

in vec2 vUv;
out vec4 fragColor;

const vec3 LUMA = vec3(0.299, 0.587, 0.114);

vec3 screen(vec3 a, vec3 b) {
    return 1.0 - (1.0 - a) * (1.0 - b);
}

// Тёплое пятно засветки: центр в q, радиус в долях высоты
float blob(vec2 q, vec2 center, float radius) {
    vec2 d = (q - center) * vec2(uAspectQ, 1.0);
    return exp(-dot(d, d) / (radius * radius));
}

void main() {
    vec2 uv = uFrameOrigin + vUv * uFrameScale;
    vec2 q = (uToQ * vec4(uv, 0.0, 1.0)).xy;
    vec3 c = texture(uInput, (uTexMatrix * vec4(uv, 0.0, 1.0)).xy).rgb;
    vec3 soft = texture(uSoft, uv).rgb;
    vec3 wide = texture(uWide, uv).rgb;

    c = mix(c, soft, uSoftness * 0.75);

    // Экспозиция с мягким плечом: 1 остаётся 1, середина светлеет
    float e = exp2(uExposure * 1.3);
    c = c * e / (1.0 + c * (e - 1.0));

    // Зелень к жёлто-зелёному
    float green = clamp((c.g - max(c.r, c.b)) * 4.0, 0.0, 1.0);
    c.r += green * uYellowGreen * 0.75 * c.g;
    c.g += green * uYellowGreen * 0.12 * c.g;
    c.b *= 1.0 - green * uYellowGreen * 0.6;

    // Насыщенность; оранжево-красные акценты не теряют цвет
    float l = dot(c, LUMA);
    float red = smoothstep(0.08, 0.3, c.r - c.b) * smoothstep(0.0, 0.15, c.r - c.g);
    c = mix(vec3(l), c, mix(uSaturation, max(uSaturation, 1.2), red));

    // Контраст
    c = clamp(c, 0.0, 1.0);
    if (uContrast >= 0.0) c = mix(c, c * c * (3.0 - 2.0 * c), uContrast);
    else c = mix(c, 0.5 + (c - 0.5) * 0.6, -uContrast);

    // Тёплые кремово-золотые света
    l = dot(c, LUMA);
    c *= mix(vec3(1.0), vec3(1.12, 1.02, 0.66), uWarmth * (0.45 + 0.55 * l));

    // Orton: размытая копия, «пересвеченная» и тёплая, накладывается экраном — света сияют
    vec3 w = wide * wide * (1.0 + uWarmth * 0.3) * vec3(1.0, 0.9, 0.62);
    c = screen(c, clamp(w * uGlow * 1.3, 0.0, 1.0));
    c = mix(c, c * (0.75 + 0.5 * wide), uGlow * 0.3);

    // Тёплая дымка: тени светлеют в кремовый
    c = mix(c, vec3(1.0, 0.93, 0.78), uHaze * 0.35 * (1.0 - 0.5 * l));

    // Засветы по краям: три тёплых пятна у краёв, медленно плывут во времени
    float leakA = blob(q, vec2(-0.06, 0.22 + 0.08 * sin(uTime * 0.13)), 0.32);
    float leakB = blob(q, vec2(1.06, 0.72 + 0.08 * sin(uTime * 0.11 + 2.0)), 0.30);
    float leakC = blob(q, vec2(0.9 + 0.06 * sin(uTime * 0.07 + 4.0), -0.06), 0.22);
    vec3 leak = leakA * vec3(1.0, 0.48, 0.18) + leakB * vec3(1.0, 0.72, 0.30) + leakC * vec3(1.0, 0.55, 0.45);
    c = screen(clamp(c, 0.0, 1.0), clamp(leak * uLeak, 0.0, 1.0));

    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
