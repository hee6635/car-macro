package com.hee.carmacro

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 화면 구성 (시안 v2)
 *  처음: role → (리모컨) pair → home  /  (수신기) pixelSetup → pixelHome
 *  리모컨: home → test, history, settings → log
 *  수신기: pixelHome → presets, pixelTest, dump, pixelSettings → log
 */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var ui: Ui
    private val stack = ArrayList<String>()
    private val handler = Handler(Looper.getMainLooper())

    /** 상태판 화면은 설정 값이 바뀌면 다시 그림 */
    private val liveScreens = setOf("home", "pixelHome", "pixelSetup", "pair", "dump", "log", "history")
    private var engineText: TextView? = null
    private val ticker = object : Runnable {
        override fun run() {
            engineText?.text = engineLine()
            handler.postDelayed(this, 1000)
        }
    }
    private var redrawPending = false
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (current() in liveScreens && !redrawPending) {
            redrawPending = true
            handler.postDelayed({ redrawPending = false; render() }, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ui = Ui(this)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BG
        Notifier.ensureChannels(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (prefs.mode == Prefs.MODE_SENDER && prefs.setupDone) StatusWorker.schedule(this)
        stack.add(startScreen())
    }

    override fun onResume() {
        super.onResume()
        prefs.sp.registerOnSharedPreferenceChangeListener(listener)
        if (prefs.mode == Prefs.MODE_SENDER && prefs.isPaired) StatusWorker.refreshNow(this)
        render()
        handler.post(ticker)
    }

    override fun onPause() {
        prefs.sp.unregisterOnSharedPreferenceChangeListener(listener)
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (stack.size > 1) {
            stack.removeAt(stack.size - 1); render()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun startScreen() = when {
        !prefs.setupDone -> "role"
        prefs.mode == Prefs.MODE_RECEIVER -> "pixelHome"
        else -> "home"
    }

    private fun current() = stack.lastOrNull() ?: "home"
    private fun go(s: String) { stack.add(s); render() }
    @Suppress("DEPRECATION")
    private fun back() = onBackPressed()
    private fun reset(s: String) { stack.clear(); stack.add(s); render() }

    private fun render() {
        engineText = null
        when (current()) {
            "role" -> roleScreen()
            "pair" -> pairScreen()
            "home" -> homeScreen()
            "test" -> testScreen(remote = true)
            "history" -> historyScreen()
            "settings" -> settingsScreen()
            "log" -> logScreen()
            "pixelSetup" -> pixelSetupScreen()
            "pixelHome" -> pixelHomeScreen()
            "presets" -> presetScreen()
            "pixelTest" -> testScreen(remote = false)
            "dump" -> dumpScreen()
            "pixelSettings" -> pixelSettingsScreen()
            else -> homeScreen()
        }
    }

    // ═════════════════════════ 처음 실행 ═════════════════════════

    private var roleChoice: String? = null

    private fun roleScreen() = with(ui) {
        val choice = roleChoice ?: prefs.mode
        val next = primary("다음") {
            prefs.mode = roleChoice ?: prefs.mode
            if (prefs.mode == Prefs.MODE_RECEIVER) go("pixelSetup") else go("pair")
        }
        val bar = if (prefs.setupDone) bottomBar(secondary("취소") { back() } to 0f, next to 1f) else bottomBar(next to 1f)
        val b = page(null, null, bar)
        b.steps(1, 3)
        b.h1("이 폰은 어떤 역할인가요?")
        b.p("같은 앱을 두 폰에 깔고, 각각 역할을 정해요. 나중에 설정에서 바꿀 수 있어요.")
        fun option(mode: String, title: String, desc: String) {
            val sel = choice == mode
            val c = b.card(if (sel) Ui.GREEN_BG else Ui.CARD, if (sel) Ui.GREEN else Ui.BORDER, pad = 20)
            c.background = bg(if (sel) Ui.GREEN_BG else Ui.CARD, 18, if (sel) Ui.GREEN else Ui.BORDER, if (sel) 2 else 1)
            c.addView(text(title, 18f, Ui.TEXT, bold = true))
            c.addView(text(desc, 14f, Ui.TEXT2))
            c.setOnClickListener { roleChoice = mode; render() }
        }
        option(Prefs.MODE_SENDER, "리모컨", "내가 들고 다니는 갤럭시. 루틴이 이 폰에서 명령을 보내요.")
        option(Prefs.MODE_RECEIVER, "수신기", "집에 두는 픽셀1. 명령을 받아 기아 앱을 대신 눌러요.")
    }

    private var pairDraft: String? = null
    private var pairChecking = false

    private fun pairScreen() = with(ui) {
        val edit = input(pairDraft ?: prefs.pairing, "여기에 붙여넣기", mono = true)
        val done = primary("완료") {
            val code = edit.text.toString().trim()
            if (!validCode(code)) {
                toast("페어링 코드 형식이 올바르지 않아요")
            } else {
                savePairing(code)
                prefs.setupDone = true
                StatusWorker.schedule(this@MainActivity)
                pairDraft = null
                reset("home")
            }
        }
        val b = page(null, null, bottomBar(secondary("이전") { pairDraft = null; back() } to 0f, done to 1f))
        if (!prefs.setupDone) b.steps(2, 3)
        b.h1("픽셀1의 페어링 코드를 붙여넣어 주세요")
        b.p("픽셀1 앱에서 [페어링 코드 → 복사] 후 카톡 '나와의 채팅' 등으로 보내서 여기에 붙여넣으면 돼요.")
        b.section("페어링 코드")
        val r = row()
        r.addView(edit, LinearLayout.LayoutParams(0, dp(56), 1f))
        r.addView(pill("붙여넣기") {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            val t = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this@MainActivity)?.toString()?.trim() else null
            if (t.isNullOrEmpty()) toast("클립보드가 비어 있어요")
            else { edit.setText(t); pairDraft = t; checkPair(t) }
        }.apply { minHeight = dp(56) }, LinearLayout.LayoutParams(-2, dp(56)).apply { marginStart = dp(8) })
        b.addView(r)

        val st = b.card()
        val code = edit.text.toString().trim()
        when {
            code.isEmpty() -> st.addView(text("코드를 붙여넣으면 픽셀1과 연결되는지 바로 확인해요", 14f, Ui.TEXT2))
            !validCode(code) -> st.addView(text("❌ 코드 형식이 올바르지 않아요. 픽셀1에서 다시 복사해 주세요.", 14f, Ui.TEXT2))
            pairChecking -> st.addView(text("⏳ 픽셀1 신호 확인 중…", 14f, Ui.TEXT2))
            prefs.pairing == code && prefs.pixelStatusTime > 0 -> {
                st.background = bg(Ui.GREEN_BG, 18, Ui.GREEN_D)
                st.addView(text("✓ 픽셀1과 연결됐어요", 15f, Ui.TEXT, bold = true))
                st.addView(text("마지막 신호 ${ago(prefs.pixelStatusTime)}", 13f, Ui.TEXT2))
            }
            prefs.pairing == code -> st.addView(text("아직 픽셀1 신호가 없어요. 픽셀1에서 준비 단계를 마치고 '시작'을 눌렀는지 확인하세요. (완료를 눌러도 나중에 연결돼요)", 14f, Ui.TEXT2))
            else -> st.addView(text("[완료]를 누르면 저장돼요", 14f, Ui.TEXT2))
        }
        val warn = b.card()
        warn.addView(text("🔒 이 코드는 차 키와 같아요. 다른 사람에게 보내지 마세요. 유출되면 픽셀1에서 새로 만들면 돼요.", 13f, Ui.TEXT2))
    }

    private fun validCode(code: String) =
        code.contains('.') && code.substringBefore('.').length >= 8 && code.substringAfter('.').length >= 12

    private fun checkPair(code: String) {
        if (!validCode(code)) { render(); return }
        savePairing(code)
        pairChecking = true; render()
        Thread {
            StatusWorker.check(this)
            runOnUiThread { pairChecking = false; render() }
        }.start()
    }

    private fun savePairing(code: String) {
        if (code != prefs.pairing) {
            prefs.pairing = code
            prefs.lastNtfyId = ""
            prefs.pixelStatusTime = 0
            prefs.pixelWarned = false
        }
    }

    // ═════════════════════════ 리모컨 ═════════════════════════

    private fun ago(t: Long): String {
        if (t == 0L) return "기록 없음"
        val m = (System.currentTimeMillis() - t) / 60_000
        return when { m < 1 -> "방금"; m < 60 -> "${m}분 전"; m < 1440 -> "${m / 60}시간 전"; else -> "${m / 1440}일 전" }
    }

    private fun pixelStatus(): JSONObject = try { JSONObject(prefs.pixelStatus) } catch (e: Exception) { JSONObject() }

    private fun engineLine(): String = if (prefs.engineRunning()) {
        if (prefs.engineMinutes > 0) {
            val left = (prefs.engineStart + prefs.engineMinutes * 60_000L - System.currentTimeMillis()) / 1000
            "시동 %d:%02d".format(left / 60, left % 60)
        } else "시동 켜짐"
    } else "시동 꺼짐"

    private fun homeScreen() = with(ui) {
        val b = page(null, null)
        b.addView(text("차량 매크로", 24f, Ui.TEXT, bold = true))

        // 픽셀1
        val t = prefs.pixelStatusTime
        val d = pixelStatus()
        val (title, color) = when {
            !prefs.isPaired -> "페어링 코드 없음" to Ui.RED
            t == 0L -> "픽셀1 신호 기다리는 중" to Ui.WARN
            prefs.pixelWarned -> "픽셀1 연결 끊김" to Ui.RED
            else -> "픽셀1 정상" to Ui.GREEN
        }
        val pc = b.card()
        val head = row()
        val ht = col()
        ht.addView(text(title, 16f, Ui.TEXT, bold = true))
        ht.addView(text(if (t == 0L) "픽셀1 앱에서 '시작'을 눌렀는지 확인하세요" else "${ago(t)} 신호", 12f, Ui.SUB))
        head.addView(ht, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(dot(color))
        pc.addView(head)
        if (!prefs.isPaired) pc.addView(pill("페어링 코드 넣기", primary = true) { go("pair") })
        if (t > 0) {
            val g = row()
            fun mini(label: String, value: String, warn: Boolean) {
                val m = col().apply { background = bg(Ui.CARD2, 12); setPadding(dp(10), dp(8), dp(10), dp(8)) }
                m.addView(text(label, 11f, Ui.SUB))
                m.addView(text(value, 14f, if (warn) Ui.WARN else Ui.TEXT, bold = true))
                g.addView(m, LinearLayout.LayoutParams(0, -2, 1f).apply { if (g.childCount > 0) marginStart = dp(8) })
            }
            val bat = d.optInt("battery", -1)
            val charging = d.optBoolean("charging")
            mini("배터리", (if (bat >= 0) "$bat%" else "?") + if (charging) " 충전" else "", !charging)
            mini("인터넷", if (d.optBoolean("wifi")) "Wi-Fi" else "모바일", !d.optBoolean("wifi"))
            val kia = d.optString("kia")
            mini("기아 앱", when (kia) { "ok" -> "로그인 됨"; "logout" -> "로그아웃"; else -> "점검 전" }, kia == "logout")
            pc.addView(g)
            if (!d.optBoolean("a11y", true)) pc.addView(text("⚠️ 픽셀1 접근성 권한이 꺼져 있어요", 13f, Ui.WARN))
        }

        // 차 상태
        val cr = row()
        val door = col(8).apply { background = bg(Ui.CARD, 18, Ui.BORDER); setPadding(dp(16), dp(14), dp(16), dp(14)) }
        door.addView(text(when (prefs.door) { "locked" -> "문 잠김"; "unlocked" -> "문 열림"; else -> "문 상태 모름" }, 18f, Ui.TEXT, bold = true))
        door.addView(text(if (prefs.doorTime > 0) "${ago(prefs.doorTime)} 기준" else "명령을 실행하면 알 수 있어요", 12f, Ui.SUB))
        val running = prefs.engineRunning()
        val eng = col(8).apply {
            background = if (running) bg(Ui.ORANGE_BG, 18, Ui.ORANGE) else bg(Ui.CARD, 18, Ui.BORDER)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val et = text(engineLine(), 18f, Ui.TEXT, bold = true)
        engineText = et
        eng.addView(et)
        eng.addView(text(if (running) "남은 시간" else "유지 시간이 끝나면 꺼짐으로 표시", 12f, Ui.SUB))
        cr.addView(door, LinearLayout.LayoutParams(0, -1, 1f))
        cr.addView(eng, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(10) })
        b.addView(cr)

        historyCard(b, 3)

        val menu = b.card(gap = 0)
        menu.item("테스트 실행", "루틴 만들 때 확인용", divider = false) { go("test") }
        menu.item("설정 · 사용법 · 로그") { go("settings") }
    }

    private fun historyCard(b: LinearLayout, limit: Int) = with(ui) {
        val c = b.card(gap = 0)
        val h = prefs.history()
        val head = row()
        head.addView(text("최근 실행", 15f, Ui.TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        if (limit < 20 && h.size > limit) head.addView(text("전체 보기", 13f, Ui.GREEN).apply { setOnClickListener { go("history") } })
        head.setPadding(0, dp(4), 0, dp(6))
        c.addView(head)
        if (h.isEmpty()) c.addView(text("아직 실행한 명령이 없어요", 13f, Ui.SUB).apply { setPadding(0, dp(6), 0, dp(8)) })
        val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)
        for (e in h.take(limit)) {
            val title = e.optString("title")
            val ok = title.startsWith("✅") || title.startsWith("ℹ️")
            val clean = title.removePrefix("✅ ").removePrefix("❌ ").removePrefix("ℹ️ ")
            c.item(clean, "${fmt.format(Date(e.optLong("t")))} · ${e.optString("src")}\n${e.optString("msg")}",
                lead = badge(if (ok) "✓" else "✕", if (ok) Ui.GREEN_D else Ui.RED))
        }
    }

    private fun historyScreen() = with(ui) {
        val b = page("실행 기록", { back() })
        historyCard(b, 20)
    }

    private fun testScreen(remote: Boolean) = with(ui) {
        val b = page("테스트 실행", { back() })
        val w = b.card(Ui.WARN_BG, Ui.WARN_BORDER)
        w.addView(text(
            if (remote) "루틴을 만들 때 확인용이에요. 누르면 확인 창이 뜨고, 보내면 실제로 차가 움직여요."
            else "픽셀1에서 바로 기아 앱을 조작해요. 실제로 차가 움직여요.", 13f, Color2.WARN_TEXT))
        val presets = pixelStatus().optJSONObject("presets")
        val c = b.card(gap = 0)
        fun cmd(cmd: String, color: Int) {
            val n = Prefs.presetOf(cmd)
            val detail: String? = when {
                n > 0 && !remote -> prefs.preset(n).summary()
                n > 0 -> presets?.optString(n.toString())?.ifEmpty { null }
                else -> null
            }
            c.item(Prefs.cmdLabel(cmd), detail, lead = dot(color)) { confirmRun(cmd, detail, remote) }
        }
        c.addView(text("문", 13f, Ui.SUB).apply { setPadding(0, dp(6), 0, 0) })
        cmd(Prefs.CMD_UNLOCK, Ui.GREEN); cmd(Prefs.CMD_LOCK, Ui.SUB)
        c.addView(text("시동", 13f, Ui.SUB).apply { setPadding(0, dp(10), 0, 0) })
        cmd(Prefs.CMD_START1, Color2.RED_DOT); cmd(Prefs.CMD_START2, Color2.ORANGE_DOT); cmd(Prefs.CMD_START3, Color2.BLUE_DOT)
        cmd(Prefs.CMD_START_MAX, Ui.RED); cmd(Prefs.CMD_STOP, Ui.SUB)
        if (!remote) {
            val c2 = b.card(gap = 0)
            c2.item("기아 앱 연결 테스트", "제어 화면까지만 열어 봐요 (차는 안 움직임)", divider = false) {
                ReceiverService.runLocal(this@MainActivity, Prefs.CMD_CHECK); toast("연결 테스트 시작")
            }
        }
    }

    private fun confirmRun(cmd: String, detail: String?, remote: Boolean) {
        if (remote && !prefs.isPaired) { toast("먼저 페어링 코드를 설정하세요"); return }
        if (!remote && MacroAccessibilityService.instance == null) { toast("접근성 권한을 먼저 켜세요"); return }
        val msg = buildString {
            append(if (remote) "픽셀1로 명령을 보내요. 실제로 차가 움직여요." else "픽셀1이 지금 기아 앱을 조작해요. 실제로 차가 움직여요.")
            if (!detail.isNullOrEmpty()) append("\n\n$detail")
        }
        dialog().setTitle("'${Prefs.cmdLabel(cmd)}' ${if (remote) "보낼까요?" else "실행할까요?"}")
            .setMessage(msg)
            .setPositiveButton(if (remote) "보내기" else "실행") { _, _ ->
                if (remote) SenderService.send(this, cmd, "테스트") else ReceiverService.runLocal(this, cmd)
                toast("${Prefs.cmdLabel(cmd)} ${if (remote) "보냄" else "시작"}")
            }
            .setNegativeButton("취소", null).show()
    }

    private fun settingsScreen() = with(ui) {
        val b = page("설정 · 사용법", { back() })
        b.section("앱 동작 사용법")
        val u = b.card()
        u.addView(text("모드 및 루틴 › 동작 추가 › 앱 동작 실행 › 차량 매크로", 13f, Ui.GREEN, bold = true))
        u.addView(text("차 문 열기 *   ·   차 문 잠그기\n차 시동 · 한파 / 난방 / 냉방 / 최대 난방\n차 시동 끄기   ·   퇴근 신호", 14f, Ui.TEXT2))
        u.addView(text("* 문 열기는 폰 잠금이 풀려 있어야 실행돼요. 퇴근 신호는 진동만 하고 차에 명령을 보내지 않아요 (루틴 조건 '앱이 열렸을 때'용).", 12f, Ui.SUB))

        b.section("연결")
        val c = b.card(gap = 0)
        c.item("페어링 코드", maskCode(prefs.pairing), pill("바꾸기") { pairDraft = null; go("pair") }, divider = false)
        c.item("이 폰의 역할", "리모컨", pill("바꾸기") { roleChoice = null; go("role") })
        c.item("중계 서버", prefs.server, pill("바꾸기") { editServer() })

        b.section("문제 해결")
        val t = b.card(gap = 0)
        t.item("자세한 로그 보기", divider = false) { go("log") }
        t.item("로그 복사 (문의할 때)") { copy(AppLog.read(this@MainActivity)) }
        b.addView(text("차량 매크로 ${versionName()}", 12f, Ui.SUB).apply { gravity = android.view.Gravity.CENTER })
    }

    private fun maskCode(c: String) = when {
        c.isEmpty() -> "없음"
        c.length < 10 -> c
        else -> c.take(4) + "••••••••" + c.takeLast(4)
    }

    private fun versionName() = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    private fun editServer() {
        val e = EditText(this).apply { setText(prefs.server); isSingleLine = true }
        dialog().setTitle("중계 서버").setView(e)
            .setPositiveButton("저장") { _, _ ->
                prefs.server = e.text.toString().ifBlank { Prefs.DEFAULT_SERVER }
                prefs.lastNtfyId = ""
                restartReceiverIfOn()
                render()
            }.setNegativeButton("취소", null).show()
    }

    private fun logScreen() = with(ui) {
        val b = page("로그", { back() })
        val r = row()
        r.addView(pill("복사") { copy(AppLog.read(this@MainActivity)) })
        r.addView(pill("지우기") { AppLog.clear(this@MainActivity); render() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        b.addView(r)
        val c = b.card()
        c.addView(text(AppLog.read(this@MainActivity).ifEmpty { "(비어 있음)" }, 11f, Ui.TEXT2, mono = true).apply { setTextIsSelectable(true) })
    }

    // ═════════════════════════ 수신기 (픽셀1) ═════════════════════════

    private class Check(val title: String, val sub: String, val done: Boolean, val required: Boolean,
                        val actionLabel: String?, val action: (() -> Unit)?)

    private fun checks(): List<Check> {
        val a11y = MacroAccessibilityService.instance != null
        val batt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val secure = getSystemService(KeyguardManager::class.java).isDeviceSecure
        val pkg = prefs.targetPackage
        val paired = prefs.isPaired
        return listOf(
            Check("페어링 코드", if (paired) "만들어서 내 폰으로 보내기" else "새로 만들어요", paired, true,
                if (paired) "복사" else "만들기",
                if (paired) ({ copy(prefs.pairing) }) else ({
                    prefs.pairing = Prefs.generatePairing(); prefs.lastNtfyId = ""; copy(prefs.pairing)
                })),
            Check("기아 앱 선택", if (pkg.isEmpty()) "앱 목록에서 기아 앱 고르기" else appLabel(pkg), pkg.isNotEmpty(), true,
                "고르기", { pickApp() }),
            Check("간편비밀번호 (PIN)", if (prefs.pin.length >= 4) "이 폰에만 저장 · ●●●●" else "기아 앱 간편비밀번호 4자리",
                prefs.pin.length >= 4, true, "입력", { editPin() }),
            Check("화면 잠금 '없음'", "자동으로 화면을 켜려면 필요", !secure, false,
                "설정", { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }),
            Check("접근성 권한 켜기", "설정에서 '차량 매크로' 켜기", a11y, true, "열기", {
                toast("'차량 매크로'를 찾아 켜 주세요")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }),
            Check("배터리 최적화 제외", "꺼지지 않고 계속 기다리게", batt, false, "열기", { requestBatteryExemption() }),
            Check("연결 테스트", "기아 앱 제어 화면 열어 보기 · 차 안 움직임", prefs.kiaLogin == "ok", false, "실행", {
                if (!a11y || pkg.isEmpty()) toast("기아 앱 선택과 접근성을 먼저 해 주세요")
                else { ReceiverService.runLocal(this, Prefs.CMD_CHECK); toast("기아 앱을 열어 확인해요") }
            }),
        )
    }

    private fun pixelSetupScreen() = with(ui) {
        val list = checks()
        val ready = list.filter { it.required }.all { it.done }
        val start = primary("모두 끝나면 시작", enabled = ready) {
            prefs.setupDone = true
            ReceiverService.start(this@MainActivity)
            reset("pixelHome")
        }
        val b = page(null, null, bottomBar(secondary("이전") { back() } to 0f, start to 1f))
        b.steps(3, 3)
        b.h1("픽셀1 준비하기")
        b.p("위에서부터 하나씩 눌러 주세요. ${list.count { it.done }} / ${list.size} 완료")
        val c = b.card(gap = 0)
        val firstTodo = list.indexOfFirst { !it.done }
        list.forEachIndexed { i, ck ->
            val lead = if (ck.done) badge("✓", Ui.GREEN_D) else badge("${i + 1}", null, if (i == firstTodo) Ui.GREEN else Ui.BORDER2)
            val fn = ck.action
            val act = if (ck.actionLabel != null && fn != null) pill(ck.actionLabel, primary = i == firstTodo) { fn(); render() } else null
            c.item(ck.title, ck.sub + if (!ck.required && !ck.done) " (권장)" else "", act, divider = i > 0, lead = lead)
        }
        b.p("충전기는 계속 꽂아 두고, Wi-Fi에 연결된 상태로 집에 두세요.", size = 13f)
    }

    private fun pixelHomeScreen() = with(ui) {
        val b = page(null, null)
        b.addView(text("차량 매크로 · 수신기", 13f, Ui.SUB))
        b.addView(text("픽셀1", 24f, Ui.TEXT, bold = true))

        val on = prefs.receiverEnabled
        val sc = b.card(if (on) Ui.GREEN_BG else Ui.WARN_BG, if (on) Ui.GREEN_D else Ui.WARN_BORDER)
        sc.addView(text(if (on) "신호 기다리는 중" else "수신 꺼짐", 18f, Ui.TEXT, bold = true))
        sc.addView(text(if (on) prefs.receiverConn else "꺼져 있으면 명령을 받지 못해요", 13f, Ui.TEXT2))
        if (!on) sc.addView(pill("수신 시작", primary = true) { ReceiverService.start(this@MainActivity); render() })

        val list = checks()
        val g = b.card()
        list.filter { it.title != "연결 테스트" && it.title != "페어링 코드" }.chunked(2).forEach { pair ->
            val r = row()
            pair.forEach { ck ->
                val label = when (ck.title) {
                    "기아 앱 선택" -> if (ck.done) "기아 앱 선택됨" else "기아 앱 미선택"
                    "간편비밀번호 (PIN)" -> if (ck.done) "PIN 설정됨" else "PIN 미설정"
                    "화면 잠금 '없음'" -> if (ck.done) "화면 잠금 없음" else "화면 잠금 있음"
                    "접근성 권한 켜기" -> if (ck.done) "접근성 켜짐" else "접근성 꺼짐"
                    "배터리 최적화 제외" -> if (ck.done) "배터리 최적화 제외" else "배터리 최적화 켜짐"
                    else -> ck.title
                }
                val c = if (ck.done) Ui.GREEN else if (ck.required) Ui.RED else Ui.WARN
                r.addView(dotLine(c, label), LinearLayout.LayoutParams(0, -2, 1f))
            }
            g.addView(r)
        }
        val kc = when (prefs.kiaLogin) { "ok" -> Ui.GREEN; "logout" -> Ui.RED; else -> Ui.WARN }
        g.addView(dotLine(kc, "기아 로그인 " + when (prefs.kiaLogin) { "ok" -> "정상"; "logout" -> "로그아웃됨"; else -> "점검 전" }))

        historyCard(b, 3)

        val menu = b.card(gap = 0)
        menu.item("시동 프리셋", "한파 · 난방 · 냉방", divider = false) { go("presets") }
        menu.item("테스트 실행") { go("pixelTest") }
        menu.item("화면 분석", "기아 앱이 바뀌어서 버튼을 못 찾을 때") { go("dump") }
        menu.item("설정 · PIN · 로그") { go("pixelSettings") }
    }

    private var presetTab = 2
    private var presetDraft: MutableMap<Int, Preset>? = null

    private fun tempSteps(): List<String> =
        listOf("그대로", "Low") + (34..64).map { val v = it / 2.0; if (v % 1.0 == 0.0) v.toInt().toString() else v.toString() } + "High"

    private fun tempLabel(s: String) = if (s == "그대로" || s == "Low" || s == "High") s else "${s}℃"

    private fun presetScreen() = with(ui) {
        val draft = presetDraft ?: (1..3).associateWith { prefs.preset(it) }.toMutableMap().also { presetDraft = it }
        val save = primary("저장") {
            draft.forEach { (n, p) -> prefs.setPreset(n, p) }
            presetDraft = null
            ReceiverService.publishNow(this@MainActivity)
            toast("저장됨")
            back()
        }
        val b = page("시동 프리셋", { presetDraft = null; back() }, bottomBar(save to 1f))
        b.addView(segmented(listOf("한파", "난방", "냉방"), presetTab - 1) { presetTab = it + 1; render() })
        b.p("기아 앱 '시동 / 공조 설정'에 이 값을 넣고 시동을 걸어요. '그대로'는 건드리지 않아요.", size = 13f)
        val tab = presetTab
        fun cur() = draft[tab] ?: Preset()
        fun update(np: Preset) { draft[tab] = np }
        val p = cur()

        val c1 = b.card(gap = 0)
        val steps = tempSteps()
        var idx = steps.indexOf(p.temp.ifEmpty { "그대로" }).let { if (it < 0) 0 else it }
        val tv = text(tempLabel(steps[idx]), 17f, Ui.TEXT, bold = true).apply {
            gravity = android.view.Gravity.CENTER; minWidth = dp(64)
        }
        fun show() {
            val s = steps[idx]
            tv.text = tempLabel(s)
            update(cur().copy(temp = if (s == "그대로") "" else s))
        }
        val stepper = row()
        stepper.addView(pill("−") { if (idx > 0) { idx--; show() } }.apply { contentDescription = "온도 내리기" })
        stepper.addView(tv)
        stepper.addView(pill("+") { if (idx < steps.size - 1) { idx++; show() } }.apply { contentDescription = "온도 올리기" })
        c1.item("희망 온도", trailing = stepper, divider = false)
        fun optionRow(rowLabel: String, value: String, set: (String) -> Unit) {
            c1.item(rowLabel, null, text(value.ifEmpty { "그대로" }, 15f, if (value.isEmpty()) Ui.SUB else Ui.TEXT2)) {
                pickOption(rowLabel, value) { v -> set(v); render() }
            }
        }
        optionRow("시동 유지 시간", p.duration) { update(cur().copy(duration = it)) }
        optionRow("공조 모드", p.mode) { update(cur().copy(mode = it)) }
        optionRow("시트 열선 / 통풍", p.seat) { update(cur().copy(seat = it)) }

        val c2 = b.card(gap = 0)
        fun tri(label: String, v: Int, set: (Int) -> Unit) {
            val seg = segmented(listOf("그대로", "켬", "끔"), when (v) { 1 -> 1; 0 -> 2; else -> 0 }) { i ->
                set(when (i) { 1 -> 1; 2 -> 0; else -> -1 })
            }
            c2.item(label, trailing = seg, divider = c2.childCount > 0)
        }
        tri("앞유리 성에 제거", p.defrost) { update(cur().copy(defrost = it)) }
        tri("미러 + 뒷유리 열선", p.mirror) { update(cur().copy(mirror = it)) }
        tri("핸들 열선", p.wheel) { update(cur().copy(wheel = it)) }

        val hasOptions = Prefs.OPTION_ROWS.any { prefs.kiaOptionList(it).isNotEmpty() }
        b.p(if (hasOptions) "유지 시간·공조 모드·시트는 기아 앱에서 읽어 온 선택지에서 골라요."
            else "유지 시간·공조 모드·시트 선택지를 기아 앱에서 읽어 오면 목록에서 고를 수 있어요. 지금은 직접 입력해요.", size = 12f)
        b.addView(pill(if (hasOptions) "기아 앱에서 선택지 다시 읽기" else "기아 앱에서 선택지 읽어 오기") {
            if (MacroAccessibilityService.instance == null || prefs.targetPackage.isEmpty()) {
                toast("기아 앱 선택과 접근성을 먼저 해 주세요")
            } else {
                ReceiverService.runLocal(this@MainActivity, Prefs.CMD_READ_OPTIONS)
                toast("기아 앱을 열어 선택지를 읽어요 (차는 안 움직임)")
            }
        })
    }

    private fun pickOption(rowLabel: String, current: String, onPick: (String) -> Unit) {
        val opts = prefs.kiaOptionList(rowLabel)
        if (opts.isEmpty()) {
            val e = EditText(this).apply { setText(current); hint = "기아 앱 화면의 문구 그대로 (예: 10분)"; isSingleLine = true }
            dialog().setTitle(rowLabel).setView(e)
                .setPositiveButton("확인") { _, _ -> onPick(e.text.toString().trim()) }
                .setNeutralButton("그대로") { _, _ -> onPick("") }
                .setNegativeButton("취소", null).show()
        } else {
            val items = listOf("그대로") + opts
            dialog().setTitle(rowLabel)
                .setSingleChoiceItems(items.toTypedArray(), items.indexOf(current.ifEmpty { "그대로" })) { d, i ->
                    onPick(if (i == 0) "" else items[i]); d.dismiss()
                }.setNegativeButton("취소", null).show()
        }
    }

    private fun dumpScreen() = with(ui) {
        val b = page("화면 분석", { back() })
        b.p("기아 앱이 업데이트돼서 버튼을 못 찾을 때 써요.\n① [5초 후 분석] → ② 5초 안에 기아 앱의 문제 화면으로 이동 → ③ 알림이 오면 이 앱으로 돌아와 [결과 복사] 후 보내 주세요.")
        val r = row()
        r.addView(pill("5초 후 분석", primary = true) { dumpLater() })
        r.addView(pill("결과 복사") { copy(prefs.lastDump) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        b.addView(r)
        val c = b.card()
        c.addView(text(prefs.lastDump.ifEmpty { "(아직 분석 결과 없음)" }, 11f, Ui.TEXT2, mono = true).apply { setTextIsSelectable(true) })
    }

    private fun pixelSettingsScreen() = with(ui) {
        val b = page("설정 · PIN · 로그", { back() })
        b.section("기아 앱")
        val k = b.card(gap = 0)
        k.item("기아 앱", if (prefs.targetPackage.isEmpty()) "선택 안 됨" else appLabel(prefs.targetPackage), pill("바꾸기") { pickApp() }, divider = false)
        k.item("간편비밀번호 (PIN)", if (prefs.pin.length >= 4) "●●●● · 이 폰에만 저장" else "미설정", pill("바꾸기") { editPin() })

        b.section("연결")
        val c = b.card(gap = 0)
        val pairBtns = row()
        pairBtns.addView(pill("복사") { copy(prefs.pairing) })
        pairBtns.addView(pill("새로") { newPairing() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        c.item("페어링 코드", maskCode(prefs.pairing), pairBtns, divider = false)
        c.item("수신", if (prefs.receiverEnabled) prefs.receiverConn else "꺼짐",
            pill(if (prefs.receiverEnabled) "중지" else "시작") {
                if (prefs.receiverEnabled) ReceiverService.stop(this@MainActivity) else ReceiverService.start(this@MainActivity)
                render()
            })
        c.item("이 폰의 역할", "수신기", pill("바꾸기") { roleChoice = null; go("role") })
        c.item("중계 서버", prefs.server, pill("바꾸기") { editServer() })
        c.item("준비 단계 다시 보기") { go("pixelSetup") }

        b.section("문제 해결")
        val t = b.card(gap = 0)
        t.item("자세한 로그 보기", divider = false) { go("log") }
        t.item("로그 복사") { copy(AppLog.read(this@MainActivity)) }
        b.addView(text("차량 매크로 ${versionName()}", 12f, Ui.SUB).apply { gravity = android.view.Gravity.CENTER })
    }

    private fun newPairing() {
        dialog().setMessage("새 코드를 만들면 내 폰에도 다시 넣어야 해요. 계속할까요?")
            .setPositiveButton("만들기") { _, _ ->
                prefs.pairing = Prefs.generatePairing()
                prefs.lastNtfyId = ""
                copy(prefs.pairing)
                restartReceiverIfOn()
                render()
            }.setNegativeButton("취소", null).show()
    }

    private fun editPin() {
        val e = EditText(this).apply {
            hint = "숫자 4자리"; isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        dialog().setTitle("기아 간편비밀번호").setMessage("이 폰 안에만 저장돼요. PIN 화면이 나올 때만 입력해요.").setView(e)
            .setPositiveButton("저장") { _, _ ->
                val v = e.text.toString().filter { it.isDigit() }
                if (v.length < 4) toast("4자리 이상 입력하세요") else { prefs.pin = v; toast("저장됨") }
                render()
            }.setNegativeButton("취소", null).show()
    }

    private fun restartReceiverIfOn() {
        if (prefs.mode == Prefs.MODE_RECEIVER && prefs.receiverEnabled) {
            ReceiverService.stop(this); ReceiverService.start(this)
        }
    }

    // ═════════════════════════ 공통 ═════════════════════════

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun dumpLater() {
        val svc = MacroAccessibilityService.instance
        if (svc == null) { toast("접근성 권한을 먼저 켜세요"); return }
        toast("5초 뒤 화면을 분석해요. 기아 앱으로 이동하세요")
        Thread {
            Thread.sleep(5000)
            prefs.lastDump = svc.dumpScreen()
            Notifier.result(this, "화면 분석 완료", "차량 매크로 앱으로 돌아가서 결과를 확인하세요", Notifier.ID_DUMP)
        }.start()
    }

    private fun requestBatteryExemption() {
        try {
            @Suppress("BatteryLife")
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun appLabel(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        "설치 안 됨"
    }

    private fun pickApp() {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != packageName }
            .sortedWith(compareBy({ !isKiaLike(it) }, { it.second }))
        dialog().setTitle("기아 앱 선택")
            .setItems(apps.map { "${it.second}\n${it.first}" }.toTypedArray()) { _, i ->
                prefs.targetPackage = apps[i].first
                render()
            }.show()
    }

    private fun isKiaLike(a: Pair<String, String>) =
        a.first.contains("kia", true) || a.second.contains("kia", true) || a.second.contains("기아")

    private fun copy(s: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("carmacro", s))
        toast("복사됨")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

/** Ui에 없는 보조 색 */
object Color2 {
    val WARN_TEXT = android.graphics.Color.parseColor("#F2E2C4")
    val RED_DOT = android.graphics.Color.parseColor("#D9534F")
    val ORANGE_DOT = android.graphics.Color.parseColor("#E08A3C")
    val BLUE_DOT = android.graphics.Color.parseColor("#5B9BE0")
}
