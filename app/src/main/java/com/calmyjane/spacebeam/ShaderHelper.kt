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

        /** Every compile/link error since start (info log + start of the source); the device tests assert it stays empty. */
        val failures: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        private fun recordFailure(kind: String, log: String, src: String) {
            val msg = "$kind: $log\n--- ${src.trim().lineSequence().take(3).joinToString(" | ")}"
            Log.e("GL", msg)
            failures.add(msg)
        }

        fun compile(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                recordFailure("Compile Failed", GLES20.glGetShaderInfoLog(shader), src)
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }

        fun createProgram(vSrc: String, fSrc: String): Int {
            val v = compile(GLES20.GL_VERTEX_SHADER, vSrc)
            val f = compile(GLES20.GL_FRAGMENT_SHADER, fSrc)
            if (v == 0 || f == 0) {
                if (v != 0) GLES20.glDeleteShader(v)
                if (f != 0) GLES20.glDeleteShader(f)
                return 0
            }
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f); GLES20.glLinkProgram(p)
            // The linked program keeps what it needs; free the shader objects
            GLES20.glDetachShader(p, v); GLES20.glDetachShader(p, f)
            GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
            // GL reuses ids of deleted programs: drop attrib locations cached for an old one
            attribCache.remove(p)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                recordFailure("Link Failed", GLES20.glGetProgramInfoLog(p), fSrc)
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

        /** Vertex shader for shader sources: flip + rotation of the generated image. */
        val CUSTOM_VERTEX = """
            attribute vec4 p; attribute vec2 t; varying vec2 v;
            uniform vec2 uFlip; uniform float uRotation;
            void main() {
                gl_Position = p; vec2 uv = t - 0.5; uv = uv * uFlip;
                float c = cos(uRotation); float s = sin(uRotation);
                uv = vec2(uv.x * c - uv.y * s, uv.x * s + uv.y * c);
                v = uv + 0.5;
            }
        """.trimIndent()

        /** Turns user/builtin shader code (Shadertoy `mainImage` or plain GLSL) into a full fragment shader. */
        fun wrapShaderCode(rawCode: String): String {
            val isShadertoy = rawCode.contains("mainImage")
            val prefix = """
                precision highp float;
                varying vec2 v;
                uniform float iTime;
                uniform float uTime;
                uniform vec2 iResolution;
            """.trimIndent()

            return if (isShadertoy) {
                prefix + "\n" + rawCode + "\n" + """
                    void main() {
                        mainImage(gl_FragColor, v * iResolution);
                    }
                """.trimIndent()
            } else {
                val needsPrecision = !rawCode.contains("precision")
                (if (needsPrecision) prefix + "\n" else "") + rawCode
            }
        }
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
    """.trimIndent(),

        "Droste Pupil" to """
        // CalmyJane - Droste pupil, minimal (black & white)
        // Infinite 1-bit Droste zoom: every right eye is a black pupil that holds the whole
        // mark again, slightly rotated. Square-cornered distances keep every corner pointy.
        // Seamless: zooms exactly two levels per LOOP_SECONDS, so it repeats forever.

        #define LOOP_SECONDS 12.0   // seconds per loop (smaller = faster zoom)
        #define ZOOM 3.2            // view size; larger shows more of the parent around the mark
        #define SWIRL 0.22          // log-polar swirl of the view (0 = straight)
        #define INVERT 0            // 1 = white mark on black
        #define HARD_EDGES 0        // 1 = pure 1-bit pixels (no edge smoothing, more shimmer)

        const float K = 0.09;       // child scale (fits the mark inside the eye)
        const float TH = 0.4;       // child rotation (radians)
        const float LEVELS = 2.0;   // levels zoomed per loop
        const float S = 2.0 / 126.0;

        mat2 rot(float a) { float c = cos(a), s = sin(a); return mat2(c, -s, s, c); }

        // mark geometry (150 x 126 SVG units scaled to 2 units tall), Chebyshev box distance
        float boxSq(vec2 p, vec2 c, vec2 h) { vec2 d = abs(p - c * S) - h * S; return max(d.x, d.y); }
        float solidSq(vec2 p) { return boxSq(p, vec2(0.0), vec2(75.0, 63.0)); }
        float frameSq(vec2 p) { return max(solidSq(p), -boxSq(p, vec2(0.225, 0.0), vec2(69.475, 57.5))); }
        float eyeLSq(vec2 p)  { return boxSq(p, vec2(-29.843, 21.043), vec2(9.31)); }
        float eyeRSq(vec2 p)  { return boxSq(p, vec2(32.737, 21.043), vec2(9.31)); }
        float mouthSq(vec2 p) {
            return min(boxSq(p, vec2(-47.741, -16.586), vec2(8.957, 15.668)),
                       boxSq(p, vec2(-0.065, -23.343), vec2(56.633, 8.911)));
        }
        float markSq(vec2 p) { return min(frameSq(p), min(min(eyeLSq(p), eyeRSq(p)), mouthSq(p))); }

        vec2 F;   // fixed point of the self-similarity (inside the right eye)
        vec2 toParent(vec2 p) { return F + K * (rot(TH) * (p - F)); }
        vec2 toChild(vec2 p)  { return F + (rot(-TH) * (p - F)) / K; }

        float fillw(float d, float w) {
        #if HARD_EDGES
            return step(d, 0.0);
        #else
            return clamp(0.5 - d / w, 0.0, 1.0);
        #endif
        }

        void mainImage(out vec4 fragColor, in vec2 fragCoord) {
            float phase = fract(iTime / LOOP_SECONDS);
            float res = min(iResolution.x, iResolution.y);
            vec2 uv = (2.0 * fragCoord - iResolution.xy) / res;

            // F solves (I - K*R) F = eyeCentre, so the child mark sits centred in the right eye
            vec2 eye = vec2(32.737, 21.043) * S;
            float c = cos(TH), s = sin(TH);
            mat2 m = mat2(1.0 - K * c, K * s, -K * s, 1.0 - K * c);       // I - K*rot(TH)
            float det = m[0][0] * m[1][1] - m[1][0] * m[0][1];
            mat2 mi = mat2(m[1][1], -m[0][1], -m[1][0], m[0][0]) / det;   // its inverse
            F = mi * eye;

            vec2 q = uv * ZOOM;
            q = rot(SWIRL * log(max(length(q), 1e-4))) * q;
            float zoom = pow(K, LEVELS * phase);
            vec2 p = F + zoom * (rot(TH * LEVELS * phase) * q);

            // pixel footprint in mark units, before the level jump
            float px = ZOOM * 2.0 / res * zoom * 1.03 * 0.75;

            float level = 0.0;
            for (int i = 0; i < 12; i++) { if (solidSq(p) <= 0.0) break; p = toParent(p); level -= 1.0; }
            for (int i = 0; i < 30; i++) { if (solidSq(toChild(p)) > 0.0) break; p = toChild(p); level += 1.0; }
            px *= pow(K, -level);

            float dM = markSq(p);
            float dE = eyeRSq(p);
            float dChild = solidSq(toChild(p)) * K;

            float pupil = fillw(dE, px);
            float v = 1.0 - fillw(dM, px) * (1.0 - pupil);                     // white face, black mark
            // white hairline around the child; fades out once it is thinner than a pixel
            // (one sample per pixel would otherwise sprinkle it into stray dots)
            float hair = fillw(abs(dChild - 0.012) - 0.004, px) * clamp(0.008 / px - 0.5, 0.0, 1.0);
            v = mix(v, hair, pupil);                                           // black pupil
        #if INVERT
            v = 1.0 - v;
        #endif
            fragColor = vec4(vec3(v), 1.0);
        }
    """.trimIndent(),

        "Logo Ripples" to """
        // CalmyJane - Logo Ripples (black & white)
        // Square-cornered ripples flow out of the mark and out of its face, op-art style.

        #define SPEED 0.6       // rings per second
        #define RINGS 7.0       // rings per unit (higher = thinner rings)
        #define SCALE 1.7       // view size; larger = smaller mark
        #define WOBBLE 0.06     // wave distortion of the rings (0 = straight)
        #define SWAY 0.3        // slow rotation sway (radians)
        #define INVERT 0        // 1 = white on black

        const float S = 2.0 / 126.0;
        mat2 rot(float a) { float c = cos(a), s = sin(a); return mat2(c, -s, s, c); }
        float boxSq(vec2 p, vec2 c, vec2 h) { vec2 d = abs(p - c * S) - h * S; return max(d.x, d.y); }
        float markSq(vec2 p) {
            float frame = max(boxSq(p, vec2(0.0), vec2(75.0, 63.0)), -boxSq(p, vec2(0.225, 0.0), vec2(69.475, 57.5)));
            float eyes = min(boxSq(p, vec2(-29.843, 21.043), vec2(9.31)), boxSq(p, vec2(32.737, 21.043), vec2(9.31)));
            float mouth = min(boxSq(p, vec2(-47.741, -16.586), vec2(8.957, 15.668)),
                              boxSq(p, vec2(-0.065, -23.343), vec2(56.633, 8.911)));
            return min(min(frame, eyes), mouth);
        }
        float fillw(float d, float w) { return clamp(0.5 - d / w, 0.0, 1.0); }

        void mainImage(out vec4 fragColor, in vec2 fragCoord) {
            float res = min(iResolution.x, iResolution.y);
            float px = 2.0 / res * SCALE;
            vec2 uv = (2.0 * fragCoord - iResolution.xy) / res * SCALE;
            uv = rot(SWAY * sin(iTime * 0.21)) * uv;

            float d = markSq(uv);
            float w = d + WOBBLE * sin(d * 6.0 - iTime * 1.3) * smoothstep(0.0, 0.3, d);
            float tri = abs(fract(w * RINGS - mod(iTime * SPEED, 1.0)) - 0.5) * 2.0;
            float v = clamp((tri - 0.5) / (px * RINGS * 2.0) + 0.5, 0.0, 1.0);   // rings
            v = max(v, fillw(d - 0.06, px));                                      // white gap around the mark
            v *= 1.0 - fillw(d, px);                                              // black mark
        #if INVERT
            v = 1.0 - v;
        #endif
            fragColor = vec4(vec3(v), 1.0);
        }
    """.trimIndent(),

        "Logo Spiral" to """
        // CalmyJane - Logo Spiral (black & white)
        // Log-polar spiral of marks flowing out of the centre forever, checker-inverted.

        #define PER_TURN 12.0   // marks around one turn
        #define ARMS 2.0        // spiral arms (0 = rings); ARMS + PER_TURN must be even
        #define SPEED 0.5       // marks per second along the spiral (negative = inward)
        #define INVERT 0        // 1 = swap black and white

        const float S = 2.0 / 126.0;
        float boxSq(vec2 p, vec2 c, vec2 h) { vec2 d = abs(p - c * S) - h * S; return max(d.x, d.y); }
        float markSq(vec2 p) {
            float frame = max(boxSq(p, vec2(0.0), vec2(75.0, 63.0)), -boxSq(p, vec2(0.225, 0.0), vec2(69.475, 57.5)));
            float eyes = min(boxSq(p, vec2(-29.843, 21.043), vec2(9.31)), boxSq(p, vec2(32.737, 21.043), vec2(9.31)));
            float mouth = min(boxSq(p, vec2(-47.741, -16.586), vec2(8.957, 15.668)),
                              boxSq(p, vec2(-0.065, -23.343), vec2(56.633, 8.911)));
            return min(min(frame, eyes), mouth);
        }
        float fillw(float d, float w) { return clamp(0.5 - d / w, 0.0, 1.0); }

        void mainImage(out vec4 fragColor, in vec2 fragCoord) {
            float res = min(iResolution.x, iResolution.y);
            vec2 uv = (2.0 * fragCoord - iResolution.xy) / res;
            float r = max(length(uv), 1e-4);
            vec2 lp = vec2(log(r), atan(uv.y, uv.x));       // conformal log-polar view

            // lattice rotated so one turn (0, 2pi) lands exactly on lattice vector (ARMS, PER_TURN)
            float L = length(vec2(ARMS, PER_TURN));
            float ca = PER_TURN / L, sa = ARMS / L;
            float k = L / 6.2831853;
            vec2 g = vec2(lp.x * ca + lp.y * sa, -lp.x * sa + lp.y * ca) * k;
            g.x -= mod(iTime * SPEED, 2.0);

            vec2 cell = floor(g);
            vec2 f = fract(g) - 0.5;
            vec2 p = vec2(-f.y, f.x) * 3.0;                 // mark upright, top pointing outward
            float px = 3.0 * k * 2.0 / (res * r);

            float m = fillw(markSq(p), px);
            float v = mod(cell.x + cell.y, 2.0) < 0.5 ? 1.0 - m : m;
            v = mix(v, 0.5, smoothstep(0.3, 1.0, px));      // marks smaller than a pixel fade to grey
        #if INVERT
            v = 1.0 - v;
        #endif
            fragColor = vec4(vec3(v), 1.0);
        }
    """.trimIndent(),

        "Logo Grid" to """
        // CalmyJane - Logo Grid (black & white)
        // A wall of marks; waves roll out from the centre, snapping each mark a quarter
        // turn and flipping its colours as they pass.

        #define CELLS 6.0       // marks across the short side of the screen
        #define SPEED 0.5       // waves per second
        #define SPREAD 0.18     // delay per cell away from the centre (0 = all at once)
        #define INVERT 0        // 1 = swap black and white

        const float S = 2.0 / 126.0;
        mat2 rot(float a) { float c = cos(a), s = sin(a); return mat2(c, -s, s, c); }
        float boxSq(vec2 p, vec2 c, vec2 h) { vec2 d = abs(p - c * S) - h * S; return max(d.x, d.y); }
        float markSq(vec2 p) {
            float frame = max(boxSq(p, vec2(0.0), vec2(75.0, 63.0)), -boxSq(p, vec2(0.225, 0.0), vec2(69.475, 57.5)));
            float eyes = min(boxSq(p, vec2(-29.843, 21.043), vec2(9.31)), boxSq(p, vec2(32.737, 21.043), vec2(9.31)));
            float mouth = min(boxSq(p, vec2(-47.741, -16.586), vec2(8.957, 15.668)),
                              boxSq(p, vec2(-0.065, -23.343), vec2(56.633, 8.911)));
            return min(min(frame, eyes), mouth);
        }
        float fillw(float d, float w) { return clamp(0.5 - d / w, 0.0, 1.0); }

        void mainImage(out vec4 fragColor, in vec2 fragCoord) {
            float res = min(iResolution.x, iResolution.y);
            vec2 g = (2.0 * fragCoord - iResolution.xy) / res * CELLS * 0.5;
            vec2 cell = floor(g);
            vec2 f = fract(g) - 0.5;

            float ph = mod(iTime * SPEED, 4.0) - length(cell + 0.5) * SPREAD;
            float k = floor(ph);
            float e = smoothstep(0.55, 1.0, fract(ph));     // hold, then a quick quarter turn
            float pop = 1.0 + 0.3 * sin(e * 3.1415927);     // shrinks a little while turning
            vec2 p = rot((k + e) * 1.5707963) * f * 3.2 * pop;
            float px = 2.0 / res * CELLS * 0.5 * 3.2 * pop;

            float m = fillw(markSq(p), px);
            float v = mod(cell.x + cell.y + k, 2.0) < 0.5 ? 1.0 - m : m;
        #if INVERT
            v = 1.0 - v;
        #endif
            fragColor = vec4(vec3(v), 1.0);
        }
    """.trimIndent(),

        "Logo Tunnel" to """
        // CalmyJane - Logo Tunnel (black & white)
        // Fly down a square corridor whose walls are tiled with the mark.

        #define SPEED 1.2       // flight speed
        #define PER_WALL 3.0    // marks across each wall
        #define SPIN 0.15       // corridor roll, radians per second
        #define FOG 0.35        // fade into the dark distance (0 = none)
        #define INVERT 0        // 1 = swap black and white

        const float S = 2.0 / 126.0;
        mat2 rot(float a) { float c = cos(a), s = sin(a); return mat2(c, -s, s, c); }
        float boxSq(vec2 p, vec2 c, vec2 h) { vec2 d = abs(p - c * S) - h * S; return max(d.x, d.y); }
        float markSq(vec2 p) {
            float frame = max(boxSq(p, vec2(0.0), vec2(75.0, 63.0)), -boxSq(p, vec2(0.225, 0.0), vec2(69.475, 57.5)));
            float eyes = min(boxSq(p, vec2(-29.843, 21.043), vec2(9.31)), boxSq(p, vec2(32.737, 21.043), vec2(9.31)));
            float mouth = min(boxSq(p, vec2(-47.741, -16.586), vec2(8.957, 15.668)),
                              boxSq(p, vec2(-0.065, -23.343), vec2(56.633, 8.911)));
            return min(min(frame, eyes), mouth);
        }
        float fillw(float d, float w) { return clamp(0.5 - d / w, 0.0, 1.0); }

        void mainImage(out vec4 fragColor, in vec2 fragCoord) {
            float res = min(iResolution.x, iResolution.y);
            vec2 uv = (2.0 * fragCoord - iResolution.xy) / res;
            uv = rot(mod(iTime * SPIN, 6.2831853)) * uv;

            // square corridor: each wall is a plane, so depth = 1 / distance to the nearest wall
            vec2 a = abs(uv);
            float r = max(max(a.x, a.y), 1e-4);
            float u, side;
            if (a.x > a.y) { u = uv.y / a.x; side = uv.x > 0.0 ? 0.0 : 2.0; }
            else           { u = uv.x / a.y; side = uv.y > 0.0 ? 1.0 : 3.0; }
            float z = 1.0 / r;

            vec2 g = vec2((u + 1.0) * 0.5 * PER_WALL, z * PER_WALL * 0.5 + mod(iTime * SPEED * PER_WALL * 0.5, 2.0));
            vec2 cell = floor(g);
            vec2 p = (fract(g) - 0.5) * 3.0;
            float px = 1.5 * PER_WALL * 2.0 / res * max(1.0 / r, 1.0 / (r * r));

            float m = fillw(markSq(p), px);
            float v = mod(cell.x + cell.y + side, 2.0) < 0.5 ? 1.0 - m : m;
            v = mix(v, 0.5, smoothstep(0.3, 1.0, px));      // marks smaller than a pixel fade to grey
        #if INVERT
            v = 1.0 - v;
        #endif
            v *= clamp(exp(-FOG * (z - 1.0)), 0.0, 1.0);
            fragColor = vec4(vec3(v), 1.0);
        }
    """.trimIndent(),

        "Logo Face" to """
        // CalmyJane - Logo Face (black & white)
        // The mark comes alive: eyes look around and blink, the face hops to the tempo
        // in front of drifting stripes, with a hard retro drop shadow.

        #define BPM 120.0       // hop tempo, one hop per beat
        #define HOP 1.0         // hop and squash amount (0 = still)
        #define LOOK 1.0        // how far the eyes wander (0 = fixed)
        #define SIZE 0.75       // mark height on screen (1 = full height)
        #define STRIPES 14.0    // background stripes across the screen
        #define INVERT 0        // 1 = swap black and white

        const float S = 2.0 / 126.0;
        float boxSq(vec2 p, vec2 c, vec2 h) { vec2 d = abs(p - c * S) - h * S; return max(d.x, d.y); }
        float fillw(float d, float w) { return clamp(0.5 - d / w, 0.0, 1.0); }
        float hash(float n) { return fract(sin(mod(n, 1000.0) * 12.9898) * 43758.5453); }

        void mainImage(out vec4 fragColor, in vec2 fragCoord) {
            float res = min(iResolution.x, iResolution.y);
            float px0 = 2.0 / res;
            vec2 uv = (2.0 * fragCoord - iResolution.xy) / res;

            // background: thin diagonal stripes drifting
            float tri = abs(fract((uv.x + uv.y) * STRIPES * 0.5 - mod(iTime * 0.5, 1.0)) - 0.5) * 2.0;
            float v = clamp((tri - 0.3) / (px0 * STRIPES * 1.5) + 0.5, 0.0, 1.0);

            // hop with squash & stretch
            float hop = abs(sin(mod(iTime * BPM / 60.0, 2.0) * 3.1415927)) * HOP;
            float squash = 0.07 * HOP - 0.07 * hop;
            vec2 p = uv / SIZE;
            p.y -= hop * 0.12 - 0.05 * HOP;
            p /= vec2(1.0 + squash, 1.0 - squash);
            float px = px0 / SIZE * 1.1;

            // eyes glance to a new random spot every 1.6 s and blink every 3.3 s
            float seg = floor(iTime / 1.6);
            vec2 from = vec2(hash(seg), hash(seg + 7.1)) - 0.5;
            vec2 to = vec2(hash(seg + 1.0), hash(seg + 8.1)) - 0.5;
            vec2 look = mix(from, to, smoothstep(0.0, 0.2, fract(iTime / 1.6))) * vec2(16.0, 10.0) * LOOK;
            float blink = 1.0 - 0.9 * (1.0 - smoothstep(0.0, 0.03, abs(fract(iTime / 3.3) - 0.5)));

            float solid = boxSq(p, vec2(0.0), vec2(75.0, 63.0));
            float frame = max(solid, -boxSq(p, vec2(0.225, 0.0), vec2(69.475, 57.5)));
            vec2 eyeH = vec2(9.31, 9.31 * blink);
            float eyes = min(boxSq(p, vec2(-29.843, 21.043) + look, eyeH), boxSq(p, vec2(32.737, 21.043) + look, eyeH));
            float mouth = min(boxSq(p, vec2(-47.741, -16.586), vec2(8.957, 15.668)),
                              boxSq(p, vec2(-0.065, -23.343), vec2(56.633, 8.911)));
            float d = min(min(frame, eyes), mouth);
            float shadow = boxSq(p - vec2(0.12, -0.12), vec2(0.0), vec2(75.0, 63.0));

            v *= 1.0 - fillw(shadow - 0.06, px);       // hard drop shadow
            v = max(v, fillw(solid - 0.06, px));       // white face with a halo
            v *= 1.0 - fillw(d, px);                   // black mark
        #if INVERT
            v = 1.0 - v;
        #endif
            fragColor = vec4(vec3(v), 1.0);
        }
    """.trimIndent()
)
