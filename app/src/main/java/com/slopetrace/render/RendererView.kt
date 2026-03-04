package com.slopetrace.render

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import kotlin.math.max

class RendererView(context: Context) : GLSurfaceView(context) {
    private val renderer = TrailRenderer()

    private var scale = 1f
    private var previousX = 0f
    private var previousY = 0f

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun updateTrail(userId: String, points: List<FloatArray>) {
        renderer.setTrail(userId, points)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - previousX
                val dy = event.y - previousY
                // Minimal interaction hook; integrate camera transform in shader matrices.
                scale = max(0.5f, scale + (dx - dy) * 0.0005f)
            }
        }
        previousX = event.x
        previousY = event.y
        return true
    }
}
