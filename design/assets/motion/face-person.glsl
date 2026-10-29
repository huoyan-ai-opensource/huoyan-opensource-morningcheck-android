/** @resolution */
uniform vec2 u_resolution;
/** @time */
uniform float u_time;
/** @content */
uniform sampler2D u_content;

float ease(float a, float b, float value) {
  return smoothstep(a, b, value);
}

vec2 gaitOffset(vec2 local, float progress, float leg) {
  float phase = progress * 6.283185;
  float settle = sin(progress * 3.141593);
  float stride = sin(phase);
  float lower = ease(198.0, 274.0, local.y);
  float lift = max(0.0, leg * cos(phase)) * 8.0 * settle;
  float bob = -1.4 * stride * stride * settle;
  return vec2(leg * 11.0 * stride * settle * lower,
              mix(bob, -lift, lower));
}

vec4 sampleLeg(vec2 local, float progress, float leg) {
  vec2 source = local;
  for (int i=0; i<4; i++) {
    source = local-gaitOffset(source,progress,leg);
  }
  float split = 75.0+5.0*ease(198.0,270.0,source.y);
  float mask = leg < 0.0 ? 1.0-step(split,source.x) : step(split,source.x);
  source = source*0.5+vec2(72.0,5.0);
  vec2 uv = vec2(source.x, u_resolution.y-source.y) / u_resolution;
  float inside = step(0.0,uv.x)*step(uv.x,1.0)*step(0.0,uv.y)*step(uv.y,1.0);
  return texture2D(u_content,uv)*mask*inside;
}

void main() {
  float t = mod(u_time + 1.6, 4.8);
  float p = smoothstep(0.0, 1.25, t);
  float fade = smoothstep(0.0, 0.2, t) * (1.0-smoothstep(4.35,4.8,t));
  vec2 pixel = vec2(gl_FragCoord.x, u_resolution.y-gl_FragCoord.y);
  vec2 local = (pixel-vec2(72.0+20.0*p,5.0))*2.0;
  vec4 back = sampleLeg(local,p,-1.0);
  vec4 front = sampleLeg(local,p,1.0);
  gl_FragColor = (front+back*(1.0-front.a))*fade;
}
