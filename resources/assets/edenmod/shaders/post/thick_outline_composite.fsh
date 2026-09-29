#version 330

uniform sampler2D MaskSampler;
uniform sampler2D SourceSampler;
in vec2 texCoord;
out vec4 fragColor;

bool isEden(vec4 color) {
	int packedValue = int(round(color.r * 255.0)) << 16 | int(round(color.g * 255.0)) << 8 | int(round(color.b * 255.0));
	return (packedValue >> 21) == 5;
}

bool isSourceMarker(vec4 color) {
	int keyRed = int(round(color.r * 255.0));
	return color.a > 0.5 && keyRed >= 0xA1 && keyRed <= 0xB0 && color.g < 0.002 && color.b < 0.002;
}

vec3 decodeColor(vec4 encoded) {
	int packedValue = int(round(encoded.r * 255.0)) << 16 | int(round(encoded.g * 255.0)) << 8 | int(round(encoded.b * 255.0));
	float red = float((packedValue >> 9) & 31) / 31.0;
	float green = float((packedValue >> 4) & 31) / 31.0;
	float blue = float(packedValue & 15) / 15.0;
	return vec3(red, green, blue);
}

void main() {
	ivec2 pixel = ivec2(gl_FragCoord.xy);
	vec4 expanded = texelFetch(MaskSampler, pixel, 0);
	vec4 original = texelFetch(SourceSampler, pixel, 0);
	if (isEden(expanded) && !isSourceMarker(original)) {
		fragColor = vec4(decodeColor(expanded), 1.0);
	} else {
		fragColor = vec4(0.0);
	}
}
