// «Плёнка»: гауссово размытие в одном направлении (9 выборок), по маленькому буферу.
precision highp float;

uniform sampler2D uSrc;
uniform vec2 uStep; // шаг между выборками в uv (направление * текстель * разброс)

in vec2 vUv;
out vec4 fragColor;

const float W0 = 0.2270270270;
const float W1 = 0.1945945946;
const float W2 = 0.1216216216;
const float W3 = 0.0540540541;
const float W4 = 0.0162162162;

void main() {
    vec3 c = texture(uSrc, vUv).rgb * W0;
    c += (texture(uSrc, vUv + uStep).rgb + texture(uSrc, vUv - uStep).rgb) * W1;
    c += (texture(uSrc, vUv + uStep * 2.0).rgb + texture(uSrc, vUv - uStep * 2.0).rgb) * W2;
    c += (texture(uSrc, vUv + uStep * 3.0).rgb + texture(uSrc, vUv - uStep * 3.0).rgb) * W3;
    c += (texture(uSrc, vUv + uStep * 4.0).rgb + texture(uSrc, vUv - uStep * 4.0).rgb) * W4;
    fragColor = vec4(c, 1.0);
}
