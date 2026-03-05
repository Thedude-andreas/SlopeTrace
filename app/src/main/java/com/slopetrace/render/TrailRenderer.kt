package com.slopetrace.render

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class TrailRenderer : GLSurfaceView.Renderer {
    private data class TrailGeometry(
        val buffer: FloatBuffer,
        val pointCount: Int,
        val color: FloatArray
    )

    private val userTrails = mutableMapOf<String, TrailGeometry>()
    private val lock = Any()

    private var program = 0
    private var aPositionHandle = 0
    private var uMvpHandle = 0
    private var uColorHandle = 0

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)

    private var yawDeg = -30f
    private var pitchDeg = 30f
    private var zoom = 1.6f

    private var gridBuffer: FloatBuffer? = null
    private var gridPointCount = 0
    private var gridMinZ = 0f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.05f, 0.07f, 0.12f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glLineWidth(3f)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        uMvpHandle = GLES20.glGetUniformLocation(program, "uMvp")
        uColorHandle = GLES20.glGetUniformLocation(program, "uColor")
        Matrix.setIdentityM(model, 0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat().coerceAtLeast(1f)
        Matrix.perspectiveM(projection, 0, 50f, aspect, 1f, 10000f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        GLES20.glUseProgram(program)

        val center = estimateCenter()
        val eyeDistance = 350f * zoom
        val yawRad = Math.toRadians(yawDeg.toDouble())
        val pitchRad = Math.toRadians(pitchDeg.toDouble())
        val ex = center[0] + (eyeDistance * kotlin.math.cos(pitchRad) * kotlin.math.cos(yawRad)).toFloat()
        val ey = center[1] + (eyeDistance * kotlin.math.cos(pitchRad) * kotlin.math.sin(yawRad)).toFloat()
        val ez = center[2] + (eyeDistance * kotlin.math.sin(pitchRad)).toFloat()

        Matrix.setLookAtM(
            view,
            0,
            ex,
            ey,
            ez,
            center[0],
            center[1],
            center[2],
            0f,
            0f,
            1f
        )
        Matrix.multiplyMM(vp, 0, projection, 0, view, 0)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)

        drawGridIfPresent()

        synchronized(lock) {
            userTrails.values.forEach { trail ->
                trail.buffer.position(0)
                GLES20.glUniformMatrix4fv(uMvpHandle, 1, false, mvp, 0)
                GLES20.glUniform4fv(uColorHandle, 1, trail.color, 0)
                GLES20.glEnableVertexAttribArray(aPositionHandle)
                GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, 3 * 4, trail.buffer)
                GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, trail.pointCount)
                GLES20.glDisableVertexAttribArray(aPositionHandle)
            }
        }
    }

    fun setTrail(userId: String, points: List<FloatArray>) {
        synchronized(lock) {
            userTrails[userId] = buildTrailGeometry(userId, points)
            rebuildGrid()
        }
    }

    fun replaceTrails(trailsByUser: Map<String, List<FloatArray>>) {
        synchronized(lock) {
            userTrails.clear()
            trailsByUser.forEach { (userId, points) ->
                userTrails[userId] = buildTrailGeometry(userId, points)
            }
            rebuildGrid()
        }
    }

    private fun buildTrailGeometry(userId: String, points: List<FloatArray>): TrailGeometry {
        val flat = FloatArray(points.size * 3)
        var i = 0
        points.forEach { p ->
            flat[i++] = p[0]
            flat[i++] = p[1]
            flat[i++] = p[2]
        }
        val buffer = ByteBuffer.allocateDirect(flat.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(flat)
        buffer.position(0)
        return TrailGeometry(
            buffer = buffer,
            pointCount = points.size,
            color = userColor(userId)
        )
    }

    fun minZ(): Float {
        synchronized(lock) {
            return userTrails.values.minOfOrNull { trail ->
                var minZ = Float.MAX_VALUE
                var index = 2
                while (index < trail.pointCount * 3) {
                    minZ = min(minZ, trail.buffer.get(index))
                    index += 3
                }
                minZ
            } ?: 0f
        }
    }

    fun rotateBy(dx: Float, dy: Float) {
        yawDeg += dx * 0.22f
        pitchDeg = (pitchDeg - dy * 0.22f).coerceIn(8f, 82f)
    }

    fun zoomBy(delta: Float) {
        zoom = (zoom + delta).coerceIn(0.55f, 4.0f)
    }

    private fun estimateCenter(): FloatArray {
        synchronized(lock) {
            if (userTrails.isEmpty()) return floatArrayOf(0f, 0f, 0f)

            var sumX = 0f
            var sumY = 0f
            var minZ = Float.MAX_VALUE
            var count = 0
            userTrails.values.forEach { trail ->
                val limit = trail.pointCount * 3
                var idx = 0
                while (idx < limit) {
                    sumX += trail.buffer.get(idx)
                    sumY += trail.buffer.get(idx + 1)
                    minZ = min(minZ, trail.buffer.get(idx + 2))
                    count++
                    idx += 3
                }
            }

            val avgX = if (count == 0) 0f else sumX / count
            val avgY = if (count == 0) 0f else sumY / count
            val centerZ = minZ + 30f
            return floatArrayOf(avgX, avgY, centerZ)
        }
    }

    private fun rebuildGrid() {
        val minZ = minZ()
        gridMinZ = minZ

        var maxAbs = 0f
        userTrails.values.forEach { trail ->
            val limit = trail.pointCount * 3
            var idx = 0
            while (idx < limit) {
                maxAbs = max(maxAbs, kotlin.math.abs(trail.buffer.get(idx)))
                maxAbs = max(maxAbs, kotlin.math.abs(trail.buffer.get(idx + 1)))
                idx += 3
            }
        }

        val half = max(100f, maxAbs + 50f)
        val spacing = max(10f, half / 10f)
        val lineCountPerAxis = ((half * 2) / spacing).toInt() + 1
        val vertexCount = lineCountPerAxis * 4
        val vertices = FloatArray(vertexCount * 3)

        var out = 0
        var value = -half
        repeat(lineCountPerAxis) {
            vertices[out++] = -half
            vertices[out++] = value
            vertices[out++] = gridMinZ
            vertices[out++] = half
            vertices[out++] = value
            vertices[out++] = gridMinZ

            vertices[out++] = value
            vertices[out++] = -half
            vertices[out++] = gridMinZ
            vertices[out++] = value
            vertices[out++] = half
            vertices[out++] = gridMinZ

            value += spacing
        }

        gridBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
            .also { it.position(0) }
        gridPointCount = vertexCount
    }

    private fun drawGridIfPresent() {
        val buffer = gridBuffer ?: return
        buffer.position(0)
        GLES20.glUniformMatrix4fv(uMvpHandle, 1, false, mvp, 0)
        GLES20.glUniform4f(uColorHandle, 0.35f, 0.45f, 0.55f, 1f)
        GLES20.glEnableVertexAttribArray(aPositionHandle)
        GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, 3 * 4, buffer)
        GLES20.glLineWidth(1f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, gridPointCount)
        GLES20.glLineWidth(3f)
        GLES20.glDisableVertexAttribArray(aPositionHandle)
    }

    private fun userColor(userId: String): FloatArray {
        val hue = (kotlin.math.abs(userId.hashCode()) % 360).toFloat()
        val s = 0.72f
        val v = 0.96f
        val c = v * s
        val x = c * (1 - kotlin.math.abs((hue / 60f) % 2 - 1f))
        val m = v - c
        val (r, g, b) = when {
            hue < 60f -> floatArrayOf(c, x, 0f)
            hue < 120f -> floatArrayOf(x, c, 0f)
            hue < 180f -> floatArrayOf(0f, c, x)
            hue < 240f -> floatArrayOf(0f, x, c)
            hue < 300f -> floatArrayOf(x, 0f, c)
            else -> floatArrayOf(c, 0f, x)
        }
        return floatArrayOf(r + m, g + m, b + m, 1f)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES20.glCreateProgram().also { program ->
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
        }
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec3 aPosition;
            uniform mat4 uMvp;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """
    }
}
