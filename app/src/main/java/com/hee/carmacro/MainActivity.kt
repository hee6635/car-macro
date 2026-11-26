package com.hee.carmacro

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var senderBox: LinearLayout
    private lateinit var receiverBox: LinearLayout
    private lateinit var pairingEdit: EditText
    private lateinit var serverEdit: EditText

    // 리모컨
    private lateinit var pixelText: TextView
    private lateinit var doorStateText: TextView
    private lateinit var doorMain: Button
    private lateinit var doorAlt: Button
    private lateinit var engineStateText: TextView
    private lateinit var engineRunningBox: LinearLayout
    private lateinit var engineIdleBox: LinearLayout
    private lateinit var engineCountdown: TextView
    private lateinit var lastResultText: TextView

    // 수신기
    private lateinit var receiverStatus: TextView
    private lateinit var targetText: TextView
    private lateinit var pinEdit: EditText
    private val presetViews = mutableMapOf<Int, PresetViews>()
    private lateinit var dumpText: TextView
    private lateinit var logText: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { refreshEngine(); ui.postDelayed(this, 1000) }
    }

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Notifier.ensureChannels(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        buildUi()
        applyMode()
    }

    override fun onResume() {
        super.onResume()
        prefs.sp.registerOnSharedPreferenceChangeListener(listener)
        if (prefs.mode == Prefs.MODE_SENDER && prefs.isPaired) StatusWorker.refreshNow(this)
        refresh()
        ui.post(ticker)
    }

    override fun onPause() {
        prefs.sp.unregisterOnSharedPreferenceChangeListener(listener)
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    // ───────────────────────── UI 도우미 ─────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun box() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun card(parent: LinearLayout, color: Int = Color.parseColor("#F1F4F7")) = box().also {
        it.setPadding(dp(16), dp(14), dp(16), dp(14))
        it.background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(color) }
        parent.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
    }

    private fun LinearLayout.title(s: String) = TextView(context).also {
        it.text = s; it.textSize = 17f; it.setTypeface(null, Typeface.BOLD)
        it.setPadding(0, dp(18), 0, dp(4)); addView(it)
    }

    private fun LinearLayout.note(s: String) = TextView(context).also {
        it.text = s; it.textSize = 13f; it.setTextColor(Color.parseColor("#5A6673")); addView(it)
    }

    private fun LinearLayout.text(s: String = "", size: Float = 15f) = TextView(context).also {
        it.text = s; it.textSize = size; it.setTextIsSelectable(true); addView(it)
    }

    private fun LinearLayout.row(vararg items: Pair<String, () -> Unit>) {
        val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, fn) in items) {
            r.addView(Button(context).apply {
                text = label; isAllCaps = false; setOnClickListener { fn() }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        addView(r)
    }

    private fun LinearLayout.edit(value: String, hint: String = "", digits: Boolean = false) = EditText(context).also {
        it.setText(value); it.hint = hint; it.isSingleLine = true
        it.inputType = if (digits) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        addView(it)
    }

    /** 실수 방지용 길게 누르기 버튼 */
    private fun holdButton(parent: LinearLayout, label: String, color: Int, height: Int, action: () -> Unit) =
        Button(this).also { b ->
            b.text = label; b.isAllCaps = false; b.textSize = 18f; b.setTextColor(Color.WHITE)
            b.background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(color) }
            b.setOnClickListener { toast("길게 눌러야 실행돼요") }
            b.setOnLongClickListener { action(); true }
            parent.addView(b, LinearLayout.LayoutParams(MATCH_PARENT, dp(height)).apply { topMargin = dp(8) })
        }

    private fun tapButton(parent: LinearLayout, label: String, action: () -> Unit) = Button(this).also { b ->
        b.text = label; b.isAllCaps = false; b.setOnClickListener { action() }
        parent.addView(b, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
    }

    // ───────────────────────── 화면 구성 ─────────────────────────

    @SuppressLint("SetTextI18n")
    private fun buildUi() {
        val root = box().apply { setPadding(dp(16), dp(12), dp(16), dp(40)) }
        setContentView(ScrollView(this).apply { addView(root) })

        senderBox = box(); receiverBox = box()
        root.addView(senderBox); root.addView(receiverBox)
        buildSender(senderBox)
        buildReceiver(receiverBox)

        // 공통 설정
        root.title("설정")
        root.note("이 폰의 역할")
        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbS = RadioButton(this).apply { text = "리모컨 (내 폰)"; id = View.generateViewId() }
        val rbR = RadioButton(this).apply { text = "수신기 (집 픽셀1)"; id = View.generateViewId() }
        rg.addView(rbS); rg.addView(rbR)
        rg.check(if (prefs.mode == Prefs.MODE_RECEIVER) rbR.id else rbS.id)
        rg.setOnCheckedChangeListener { _, id ->
            prefs.mode = if (id == rbR.id) Prefs.MODE_RECEIVER else Prefs.MODE_SENDER
            applyMode(); refresh()
        }
        root.addView(rg)

        root.note("\n페어링 코드: 두 폰에 똑같이 넣어야 해요. 한쪽에서 [새로 만들기] → [복사] → 카톡 '나와의 채팅' 등으로 다른 폰에 붙여넣기. 이 코드를 아는 사람은 차 문을 열 수 있으니 공유하지 마세요.")
        pairingEdit = root.edit(prefs.pairing)
        root.row(
            "새로 만들기" to {
                AlertDialog.Builder(this).setMessage("새 코드를 만들면 다른 폰에도 다시 넣어야 해요. 계속할까요?")
                    .setPositiveButton("만들기") { _, _ -> pairingEdit.setText(Prefs.generatePairing()); saveCommon() }
                    .setNegativeButton("취소", null).show()
                Unit
            },
            "복사" to { copy(pairingEdit.text.toString()) },
            "저장" to { saveCommon() },
        )
        root.note("중계 서버 (기본: https://ntfy.sh)")
        serverEdit = root.edit(prefs.server)

        root.title("기록")
        root.row("새로고침" to { refresh() }, "지우기" to { AppLog.clear(this); refresh() })
        logText = root.text(size = 12f).apply { typeface = Typeface.MONOSPACE }
    }

    private fun buildSender(b: LinearLayout) {
        b.addView(TextView(this).apply { text = "쏘렌토"; textSize = 26f; setTypeface(null, Typeface.BOLD) })

        val pixelCard = card(b)
        pixelText = pixelCard.text()

        val doorCard = card(b)
        doorStateText = doorCard.text(size = 15f)
        doorMain = holdButton(doorCard, "문 열기", Color.parseColor("#2E7D5B"), 84) { send(Prefs.CMD_UNLOCK) }
        doorAlt = tapButton(doorCard, "문 잠그기") { send(Prefs.CMD_LOCK) }

        val engineCard = card(b, Color.parseColor("#FBF1EA"))
        engineStateText = engineCard.text(size = 15f)
        engineRunningBox = box().also { engineCard.addView(it) }
        engineCountdown = TextView(this).apply { textSize = 34f; setTypeface(null, Typeface.BOLD) }
        engineRunningBox.addView(engineCountdown)
        tapButton(engineRunningBox, "시동 끄기") { send(Prefs.CMD_STOP) }
        engineIdleBox = box().also { engineCard.addView(it) }
        holdButton(engineIdleBox, "시동 · 한파", Color.parseColor("#B23A3A"), 56) { send(Prefs.CMD_START1) }
        holdButton(engineIdleBox, "시동 · 난방", Color.parseColor("#C76A1E"), 56) { send(Prefs.CMD_START2) }
        holdButton(engineIdleBox, "시동 · 냉방", Color.parseColor("#2F6FB0"), 56) { send(Prefs.CMD_START3) }
        holdButton(engineIdleBox, "시동 · 최대 난방", Color.parseColor("#7A2E2E"), 56) { send(Prefs.CMD_START_MAX) }
        tapButton(engineIdleBox, "시동 끄기 (상태와 상관없이)") { send(Prefs.CMD_STOP) }
        engineCard.note("프리셋 내용(온도·열선·유지 시간)은 픽셀1 앱에서 정해요.")

        lastResultText = b.text(size = 13f).apply { setPadding(0, dp(10), 0, 0) }

        b.title("위젯 · 실행 아이콘")
        b.note("• 위젯: 홈 화면 길게 누르기 → 위젯 → '차량 매크로'. 문 열기·시동은 두 번 눌러야 실행돼요.\n" +
            "• 실행 아이콘: 앱 목록의 '차 문 열기', '차 시동 · 난방', '퇴근 신호' 등을 갤럭시 루틴 '앱 열기'나 빅스비 빠른 명령어에서 고르세요.\n" +
            "• '차 문 열기'는 폰 잠금이 풀려 있어야 실행돼요. 시동은 잠긴 상태에서도 돼요.")
        b.note("\n위젯 시동 버튼에 쓸 프리셋")
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("한파", "난방", "냉방"))
        sp.setSelection(prefs.widgetPreset - 1)
        sp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (prefs.widgetPreset != pos + 1) { prefs.widgetPreset = pos + 1; CarWidget.updateAll(this@MainActivity, null) }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        b.addView(sp)
    }

    private fun buildReceiver(b: LinearLayout) {
        b.addView(TextView(this).apply { text = "픽셀1 수신기"; textSize = 26f; setTypeface(null, Typeface.BOLD) })
        val st = card(b)
        receiverStatus = st.text()
        b.row(
            "수신 시작" to { saveCommon(); ReceiverService.start(this); toast("수신 시작") },
            "수신 중지" to { ReceiverService.stop(this) },
        )
        b.row(
            "접근성 설정" to {
                toast("'차량 매크로'를 찾아 켜 주세요")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            "배터리 최적화 제외" to { requestBatteryExemption() },
        )

        b.title("기아 앱")
        targetText = b.text()
        b.row("앱 고르기" to { pickApp() }, "로그인 점검" to { ReceiverService.runLocal(this, Prefs.CMD_CHECK); toast("점검 시작") })

        b.title("기아 간편비밀번호 (PIN)")
        b.note("이 폰 안에만 저장돼요. PIN 화면이 나올 때만 입력해요.")
        pinEdit = b.edit(prefs.pin, "숫자 4자리", digits = true)
        b.row("PIN 저장" to {
            prefs.pin = pinEdit.text.toString(); toast(if (prefs.pin.length >= 4) "저장됨" else "4자리 이상 입력하세요")
        })

        b.title("시동 프리셋")
        b.note("기아 앱 '시동 / 공조 설정'에 적용할 값이에요. 비워 두거나 '그대로'면 건드리지 않아요.\n" +
            "희망 온도: Low / High / 숫자(예: 25)\n시트·유지 시간·공조 모드: 기아 앱 하위 화면의 선택지 문구 그대로 (예: 10분)")
        for (n in 1..3) presetViews[n] = presetEditor(b, n)
        b.row("프리셋 저장" to { savePresets(); toast("저장됨") }, "기본값으로" to { resetPresets() })

        b.title("명령별 테스트 (실제로 차가 움직여요)")
        b.row("문 열기" to { testRun(Prefs.CMD_UNLOCK) }, "문 잠그기" to { testRun(Prefs.CMD_LOCK) })
        b.row("시동·한파" to { testRun(Prefs.CMD_START1) }, "시동·난방" to { testRun(Prefs.CMD_START2) }, "시동·냉방" to { testRun(Prefs.CMD_START3) })
        b.row("최대 난방" to { testRun(Prefs.CMD_START_MAX) }, "시동 끄기" to { testRun(Prefs.CMD_STOP) })

        b.title("화면 분석")
        b.note("기아 앱이 업데이트돼서 버튼을 못 찾을 때: [5초 후 분석] → 5초 안에 기아 앱의 해당 화면으로 이동 → 알림이 오면 이 앱으로 돌아와 결과 확인.")
        b.row("5초 후 분석" to { dumpLater() }, "결과 복사" to { copy(prefs.lastDump) })
        dumpText = b.text(size = 12f).apply { typeface = Typeface.MONOSPACE }
    }

    class PresetViews(val temp: EditText, val defrost: Spinner, val mirror: Spinner, val wheel: Spinner,
                      val seat: EditText, val duration: EditText, val mode: EditText)

    private fun presetEditor(parent: LinearLayout, n: Int): PresetViews {
        val c = card(parent)
        c.addView(TextView(this).apply { text = "${n}. ${Prefs.PRESET_NAMES[n]}"; textSize = 16f; setTypeface(null, Typeface.BOLD) })
        val p = prefs.preset(n)
        fun labeled(label: String, v: View) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(TextView(this).apply { text = label; textSize = 14f }, LinearLayout.LayoutParams(dp(130), WRAP_CONTENT))
            r.addView(v, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            c.addView(r)
        }
        fun field(v: String, hint: String) = EditText(this).apply { setText(v); this.hint = hint; isSingleLine = true }
        fun tri(v: Int) = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("그대로", "켬", "끔"))
            setSelection(when (v) { 1 -> 1; 0 -> 2; else -> 0 })
        }
        val pv = PresetViews(field(p.temp, "Low/High/25"), tri(p.defrost), tri(p.mirror), tri(p.wheel),
            field(p.seat, "비우면 그대로"), field(p.duration, "예: 10분"), field(p.mode, "비우면 그대로"))
        labeled("희망 온도", pv.temp)
        labeled("앞유리 성에 제거", pv.defrost)
        labeled("미러+뒷유리 열선", pv.mirror)
        labeled("핸들 열선", pv.wheel)
        labeled("시트 열선/통풍", pv.seat)
        labeled("시동 유지 시간", pv.duration)
        labeled("공조 모드", pv.mode)
        return pv
    }

    private fun savePresets() {
        fun triVal(s: Spinner) = when (s.selectedItemPosition) { 1 -> 1; 2 -> 0; else -> -1 }
        for ((n, v) in presetViews) {
            prefs.setPreset(n, Preset(
                v.temp.text.toString().trim(), triVal(v.defrost), triVal(v.mirror), triVal(v.wheel),
                v.seat.text.toString().trim(), v.duration.text.toString().trim(), v.mode.text.toString().trim(),
            ))
        }
    }

    private fun resetPresets() {
        for (n in 1..3) prefs.setPreset(n, Prefs.defaultPreset(n))
        receiverBox.removeAllViews(); presetViews.clear(); buildReceiver(receiverBox); refresh()
    }

    // ───────────────────────── 상태 표시 ─────────────────────────

    private fun ago(t: Long): String {
        if (t == 0L) return "기록 없음"
        val m = (System.currentTimeMillis() - t) / 60_000
        return when { m < 1 -> "방금"; m < 60 -> "${m}분 전"; m < 1440 -> "${m / 60}시간 전"; else -> "${m / 1440}일 전" }
    }

    @SuppressLint("SetTextI18n")
    private fun refresh() {
        logText.text = AppLog.read(this)
        if (prefs.mode == Prefs.MODE_SENDER) refreshSender() else refreshReceiver()
    }

    @SuppressLint("SetTextI18n")
    private fun refreshSender() {
        val t = prefs.pixelStatusTime
        val d = try { JSONObject(prefs.pixelStatus) } catch (e: Exception) { JSONObject() }
        pixelText.text = when {
            !prefs.isPaired -> "❌ 페어링 코드를 먼저 설정하세요 (아래 설정)"
            t == 0L -> "⏳ 픽셀1 신호를 아직 받지 못했어요"
            else -> buildString {
                append(if (prefs.pixelWarned) "⚠️ 픽셀1 연결 끊김" else "🟢 픽셀1 정상")
                append(" · ${ago(t)} 신호\n")
                val bat = d.optInt("battery", -1)
                append("배터리 ${if (bat >= 0) "$bat%" else "?"}${if (d.optBoolean("charging")) " · 충전 중" else " · 충전 안 됨"}")
                append(" · ${if (d.optBoolean("wifi")) "Wi-Fi" else "모바일 데이터"}\n")
                append("기아 앱 ${when (d.optString("kia")) { "ok" -> "로그인 됨"; "logout" -> "⚠️ 로그아웃됨"; else -> "점검 전" }}")
                if (!d.optBoolean("a11y", true)) append(" · ⚠️ 접근성 꺼짐")
            }
        }

        val lock = CarWidget.doorShowsLock(prefs)
        doorStateText.text = "문 · " + when (prefs.door) {
            "locked" -> "잠김"; "unlocked" -> "열림"; else -> "상태 모름"
        } + if (prefs.doorTime > 0) " (${ago(prefs.doorTime)} 기준)" else ""
        if (lock) {
            doorMain.text = "문 잠그기"
            (doorMain.background as GradientDrawable).setColor(Color.parseColor("#455A64"))
            doorMain.setOnClickListener { send(Prefs.CMD_LOCK) }   // 잠그기는 짧게 눌러도 실행
            doorAlt.text = "문 열기 (길게 누르기)"
            doorAlt.setOnClickListener { toast("길게 눌러야 실행돼요") }
            doorAlt.setOnLongClickListener { send(Prefs.CMD_UNLOCK); true }
        } else {
            doorMain.text = "문 열기\n길게 눌러 실행"
            (doorMain.background as GradientDrawable).setColor(Color.parseColor("#2E7D5B"))
            doorMain.setOnClickListener { toast("길게 눌러야 실행돼요") }
            doorAlt.text = "문 잠그기"
            doorAlt.setOnClickListener { send(Prefs.CMD_LOCK) }
            doorAlt.setOnLongClickListener { send(Prefs.CMD_LOCK); true }
        }
        refreshEngine()
        lastResultText.text = "마지막: ${prefs.lastResult}"
    }

    @SuppressLint("SetTextI18n")
    private fun refreshEngine() {
        if (prefs.mode != Prefs.MODE_SENDER) return
        val running = prefs.engineRunning()
        engineRunningBox.visibility = if (running) View.VISIBLE else View.GONE
        engineIdleBox.visibility = if (running) View.GONE else View.VISIBLE
        if (running) {
            engineStateText.text = "시동 · 공조 켜짐"
            if (prefs.engineMinutes > 0) {
                val left = (prefs.engineStart + prefs.engineMinutes * 60_000L - System.currentTimeMillis()) / 1000
                engineCountdown.text = "%d:%02d 남음".format(left / 60, left % 60)
            } else engineCountdown.text = "켜짐 (유지 시간 모름)"
        } else engineStateText.text = "시동 · 꺼짐"
    }

    @SuppressLint("SetTextI18n")
    private fun refreshReceiver() {
        val a11y = MacroAccessibilityService.instance != null
        val batt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        val secure = getSystemService(KeyguardManager::class.java).isDeviceSecure
        val pkg = prefs.targetPackage
        receiverStatus.text = buildString {
            append(if (prefs.isPaired) "✅ 페어링 코드 있음\n" else "❌ 페어링 코드 없음 (아래 설정)\n")
            append(if (prefs.receiverEnabled) "✅ 수신: ${prefs.receiverConn}\n" else "❌ 수신 꺼짐 → [수신 시작]\n")
            append(if (a11y) "✅ 접근성 권한 켜짐\n" else "❌ 접근성 권한 꺼짐 → [접근성 설정]\n")
            append(if (batt) "✅ 배터리 최적화 제외됨\n" else "⚠️ 배터리 최적화 제외 권장\n")
            append(if (secure) "⚠️ 화면 잠금(PIN/패턴)이 있어요 → '없음'으로 바꾸세요\n" else "✅ 화면 잠금 없음\n")
            append(if (pkg.isNotEmpty()) "✅ 기아 앱 선택됨\n" else "❌ 기아 앱을 고르세요\n")
            append(if (prefs.pin.length >= 4) "✅ PIN 설정됨\n" else "❌ PIN 미설정\n")
            append("기아 로그인: ${when (prefs.kiaLogin) { "ok" -> "정상"; "logout" -> "⚠️ 로그아웃됨"; else -> "점검 전" }}")
        }
        targetText.text = if (pkg.isEmpty()) "선택 안 됨" else "${appLabel(pkg)} ($pkg)"
        dumpText.text = prefs.lastDump.ifEmpty { "(아직 분석 결과 없음)" }
    }

    // ───────────────────────── 동작 ─────────────────────────

    private fun applyMode() {
        val receiver = prefs.mode == Prefs.MODE_RECEIVER
        senderBox.visibility = if (receiver) View.GONE else View.VISIBLE
        receiverBox.visibility = if (receiver) View.VISIBLE else View.GONE
        // 실행 아이콘은 리모컨에서만 앱 목록에 보이게
        val state = if (receiver) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        for (c in listOf("unlock", "lock", "start1", "start2", "start3", "start_max", "stop", "signal")) {
            try {
                packageManager.setComponentEnabledSetting(
                    ComponentName(this, "$packageName.T_$c"), state, PackageManager.DONT_KILL_APP
                )
            } catch (_: Exception) {
            }
        }
        if (!receiver) StatusWorker.schedule(this)
    }

    private fun saveCommon() {
        val code = pairingEdit.text.toString().trim()
        if (code.isNotEmpty() && (!code.contains('.') || code.substringAfter('.').length < 12)) {
            toast("페어링 코드 형식이 올바르지 않아요"); return
        }
        val changed = code != prefs.pairing || serverEdit.text.toString().trim().trimEnd('/') != prefs.server
        prefs.pairing = code
        prefs.server = serverEdit.text.toString().ifBlank { Prefs.DEFAULT_SERVER }
        if (changed) {
            prefs.lastNtfyId = ""
            prefs.pixelStatusTime = 0
            toast("저장됨")
            if (prefs.mode == Prefs.MODE_RECEIVER && prefs.receiverEnabled) {
                ReceiverService.stop(this); ReceiverService.start(this)
            }
        }
    }

    private fun send(cmd: String) {
        if (!prefs.isPaired) { toast("먼저 페어링 코드를 설정하세요"); return }
        SenderService.send(this, cmd, "앱")
        toast("${Prefs.cmdLabel(cmd)} 보냄")
    }

    private fun testRun(cmd: String) {
        savePresets()
        if (MacroAccessibilityService.instance == null) { toast("접근성 권한을 먼저 켜세요"); return }
        AlertDialog.Builder(this)
            .setMessage("실제로 기아 앱을 조작해 차에 '${Prefs.cmdLabel(cmd)}' 명령이 가요. 실행할까요?")
            .setPositiveButton("실행") { _, _ -> ReceiverService.runLocal(this, cmd) }
            .setNegativeButton("취소", null).show()
    }

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

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        try {
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
        AlertDialog.Builder(this)
            .setTitle("기아 앱 선택")
            .setItems(apps.map { "${it.second}\n${it.first}" }.toTypedArray()) { _, i ->
                prefs.targetPackage = apps[i].first; refresh()
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
