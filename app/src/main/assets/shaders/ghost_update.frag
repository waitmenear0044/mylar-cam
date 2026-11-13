// «Гостинг», шаг 1: обновление буфера истории (уменьшенный кадр).
// Эхо: история плавно тянется к текущему кадру (uDecay = exp(-dt / tau) — по времени, а не по числу кадров).
// Глюк-копии: в моменты «штампа» (uStamp, раз в ~0.12 с) текущий кадр записывается только там, где есть движение,
// — движущийся объект оставляет застывшую копию; остальное время история медленно стирается к текущему кадру.
precision highp float;

uniform SAMPLER_TYPE uInput;
uniform mat4 uTexMatrix;
uniform sampler2D uHist;   // история прошлого кадра
uniform sampler2D uPrev;   // прошлый кадр камеры (для движения)
uniform int uMode;         // 0 — глюк-копии, 1 — эхо
uniform float uDecay;      // доля истории, которая остаётся за этот кадр
uniform float uThreshold;  // порог движения
uniform float uStamp;      // 1 — в этом кадре копии записываются
uniform float uFirst;      // 1 — истории ещё нет

in vec2 vUv;
out vec4 fragColor;

// Альфа — маска следа (где история отличается от кадра): по ней форма шлейфа меняет только след
vec4 withTrail(vec3 h, vec3 cur) {
    vec3 d = abs(h - cur);
    return vec4(h, smoothstep(0.03, 0.14, max(d.r, max(d.g, d.b))));
}

void main() {
    vec3 cur = texture(uInput, (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy).rgb;
    if (uFirst > 0.5) {
        fragColor = vec4(cur, 0.0);
        return;
    }
    vec3 old = texture(uHist, vUv).rgb;
    vec3 faded = mix(cur, old, uDecay);
    if (uMode == 1) {
        fragColor = withTrail(faded, cur);
        return;
    }
    vec3 dm = abs(cur - texture(uPrev, vUv).rgb);
    float motion = max(dm.r, max(dm.g, dm.b));
    float m = smoothstep(uThreshold, uThreshold * 1.6, motion) * uStamp;
    fragColor = withTrail(mix(faded, cur, m), cur);
}
