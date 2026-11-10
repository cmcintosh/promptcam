package com.wembassy.promptcam.teleprompter

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State management for the teleprompter overlay.
 * 
 * This class holds the current teleprompter text, scroll position,
 * font size, and scroll speed. It's updated by the ControlServer
 * when messages arrive from the desktop client, and observed by
 * the Compose UI for rendering.
 * 
 * The teleprompter displays scrolling text overlaid on the camera
 * preview. The scroll position (0.0 = top, 1.0 = bottom) is set
 * by the desktop client to control where in the text the user
 * should be reading.
 */
class TeleprompterState {

    companion object {
        private const val TAG = "TeleprompterState"
    }

    // Backing fields for state
    private val _text = MutableStateFlow("")
    private val _fontSize = MutableStateFlow(28)  // in sp
    private val _speed = MutableStateFlow(1.0f)   // scroll speed multiplier
    private val _scrollPosition = MutableStateFlow(0.0f)  // 0.0 = top, 1.0 = bottom
    private val _visible = MutableStateFlow(true)
    private val _hasContent = MutableStateFlow(false)

    // Public state flows observed by Compose
    val text: StateFlow<String> = _text.asStateFlow()
    val fontSize: StateFlow<Int> = _fontSize.asStateFlow()
    val speed: StateFlow<Float> = _speed.asStateFlow()
    val scrollPosition: StateFlow<Float> = _scrollPosition.asStateFlow()
    val visible: StateFlow<Boolean> = _visible.asStateFlow()
    val hasContent: StateFlow<Boolean> = _hasContent.asStateFlow()

    /**
     * Update the teleprompter text content.
     * Called when a 0x03 message is received from the desktop.
     * 
     * @param newText The text to display
     * @param newFontSize Font size in sp (scaled pixels)
     * @param newSpeed Scroll speed multiplier (1.0 = normal)
     */
    fun updateText(newText: String, newFontSize: Int, newSpeed: Float) {
        _text.value = newText
        _fontSize.value = newFontSize
        _speed.value = newSpeed
        _hasContent.value = newText.isNotEmpty()
        Log.i(TAG, "Text updated: ${newText.length} chars, fontSize=$newFontSize, speed=$newSpeed")
    }

    /**
     * Update the scroll position.
     * Called when a 0x04 message is received from the desktop.
     * 
     * @param position Scroll position (0.0 = top of text, 1.0 = bottom)
     */
    fun updateScrollPosition(position: Float) {
        val clamped = position.coerceIn(0.0f, 1.0f)
        _scrollPosition.value = clamped
        // Log spam reduction: only log on significant changes
        // Log.d(TAG, "Scroll position: $clamped")
    }

    /**
     * Show or hide the teleprompter overlay.
     */
    fun setVisible(isVisible: Boolean) {
        _visible.value = isVisible
    }

    /**
     * Clear all teleprompter content.
     */
    fun clear() {
        _text.value = ""
        _scrollPosition.value = 0.0f
        _hasContent.value = false
        Log.i(TAG, "Teleprompter cleared")
    }

    /**
     * Handle a control command (0x05) that affects the teleprompter.
     * Returns true if the command was handled.
     */
    fun handleControlCommand(command: String): Boolean {
        return when (command) {
            "hide" -> {
                setVisible(false)
                true
            }
            "show" -> {
                setVisible(true)
                true
            }
            "clear" -> {
                clear()
                true
            }
            else -> false
        }
    }
}
