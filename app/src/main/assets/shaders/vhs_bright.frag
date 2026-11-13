// «VHS», свечение, шаг 1: пересвеченные места из буфера строк в маленький буфер (1/4 x 1/4).
// Дальше размывается тем же гауссианом, что halation «Плёнки», — широко по горизонтали, узко по вертикали.
precision highp float;

uniform sampler2D uSignal;  // Y в канале r
uniform vec2 uSrcTexel;

in vec2 vUv;
out vec4 fragColor;

void main() {
    float y = texture(uSignal, vUv + uSrcTexel * vec2(-1.0, -1.0)).r
            + texture(uSignal, vUv + uSrcTexel * vec2( 1.0, -1.0)).r
            + texture(uSignal, vUv + uSrcTexel * vec2(-1.0,  1.0)).r
            + texture(uSignal, vUv + uSrcTexel * vec2( 1.0,  1.0)).r;
    float b = max(y * 0.25 - 0.7, 0.0) / 0.3;
    fragColor = vec4(vec3(b * b), 1.0);
}
