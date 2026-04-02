package com.example.jusay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast

class OverlayManager(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayButton: View? = null

    fun showMicOverlay() {
        if (overlayButton != null) return

        val button = Button(context).apply {
            text = "MIC"
            alpha = 0.92f
            setOnClickListener {
                val result = VoiceAgentService.requestManualListening()
                Toast.makeText(context, result, Toast.LENGTH_SHORT).show()
            }
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 220
        }

        windowManager.addView(button, params)
        overlayButton = button
    }

    fun hideMicOverlay() {
        val view = overlayButton ?: return
        windowManager.removeView(view)
        overlayButton = null
    }
}
