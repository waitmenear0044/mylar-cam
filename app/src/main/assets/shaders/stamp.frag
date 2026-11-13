// Штамп даты поверх готового кадра: надпись (цвет уже умножен на альфу) в прямоугольнике uRect итогового кадра.
precision mediump float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uTex;
uniform mat4 uToQ;   // uv выхода -> q (итоговый кадр: x вправо, y вниз)
uniform vec4 uRect;  // x0, y0, x1, y1 в q
uniform float uAlpha;

void main() {
    vec2 q = (uToQ * vec4(vUv, 0.0, 1.0)).xy;
    if (q.x < uRect.x || q.x > uRect.z || q.y < uRect.y || q.y > uRect.w) discard;
    fragColor = texture(uTex, (q - uRect.xy) / (uRect.zw - uRect.xy)) * uAlpha;
}
