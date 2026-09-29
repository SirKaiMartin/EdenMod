#version 330

layout(std140) uniform DilationConfig {
	vec2 Direction;
	float Step;
	float Seed;
};

layout(std140) uniform StyleConfig {
	vec4 Style0;
	vec4 Style1;
	vec4 Style2;
	vec4 Style3;
	vec4 Style4;
	vec4 Style5;
	vec4 Style6;
	vec4 Style7;
	vec4 Style8;
	vec4 Style9;
	vec4 Style10;
	vec4 Style11;
	vec4 Style12;
	vec4 Style13;
	vec4 Style14;
	vec4 Style15;
};

uniform sampler2D InSampler;
in vec2 texCoord;
out vec4 fragColor;

int packedRgb(vec4 color) {
	return int(round(color.r * 255.0)) << 16 | int(round(color.g * 255.0)) << 8 | int(round(color.b * 255.0));
}

bool isEden(vec4 color) {
	return (packedRgb(color) >> 21) == 5;
}

bool isSourceMarker(vec4 color) {
	int keyRed = int(round(color.r * 255.0));
	return color.a > 0.5 && keyRed >= 0xA1 && keyRed <= 0xB0 && color.g < 0.002 && color.b < 0.002;
}

int remainingRadius(vec4 color) {
	return (packedRgb(color) >> 14) & 127;
}

vec4 withRemainingRadius(vec4 color, int radius) {
	int packedValue = (packedRgb(color) & ~(127 << 14)) | (radius << 14);
	return vec4(float((packedValue >> 16) & 255), float((packedValue >> 8) & 255), float(packedValue & 255), 255.0) / 255.0;
}

vec4 styleAt(int slot) {
	if (slot == 0) return Style0;
	if (slot == 1) return Style1;
	if (slot == 2) return Style2;
	if (slot == 3) return Style3;
	if (slot == 4) return Style4;
	if (slot == 5) return Style5;
	if (slot == 6) return Style6;
	if (slot == 7) return Style7;
	if (slot == 8) return Style8;
	if (slot == 9) return Style9;
	if (slot == 10) return Style10;
	if (slot == 11) return Style11;
	if (slot == 12) return Style12;
	if (slot == 13) return Style13;
	if (slot == 14) return Style14;
	return Style15;
}

vec4 encodeSourceMarker(vec4 color) {
	vec4 style = styleAt(int(round(color.r * 255.0)) - 0xA1);
	int red = int(round(style.r * 31.0));
	int green = int(round(style.g * 31.0));
	int blue = int(round(style.b * 15.0));
	int radius = int(round(style.a));
	int packedValue = 0xA00000 | (radius << 14) | (red << 9) | (green << 4) | blue;
	return vec4(float((packedValue >> 16) & 255), float((packedValue >> 8) & 255), float(packedValue & 255), 255.0) / 255.0;
}

void consider(vec4 candidate, int stepSize, inout int bestRemaining, inout vec4 best) {
	if (Seed > 0.5 && isSourceMarker(candidate)) {
		candidate = encodeSourceMarker(candidate);
	}
	if (!isEden(candidate)) return;
	int remaining = remainingRadius(candidate);
	if (remaining < stepSize) return;
	remaining -= stepSize;
	if (remaining > bestRemaining) {
		bestRemaining = remaining;
		best = withRemainingRadius(candidate, remaining);
	}
}

void main() {
	ivec2 pixel = ivec2(gl_FragCoord.xy);
	ivec2 size = textureSize(InSampler, 0);
	ivec2 delta = ivec2(Direction * Step);
	int stepSize = int(round(Step));
	vec4 center = texelFetch(InSampler, pixel, 0);
	if (Seed > 0.5 && isSourceMarker(center)) {
		center = encodeSourceMarker(center);
	}
	int bestRemaining = isEden(center) ? remainingRadius(center) : -1;
	vec4 best = center;
	ivec2 before = pixel - delta;
	ivec2 after = pixel + delta;
	if (all(greaterThanEqual(before, ivec2(0)))) {
		consider(texelFetch(InSampler, before, 0), stepSize, bestRemaining, best);
	}
	if (all(lessThan(after, size))) {
		consider(texelFetch(InSampler, after, 0), stepSize, bestRemaining, best);
	}
	fragColor = bestRemaining >= 0 ? best : vec4(0.0);
}
