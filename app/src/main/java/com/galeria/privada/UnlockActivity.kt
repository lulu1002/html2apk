package com.galeria.privada

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class UnlockActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APP_MODE = "app_mode"
    }

    private var appMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_unlock)
        appMode = intent.getBooleanExtra(EXTRA_APP_MODE, false)

        val creating = !Vault.hasPassword(this)
        val card = findViewById<View>(R.id.card)
        val tvTitle = findViewById<TextView>(R.id.tvTitle)
        val tvSubtitle = findViewById<TextView>(R.id.tvSubtitle)
        val tvError = findViewById<TextView>(R.id.tvError)
        val layoutConfirm = findViewById<TextInputLayout>(R.id.layoutConfirm)
        val input = findViewById<TextInputEditText>(R.id.inputPass)
        val confirm = findViewById<TextInputEditText>(R.id.inputConfirm)
        val btn = findViewById<MaterialButton>(R.id.btnEnter)

        if (creating) {
            tvTitle.text = "Crie sua senha"
            tvSubtitle.text = "Ela protege todas as fotos e vídeos da sua galeria."
            layoutConfirm.visibility = View.VISIBLE
            btn.text = "Criar senha"
        } else {
            tvTitle.text = "Galeria Privada"
            tvSubtitle.text = if (appMode) "Digite sua senha para entrar"
            else "Digite a senha para liberar os arquivos"
        }

        fun fail(msg: String) {
            tvError.text = msg
            tvError.visibility = View.VISIBLE
            ObjectAnimator.ofFloat(card, "translationX", 0f, -24f, 24f, -16f, 16f, 0f)
                .setDuration(350).start()
        }

        fun success() {
            Session.unlock()
            setResult(RESULT_OK)
            finish()
        }

        btn.setOnClickListener {
            val pw = input.text?.toString().orEmpty()
            if (creating) {
                when {
                    pw.length < 4 -> fail("Use pelo menos 4 caracteres")
                    pw != confirm.text?.toString().orEmpty() -> fail("As senhas não são iguais")
                    else -> {
                        Vault.setPassword(this, pw)
                        success()
                    }
                }
            } else {
                val wait = Vault.lockRemainingMs(this)
                if (wait > 0) {
                    fail("Muitas tentativas. Aguarde ${wait / 1000 + 1}s")
                } else if (Vault.check(this, pw)) {
                    Vault.resetFails(this)
                    success()
                } else {
                    Vault.registerFail(this)
                    fail("Senha incorreta")
                }
            }
        }

        val done = { _: TextView?, _: Int, _: android.view.KeyEvent? -> btn.performClick(); true }
        confirm.setOnEditorActionListener(done)
        if (!creating) input.setOnEditorActionListener(done)

        onBackPressedDispatcher.addCallback(this) {
            if (appMode) {
                finishAffinity()
            } else {
                setResult(RESULT_CANCELED)
                finish()
            }
        }
    }
}
