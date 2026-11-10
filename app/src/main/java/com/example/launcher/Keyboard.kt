package com.example.launcher

import android.content.Context
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager

object Keyboard {
    /**
     * Foca o campo e abre o teclado. Em diálogos a janela demora a receber o foco, então
     * tenta algumas vezes (é inofensivo repetir).
     */
    fun show(v: View) {
        v.requestFocus()
        val imm = v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val attempt = Runnable {
            if (v.isAttachedToWindow) {
                v.requestFocus()
                imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        v.windowInsetsController?.show(WindowInsets.Type.ime())
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        v.post(attempt)
        v.postDelayed(attempt, 150)
        v.postDelayed(attempt, 450)
    }
}
