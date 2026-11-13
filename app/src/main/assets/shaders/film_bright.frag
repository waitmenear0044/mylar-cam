// «Плёнка», проход 2: уменьшение в 4 раза и выделение светов для halation.
precision highp float;

uniform sampler2D uSrc;
uniform vec2 uSrcTexel; // 1 / размер исходного буфера

in vec2 vUv;
out vec4 fragColor;

void main() {
    // 4 билинейные выборки накрывают блок 4x4 исходных пикселей
    vec3 c = texture(uSrc, vUv + uSrcTexel * vec2(-1.0, -1.0)).rgb
           + texture(uSrc, vUv + uSrcTexel * vec2( 1.0, -1.0)).rgb
           + texture(uSrc, vUv + uSrcTexel * vec2(-1.0,  1.0)).rgb
           + texture(uSrc, vUv + uSrcTexel * vec2( 1.0,  1.0)).rgb;
    c *= 0.25;
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    fragColor = vec4(c * smoothstep(0.6, 1.0, l), 1.0);
}
