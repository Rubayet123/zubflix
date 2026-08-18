package com.example.zubflix.util

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup

object ShimmerHelper {

    fun startShimmer(container: ViewGroup) {
        val animators = ArrayList<ObjectAnimator>()
        animateViewGroup(container, animators)
        container.setTag(com.example.R.id.loading_progress, animators)
    }

    private fun animateViewGroup(viewGroup: ViewGroup, animators: ArrayList<ObjectAnimator>) {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is ViewGroup) {
                animateViewGroup(child, animators)
            } else {
                startPulseAnimation(child, animators)
            }
        }
    }

    private fun startPulseAnimation(view: View, animators: ArrayList<ObjectAnimator>) {
        val animator = ObjectAnimator.ofFloat(view, "alpha", 0.3f, 0.85f).apply {
            duration = 750
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
        animators.add(animator)
    }

    fun stopShimmer(container: ViewGroup) {
        @Suppress("UNCHECKED_CAST")
        val animators = container.getTag(com.example.R.id.loading_progress) as? ArrayList<ObjectAnimator>
        animators?.forEach { it.cancel() }
        container.setTag(com.example.R.id.loading_progress, null)
    }
}
