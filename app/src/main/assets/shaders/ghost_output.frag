// «Гостинг», шаг 2: текущий кадр в полном разрешении + история. След (то, чем история отличается от кадра)
// может уходить в другой оттенок. Форма шлейфа меняет только след — текущий кадр остаётся чётким (маска следа
// берётся в тех же точках, что и изменённая история): 0 — чёткий, 1 — размытый, 2 — пиксельный (блоки, как у
// «Пикселя»), 3 — блочный (цвет размазан по макроблоку, как блочность «VHS», плюс квантование), 4 — «шакальный»
// (низкое разрешение, смаз цвета, постеризация с дизерингом, шум — как убитый JPEG). Размеры — доли короткой стороны.
precision highp float;

uniform SAMPLER_TYPE uInput;
uniform mat4 uTexMatrix;
uniform sampler2D uHist;
uniform float uStrength;
uniform float uColorShift; // 0..1
uniform int uShape;
uniform float uShapeSize;  // 0..1: радиус размытия / размер блока
uniform float uDegrade;    // 0..1: сила деградации (квантование, дизеринг, шум)
uniform vec2 uOutSize;     // выход в пикселях
uniform float uTime;
uniform float uHistScale; // текселей истории на пиксель выхода

in vec2 vUv;
out vec4 fragColor;

vec3 hueRotate(vec3 c, float a) {
    const vec3 k = vec3(0.57735);
    float ca = cos(a);
    return c * ca + cross(k, c) * sin(a) + k * dot(k, c) * (1.0 - ca);
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float bayer4(vec2 p) {
    ivec2 i = ivec2(mod(p, 4.0));
    const float m[16] = float[16](0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0, 3.0, 11.0, 1.0, 9.0, 15.0, 7.0, 13.0, 5.0);
    return (m[i.x + i.y * 4] + 0.5) / 16.0 - 0.5;
}

vec3 inputAt(vec2 uv) {
    return texture(uInput, (uTexMatrix * vec4(uv, 0.0, 1.0)).xy).rgb;
}

// Размер блока в пикселях выхода: от 0.4% до 6% короткой стороны
float blockPx(float scale) {
    return max(2.0, mix(0.004, 0.06, uShapeSize) * scale * min(uOutSize.x, uOutSize.y));
}

vec2 blockUv(vec2 uv, float px) {
    vec2 cells = uOutSize / px;
    return (floor(uv * cells) + 0.5) / cells;
}

// Уровень мип-карты истории, усредняющий область размером px пикселей выхода
float lodFor(float px) {
    return log2(max(1.0, px * uHistScale));
}

// Изменённая история (rgb) и маска следа (a — из истории, усреднённая так же, как цвет)
vec4 shaped(vec2 uv) {
    if (uShape == 1) {
        // Размытие: выборки из мип-уровня (радиус до 3% короткой стороны) — пять выборок вместо десятков
        float r = mix(0.002, 0.03, uShapeSize) * min(uOutSize.x, uOutSize.y);
        float lod = lodFor(r);
        vec2 o = 0.5 * r / uOutSize;
        vec4 sum = textureLod(uHist, uv, lod) * 2.0;
        sum += textureLod(uHist, uv + vec2(o.x, o.y), lod);
        sum += textureLod(uHist, uv + vec2(-o.x, o.y), lod);
        sum += textureLod(uHist, uv + vec2(o.x, -o.y), lod);
        sum += textureLod(uHist, uv + vec2(-o.x, -o.y), lod);
        sum /= 6.0;
        return vec4(sum.rgb, sum.a * 1.6);
    }
    if (uShape == 2) {
        // Пиксели: средний цвет блока (как у «Пикселя» — блоки в долях кадра)
        float px = blockPx(1.0);
        return textureLod(uHist, blockUv(uv, px), lodFor(px));
    }
    if (uShape == 3) {
        // Макроблоки: цвет тянется к среднему блока, яркость и цвет квантуются, на границах блоков — ступенька
        float px = blockPx(0.5);
        vec4 hc = textureLod(uHist, blockUv(uv, px), lodFor(px));
        vec4 h = texture(uHist, uv);
        vec3 c = mix(h.rgb, hc.rgb, 0.45 + 0.5 * uDegrade);
        float levels = mix(24.0, 6.0, uDegrade);
        c = floor(c * levels + 0.5) / levels;
        vec2 f = fract(uv * uOutSize / px);
        float edge = step(f.x, 1.0 / px) + step(f.y, 1.0 / px);
        return vec4(c * (1.0 - 0.06 * uDegrade * min(edge, 1.0)), max(h.a, hc.a));
    }
    // «Шакальный»: низкое разрешение, смаз цвета по соседнему блоку, постеризация с дизерингом, шум
    float px = blockPx(1.3);
    vec4 low = textureLod(uHist, mix(uv, blockUv(uv, px), 0.85), lodFor(px * 0.5));
    vec3 chroma = textureLod(uHist, blockUv(uv + vec2(px * 1.5, 0.0) / uOutSize, px * 2.0), lodFor(px * 2.0)).rgb;
    float y = dot(low.rgb, vec3(0.299, 0.587, 0.114));
    float yc = dot(chroma, vec3(0.299, 0.587, 0.114));
    vec3 c = clamp(y + (chroma - yc) * (1.0 + 0.6 * uDegrade), 0.0, 1.0);
    float levels = mix(10.0, 3.0, uDegrade);
    vec2 pp = uv * uOutSize / max(1.0, px * 0.25);
    c = floor(c * levels + 0.5 + bayer4(pp) * 0.9) / levels;
    c += (hash(floor(uv * uOutSize / 2.0) + floor(uTime * 12.0)) - 0.5) * 0.12 * uDegrade;
    return vec4(clamp(c, 0.0, 1.0), low.a * 1.3);
}

void main() {
    vec3 cur = inputAt(vUv);
    vec3 h = texture(uHist, vUv).rgb;
    vec3 d = abs(h - cur);
    float trail = smoothstep(0.03, 0.14, max(d.r, max(d.g, d.b)));
    if (uShape > 0) {
        vec4 s = shaped(vUv);
        float m = clamp(s.a, 0.0, 1.0);
        h = mix(h, s.rgb, m);
        trail = max(trail, m);
    }
    h = mix(h, hueRotate(h, uColorShift * 2.4), trail);
    fragColor = vec4(clamp(mix(cur, h, uStrength), 0.0, 1.0), 1.0);
}
