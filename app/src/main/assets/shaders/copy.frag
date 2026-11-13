// Эффект выключен: кадр камеры как есть, в полном разрешении.
precision highp float;

uniform SAMPLER_TYPE uInput;
uniform mat4 uTexMatrix;

in vec2 vUv;
out vec4 fragColor;

void main() {
    fragColor = vec4(texture(uInput, (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy).rgb, 1.0);
}
