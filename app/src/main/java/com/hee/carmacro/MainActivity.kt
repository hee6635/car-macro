package com.hee.carmacro

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private lateinit var senderBox: LinearLayout
    private lateinit var receiverBox: LinearLayout
    private lateinit var pairingEdit: EditText
    private lateinit var serverEdit: EditText
    private lateinit var senderStatus: TextView
    private lateinit var receiverStatus: TextView
    private lateinit var targetText: TextView
    private lateinit var unlockScript: EditText
    private lateinit var lockScript: EditText
    private lateinit var dumpText: TextView
    private lateinit var logText: TextView

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
    }

    override fun onResume() {
        super.onResume()
        prefs.sp.registerOnSharedPreferenceChangeListener(listener)
        refresh()
    }

    override fun onPause() {
        prefs.sp.unregisterOnSharedPreferenceChangeListener(listener)
        super.onPause()
    }

    // ───────────────────────── UI 구성 ─────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun LinearLayout.title(s: String) = TextView(context).also {
        it.text = s; it.textSize = 18f; it.setTypeface(null, Typeface.BOLD)
        it.setPadding(0, dp(20), 0, dp(6)); addView(it)
    }

    private fun LinearLayout.note(s: String) = TextView(context).also {
        it.text = s; it.textSize = 13f; it.setTextColor(Color.GRAY); addView(it)
    }

    private fun LinearLayout.text(s: String = "", size: Float = 15f) = TextView(context).also {
        it.text = s; it.textSize = size; it.setTextIsSelectable(true); addView(it)
    }

    private fun LinearLayout.button(s: String, onClick: () -> Unit) = Button(context).also {
        it.text = s; it.isAllCaps = false; it.setOnClickListener { onClick() }; addView(it)
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

    private fun LinearLayout.edit(value: String, multi: Boolean = false) = EditText(context).also {
        it.setText(value)
        if (multi) {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            it.typeface = Typeface.MONOSPACE; it.textSize = 13f; it.minLines = 6
            it.gravity = Gravity.TOP or Gravity.START
        } else {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            it.isSingleLine = true
        }
        addView(it)
    }

    private fun box() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun bigButton(parent: LinearLayout, s: String, color: Int, onClick: () -> Unit) {
        parent.addView(Button(this).apply {
            text = s; textSize = 22f; isAllCaps = false
            setTextColor(Color.WHITE); setBackgroundColor(color)
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(84)).apply { topMargin = dp(10) })
    }

    @SuppressLint("SetTextI18n")
    private fun buildUi() {
        root = box().apply { setPadding(dp(16), dp(12), dp(16), dp(40)) }
        setContentView(ScrollView(this).apply { addView(root) })

        // 모드 선택
        root.title("이 폰의 역할")
        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbS = RadioButton(this).apply { text = "리모컨 (내 폰)"; id = View.generateViewId() }
        val rbR = RadioButton(this).apply { text = "수신기 (집 픽셀1)"; id = View.generateViewId() }
        rg.addView(rbS); rg.addView(rbR)
        rg.check(if (prefs.mode == Prefs.MODE_RECEIVER) rbR.id else rbS.id)
        rg.setOnCheckedChangeListener { _, id ->
            prefs.mode = if (id == rbR.id) Prefs.MODE_RECEIVER else Prefs.MODE_SENDER
            refresh()
        }
        root.addView(rg)

        // 페어링
        root.title("페어링 코드")
        root.note("두 폰에 똑같은 코드를 넣어야 합니다. 한 쪽에서 [새로 만들기] → [복사] 후 카톡 '나와의 채팅' 등으로 다른 폰에 붙여넣으세요. 이 코드를 아는 사람은 차 문을 열 수 있으니 공유하지 마세요.")
        pairingEdit = root.edit(prefs.pairing)
        root.row(
            "새로 만들기" to {
                AlertDialog.Builder(this).setMessage("새 코드를 만들면 다른 폰에도 다시 넣어야 합니다. 계속할까요?")
                    .setPositiveButton("만들기") { _, _ -> pairingEdit.setText(Prefs.generatePairing()); saveCommon() }
                    .setNegativeButton("취소", null).show()
            },
            "복사" to { copy(pairingEdit.text.toString()) },
            "저장" to { saveCommon() },
        )
        root.note("중계 서버 (기본: https://ntfy.sh)")
        serverEdit = root.edit(prefs.server)

        // ── 리모컨 ──
        senderBox = box()
        senderBox.title("원격 제어")
        bigButton(senderBox, "🔓  문 열기", Color.parseColor("#2E7D32")) { send(Prefs.CMD_UNLOCK) }
        bigButton(senderBox, "🔒  문 잠그기", Color.parseColor("#455A64")) { send(Prefs.CMD_LOCK) }
        senderStatus = senderBox.text(size = 16f).apply { setPadding(0, dp(12), 0, 0) }
        senderBox.note("\n홈 화면을 길게 눌러 '위젯' → '차량 매크로' 위젯을 추가하면 앱을 열지 않고 바로 누를 수 있습니다. 결과는 알림으로 옵니다.")
        root.addView(senderBox)

        // ── 수신기 ──
        receiverBox = box()
        receiverBox.title("수신기 상태")
        receiverStatus = receiverBox.text()
        receiverBox.row(
            "수신 시작" to { saveCommon(); ReceiverService.start(this); toast("수신 시작") },
            "수신 중지" to { ReceiverService.stop(this) },
        )
        receiverBox.row(
            "접근성 설정" to {
                toast("'차량 매크로' 를 찾아 켜 주세요")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            "배터리 최적화 제외" to { requestBatteryExemption() },
        )

        receiverBox.title("기아 앱 선택")
        targetText = receiverBox.text()
        receiverBox.button("설치된 앱에서 고르기") { pickApp() }

        receiverBox.title("문 열기 스크립트")
        unlockScript = receiverBox.edit(prefs.script(Prefs.CMD_UNLOCK), multi = true)
        receiverBox.title("문 잠그기 스크립트")
        lockScript = receiverBox.edit(prefs.script(Prefs.CMD_LOCK), multi = true)
        receiverBox.row(
            "스크립트 저장" to { saveScripts(); toast("저장됨") },
            "기본값으로" to {
                unlockScript.setText(Prefs.defaultScript(Prefs.CMD_UNLOCK))
                lockScript.setText(Prefs.defaultScript(Prefs.CMD_LOCK))
            },
        )
        receiverBox.row(
            "열기 테스트" to { testRun(Prefs.CMD_UNLOCK) },
            "잠금 테스트" to { testRun(Prefs.CMD_LOCK) },
        )
        receiverBox.note(HELP)

        receiverBox.title("화면 분석")
        receiverBox.note("버튼 문구를 모를 때: [5초 후 분석] → 5초 안에 기아 앱의 문 열기 버튼이 보이는 화면으로 이동 → 분석이 끝나면 알림이 옵니다. 다시 이 앱으로 오면 아래에 버튼 목록이 보입니다. 성공/실패 메시지를 찾으려면 기아 앱에서 직접 한 번 문을 열어 본 뒤 분석하세요.")
        receiverBox.row(
            "5초 후 분석" to { dumpLater() },
            "결과 복사" to { copy(prefs.lastDump) },
        )
        dumpText = receiverBox.text(size = 12f).apply { typeface = Typeface.MONOSPACE }
        root.addView(receiverBox)

        // 로그
        root.title("기록")
        root.row("새로고침" to { refresh() }, "지우기" to { AppLog.clear(this); refresh() })
        logText = root.text(size = 12f).apply { typeface = Typeface.MONOSPACE }
    }

    private fun refresh() {
        val receiver = prefs.mode == Prefs.MODE_RECEIVER
        senderBox.visibility = if (receiver) View.GONE else View.VISIBLE
        receiverBox.visibility = if (receiver) View.VISIBLE else View.GONE
        senderStatus.text = "상태: ${prefs.senderStatus}"

        val a11y = MacroAccessibilityService.instance != null
        val pm = getSystemService(PowerManager::class.java)
        val batt = pm.isIgnoringBatteryOptimizations(packageName)
        val pkg = prefs.targetPackage
        receiverStatus.text = buildString {
            append(if (prefs.isPaired) "✅ 페어링 코드 있음\n" else "❌ 페어링 코드 없음\n")
            append(if (prefs.receiverEnabled) "✅ 수신 서비스: ${prefs.receiverConn}\n" else "❌ 수신 서비스 꺼짐\n")
            append(if (a11y) "✅ 접근성 서비스 켜짐\n" else "❌ 접근성 서비스 꺼짐 → [접근성 설정]\n")
            append(if (batt) "✅ 배터리 최적화 제외됨\n" else "⚠️ 배터리 최적화 제외 권장\n")
            append(if (pkg.isNotEmpty()) "✅ 대상 앱: $pkg" else "❌ 기아 앱을 선택하세요")
        }
        targetText.text = if (pkg.isEmpty()) "선택 안 됨" else "${appLabel(pkg)} ($pkg)"
        dumpText.text = prefs.lastDump.ifEmpty { "(아직 분석 결과 없음)" }
        logText.text = AppLog.read(this)
    }

    // ───────────────────────── 동작 ─────────────────────────

    private fun saveCommon() {
        val code = pairingEdit.text.toString().trim()
        if (code.isNotEmpty() && (!code.contains('.') || code.substringAfter('.').length < 12)) {
            toast("페어링 코드 형식이 올바르지 않습니다")
            return
        }
        val changed = code != prefs.pairing || serverEdit.text.toString().trim().trimEnd('/') != prefs.server
        prefs.pairing = code
        prefs.server = serverEdit.text.toString().ifBlank { Prefs.DEFAULT_SERVER }
        if (changed) {
            prefs.lastNtfyId = ""
            toast("저장됨")
            if (prefs.mode == Prefs.MODE_RECEIVER && prefs.receiverEnabled) {
                ReceiverService.stop(this); ReceiverService.start(this)
            }
        }
    }

    private fun saveScripts() {
        prefs.setScript(Prefs.CMD_UNLOCK, unlockScript.text.toString())
        prefs.setScript(Prefs.CMD_LOCK, lockScript.text.toString())
    }

    private fun send(cmd: String) {
        saveCommon()
        if (!prefs.isPaired) { toast("먼저 페어링 코드를 설정하세요"); return }
        SenderService.send(this, cmd)
        toast("${Prefs.cmdLabel(cmd)} 명령을 보냈습니다")
    }

    private fun testRun(cmd: String) {
        saveScripts()
        val svc = MacroAccessibilityService.instance
        if (svc == null) { toast("접근성 서비스를 먼저 켜세요"); return }
        AlertDialog.Builder(this)
            .setMessage("실제로 기아 앱을 조작해 차에 '${Prefs.cmdLabel(cmd)}' 명령이 갈 수 있습니다. 실행할까요?")
            .setPositiveButton("실행") { _, _ ->
                Thread {
                    val r = svc.runScript(prefs.script(cmd), prefs.targetPackage)
                    Notifier.result(this, (if (r.ok) "✅ 테스트 성공: " else "❌ 테스트 실패: ") + Prefs.cmdLabel(cmd), r.message)
                    AppLog.i(this, "[테스트] ${r.ok} ${r.message}")
                }.start()
            }
            .setNegativeButton("취소", null).show()
    }

    private fun dumpLater() {
        val svc = MacroAccessibilityService.instance
        if (svc == null) { toast("접근성 서비스를 먼저 켜세요"); return }
        toast("5초 뒤 화면을 분석합니다. 기아 앱으로 이동하세요")
        Thread {
            Thread.sleep(5000)
            val d = svc.dumpScreen()
            prefs.lastDump = d
            Notifier.result(this, "화면 분석 완료", "차량 매크로 앱으로 돌아가서 결과를 확인하세요", Notifier.ID_DUMP)
        }.start()
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
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
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .filter { it.first != packageName }
            .sortedWith(compareBy({ !isKiaLike(it) }, { it.second }))
        AlertDialog.Builder(this)
            .setTitle("기아 앱 선택")
            .setItems(apps.map { "${it.second}\n${it.first}" }.toTypedArray()) { _, i ->
                prefs.targetPackage = apps[i].first
                refresh()
            }.show()
    }

    private fun isKiaLike(a: Pair<String, String>) =
        a.first.contains("kia", true) || a.second.contains("kia", true) || a.second.contains("기아")

    private fun copy(s: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("carmacro", s))
        toast("복사됨")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private val HELP = """
            스크립트 명령 (한 줄에 하나, # 뒤는 주석, 여러 문구는 | 로 구분)
            restart — 기아 앱을 새로 시작
            launch — 기아 앱 열기
            wait 3 — 3초 대기
            click 문 열림|도어 열림 — 그 문구의 버튼 누르기 (최대 15초 기다림)
            click 30 문 열림 — 최대 30초 기다렸다 누르기
            clickif 확인|닫기 — 팝업이 있으면 누르고, 없으면 넘어감
            waitfor 원격 제어 — 그 문구가 보일 때까지 대기
            tap 540 1600 — 좌표 터치 (화면 분석 결과 참고)
            click id:btn_door_open — 화면 분석에 나온 id로 누르기
            click =열기 — 완전히 같은 문구만
            pin 1234 — 숫자 키패드로 PIN 입력
            failon 실패|오류 — 이 문구가 보이면 실패
            expect 90 완료|성공 — 90초 안에 이 문구가 화면/알림에 나오면 성공
            expectnoti 90 완료 — 알림/토스트에서만 확인
            back / home — 뒤로 / 홈
        """.trimIndent()
    }
}
