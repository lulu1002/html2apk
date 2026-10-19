package com.example.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast

/**
 * Autenticação para o bloqueio por app. Usa a senha própria do launcher (modo 1, se já
 * definida) ou a senha/PIN/padrão/digital do celular (modo 0).
 */
class LockHelper(private val act: Activity, private val prefs: Prefs) {

    companion object {
        private const val REQ = 7001
        private const val MAX_FAILURES = 5
        private const val BLOCK_MS = 30_000L
        private var failures = 0
        private var blockedUntil = 0L
    }

    private var pending: (() -> Unit)? = null
    private var pendingCancel: (() -> Unit)? = null
    private var dialog: AlertDialog? = null

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density).toInt()

    private fun usesOwnPin(): Boolean = prefs.lockMode == 1 && prefs.hasPin()

    private fun isDeviceSecure(): Boolean =
        (act.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    /** Há como proteger? (senha própria ativa ou celular com bloqueio de tela) */
    fun canProtect(): Boolean = usesOwnPin() || isDeviceSecure()

    /**
     * Pede a senha e executa [onSuccess]; em cancelamento ou erro final chama [onCancel].
     * Sem senha própria e sem bloqueio no aparelho não há como proteger: executa direto.
     */
    fun authenticate(title: String, onCancel: (() -> Unit)? = null, onSuccess: () -> Unit) {
        if (usesOwnPin()) {
            askPin(title, onCancel, onSuccess)
            return
        }
        val km = act.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val i = km.createConfirmDeviceCredentialIntent(title, "Confirme que é você")
        if (i == null) {
            onSuccess()
            return
        }
        pending = onSuccess
        pendingCancel = onCancel
        try {
            @Suppress("DEPRECATION")
            act.startActivityForResult(i, REQ)
        } catch (e: Exception) {
            pending = null
            pendingCancel = null
            Toast.makeText(act, "Não foi possível pedir a autenticação", Toast.LENGTH_SHORT).show()
            onCancel?.invoke()
        }
    }

    /** Chamar no onActivityResult da activity. Retorna true se o resultado era desta classe. */
    fun handleResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQ) return false
        val ok = pending
        val cancel = pendingCancel
        pending = null
        pendingCancel = null
        if (resultCode == Activity.RESULT_OK) ok?.invoke() else cancel?.invoke()
        return true
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    private fun askPin(title: String, onCancel: (() -> Unit)?, onSuccess: () -> Unit) {
        val now = SystemClock.elapsedRealtime()
        if (now < blockedUntil) {
            val s = (blockedUntil - now) / 1000 + 1
            Toast.makeText(act, "Muitas tentativas. Aguarde ${s}s", Toast.LENGTH_SHORT).show()
            onCancel?.invoke()
            return
        }

        val et = EditText(act).apply {
            hint = "Senha"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            setSingleLine()
        }
        val box = FrameLayout(act).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(et)
        }

        var done = false
        val d = AlertDialog.Builder(act)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK", null) // tratado abaixo, para não fechar em senha errada
            .setNegativeButton("Cancelar", null)
            .setOnDismissListener {
                if (!done && !act.isDestroyed) onCancel?.invoke()
            }
            .create()

        d.setOnShowListener {
            val ok = d.getButton(AlertDialog.BUTTON_POSITIVE)
            val check = {
                if (prefs.checkPin(et.text.toString())) {
                    failures = 0
                    done = true
                    d.dismiss()
                    onSuccess()
                } else {
                    failures++
                    et.setText("")
                    if (failures >= MAX_FAILURES) {
                        failures = 0
                        blockedUntil = SystemClock.elapsedRealtime() + BLOCK_MS
                        Toast.makeText(act, "Muitas tentativas. Aguarde 30s", Toast.LENGTH_SHORT).show()
                        d.dismiss()
                    } else {
                        et.error = "Senha incorreta"
                    }
                }
            }
            ok.setOnClickListener { check() }
            et.setOnEditorActionListener { _, _, _ ->
                check()
                true
            }
        }
        dialog = d
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
    }
}
