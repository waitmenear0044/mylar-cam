// VHS-эффект, проход 2: растягиваем внутренний буфер на весь кадр без сглаживания.
// Текстура буфера создана с GL_NEAREST, поэтому пиксели остаются крупными и чёткими.
precision highp float;

uniform sampler2D uLowRes;
uniform vec2 uUvScale; // доля буфера, занятая кадром (буфер выделен под максимальную ширину)
uniform vec2 uFrameOrigin; // окно кадра: uv полного кадра = uFrameOrigin + vUv * uFrameScale
uniform vec2 uFrameScale;  // (в живом конвейере 0 и 1; в тайлах фото — положение тайла)


in vec2 vUv;
out vec4 fragColor;

void main() {
    fragColor = texture(uLowRes, (uFrameOrigin + vUv * uFrameScale) * uUvScale);
}
