/** @resolution */
uniform vec2 u_resolution;
/** @time */
uniform float u_time;
/** @content */
uniform sampler2D u_content;
/** @label Instruction stage */
uniform float u_stage;

void main() {
  float t = mod(u_time + 1.6, 4.8);
  float stage = t < 1.25 ? 0.0 : (t < 2.2 ? 1.0 : 2.0);
  gl_FragColor = texture2D(u_content,gl_FragCoord.xy/u_resolution)
                * (1.0-step(0.5,abs(stage-u_stage)));
}
