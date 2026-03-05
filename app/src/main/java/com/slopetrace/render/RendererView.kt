package com.slopetrace.render

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ScaleGestureDetector

class RendererView(context: Context) : GLSurfaceView(context) {
    private val renderer = TrailRenderer()
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val delta = (1f - detector.scaleFactor) * 1.35f
            renderer.zoomBy(delta)
            return true
        }
    })

    private var previousX = 0f
    private var previousY = 0f
    private var previousFocusX = 0f
    private var previousFocusY = 0f

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun replaceTrails(
        trailsByUser: Map<String, List<RenderTrailPoint>>,
        currentPositionsByUser: Map<String, FloatArray>,
        userColorById: Map<String, FloatArray>
    ) {
        renderer.replaceTrails(
            trailsByUser = trailsByUser,
            currentPositionsByUser = currentPositionsByUser,
            userColorById = userColorById
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previousX = event.x
                previousY = event.y
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                previousFocusX = focusX(event)
                previousFocusY = focusY(event)
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    val dx = event.x - previousX
                    val dy = event.y - previousY
                    renderer.panBy(dx, dy)
                    previousX = event.x
                    previousY = event.y
                } else if (event.pointerCount >= 2) {
                    val fx = focusX(event)
                    val fy = focusY(event)
                    val dx = fx - previousFocusX
                    val dy = fy - previousFocusY
                    renderer.rotateBy(dx, dy)
                    previousFocusX = fx
                    previousFocusY = fy
                }
            }
        }
        return true
    }

    private fun focusX(event: MotionEvent): Float {
        var sum = 0f
        repeat(event.pointerCount) { idx -> sum += event.getX(idx) }
        return sum / event.pointerCount.toFloat().coerceAtLeast(1f)
    }

    private fun focusY(event: MotionEvent): Float {
        var sum = 0f
        repeat(event.pointerCount) { idx -> sum += event.getY(idx) }
        return sum / event.pointerCount.toFloat().coerceAtLeast(1f)
    }
}
