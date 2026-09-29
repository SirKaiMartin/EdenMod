#version 330

uniform sampler2D SourceSampler;
uniform sampler2D OverlaySampler;
in vec2 texCoord;
out vec4 fragColor;

void main() {
	ivec2 pixel = ivec2(gl_FragCoord.xy);
	vec4 source = texelFetch(SourceSampler, pixel, 0);
	vec4 overlay = texelFetch(OverlaySampler, pixel, 0);
	fragColor = vec4(mix(source.rgb, overlay.rgb, overlay.a), max(source.a, overlay.a));
}