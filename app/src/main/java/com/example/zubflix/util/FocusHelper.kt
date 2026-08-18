package com.example.zubflix.util

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.children

/**
 * Centralized Focus Management and D-pad TV Focus Animation utility for Zubflix.
 * Ensures consistent visual feedback, accessible touch/focus targets, and seamless focus restoration.
 */
object FocusHelper {

    /**
     * Applies standard Leanback TV focus scaling, elevation, and visual highlight.
     */
    fun applyTvFocus(
        view: View,
        scale: Float = 1.08f,
        elevationDp: Float = 6f,
        onFocusChanged: ((View, Boolean) -> Unit)? = null
    ) {
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        val density = view.resources.displayMetrics.density
        val elevationPx = elevationDp * density

        view.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .translationZ(elevationPx)
                    .setDuration(150)
                    .start()
            } else {
                v.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .translationZ(0f)
                    .setDuration(120)
                    .start()
            }
            onFocusChanged?.invoke(v, hasFocus)
        }
    }

    /**
     * Applies TV focus styling for button controls and clickable containers.
     */
    fun applyControlFocus(
        view: View,
        scale: Float = 1.15f,
        highlightBackground: Boolean = false
    ) {
        view.isFocusable = true
        view.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
            }
        }
    }

    /**
     * Safe focus requester that posts to ensure the view hierarchy has been laid out.
     */
    fun safeRequestFocus(view: View?, delayMs: Long = 50L) {
        if (view == null) return
        view.postDelayed({
            if (view.isAttachedToWindow && view.visibility == View.VISIBLE) {
                view.requestFocus()
            }
        }, delayMs)
    }

    /**
     * Finds the first focusable child in a ViewGroup hierarchy.
     */
    fun findFirstFocusableChild(viewGroup: ViewGroup): View? {
        for (child in viewGroup.children) {
            if (child.visibility == View.VISIBLE) {
                if (child.isFocusable) return child
                if (child is ViewGroup) {
                    val subChild = findFirstFocusableChild(child)
                    if (subChild != null) return subChild
                }
            }
        }
        return null
    }
}
