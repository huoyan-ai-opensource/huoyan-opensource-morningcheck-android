/** @resolution */
uniform vec2 u_resolution;
/** @time */
uniform float u_time;

float box(vec2 p, vec2 a, vec2 b) {
  vec2 d = max(a-p,p-b);
  return 1.0-smoothstep(-0.35,0.35,max(d.x,d.y));
}
float corner(vec2 p, vec2 q, vec2 sign, float len) {
  vec2 k = (p-q)*sign;
  return max(box(k,vec2(-0.7),vec2(len,0.7)),
             box(k,vec2(-0.7),vec2(0.7,len)));
}
float bracket(vec2 p,vec2 a,vec2 b,float len) {
  return max(max(corner(p,a,vec2(1.0),len),corner(p,b,vec2(-1.0),len)),
    max(corner(p,vec2(b.x,a.y),vec2(-1.0,1.0),len),
        corner(p,vec2(a.x,b.y),vec2(1.0,-1.0),len)));
}
void main() {
  vec2 p=vec2(gl_FragCoord.x,u_resolution.y-gl_FragCoord.y);
  float t=mod(u_time+1.6,4.8);
  float progress=smoothstep(0.0,1.25,t);
  float fade=smoothstep(0.0,0.2,t)*(1.0-smoothstep(4.35,4.8,t));
  float show=smoothstep(0.9,1.55,t)*fade;
  float x=72.0+20.0*progress;
  vec3 purple=vec3(0.298,0.259,0.961);
  vec3 green=vec3(0.153,0.514,0.357);
  vec3 color=t>3.425?green:purple;
  float alpha=max(bracket(p,vec2(x+22.0,13.5),vec2(x+59.5,57.5),6.0),
                  bracket(p,vec2(199.0,51.5),vec2(227.0,85.5),4.0))*show;
  float line=box(p,vec2(x+61.5,37.1),vec2(206.5,37.9))
            *(1.0-step(2.5,mod(p.x-(x+61.5),6.5)))*show*0.6;
  float r=6.5+1.5*(sin(t*6.283185)+1.0)/2.0;
  float ring=(1.0-smoothstep(0.3,0.85,abs(length(p-vec2(213.0,37.5))-r)))*show*0.55;
  alpha=max(alpha,max(line,ring));
  if(t>=1.55&&t<=3.4){
    float y=53.5+29.0*(t-1.55)/1.85;
    alpha=max(alpha,box(p,vec2(200.0,y-0.5),vec2(226.0,y+0.5))*show*0.9);
  }
  float stage=t<1.25?0.0:(t<2.2?1.0:2.0);
  for(int i=0;i<3;i++){
    float indicator=box(p,vec2(150.5+float(i)*11.5,193.0),vec2(156.5+float(i)*11.5,195.0));
    if(indicator>0.0){
      color=abs(float(i)-stage)<0.5?purple:vec3(0.835,0.851,0.914);
      alpha=max(alpha,indicator);
    }
  }
  gl_FragColor=vec4(color*alpha,alpha);
}
