struct Light { vec4 posR; vec4 col; vec4 dir; };
layout(std430, binding = 0) readonly buffer Lights { Light lights[]; };
