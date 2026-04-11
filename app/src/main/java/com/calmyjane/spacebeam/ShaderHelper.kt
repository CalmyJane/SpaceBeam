package com.calmyjane.spacebeam

import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object ShaderHelper {
        var pBuf: FloatBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)
        }
        var tBuf: FloatBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)).position(0)
        }
        private val attribCache = HashMap<Int, Pair<Int, Int>>()

        fun compile(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                Log.e("GL", "Compile Failed: ${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }

        fun createProgram(vSrc: String, fSrc: String): Int {
            val v = compile(GLES20.GL_VERTEX_SHADER, vSrc)
            val f = compile(GLES20.GL_FRAGMENT_SHADER, fSrc)
            if (v == 0 || f == 0) return 0
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f); GLES20.glLinkProgram(p)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                Log.e("GL", "Link Failed: ${GLES20.glGetProgramInfoLog(p)}")
                GLES20.glDeleteProgram(p)
                return 0
            }
            return p
        }

        fun bindQuad(prog: Int) {
            val (pL, tL) = attribCache.getOrPut(prog) {
                Pair(GLES20.glGetAttribLocation(prog, "p"), GLES20.glGetAttribLocation(prog, "t"))
            }
            GLES20.glEnableVertexAttribArray(pL); GLES20.glVertexAttribPointer(pL, 2, GLES20.GL_FLOAT, false, 0, pBuf)
            GLES20.glEnableVertexAttribArray(tL); GLES20.glVertexAttribPointer(tL, 2, GLES20.GL_FLOAT, false, 0, tBuf)
        }

        fun clearAttribCache() { attribCache.clear() }
}

val BUILTIN_SHADERS = mapOf(
        "Matrix Rain" to """
        #define SPEED 4.0
        #define DENSITY 25.0
        #define COLOR vec3(0.2, 1.0, 0.3)
        #define ROT_FREQ 0.2
        #define ROT_AMOUNT 0.15
        
        mat2 rot(float a) { return mat2(cos(a), -sin(a), sin(a), cos(a)); }

        void mainImage(out vec4 O, in vec2 U) {
            vec2 uv = U / iResolution.y;
            
            float angle = sin(iTime * ROT_FREQ) * ROT_AMOUNT;
            vec2 center = vec2(iResolution.x / iResolution.y * 0.5, 0.5);
            uv -= center;
            uv *= rot(angle);
            uv += center;

            uv.x *= DENSITY;
            
            float id = floor(uv.x);
            float offset = fract(sin(id * 34.23) * 543.21);
            uv.y = uv.y * 5.0 + iTime * SPEED * (0.5 + offset);
            
            vec2 cell = fract(uv) - 0.5;
            float drop = smoothstep(0.3, 0.0, abs(cell.x));
            float tail = smoothstep(1.0, 0.0, fract(uv.y));
            
            float hash = fract(sin(floor(uv.y) * 23.4 + id) * 43.1);
            float active = smoothstep(0.85, 1.0, hash);
            
            O = vec4(COLOR * drop * tail * active, 1.0);
        }
    """.trimIndent(),

        "Magnetic Fluid" to """
        #define SPEED 0.3
        #define SCALE 3.0
        #define COLOR vec3(0.9, 0.4, 0.1)

        void mainImage(out vec4 O, in vec2 U) {
            vec2 p = U / iResolution.y * SCALE;
            for(int i = 1; i < 4; i++) {
                vec2 newp = p;
                newp.x += 0.6 / float(i) * sin(float(i) * p.y + iTime * SPEED + 0.3);
                newp.y += 0.6 / float(i) * cos(float(i) * p.x + iTime * SPEED + 0.3);
                p = newp;
            }
            float val = cos(p.x + p.y);
            float glow = smoothstep(0.85, 1.0, val) + smoothstep(0.95, 1.0, val) * 2.0;
            O = vec4(COLOR * glow, 1.0);
        }
    """.trimIndent(),

        "Cosmic Pulse" to """
        #define SPEED 1.0
        #define COLOR_A vec3(0.1, 0.5, 0.9)
        #define COLOR_B vec3(0.9, 0.2, 0.5)
        #define INTENSITY 0.015

        void mainImage(out vec4 O, in vec2 U) {
            vec2 uv = (U * 2.0 - iResolution.xy) / min(iResolution.x, iResolution.y);
            vec3 col = vec3(0.0);
            float d = length(uv);
            
            for(float i = 0.0; i < 3.0; i++) {
                vec2 p = fract(uv * (1.5 + i * 0.2)) - 0.5;
                float a = atan(p.y, p.x);
                float r = length(p) + sin(a * 4.0 + iTime * 0.5) * 0.1; 
                float glow = INTENSITY / (abs(sin(r * 8.0 - iTime * SPEED)) + 0.01);
                col += mix(COLOR_A, COLOR_B, d) * glow;
            }
            O = vec4(col, 1.0);
        }
    """.trimIndent(),

        "Wireframe Grid" to """
        #define SPEED 0.5
        #define GRID_SIZE 8.0
        #define LINE_WIDTH 0.05
        #define WARP_STRENGTH 0.3
        #define COLOR vec3(0.7, 0.1, 0.9)
        #define ROT_FREQ 0.15
        #define ROT_AMOUNT 0.4
        
        mat2 rot(float a) { return mat2(cos(a), -sin(a), sin(a), cos(a)); }

        void mainImage(out vec4 O, in vec2 U) {
            vec2 uv = (U - iResolution.xy * 0.5) / iResolution.y;
            uv *= rot(sin(iTime * ROT_FREQ) * ROT_AMOUNT);
            uv *= 1.0 + sin(iTime * SPEED + length(uv) * 3.0) * WARP_STRENGTH; 
            vec2 g = fract(uv * GRID_SIZE) - 0.5;
            float line = min(abs(g.x), abs(g.y));
            float glow = LINE_WIDTH / (line + 0.01);
            O = vec4(COLOR * glow, 1.0);
        }
    """.trimIndent(),

        "Monochrome Distortion" to """
        #define SPEED 0.4

        void mainImage(out vec4 O, in vec2 U) {
            vec2 uv = U / iResolution.y;
            float t = iTime * SPEED;
            
            // Base asymmetric warping
            vec2 p = uv * 3.0;
            p.x += sin(p.y * 2.0 + t) * 1.5;
            p.y += cos(p.x * 2.2 - t * 0.8) * 1.5;
            
            // Secondary chaotic warping for jagged details
            p.x += sin(p.y * 4.5 + t * 1.5) * 0.5;
            p.y += cos(p.x * 4.1 - t * 1.2) * 0.5;
            
            // Topographical zebra stripes
            float stripes = sin(p.x * 5.0 + p.y * 3.0);
            
            // Sharp threshold for pure black and white, with minimal anti-aliasing
            float col = smoothstep(0.0, 0.05, stripes);
            
            // Carve out asymmetric black voids and white clumps
            float clumps = cos(p.x * 2.0 - p.y * 2.0);
            col *= smoothstep(-0.2, 0.0, clumps);
            
            O = vec4(vec3(col), 1.0);
        }
    """.trimIndent(),

        "Kaleidoscope Core" to """
        #define SPEED 1.2
        #define LAYERS 6.0
        #define COLOR vec3(0.0, 0.8, 1.0)
        
        mat2 rot(float a) { return mat2(cos(a), -sin(a), sin(a), cos(a)); }

        void mainImage(out vec4 O, in vec2 U) {
            vec2 uv = (U - 0.5 * iResolution.xy) / iResolution.y;
            vec3 col = vec3(0.0);
            
            for(float i = 0.0; i < LAYERS; i++) {
                vec2 p = uv;
                p *= rot(iTime * 0.2 + i * 0.5);
                float scale = mod(iTime * SPEED - i * (3.0 / LAYERS), 3.0);
                p *= scale * 1.5;
                
                float a = atan(p.y, p.x);
                float r = length(p);
                
                float shape = abs(cos(a * 3.0) * sin(a * 2.0)) * 0.5 + 0.5;
                float dist = abs(r - shape);
                
                float glow = 0.02 / (dist + 0.01);
                col += COLOR * glow * smoothstep(3.0, 0.0, scale);
            }
            O = vec4(col, 1.0);
        }
    """.trimIndent(),

        "Void Eclipse" to """
        #define SPEED 0.4
        #define RADIUS 0.25
        #define GLOW_INTENSITY 0.03
        #define COLOR vec3(1.0, 0.3, 0.05)
        #define SECONDARY_COLOR vec3(0.2, 0.5, 1.0)

        void mainImage(out vec4 O, in vec2 U) {
            vec2 uv = (U - 0.5 * iResolution.xy) / iResolution.y;
            float t = iTime * SPEED;
            
            float aspect = iResolution.x / iResolution.y;
            
            vec2 c1;
            c1.x = sin(t * 0.8) * (aspect * 0.5 + 0.2) + cos(t * 0.3) * 0.3;
            c1.y = cos(t * 0.6) * (0.5 + 0.2) + sin(t * 0.4) * 0.2;
            
            vec2 c2;
            c2.x = cos(t * 0.5 + 1.0) * (aspect * 0.5 + 0.2) + sin(t * 0.7) * 0.3;
            c2.y = sin(t * 0.9 - 2.0) * (0.5 + 0.2) + cos(t * 0.2) * 0.2;
            
            vec2 l1 = uv - c1;
            vec2 l2 = uv - c2;
            
            float a1 = atan(l1.y, l1.x);
            float r1 = length(l1);
            float a2 = atan(l2.y, l2.x);
            float r2 = length(l2);
            
            float w1 = sin(a1 * 2.0 + t * 2.0) * 0.04 + cos(a1 * 5.0 - t * 1.5) * 0.02;
            float w2 = sin(a2 * 3.0 - t * 1.8) * 0.04 + cos(a2 * 4.0 + t * 2.1) * 0.02;
            
            float rw1 = r1 + w1;
            float rw2 = r2 + w2;
            
            float d1 = abs(rw1 - RADIUS);
            float d2 = abs(rw2 - RADIUS);
            
            float m1 = smoothstep(RADIUS - 0.02, RADIUS + 0.02, rw1);
            float m2 = smoothstep(RADIUS - 0.02, RADIUS + 0.02, rw2);
            float combinedMask = m1 * m2;
            
            float f1 = sin(a1 - t * 2.0) * 0.5 + 0.5;
            float f2 = sin(a2 + t * 1.5) * 0.5 + 0.5;
            
            float g1 = GLOW_INTENSITY / (d1 + 0.005) * (0.8 + f1 * 1.2);
            float g2 = GLOW_INTENSITY / (d2 + 0.005) * (0.8 + f2 * 1.2);
            
            vec3 col1 = mix(COLOR, SECONDARY_COLOR, sin(a1 * 3.0 + t) * 0.5 + 0.5);
            vec3 col2 = mix(SECONDARY_COLOR, COLOR, sin(a2 * 2.0 - t) * 0.5 + 0.5);
            
            vec3 finalCol = (col1 * g1 + col2 * g2) * combinedMask;
            
            O = vec4(finalCol, 1.0);
        }
    """.trimIndent(),

        "Neon Symmetry" to """
        #define SPEED 1.5
        #define SHAPE_SIDES 4.0
        #define PULSE_SPEED 3.0
        #define BASE_COLOR vec3(1.0, 0.2, 0.5)
        #define ALT_COLOR vec3(0.2, 0.8, 1.0)

        mat2 rot(float a) { return mat2(cos(a), -sin(a), sin(a), cos(a)); }

        void mainImage(out vec4 O, in vec2 U) {
            vec2 uv = (U - 0.5 * iResolution.xy) / iResolution.y;
            vec3 col = vec3(0.0);
            
            uv *= rot(sin(iTime * 0.3) * 0.5);
            
            for(float i = 0.0; i < 4.0; i++) {
                vec2 p = uv * (1.0 + i * 0.5);
                p *= rot(iTime * 0.2 * (mod(i, 2.0) == 0.0 ? 1.0 : -1.0));
                
                float a = atan(p.y, p.x) + iTime * 0.5;
                float r = length(p);
                
                float poly = cos(floor(0.5 + a / 6.283 * SHAPE_SIDES) * 6.283 / SHAPE_SIDES - a) * r;
                
                float wave = fract(poly * 5.0 - iTime * SPEED);
                float line = smoothstep(0.1, 0.0, abs(wave - 0.5));
                
                vec3 c = mix(BASE_COLOR, ALT_COLOR, sin(iTime + i) * 0.5 + 0.5);
                col += c * line * (0.1 / (r + 0.1)) * (sin(iTime * PULSE_SPEED + i) * 0.5 + 0.5 + 0.5);
            }
            
            O = vec4(col, 1.0);
        }
    """.trimIndent(),

        "Cyber Thread" to """
        #define SPEED 0.3
        #define THICKNESS 0.015
        #define INTENSITY 1.2
        #define ZOOM 0.4
        #define COLOR vec3(0.0, 0.9, 0.6)
        #define SHIFT_COLOR vec3(0.8, 0.1, 0.9)

        void mainImage(out vec4 O, in vec2 U) {
            vec2 uv = (U - 0.5 * iResolution.xy) / iResolution.y;
            uv *= ZOOM;
            float t = iTime * SPEED;
            vec3 col = vec3(0.0);
            
            for(float i = 1.0; i <= 3.0; i++) {
                vec2 p = uv;
                
                p.x += sin(p.y * 3.0 + t * i) * 0.2;
                p.y += cos(p.x * 2.5 + t * i * 0.8) * 0.3;
                
                float wave = abs(p.y + sin(p.x * 4.0 - t * 1.2) * 0.2);
                float glow = THICKNESS / (wave + 0.002);
                
                vec3 curCol = mix(COLOR, SHIFT_COLOR, i * 0.3 + sin(t + p.x)*0.2);
                col += curCol * glow * INTENSITY;
            }
            
            col *= smoothstep(1.5, 0.2, length(uv));
            
            O = vec4(col, 1.0);
        }
    """.trimIndent(),

        "Fast Nebula" to """
        #define SPEED 0.15
        #define COLOR vec3(0.6, 0.1, 1.0)
        
        float hash(vec2 p) {
            return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
        }
        
        float noise(vec2 p) {
            vec2 i = floor(p);
            vec2 f = fract(p);
            f = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
                       mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
        }
        
        float fbm(vec2 p) {
            float f = 0.0;
            float amp = 0.5;
            for(int i = 0; i < 4; i++) {
                f += amp * noise(p);
                p *= 2.0;
                amp *= 0.5;
            }
            return f;
        }

        void mainImage(out vec4 O, in vec2 U) {
            vec2 uv = U / iResolution.y;
            
            float q = fbm(uv * 3.0 + iTime * SPEED);
            float n = fbm(uv * 5.0 - iTime * SPEED * 0.8 + vec2(q));
            
            float glow = smoothstep(0.2, 0.8, n);
            vec3 col = COLOR * glow * 2.0;
            
            col += vec3(0.2, 0.5, 0.8) * smoothstep(0.4, 1.0, q) * 0.5;
            
            O = vec4(col, 1.0);
        }
    """.trimIndent()
)
