package com.galeria.privada

import android.animation.ObjectAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
        const val EXTRA_CHANGE_MODE = "change_mode"
    }

    private enum class Step { CHOOSE, CREATE_PW, UNLOCK_PW, CREATE_PATTERN_1, CREATE_PATTERN_2, UNLOCK_PATTERN }

    private var appMode = false
    private var changeMode = false
    private var verifiedForChange = false
    private var method = "password"
    private var tempPattern: List<Int>? = null
    private var step = Step.UNLOCK_PW

    private lateinit var card: View
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvError: TextView
    private lateinit var groupChoose: View
    private lateinit var groupPassword: View
    private lateinit var groupPattern: View
    private lateinit var layoutConfirm: TextInputLayout
    private lateinit var input: TextInputEditText
    private lateinit var confirm: TextInputEditText
    private lateinit var btn: MaterialButton
    private lateinit var pattern: PatternView
    private lateinit var tvPatternHint: TextView

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_unlock)
        appMode = intent.getBooleanExtra(EXTRA_APP_MODE, false)
        changeMode = intent.getBooleanExtra(EXTRA_CHANGE_MODE, false)

        card = findViewById(R.id.card)
        tvTitle = findViewById(R.id.tvTitle)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        tvError = findViewById(R.id.tvError)
        groupChoose = findViewById(R.id.groupChoose)
        groupPassword = findViewById(R.id.groupPassword)
        groupPattern = findViewById(R.id.groupPattern)
        layoutConfirm = findViewById(R.id.layoutConfirm)
        input = findViewById(R.id.inputPass)
        confirm = findViewById(R.id.inputConfirm)
        btn = findViewById(R.id.btnEnter)
        pattern = findViewById(R.id.patternView)
        tvPatternHint = findViewById(R.id.tvPatternHint)

        findViewById<View>(R.id.btnChoosePassword).setOnClickListener { chooseMethod("password") }
        findViewById<View>(R.id.btnChoosePattern).setOnClickListener { chooseMethod("pattern") }
        btn.setOnClickListener { onPasswordAction() }
        pattern.onComplete = { pts -> onPatternAction(pts) }

        when {
            changeMode -> {
                method = Vault.authMethod(this)
                tvTitle.text = "Confirme para continuar"
                tvSubtitle.text = if (method == "pattern") "Desenhe o padrão atual" else "Digite a senha atual"
                enterUnlock()
            }
            !Vault.isConfigured(this) -> {
                tvTitle.text = "Proteja sua galeria"
                tvSubtitle.text = "Escolha como você quer desbloquear"
                step = Step.CHOOSE
                show(groupChoose)
            }
            else -> {
                method = Vault.authMethod(this)
                tvTitle.text = "Galeria Privada"
                tvSubtitle.text = if (appMode) "Entre para continuar" else "Libere os arquivos"
                enterUnlock()
            }
        }

        val done = { _: TextView?, _: Int, _: android.view.KeyEvent? -> btn.performClick(); true }
        confirm.setOnEditorActionListener(done)
        input.setOnEditorActionListener(done)

        onBackPressedDispatcher.addCallback(this) {
            if (appMode && !changeMode) {
                finishAffinity()
            } else {
                setResult(RESULT_CANCELED)
                finish()
            }
        }
    }

    private fun show(group: View) {
        groupChoose.visibility = if (group === groupChoose) View.VISIBLE else View.GONE
        groupPassword.visibility = if (group === groupPassword) View.VISIBLE else View.GONE
        groupPattern.visibility = if (group === groupPattern) View.VISIBLE else View.GONE
        btn.visibility = if (group === groupPassword) View.VISIBLE else View.GONE
        tvError.visibility = View.GONE
    }

    private fun enterUnlock() {
        if (method == "pattern") {
            step = Step.UNLOCK_PATTERN
            tvPatternHint.text = "Desenhe seu padrão"
            pattern.reset()
            show(groupPattern)
        } else {
            step = Step.UNLOCK_PW
            layoutConfirm.visibility = View.GONE
            btn.text = if (changeMode) "Entrar" else "Entrar"
            input.text = null
            show(groupPassword)
        }
    }

    private fun chooseMethod(m: String) {
        method = m
        tempPattern = null
        if (m == "password") {
            step = Step.CREATE_PW
            layoutConfirm.visibility = View.VISIBLE
            btn.text = "Criar senha"
            input.text = null; confirm.text = null
            tvTitle.text = "Crie sua senha"
            tvSubtitle.text = "Ela protege todas as fotos e vídeos."
            show(groupPassword)
        } else {
            step = Step.CREATE_PATTERN_1
            tvTitle.text = "Crie seu padrão"
            tvSubtitle.text = "Ele protege todas as fotos e vídeos."
            tvPatternHint.text = "Desenhe um padrão (mínimo 4 pontos)"
            pattern.reset()
            show(groupPattern)
        }
    }

    private fun fail(msg: String) {
        tvError.text = msg
        tvError.visibility = View.VISIBLE
        ObjectAnimator.ofFloat(card, "translationX", 0f, -24f, 24f, -16f, 16f, 0f)
            .setDuration(350).start()
    }

    private fun afterVerifySuccess() {
        if (changeMode && !verifiedForChange) {
            verifiedForChange = true
            tvTitle.text = "Nova proteção"
            tvSubtitle.text = "Escolha como você quer desbloquear"
            tempPattern = null
            step = Step.CHOOSE
            show(groupChoose)
            return
        }
        Session.unlock()
        setResult(RESULT_OK)
        finish()
    }

    private fun onPasswordAction() {
        val pw = input.text?.toString().orEmpty()
        if (step == Step.CREATE_PW) {
            when {
                pw.length < 4 -> fail("Use pelo menos 4 caracteres")
                pw != confirm.text?.toString().orEmpty() -> fail("As senhas não são iguais")
                else -> {
                    Vault.setPassword(this, pw)
                    Vault.setAuthMethod(this, "password")
                    Vault.resetFails(this)
                    afterVerifySuccess()
                }
            }
            return
        }
        val wait = Vault.lockRemainingMs(this)
        if (wait > 0) {
            fail("Muitas tentativas. Aguarde ${wait / 1000 + 1}s")
        } else if (Vault.check(this, pw)) {
            Vault.resetFails(this)
            afterVerifySuccess()
        } else {
            Vault.registerFail(this)
            fail("Senha incorreta")
        }
    }

    private fun onPatternAction(points: List<Int>) {
        when (step) {
            Step.CREATE_PATTERN_1, Step.CREATE_PATTERN_2 -> {
                if (points.size < 4) {
                    pattern.errorMode = true
                    fail("Use pelo menos 4 pontos")
                    handler.postDelayed({
                        step = Step.CREATE_PATTERN_1
                        tempPattern = null
                        tvPatternHint.text = "Desenhe um padrão (mínimo 4 pontos)"
                        pattern.reset()
                    }, 500)
                    return
                }
                if (tempPattern == null) {
                    tempPattern = points
                    step = Step.CREATE_PATTERN_2
                    tvPatternHint.text = "Desenhe novamente para confirmar"
                    pattern.reset()
                } else if (points == tempPattern) {
                    Vault.setPattern(this, points)
                    Vault.setAuthMethod(this, "pattern")
                    Vault.resetFails(this)
                    afterVerifySuccess()
                } else {
                    pattern.errorMode = true
                    fail("Os padrões não coincidem")
                    handler.postDelayed({
                        step = Step.CREATE_PATTERN_1
                        tempPattern = null
                        tvPatternHint.text = "Desenhe um padrão (mínimo 4 pontos)"
                        pattern.reset()
                    }, 600)
                }
            }
            Step.UNLOCK_PATTERN -> {
                val wait = Vault.lockRemainingMs(this)
                when {
                    wait > 0 -> {
                        pattern.errorMode = true
                        fail("Muitas tentativas. Aguarde ${wait / 1000 + 1}s")
                        handler.postDelayed({ tvPatternHint.text = "Desenhe seu padrão"; pattern.reset() }, 600)
                    }
                    Vault.checkPattern(this, points) -> {
                        Vault.resetFails(this)
                        afterVerifySuccess()
                    }
                    else -> {
                        Vault.registerFail(this)
                        pattern.errorMode = true
                        fail("Padrão incorreto")
                        handler.postDelayed({ tvPatternHint.text = "Desenhe seu padrão"; pattern.reset() }, 600)
                    }
                }
            }
            else -> {}
        }
    }
}
