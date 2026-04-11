package com.calmyjane.spacebeam

import android.content.Context
import android.view.*
import android.os.Bundle
import android.app.Presentation
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display

class ExternalDisplayHelper(
    private val context: Context,
    private val renderer: KaleidoscopeRenderer
) {
    private var presentation: CleanFeedPresentation? = null
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = updatePresentation()
        override fun onDisplayChanged(displayId: Int) = updatePresentation()
        override fun onDisplayRemoved(displayId: Int) = updatePresentation()
    }

    fun start() {
        displayManager.registerDisplayListener(displayListener, null)
        updatePresentation()
    }

    fun stop() {
        displayManager.unregisterDisplayListener(displayListener)
        dismissPresentation()
    }

    private fun dismissPresentation() {
        try {
            presentation?.dismiss()
        } catch (e: Exception) {
            Log.e("ExternalDisplay", "Error dismissing", e)
        }
        presentation = null
    }

    fun updatePresentation() {
        val allDisplays = displayManager.displays
        var targetDisplay: Display? = null

        for (display in allDisplays) {
            if (display.displayId != Display.DEFAULT_DISPLAY) {
                if ((display.flags and Display.FLAG_PRESENTATION) != 0) {
                    targetDisplay = display
                    break
                }
                if (targetDisplay == null) {
                    targetDisplay = display
                }
            }
        }

        if (targetDisplay != null) {
            if (presentation != null && presentation!!.display.displayId != targetDisplay.displayId) {
                dismissPresentation()
            }

            if (presentation == null) {
                presentation = CleanFeedPresentation(context, targetDisplay, renderer)
                try {
                    presentation?.show()
                } catch (e: Exception) {
                    Log.e("ExternalDisplay", "Failed to show presentation", e)
                    presentation = null
                }
            }
        } else {
            dismissPresentation()
            renderer.removeExternalSurface()
        }
    }

    private class CleanFeedPresentation(
        ctx: Context,
        display: Display,
        val renderer: KaleidoscopeRenderer
    ) : Presentation(ctx, display) {

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val surfaceView = SurfaceView(context)
            setContentView(surfaceView)

            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    renderer.setExternalSurface(holder.surface, display.width, display.height)
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    renderer.setExternalSurface(holder.surface, width, height)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    renderer.removeExternalSurface()
                }
            })
        }
    }
}





