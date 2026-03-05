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

data class RenderTrailPoint(
    val x: Float,
    val y: Float,
    val z: Float,
    val rgba: FloatArray
)

class TrailRenderer : GLSurfaceView.Renderer {
    private data class TrailGeometry(
        val buffer: FloatBuffer,
        val pointCount: Int,
        val color: FloatArray
    )

    private val userTrailChunks = mutableMapOf<String, List<TrailGeometry>>()
    private val currentUserPoints = mutableMapOf<String, FloatArray>()
    private val userColors = mutableMapOf<String, FloatArray>()
    private val lock = Any()

    private var program = 0
    private var aPositionHandle = 0
    private var uMvpHandle = 0
    private var uColorHandle = 0
    private var uPointSizeHandle = 0
    private var uPointModeHandle = 0

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)

    private var yawDeg = -35f
    private var pitchDeg = 33f
    private var zoom = 1.8f
    private var panEast = 0f
    private var panNorth = 0f

    private var gridBuffer: FloatBuffer? = null
    private var gridPointCount = 0
    private var gridMinZ = 0f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.03f, 0.04f, 0.08f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glLineWidth(3f)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        uMvpHandle = GLES20.glGetUniformLocation(program, "uMvp")
        uColorHandle = GLES20.glGetUniformLocation(program, "uColor")
        uPointSizeHandle = GLES20.glGetUniformLocation(program, "uPointSize")
        uPointModeHandle = GLES20.glGetUniformLocation(program, "uPointMode")
        Matrix.setIdentityM(model, 0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat().coerceAtLeast(1f)
        Matrix.perspectiveM(projection, 0, 50f, aspect, 1f, 20000f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val center = estimateCenter()
        val extent = estimateExtent()
        val eyeDistance = (max(200f, extent * 1.8f)) * zoom
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
        drawTrails()
        drawCurrentPositions()
    }

    fun replaceTrails(
        trailsByUser: Map<String, List<RenderTrailPoint>>,
        currentPositionsByUser: Map<String, FloatArray>,
        userColorById: Map<String, FloatArray>
    ) {
        synchronized(lock) {
            userTrailChunks.clear()
            trailsByUser.forEach { (userId, points) ->
                userTrailChunks[userId] = buildTrailChunks(points)
            }
            currentUserPoints.clear()
            currentUserPoints.putAll(currentPositionsByUser)
            userColors.clear()
            userColors.putAll(userColorById)
            rebuildGrid()
        }
    }

    fun rotateBy(dx: Float, dy: Float) {
        yawDeg += dx * 0.24f
        pitchDeg = (pitchDeg - dy * 0.20f).coerceIn(2f, 88f)
    }

    fun panBy(dx: Float, dy: Float) {
        val panScale = 0.90f * zoom
        val yawRad = Math.toRadians(yawDeg.toDouble())
        val cosYaw = kotlin.math.cos(yawRad).toFloat()
        val sinYaw = kotlin.math.sin(yawRad).toFloat()

        // Pan in camera space on ground plane:
        // - horizontal drag: left/right relative to camera right vector
        // - vertical drag: toward/away from camera
        val deltaEast = (sinYaw * dx - cosYaw * dy) * panScale
        val deltaNorth = (-cosYaw * dx - sinYaw * dy) * panScale

        panEast += deltaEast
        panNorth += deltaNorth
    }

    fun zoomBy(delta: Float) {
        zoom = (zoom + delta).coerceIn(0.22f, 12.0f)
    }

    private fun drawTrails() {
        synchronized(lock) {
            userTrailChunks.values.flatten().forEach { trail ->
                trail.buffer.position(0)
                GLES20.glUniformMatrix4fv(uMvpHandle, 1, false, mvp, 0)
                GLES20.glUniform4fv(uColorHandle, 1, trail.color, 0)
                GLES20.glUniform1f(uPointSizeHandle, 1f)
                GLES20.glUniform1i(uPointModeHandle, 0)
                GLES20.glEnableVertexAttribArray(aPositionHandle)
                GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, 3 * 4, trail.buffer)
                GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, trail.pointCount)
                GLES20.glDisableVertexAttribArray(aPositionHandle)
            }
        }
    }

    private fun drawCurrentPositions() {
        synchronized(lock) {
            currentUserPoints.forEach { (userId, point) ->
                val color = userColors[userId] ?: floatArrayOf(1f, 1f, 1f, 1f)
                val buffer = floatBufferOf(point)
                GLES20.glUniformMatrix4fv(uMvpHandle, 1, false, mvp, 0)
                GLES20.glUniform4fv(uColorHandle, 1, color, 0)
                GLES20.glUniform1f(uPointSizeHandle, 13f)
                GLES20.glUniform1i(uPointModeHandle, 1)
                GLES20.glEnableVertexAttribArray(aPositionHandle)
                GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, 3 * 4, buffer)
                GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1)
                GLES20.glDisableVertexAttribArray(aPositionHandle)
            }
        }
    }

    private fun buildTrailChunks(points: List<RenderTrailPoint>): List<TrailGeometry> {
        if (points.size < 2) return emptyList()

        val chunks = mutableListOf<TrailGeometry>()
        var currentColor = points.first().rgba
        var current = mutableListOf(points.first())

        for (index in 1 until points.size) {
            val point = points[index]
            if (!sameColor(point.rgba, currentColor)) {
                if (current.size >= 2) {
                    chunks.add(geometryFromPoints(current, currentColor))
                }
                current = mutableListOf(points[index - 1], point)
                currentColor = point.rgba
            } else {
                current.add(point)
            }
        }

        if (current.size >= 2) {
            chunks.add(geometryFromPoints(current, currentColor))
        }

        return chunks
    }

    private fun geometryFromPoints(points: List<RenderTrailPoint>, color: FloatArray): TrailGeometry {
        val flat = FloatArray(points.size * 3)
        var i = 0
        points.forEach { p ->
            flat[i++] = p.x
            flat[i++] = p.y
            flat[i++] = p.z
        }
        val buffer = ByteBuffer.allocateDirect(flat.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(flat)
        buffer.position(0)
        return TrailGeometry(
            buffer = buffer,
            pointCount = points.size,
            color = color
        )
    }

    private fun sameColor(a: FloatArray, b: FloatArray): Boolean {
        if (a.size != b.size) return false
        return a.indices.all { idx -> kotlin.math.abs(a[idx] - b[idx]) < 0.01f }
    }

    private fun estimateCenter(): FloatArray {
        synchronized(lock) {
            if (userTrailChunks.isEmpty() && currentUserPoints.isEmpty()) {
                return floatArrayOf(panEast, panNorth, 0f)
            }

            var sumX = 0f
            var sumY = 0f
            var minZ = Float.MAX_VALUE
            var count = 0

            userTrailChunks.values.flatten().forEach { trail ->
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

            currentUserPoints.values.forEach { p ->
                sumX += p[0]
                sumY += p[1]
                minZ = min(minZ, p[2])
                count++
            }

            val avgX = if (count == 0) 0f else sumX / count
            val avgY = if (count == 0) 0f else sumY / count
            val centerZ = if (minZ.isFinite()) minZ + 35f else 0f
            return floatArrayOf(avgX + panEast, avgY + panNorth, centerZ)
        }
    }

    private fun estimateExtent(): Float {
        synchronized(lock) {
            var maxAbs = 100f
            userTrailChunks.values.flatten().forEach { trail ->
                val limit = trail.pointCount * 3
                var idx = 0
                while (idx < limit) {
                    maxAbs = max(maxAbs, kotlin.math.abs(trail.buffer.get(idx)))
                    maxAbs = max(maxAbs, kotlin.math.abs(trail.buffer.get(idx + 1)))
                    idx += 3
                }
            }
            currentUserPoints.values.forEach { p ->
                maxAbs = max(maxAbs, kotlin.math.abs(p[0]))
                maxAbs = max(maxAbs, kotlin.math.abs(p[1]))
            }
            return maxAbs + 80f
        }
    }

    private fun rebuildGrid() {
        val minZ = minZ()
        gridMinZ = minZ

        val half = estimateExtent()
        val spacing = max(15f, half / 12f)
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

    private fun minZ(): Float {
        synchronized(lock) {
            var minimum = Float.MAX_VALUE
            userTrailChunks.values.flatten().forEach { trail ->
                var index = 2
                while (index < trail.pointCount * 3) {
                    minimum = min(minimum, trail.buffer.get(index))
                    index += 3
                }
            }
            currentUserPoints.values.forEach { point ->
                minimum = min(minimum, point[2])
            }
            return if (minimum == Float.MAX_VALUE) 0f else minimum
        }
    }

    private fun drawGridIfPresent() {
        val buffer = gridBuffer ?: return
        buffer.position(0)
        GLES20.glUniformMatrix4fv(uMvpHandle, 1, false, mvp, 0)
        GLES20.glUniform4f(uColorHandle, 0.28f, 0.35f, 0.48f, 1f)
        GLES20.glUniform1f(uPointSizeHandle, 1f)
        GLES20.glUniform1i(uPointModeHandle, 0)
        GLES20.glEnableVertexAttribArray(aPositionHandle)
        GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, 3 * 4, buffer)
        GLES20.glLineWidth(1f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, gridPointCount)
        GLES20.glLineWidth(3f)
        GLES20.glDisableVertexAttribArray(aPositionHandle)
    }

    private fun floatBufferOf(point: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(point.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(point)
            .also { it.position(0) }
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
            uniform float uPointSize;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                gl_PointSize = uPointSize;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            uniform int uPointMode;
            void main() {
                if (uPointMode == 1) {
                    vec2 c = gl_PointCoord - vec2(0.5, 0.5);
                    float r = length(c);
                    if (r > 0.5) discard;
                    float shade = 1.0 - r * 0.9;
                    gl_FragColor = vec4(uColor.rgb * shade, uColor.a);
                } else {
                    gl_FragColor = uColor;
                }
            }
        """
    }
}
