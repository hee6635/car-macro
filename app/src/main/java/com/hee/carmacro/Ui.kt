package com.hee.carmacro

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 어두운 테마 화면 부품 (시안 v2 스타일) */
class Ui(private val a: Activity) {
    companion object {
        val BG = Color.parseColor("#0E1621")
        val CARD = Color.parseColor("#18222F")
        val CARD2 = Color.parseColor("#121B26")
        val BORDER = Color.parseColor("#2A3646")
        val BORDER2 = Color.parseColor("#3A4A5E")
        val TEXT = Color.parseColor("#F2F5F8")
        val TEXT2 = Color.parseColor("#C7D1DC")
        val SUB = Color.parseColor("#9AA8B8")
        val GREEN = Color.parseColor("#4CC38A")
        val GREEN_D = Color.parseColor("#2E7D5B")
        val GREEN_BG = Color.parseColor("#16261F")
        val WARN = Color.parseColor("#F5A524")
        val WARN_BG = Color.parseColor("#3A2C14")
        val WARN_BORDER = Color.parseColor("#B9852A")
        val RED = Color.parseColor("#A8474E")
        val ORANGE_BG = Color.parseColor("#2A1E14")
        val ORANGE = Color.parseColor("#B5562B")
    }

    fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()

    fun bg(color: Int, radius: Int, stroke: Int? = null, strokeW: Int = 1) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(strokeW), stroke)
    }

    fun lp(w: Int = MATCH_PARENT, h: Int = WRAP_CONTENT, top: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) }

    fun col(gap: Int = 0) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        if (gap > 0) { showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE; dividerDrawable = spacer(gap) }
    }

    fun row() = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    private fun spacer(h: Int) = GradientDrawable().apply { setSize(dp(h), dp(h)); setColor(Color.TRANSPARENT) }

    /** 화면 하나: 스크롤 + (뒤로) 제목. 본문 LinearLayout 반환 */
    fun page(title: String?, onBack: (() -> Unit)?, bottom: View? = null): LinearLayout {
        val outer = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        val body = col(14).apply { setPadding(dp(16), dp(16), dp(16), dp(28)) }
        if (title != null || onBack != null) {
            val head = row()
            if (onBack != null) {
                head.addView(TextView(a).apply {
                    text = "‹"; textSize = 30f; setTextColor(TEXT); gravity = Gravity.CENTER
                    contentDescription = "뒤로"; setOnClickListener { onBack() }
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
            }
            if (title != null) head.addView(text(title, 21f, TEXT, bold = true))
            body.addView(head)
        }
        val scroll = ScrollView(a).apply { addView(body); isFillViewport = true }
        outer.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        if (bottom != null) {
            val f = FrameLayout(a).apply { setPadding(dp(16), dp(8), dp(16), dp(16)); addView(bottom) }
            outer.addView(f)
        }
        // 안드로이드 15부터 화면이 상태 표시줄/내비게이션 바 밑까지 깔리므로 그만큼 여백
        outer.setOnApplyWindowInsetsListener { v, insets ->
            val (top, bottomInset) = barInsets(insets)
            v.setPadding(0, top, 0, bottomInset)
            insets
        }
        a.setContentView(outer)
        outer.requestApplyInsets()
        return body
    }

    @Suppress("DEPRECATION")
    private fun barInsets(insets: android.view.WindowInsets): Pair<Int, Int> =
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
            bars.top to bars.bottom
        } else {
            insets.systemWindowInsetTop to insets.systemWindowInsetBottom
        }

    fun text(s: String, size: Float = 15f, color: Int = TEXT, bold: Boolean = false, mono: Boolean = false) =
        TextView(a).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            if (mono) typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.2f)
        }

    fun LinearLayout.h1(s: String) = text(s, 25f, TEXT, bold = true).also { addView(it) }
    fun LinearLayout.p(s: String, color: Int = SUB, size: Float = 14f) = text(s, size, color).also { addView(it) }
    fun LinearLayout.section(s: String) = text(s, 13f, SUB, bold = true).also {
        it.setPadding(dp(4), dp(6), 0, 0); addView(it)
    }

    fun LinearLayout.card(color: Int = CARD, stroke: Int? = BORDER, pad: Int = 16, gap: Int = 10): LinearLayout {
        val c = col(gap).apply {
            background = bg(color, 18, stroke); setPadding(dp(pad), dp(pad - 2), dp(pad), dp(pad - 2))
        }
        addView(c, lp())
        return c
    }

    /** 카드 안의 목록 줄. trailing: 오른쪽 버튼/글자 */
    fun LinearLayout.item(title: String, sub: String? = null, trailing: View? = null, divider: Boolean = true,
                          lead: View? = null, onClick: (() -> Unit)? = null): LinearLayout {
        val r = row().apply { setPadding(0, dp(12), 0, dp(12)) }
        if (divider && childCount > 0) addView(View(a).apply { setBackgroundColor(BORDER) }, LinearLayout.LayoutParams(MATCH_PARENT, 1))
        if (lead != null) {
            val llp = (lead.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            llp.marginEnd = dp(12)
            r.addView(lead, llp)
        }
        val t = col()
        t.addView(text(title, 15f, TEXT))
        if (sub != null) t.addView(text(sub, 12f, SUB))
        r.addView(t, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (trailing != null) r.addView(trailing)
        else if (onClick != null) r.addView(text("›", 22f, SUB))
        if (onClick != null) r.setOnClickListener { onClick() }
        addView(r, lp())
        return r
    }

    fun pill(s: String, primary: Boolean = false, onClick: () -> Unit) = TextView(a).apply {
        text = s; textSize = 13f; gravity = Gravity.CENTER
        setPadding(dp(14), 0, dp(14), 0); minHeight = dp(36); minWidth = dp(56)
        if (primary) { setTextColor(BG); setTypeface(null, Typeface.BOLD); background = bg(GREEN, 18) }
        else { setTextColor(TEXT); background = bg(Color.TRANSPARENT, 18, BORDER2) }
        setOnClickListener { onClick() }
    }

    fun primary(s: String, enabled: Boolean = true, color: Int = GREEN, onClick: () -> Unit) = Button(a).apply {
        text = s; isAllCaps = false; textSize = 17f; setTypeface(null, Typeface.BOLD)
        stateListAnimator = null
        isEnabled = enabled
        setTextColor(if (enabled) BG else SUB)
        background = bg(if (enabled) color else BORDER, 16)
        minHeight = dp(56)
        setOnClickListener { onClick() }
    }

    fun secondary(s: String, onClick: () -> Unit) = Button(a).apply {
        text = s; isAllCaps = false; textSize = 16f; setTextColor(TEXT); stateListAnimator = null
        background = bg(Color.TRANSPARENT, 16, BORDER2); minHeight = dp(56)
        setOnClickListener { onClick() }
    }

    /** 아래쪽 버튼 줄 (이전 / 다음) */
    fun bottomBar(vararg buttons: Pair<Button, Float>) = row().apply {
        buttons.forEachIndexed { i, (b, w) ->
            addView(b, LinearLayout.LayoutParams(if (w == 0f) WRAP_CONTENT else 0, dp(56), w).apply {
                if (i > 0) marginStart = dp(10)
            })
        }
    }

    fun dot(color: Int, size: Int = 10) = View(a).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    /** 원 안의 글자/기호 (체크, 번호 등) */
    fun badge(s: String, fill: Int?, stroke: Int? = null, size: Int = 26) = TextView(a).apply {
        text = s; gravity = Gravity.CENTER; textSize = 12f; setTypeface(null, Typeface.BOLD)
        setTextColor(if (fill != null) Color.WHITE else (stroke ?: SUB))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(fill ?: Color.TRANSPARENT)
            if (stroke != null) setStroke(dp(2), stroke)
        }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    fun dotLine(color: Int, s: String) = row().apply {
        addView(dot(color, 8)); addView(text(s, 13f, TEXT).apply { setPadding(dp(8), 0, 0, 0) })
    }

    /** 위쪽 진행 막대 (n/total 단계) */
    fun LinearLayout.steps(n: Int, total: Int) {
        val r = row()
        for (i in 1..total) {
            r.addView(View(a).apply { background = bg(if (i <= n) GREEN else BORDER, 2) },
                LinearLayout.LayoutParams(0, dp(4), 1f).apply { if (i > 1) marginStart = dp(6) })
        }
        addView(r, lp())
    }

    /** 그대로/켬/끔 같은 선택 버튼 묶음 */
    fun segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val r = row().apply { background = bg(CARD2, 10); setPadding(dp(3), dp(3), dp(3), dp(3)) }
        val views = mutableListOf<TextView>()
        fun paint(sel: Int) = views.forEachIndexed { i, v ->
            v.setTextColor(if (i == sel) TEXT else SUB)
            v.background = if (i == sel) bg(if (options[i] == "켬") GREEN_D else BORDER2, 8) else null
        }
        options.forEachIndexed { i, o ->
            val v = text(o, 12f, SUB).apply {
                gravity = Gravity.CENTER; setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { paint(i); onSelect(i) }
            }
            views += v; r.addView(v)
        }
        paint(selected)
        return r
    }

    fun input(value: String, hint: String = "", mono: Boolean = false, number: Boolean = false) = EditText(a).apply {
        setText(value); this.hint = hint; isSingleLine = true; textSize = 16f
        setTextColor(TEXT); setHintTextColor(SUB)
        if (mono) typeface = Typeface.MONOSPACE
        inputType = if (number) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        background = bg(CARD, 14, GREEN, 2)
        setPadding(dp(16), 0, dp(16), 0); minHeight = dp(56)
    }
}
