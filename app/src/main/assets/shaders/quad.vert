// Полноэкранный прямоугольник. Строку #version подставляет загрузчик (GlProgram).
layout(location = 0) in vec2 aPosition; // -1..1
out vec2 vUv;                           // 0..1 в координатах выходного кадра

void main() {
    vUv = aPosition * 0.5 + 0.5;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
