// Непрозрачность слоя: смесь результата слоя с тем, что под ним.
precision highp float;

uniform sampler2D uBelow;
uniform sampler2D uLayer;
uniform float uOpacity;

in vec2 vUv;
out vec4 fragColor;

void main() {
    fragColor = vec4(mix(texture(uBelow, vUv).rgb, texture(uLayer, vUv).rgb, uOpacity), 1.0);
}
