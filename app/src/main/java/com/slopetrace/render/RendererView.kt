package com.slopetrace.render

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.ScaleGestureDetector
import android.view.MotionEvent

class RendererView(context: Context) : GLSurfaceView(context) {
    private val renderer = TrailRenderer()
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val delta = (1f - detector.scaleFactor) * 0.9f
            renderer.zoomBy(delta)
            return true
        }
    })

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

    fun replaceTrails(trailsByUser: Map<String, List<FloatArray>>) {
        renderer.replaceTrails(trailsByUser)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) return true
                val dx = event.x - previousX
                val dy = event.y - previousY
                renderer.rotateBy(dx, dy)
            }
        }
        previousX = event.x
        previousY = event.y
        return true
    }
}
