#version 330

uniform sampler2D InSampler;
in vec2 texCoord;
out vec4 fragColor;

void main() {
	ivec2 pixel = ivec2(gl_FragCoord.xy);
	vec4 color = texelFetch(InSampler, pixel, 0);
	int keyRed = int(round(color.r * 255.0));
	bool selected = color.a > 0.5 && keyRed >= 0xA1 && keyRed <= 0xB0 && color.g < 0.002 && color.b < 0.002;
	fragColor = selected ? vec4(0.0) : color;
}
