package com.slopetrace.render

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class TrailRenderer : GLSurfaceView.Renderer {
    private val userTrails = mutableMapOf<String, MutableList<FloatArray>>()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.05f, 0.07f, 0.12f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        // MVP placeholder: this renderer stores trail geometry; drawing shader pipeline can be expanded.
    }

    fun setTrail(userId: String, points: List<FloatArray>) {
        userTrails[userId] = points.toMutableList()
    }

    fun minZ(): Float {
        return userTrails.values.flatMap { it.asIterable() }.minOfOrNull { it[2] } ?: 0f
    }
}
