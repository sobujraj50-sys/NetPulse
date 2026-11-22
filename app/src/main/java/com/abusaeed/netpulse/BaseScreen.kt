package com.abusaeed.netpulse

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Shared look for the tool screens: dark top bar with back + title, scrolling content. */
abstract class BaseScreen : AppCompatActivity() {

    protected lateinit var p: Palette
    protected lateinit var content: LinearLayout

    abstract val titleRes: Int
    abstract fun buildContent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Monitor.init(this)
        p = Palette((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.header) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(12), dp(10))
        }
        top.addView(tv("←", 24f, Color.WHITE).apply { setPadding(dp(8), 0, dp(14), 0); setOnClickListener { finish() } })
        top.addView(tv(getString(titleRes), 19f, Color.WHITE, true))
        root.addView(top)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            setPadding(dp(12), dp(10), dp(12), dp(24))
        }
        root.addView(ScrollView(this).apply { setBackgroundColor(p.bg); addView(content) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        buildContent()
    }

    protected fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    protected fun mw() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    protected fun str(id: Int) = getString(id)

    protected fun tv(s: CharSequence, size: Float, color: Int = p.text, bold: Boolean = false, g: Int = Gravity.START) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); gravity = g
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    protected fun button(s: String, bg: Int, onClick: () -> Unit) = tv(s, 15f, Color.WHITE, true, Gravity.CENTER).apply {
        background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(12).toFloat() }
        setPadding(dp(14), dp(11), dp(14), dp(11))
        setOnClickListener { onClick() }
    }

    protected fun section(title: String, into: LinearLayout = content) {
        into.addView(tv(title, 17f, p.text, true).apply { setPadding(0, dp(14), 0, dp(6)) }, mw())
    }

    protected fun card(into: LinearLayout = content): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.panel)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        into.addView(c, mw().apply { bottomMargin = dp(6) })
        return c
    }

    protected fun kvRow(into: LinearLayout, k: String, v: String, vColor: Int = p.text) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, dp(3)) }
        r.addView(tv(k, 14f, p.label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(tv(v, 14f, vColor, g = Gravity.END), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        into.addView(r, mw())
    }

    protected fun note(s: String, into: LinearLayout = content) {
        into.addView(tv(s, 12f, p.label).apply { setPadding(0, dp(6), 0, dp(6)) }, mw())
    }

    protected fun colored(text: String, color: Int): CharSequence {
        val sb = SpannableStringBuilder()
        sb.append(text, ForegroundColorSpan(color), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    protected fun spacer(h: Int = 8): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
}
