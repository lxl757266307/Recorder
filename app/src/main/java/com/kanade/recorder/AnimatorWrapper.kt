package com.kanade.recorder

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View

class AnimatorWrapper {
    val ani = AnimatorSet()
    var duration: Int = 0
        set(value) {
            field = value
        }

    var listener: Animator.AnimatorListener? = null
        set(value) {
            field = value
        }

    fun invoke(block: AnimatorSet.() -> Unit) {
        ani.block()
    }

    fun scaleAnis(target: View, from: Float, to: Float) {
        ani.playTogether(ObjectAnimator.ofFloat(target, "scaleX", from, to), ObjectAnimator.ofFloat(target, "scaleY", from, to))
    }
}

fun AnimatorSet(block: AnimatorWrapper.() -> Unit): AnimatorSet {
    val ani = AnimatorWrapper()
    ani.block()
    return ani.ani
}