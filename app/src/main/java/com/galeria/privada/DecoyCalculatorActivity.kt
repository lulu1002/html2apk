package com.galeria.privada

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.math.BigDecimal
import java.math.MathContext

/**
 * Calculadora de verdade que funciona normalmente. Digitar o código secreto
 * (configurável em Galeria Privada > menu) e tocar em "=" abre a galeria.
 */
class DecoyCalculatorActivity : AppCompatActivity() {

    private lateinit var display: TextView
    private var expr = StringBuilder("0")
    private var typedDigits = StringBuilder()
    private var justEvaluated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calc)
        display = findViewById(R.id.display)

        val digitIds = mapOf(
            R.id.btn0 to '0', R.id.btn1 to '1', R.id.btn2 to '2', R.id.btn3 to '3',
            R.id.btn4 to '4', R.id.btn5 to '5', R.id.btn6 to '6', R.id.btn7 to '7',
            R.id.btn8 to '8', R.id.btn9 to '9'
        )
        digitIds.forEach { (id, c) -> findViewById<MaterialButton>(id).setOnClickListener { onDigit(c) } }

        findViewById<MaterialButton>(R.id.btnDot).setOnClickListener { onDot() }
        findViewById<MaterialButton>(R.id.btnAdd).setOnClickListener { onOp('+') }
        findViewById<MaterialButton>(R.id.btnSub).setOnClickListener { onOp('-') }
        findViewById<MaterialButton>(R.id.btnMul).setOnClickListener { onOp('×') }
        findViewById<MaterialButton>(R.id.btnDiv).setOnClickListener { onOp('÷') }
        findViewById<MaterialButton>(R.id.btnPct).setOnClickListener { onPercent() }
        findViewById<MaterialButton>(R.id.btnC).setOnClickListener { clear() }
        findViewById<MaterialButton>(R.id.btnDel).setOnClickListener { onDelete() }
        findViewById<MaterialButton>(R.id.btnEq).setOnClickListener { onEquals() }
    }

    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    private fun refresh() {
        display.text = expr.toString()
    }

    private fun onDigit(c: Char) {
        typedDigits.append(c)
        if (justEvaluated) {
            expr = StringBuilder(c.toString())
            justEvaluated = false
        } else if (expr.toString() == "0") {
            expr = StringBuilder(c.toString())
        } else {
            expr.append(c)
        }
        refresh()
    }

    private fun onDot() {
        justEvaluated = false
        val lastNumber = expr.toString().substringAfterLast(Regex("[+\\-×÷]"))
        if (!lastNumber.contains('.')) {
            expr.append('.')
            refresh()
        }
    }

    private fun onOp(op: Char) {
        justEvaluated = false
        val s = expr.toString()
        if (s.isEmpty()) return
        val last = s.last()
        expr = if (last in "+-×÷") StringBuilder(s.dropLast(1) + op) else StringBuilder(s + op)
        refresh()
    }

    private fun onPercent() {
        val v = currentValue() ?: return
        expr = StringBuilder(format(v.divide(BigDecimal(100), MathContext.DECIMAL64)))
        justEvaluated = false
        refresh()
    }

    private fun clear() {
        expr = StringBuilder("0")
        typedDigits = StringBuilder()
        justEvaluated = false
        refresh()
    }

    private fun onDelete() {
        justEvaluated = false
        if (expr.isNotEmpty()) expr.deleteCharAt(expr.length - 1)
        if (expr.isEmpty()) expr.append('0')
        refresh()
    }

    private fun currentValue(): BigDecimal? = try {
        BigDecimal(expr.toString())
    } catch (e: Exception) {
        null
    }

    private fun format(v: BigDecimal): String {
        val s = v.stripTrailingZeros().toPlainString()
        return if (s == "-0") "0" else s
    }

    private fun evaluate(s: String): BigDecimal? {
        // Split respeitando sinal de negativo no início de número (ex.: 5×-2 não é suportado; simples e suficiente).
        val tokens = Regex("(\\d+\\.?\\d*)|[+\\-×÷]").findAll(s).map { it.value }.toList()
        if (tokens.isEmpty()) return null
        // 1ª passada: × e ÷
        val pass1 = mutableListOf<String>()
        var i = 0
        try {
            while (i < tokens.size) {
                val t = tokens[i]
                if ((t == "×" || t == "÷") && pass1.isNotEmpty() && i + 1 < tokens.size) {
                    val a = BigDecimal(pass1.removeAt(pass1.size - 1))
                    val b = BigDecimal(tokens[i + 1])
                    val r = if (t == "×") a.multiply(b) else a.divide(b, MathContext.DECIMAL64)
                    pass1.add(r.toPlainString())
                    i += 2
                } else {
                    pass1.add(t)
                    i++
                }
            }
            var result = BigDecimal(pass1[0])
            var j = 1
            while (j < pass1.size - 1) {
                val op = pass1[j]
                val b = BigDecimal(pass1[j + 1])
                result = if (op == "+") result.add(b) else result.subtract(b)
                j += 2
            }
            return result
        } catch (e: Exception) {
            return null
        }
    }

    private fun onEquals() {
        val code = DisguiseManager.code(this)
        if (code.isNotEmpty() && typedDigits.toString().endsWith(code)) {
            typedDigits = StringBuilder()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        val r = evaluate(expr.toString())
        if (r != null) {
            expr = StringBuilder(format(r))
            justEvaluated = true
            typedDigits = StringBuilder()
            refresh()
        }
    }
}
