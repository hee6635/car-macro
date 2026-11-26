package com.hee.carmacro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.Notification
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 수신기(픽셀1)에서 기아 앱을 대신 조작하는 접근성 서비스.
 * 기아 앱 화면 구조(2026-11 확인):
 *   하단 탭 '제어' → 타일 [시동/공조 켜기] [최대 난방 켜기] [문 잠금 해제|잠금] ...
 *   '시동 / 공조 설정' → 공조 모드, 시동 유지 시간, 희망 온도(+/-), 스위치들, 시트 열선/통풍
 *   PIN: '간편비밀번호' 숫자 키패드
 *   결과: 기아 알림 '원격제어 결과 안내 …'
 */
class MacroAccessibilityService : AccessibilityService() {

    data class Result(
        val code: String,
        val message: String,
        val door: String = "",
        val engine: String = "",
        val minutes: Int = 0,
    ) {
        val ok get() = code == Protocol.CODE_OK || code == Protocol.CODE_ALREADY

        fun dataJson(): String = JSONObject().apply {
            put("code", code); put("door", door); put("engine", engine); put("minutes", minutes)
        }.toString()
    }

    private class Fail(val code: String, msg: String) : Exception(msg)

    private data class Ev(val time: Long, val pkg: String, val type: Int, val text: String)

    private val events = ArrayDeque<Ev>()
    private val busy = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private var kiaPkg = ""
    private var pin = ""

    override fun onServiceConnected() {
        instance = this
        AppLog.i(this, "[접근성] 서비스 연결됨")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        e ?: return
        val type = e.eventType
        if (type != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_ANNOUNCEMENT &&
            type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return
        val sb = StringBuilder(e.text.joinToString(" "))
        (e.parcelableData as? Notification)?.extras?.let { x ->
            listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
                .forEach { k -> x.getCharSequence(k)?.let { sb.append(' ').append(it) } }
        }
        val text = sb.toString().trim()
        if (text.isEmpty()) return
        synchronized(events) {
            events.addLast(Ev(System.currentTimeMillis(), e.packageName?.toString() ?: "", type, text))
            while (events.size > 80) events.removeFirst()
        }
    }

    // ═════════════════════════ 명령 실행 ═════════════════════════

    /** 백그라운드 스레드에서 호출 (블로킹). */
    /** 진행 상황 알림 (리모컨에 실시간으로 보냄). PIN 숫자는 절대 넣지 않음 */
    @Volatile private var progress: (String) -> Unit = {}

    fun run(cmd: String, prefs: Prefs, onProgress: (String) -> Unit = {}): Result {
        progress = onProgress
        if (!busy.compareAndSet(false, true)) return Result(Protocol.CODE_FAIL, "이미 다른 명령을 실행 중이에요")
        kiaPkg = prefs.targetPackage
        pin = prefs.pin
        val pm = getSystemService(PowerManager::class.java)
        @Suppress("DEPRECATION")
        val screen = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "carmacro:screen"
        )
        screen.acquire(4 * 60_000L)
        log("━━ ${Prefs.cmdLabel(cmd)} 시작")
        return try {
            if (kiaPkg.isEmpty()) throw Fail(Protocol.CODE_FAIL, "기아 앱이 선택되지 않았어요 (픽셀1 설정)")
            progress("화면 켜는 중")
            wakeAndUnlock()
            openControlTab()
            val r = when (cmd) {
                Prefs.CMD_UNLOCK -> door(unlock = true)
                Prefs.CMD_LOCK -> door(unlock = false)
                Prefs.CMD_START1, Prefs.CMD_START2, Prefs.CMD_START3 ->
                    startWithPreset(prefs.preset(Prefs.presetOf(cmd)))
                Prefs.CMD_START_MAX -> startMax()
                Prefs.CMD_STOP -> stop()
                Prefs.CMD_CHECK -> Result(Protocol.CODE_OK, "기아 앱 로그인 정상 · 제어 화면 확인")
                Prefs.CMD_READ_OPTIONS -> readOptions(prefs)
                else -> throw Fail(Protocol.CODE_FAIL, "알 수 없는 명령: $cmd")
            }
            // 마지막으로 화면에 보이는 문/시동 상태를 함께 보고
            val after = readCarState()
            prefs.kiaLogin = "ok"
            r.copy(
                door = r.door.ifEmpty { after.first },
                engine = r.engine.ifEmpty { after.second },
            ).also { log("✔ ${it.code} ${it.message}") }
        } catch (e: Fail) {
            if (e.code == Protocol.CODE_LOGIN) prefs.kiaLogin = "logout"
            log("✖ ${e.message}")
            Result(e.code, e.message ?: "실패")
        } catch (e: Exception) {
            log("✖ 오류: $e")
            Result(Protocol.CODE_FAIL, "오류: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            try {
                performGlobalAction(GLOBAL_ACTION_HOME)
            } catch (_: Exception) {
            }
            if (screen.isHeld) screen.release()
            busy.set(false)
        }
    }

    // ── 문 ──
    private fun door(unlock: Boolean): Result {
        val state = ensureTile("문")
            ?: throw Fail(Protocol.CODE_FAIL, "제어 화면에서 '문' 버튼을 찾지 못했어요")
        val isLocked = state.contains("해제")   // 잠겨 있으면 버튼이 '잠금 해제'
        if (unlock && !isLocked) return Result(Protocol.CODE_ALREADY, "이미 열려 있어요", door = "unlocked")
        if (!unlock && isLocked) return Result(Protocol.CODE_ALREADY, "이미 잠겨 있어요", door = "locked")
        val r = pressAndWait("문", if (unlock) "문이 열렸어요" else "문이 잠겼어요") { s -> if (unlock) !s.contains("해제") else s.contains("해제") }
        return r.copy(door = if (r.code == Protocol.CODE_OK) (if (unlock) "unlocked" else "locked") else "")
    }

    // ── 시동 ──
    private fun startWithPreset(p: Preset): Result {
        if (engineIsOn()) return Result(Protocol.CODE_ALREADY, "이미 시동이 켜져 있어요", engine = "on")
        var startedInSettings = false
        if (!p.isEmpty) startedInSettings = applyPreset(p)
        val r = if (startedInSettings) waitResult(null, "시동이 걸렸어요") { true }
        else {
            if (ensureTile("시동/공조", 6) == null) openControlTab()
            pressAndWait("시동/공조", "시동이 걸렸어요") { it.contains("끄기") }
        }
        return r.copy(engine = if (r.code == Protocol.CODE_OK) "on" else "", minutes = p.minutes)
    }

    private fun startMax(): Result {
        if (engineIsOn()) return Result(Protocol.CODE_ALREADY, "이미 시동이 켜져 있어요", engine = "on")
        val r = pressAndWait("최대 난방", "최대 난방으로 시동이 걸렸어요") { it.contains("끄기") }
        return r.copy(engine = if (r.code == Protocol.CODE_OK) "on" else "")
    }

    private fun stop(): Result {
        val label = listOf("시동/공조", "최대 난방").firstOrNull { ensureTile(it, 6)?.contains("끄기") == true }
            ?: return Result(Protocol.CODE_ALREADY, "이미 시동이 꺼져 있어요", engine = "off")
        val r = pressAndWait(label, "시동이 꺼졌어요") { !it.contains("끄기") }
        return r.copy(engine = if (r.code == Protocol.CODE_OK) "off" else "")
    }

    private fun engineIsOn() = listOf("시동/공조", "최대 난방").any { ensureTile(it, 6)?.contains("끄기") == true }

    private fun readCarState(): Pair<String, String> = try {
        val ds = ensureTile("문", 5)
        if (ds == null) "" to ""
        else {
            val d = if (ds.contains("해제")) "locked" else "unlocked"
            d to (if (engineIsOn()) "on" else "off")
        }
    } catch (e: Exception) {
        "" to ""
    }

    // ═════════════════════════ 기아 앱 화면 이동 ═════════════════════════

    private fun wakeAndUnlock() {
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) return
        startActivity(Intent(this, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val end = System.currentTimeMillis() + 4000
        while (km.isKeyguardLocked && System.currentTimeMillis() < end) Thread.sleep(200)
        if (km.isKeyguardLocked) throw Fail(
            Protocol.CODE_FAIL, "픽셀1 잠금화면을 넘기지 못했어요. 화면 잠금을 '없음'으로 설정하세요."
        )
    }

    /** 기아 앱을 새로 띄우고 하단 '제어' 탭까지 이동 */
    private fun openControlTab() {
        val intent = packageManager.getLaunchIntentForPackage(kiaPkg)
            ?: throw Fail(Protocol.CODE_FAIL, "기아 앱($kiaPkg)을 찾을 수 없어요")
        performGlobalAction(GLOBAL_ACTION_HOME)
        Thread.sleep(600)
        try {
            getSystemService(ActivityManager::class.java).killBackgroundProcesses(kiaPkg)
        } catch (_: Exception) {
        }
        Thread.sleep(400)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        log("기아 앱 실행")
        progress("기아 앱 여는 중")

        val end = System.currentTimeMillis() + 35_000
        var lastTabClick = 0L
        while (System.currentTimeMillis() < end) {
            handleInterrupts()
            if (tileState("시동/공조") != null || tileState("문") != null) {
                // 기아 앱은 제어 탭에 들어오면 스스로 새로고침하며 화면을 밀었다 올림 → 멈출 때까지 대기
                progress("제어 화면 도착")
                waitSettled()
                return
            }
            // 탭을 계속 누르면 새로고침이 반복되므로 4초에 한 번만
            if (System.currentTimeMillis() - lastTabClick > 4000) {
                bottomTab("제어")?.let { click(it); lastTabClick = System.currentTimeMillis() }
            }
            Thread.sleep(500)
        }
        throw Fail(Protocol.CODE_FAIL, "기아 앱 '제어' 화면을 열지 못했어요")
    }

    /** 화면 아래쪽에 있는 탭 버튼 */
    private fun bottomTab(label: String): AccessibilityNodeInfo? {
        val h = resources.displayMetrics.heightPixels
        val r = Rect()
        return visibleNodes().filter { exact(it, label) }
            .map { it.getBoundsInScreen(r); it to r.centerY() }
            .filter { it.second > h * 0.8 }
            .maxByOrNull { it.second }?.first
    }

    /** 끼어드는 화면 처리: 로그인 화면이면 중단, PIN 화면이면 입력, 알림 팝업이면 닫기 */
    private fun handleInterrupts() {
        val texts = allTexts()
        val norm = texts.map { norm(it) }
        val hasLoginButton = visibleNodes().any { exact(it, "로그인") && clickableOf(it) != null }
        if (hasLoginButton && norm.none { it.contains("간편비밀번호") }) {
            throw Fail(Protocol.CODE_LOGIN, "기아 앱이 로그아웃됐어요. 픽셀1에서 다시 로그인해 주세요.")
        }
        if (norm.any { it.contains("간편비밀번호") }) enterPin()
        for (w in POPUP_DISMISS) {
            visibleNodes().firstOrNull { exact(it, w) }?.let {
                log("팝업 닫기: $w"); click(it); Thread.sleep(600); return
            }
        }
    }

    // ═════════════════════════ 타일 조작 ═════════════════════════

    /** 제어 탭 타일의 상태 문구 (라벨 제외한 나머지). 타일이 없으면 null */
    private fun tileState(label: String): String? {
        val n = visibleNodes().firstOrNull { exact(it, label) } ?: return null
        val tile = clickableOf(n) ?: return null
        return subtreeTexts(tile).filter { norm(it) != norm(label) }.joinToString(" ")
    }

    /** 화면 움직임(새로고침·애니메이션)이 멈출 때까지 기다림 */
    private fun waitSettled(maxMs: Long = 6000) {
        val end = System.currentTimeMillis() + maxMs
        var last = snapshot()
        var stable = 0
        while (System.currentTimeMillis() < end) {
            Thread.sleep(400)
            val now = snapshot()
            if (now == last) { if (++stable >= 2) return } else stable = 0
            last = now
        }
    }

    private fun snapshot(): String {
        val r = Rect()
        return visibleNodes().mapNotNull { n ->
            val t = nodeText(n) ?: return@mapNotNull null
            n.getBoundsInScreen(r); "$t@${r.top}"
        }.joinToString("|")
    }

    /**
     * 타일을 화면에 보이게 한 뒤 상태 문구 반환. 작은 화면(픽셀1)에서는 아래 줄 타일이
     * 메뉴 바 밑이나 화면 밖에 있을 수 있어 기다리기 → 화면에 보이게 요청 → 스크롤 순서로 찾는다.
     */
    private fun ensureTile(label: String, timeoutSec: Int = 12): String? {
        tileState(label)?.let { return it }
        val end = System.currentTimeMillis() + timeoutSec * 1000L
        var forward = 0
        var backward = 0
        while (System.currentTimeMillis() < end) {
            waitSettled(3000)
            tileState(label)?.let { return it }
            // 트리에는 있는데 화면 밖이면 보이게 해 달라고 요청
            allNodes().firstOrNull { exact(it, label) }?.let { n ->
                (clickableOf(n) ?: n).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                Thread.sleep(600)
                tileState(label)?.let { return it }
            }
            val r = Rect()
            val scroller = allNodes().filter { it.isScrollable }
                .maxByOrNull { it.getBoundsInScreen(r); r.width() * r.height() }
            when {
                scroller == null -> Thread.sleep(500)
                forward < 3 -> { forward++; if (!scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) forward = 3 }
                backward < 4 -> { backward++; scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }
                else -> { forward = 0; backward = 0 }
            }
            Thread.sleep(500)
        }
        log("'$label' 버튼을 ${timeoutSec}초 동안 찾지 못함")
        return null
    }

    /** 타일 누르기 → (확인 팝업/PIN 처리) → 결과 대기 */
    private fun pressAndWait(label: String, okText: String, reached: (String) -> Boolean): Result {
        ensureTile(label) ?: throw Fail(Protocol.CODE_FAIL, "'$label' 버튼을 찾지 못했어요")
        val n = visibleNodes().firstOrNull { exact(it, label) }
            ?: throw Fail(Protocol.CODE_FAIL, "'$label' 버튼을 찾지 못했어요")
        val since = System.currentTimeMillis()
        val st = tileState(label).orEmpty()
        log("누름: $label ($st)")
        progress("'$label $st' 누름")
        click(clickableOf(n) ?: n)
        return waitResult(label, okText, since, reached)
    }

    private fun waitResult(label: String?, okText: String, since: Long = System.currentTimeMillis(), reached: (String) -> Boolean): Result {
        val end = System.currentTimeMillis() + 90_000
        var requested = false
        var reported = false
        progress("차 응답 기다리는 중")
        while (System.currentTimeMillis() < end) {
            if (requested && !reported) { reported = true; progress("차 응답 기다리는 중 (기아: 차량 제어를 요청했습니다)") }
            // PIN / 확인 팝업
            val texts = allTexts()
            if (texts.any { norm(it).contains("간편비밀번호") }) enterPin()
            visibleNodes().firstOrNull { exact(it, "확인") && clickableOf(it) != null }?.let {
                log("확인 누름"); click(clickableOf(it)!!); Thread.sleep(600)
            }
            if (texts.any { it.contains("요청했습니다") }) requested = true

            // 기아 결과 알림/토스트
            val evs = synchronized(events) {
                events.filter { it.time >= since && (it.pkg == kiaPkg || it.text.contains("원격제어")) }.map { it.text }
            }
            val screenResults = texts.filter { t -> t.contains("원격제어") || OK_WORDS.any { t.contains(it) } }
            for (t in evs + screenResults) {
                if (t.contains("요청했습니다")) { requested = true; continue }
                if (FAIL_WORDS.any { t.contains(it) }) return Result(Protocol.CODE_FAIL, "기아: ${t.take(120)}")
                if (OK_WORDS.any { t.contains(it) }) return Result(Protocol.CODE_OK, "기아: ${t.take(120)}")
            }
            // 타일 상태가 바뀌었으면 성공으로 봄
            if (label != null) {
                val s = tileState(label)
                if (s != null && reached(s)) return Result(Protocol.CODE_OK, "$okText (기아 앱 상태로 확인)")
            }
            Thread.sleep(700)
        }
        return Result(
            Protocol.CODE_FAIL,
            if (requested) "기아에 요청은 됐지만 90초 안에 결과가 오지 않았어요. 기아 앱을 확인하세요."
            else "기아 앱에서 요청이 접수되지 않았어요."
        )
    }

    private fun enterPin() {
        if (pin.length < 4) throw Fail(Protocol.CODE_FAIL, "간편비밀번호를 물어보는데 픽셀1에 PIN이 설정되지 않았어요")
        log("간편비밀번호 입력")
        progress("간편비밀번호 입력 중 " + "○".repeat(pin.length))
        for ((i, ch) in pin.withIndex()) {
            val n = waitNode(6) { visibleNodes().firstOrNull { exact(it, ch.toString()) } }
                ?: throw Fail(Protocol.CODE_FAIL, "PIN 키패드 숫자를 읽지 못했어요 (화면 분석 필요)")
            click(clickableOf(n) ?: n)
            // 몇 자리째인지만 알림 (숫자는 보내지 않음)
            progress("간편비밀번호 입력 중 " + "●".repeat(i + 1) + "○".repeat(pin.length - i - 1))
            Thread.sleep(500)
        }
        val end = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < end) {
            val t = allTexts()
            if (t.none { norm(it).contains("간편비밀번호") }) return
            if (t.any { it.contains("일치하지") || it.contains("잘못") }) {
                throw Fail(Protocol.CODE_FAIL, "간편비밀번호가 틀렸어요. 픽셀1의 PIN 설정을 확인하세요.")
            }
            Thread.sleep(300)
        }
    }

    // ═════════════════════════ 시동/공조 설정 ═════════════════════════

    /** 설정 화면에서 프리셋 적용. 설정 화면 안에서 바로 시동을 걸었으면 true */
    private fun applyPreset(p: Preset): Boolean {
        val entry = findWithScroll { exact(it, "시동 / 공조 설정") }
            ?: throw Fail(Protocol.CODE_FAIL, "'시동 / 공조 설정' 메뉴를 찾지 못했어요")
        click(clickableOf(entry) ?: entry)
        waitNode(10) { visibleNodes().firstOrNull { exact(it, "희망 온도") } }
            ?: throw Fail(Protocol.CODE_FAIL, "시동/공조 설정 화면이 열리지 않았어요")
        log("시동/공조 설정 적용")
        progress("공조 설정 여는 중")

        if (p.mode.isNotEmpty()) selectOption("공조 모드", p.mode)
        if (p.duration.isNotEmpty()) selectOption("시동 유지 시간", p.duration)
        if (p.temp.isNotEmpty()) setTemp(p.temp)
        setSwitch("앞유리 성에 제거", p.defrost)
        setSwitch("사이드 미러 + 뒷유리", p.mirror)
        setSwitch("핸들", p.wheel)
        if (p.seat.isNotEmpty()) selectOption("시트 열선 / 통풍", p.seat)

        // 하단 버튼: 저장류면 저장, 시동 버튼이면 바로 시동
        val h = resources.displayMetrics.heightPixels
        val r = Rect()
        val bottom = visibleNodes().filter { n ->
            n.getBoundsInScreen(r); r.centerY() > h * 0.75 && clickableOf(n) != null &&
                !n.text.isNullOrBlank()
        }
        bottom.firstOrNull { n -> SAVE_WORDS.any { exact(n, it) } }?.let {
            log("설정 저장: ${it.text}"); click(clickableOf(it)!!); Thread.sleep(1500)
            backToControl(); return false
        }
        bottom.firstOrNull { n -> norm(n.text.toString()).let { it.contains("시동") && (it.contains("켜") || it.contains("걸")) } }?.let {
            log("설정 화면에서 시동: ${it.text}"); click(clickableOf(it)!!)
            return true
        }
        log("저장 버튼이 없어 뒤로 나감 (자동 저장으로 가정)")
        backToControl()
        return false
    }

    /** '시동 / 공조 설정'의 하위 화면 선택지를 읽어서 저장 (차는 안 움직임) */
    private fun readOptions(prefs: Prefs): Result {
        val entry = findWithScroll { exact(it, "시동 / 공조 설정") }
            ?: throw Fail(Protocol.CODE_FAIL, "'시동 / 공조 설정' 메뉴를 찾지 못했어요")
        click(clickableOf(entry) ?: entry)
        waitNode(10) { visibleNodes().firstOrNull { exact(it, "희망 온도") } }
            ?: throw Fail(Protocol.CODE_FAIL, "시동/공조 설정 화면이 열리지 않았어요")
        val out = JSONObject()
        val found = mutableListOf<String>()
        for (rowLabel in Prefs.OPTION_ROWS) {
            val row = findWithScroll { exact(it, rowLabel) } ?: continue
            val rr = Rect().also { row.getBoundsInScreen(it) }
            val r = Rect()
            // 줄에 표시된 현재 값
            val current = visibleNodes().filter { n ->
                n.getBoundsInScreen(r); !n.text.isNullOrBlank() && !exact(n, rowLabel) &&
                    kotlin.math.abs(r.centerY() - rr.centerY()) < rr.height()
            }.map { it.text.toString().trim() }
            val before = allTexts().toSet()
            click(clickableOf(row) ?: row)
            Thread.sleep(1500)
            val skip = setOf("적용", "확인", "취소", "닫기", "저장", "완료", rowLabel)
            val opts = visibleNodes().filter { clickableOf(it) != null && !it.text.isNullOrBlank() }
                .map { it.text.toString().trim() }
                .filter { it !in skip && (it !in before || it in current) }
                .distinct()
            if (opts.isNotEmpty()) { out.put(rowLabel, org.json.JSONArray(opts)); found += "$rowLabel ${opts.size}개" }
            log("$rowLabel 선택지: $opts")
            performGlobalAction(GLOBAL_ACTION_BACK); Thread.sleep(900)
            if (visibleNodes().none { exact(it, "희망 온도") }) {
                // 뒤로 가기로 설정 화면까지 나갔으면 다시 들어감
                if (ensureTile("시동/공조", 6) == null) openControlTab()
                val e2 = findWithScroll { exact(it, "시동 / 공조 설정") } ?: break
                click(clickableOf(e2) ?: e2)
                waitNode(8) { visibleNodes().firstOrNull { exact(it, "희망 온도") } } ?: break
            }
        }
        if (out.length() == 0) throw Fail(Protocol.CODE_FAIL, "선택지를 읽지 못했어요. 프리셋에서 직접 입력하세요.")
        prefs.kiaOptions = out.toString()
        backToControl()
        return Result(Protocol.CODE_OK, "선택지 읽음: ${found.joinToString(", ")}")
    }

    private fun backToControl() {
        repeat(3) {
            if (tileState("시동/공조") != null) return
            performGlobalAction(GLOBAL_ACTION_BACK); Thread.sleep(900)
        }
        if (ensureTile("시동/공조", 6) == null) openControlTab()
    }

    private fun tempValue(s: String): Double? {
        val t = s.trim().replace("℃", "").replace("°C", "").replace("°", "").trim()
        return when {
            t.equals("low", true) || t == "LO" -> -1000.0
            t.equals("high", true) || t == "HI" -> 1000.0
            else -> t.toDoubleOrNull()
        }
    }

    private fun setTemp(target: String) {
        val goal = tempValue(target) ?: throw Fail(Protocol.CODE_FAIL, "희망 온도 값 '$target' 형식 오류")
        val row = findWithScroll { exact(it, "희망 온도") } ?: throw Fail(Protocol.CODE_FAIL, "'희망 온도'를 찾지 못했어요")
        click(clickableOf(row) ?: row)
        if (waitNode(5) { visibleNodes().firstOrNull { exact(it, "적용") } } == null) {
            throw Fail(Protocol.CODE_FAIL, "희망 온도 창이 열리지 않았어요")
        }
        var last: Double? = null
        var stuck = 0
        var tries = 0
        val seen = mutableListOf<String>()
        fun show(v: Double, done: Boolean = false) {
            val f = fmtTemp(v)
            if (seen.lastOrNull() != f) seen += f
            progress("희망 온도 맞추는 중 " + seen.takeLast(6).joinToString(" → ") + if (done) " ✓" else "")
        }
        while (true) {
            if (++tries > 60) throw Fail(Protocol.CODE_FAIL, "희망 온도를 맞추지 못했어요")
            val (valueNode, cur) = bigTempValue() ?: throw Fail(Protocol.CODE_FAIL, "희망 온도 값을 읽지 못했어요")
            show(cur, done = cur == goal)
            if (cur == goal) { log("희망 온도 = $target"); break }
            val prev = last
            if (prev != null && cur == prev) {
                if (++stuck >= 3) throw Fail(Protocol.CODE_FAIL, "희망 온도가 더 바뀌지 않아요 (현재 ${fmtTemp(cur)})")
            } else stuck = 0
            // 목표를 지나쳤으면 (0.5도 단위 등) 거기서 멈춤
            if (prev != null && ((prev < goal && cur > goal) || (prev > goal && cur < goal))) {
                show(cur, done = true); log("희망 온도 ≈ ${fmtTemp(cur)}"); break
            }
            last = cur
            val (minus, plus) = plusMinusButtons(valueNode)
            val btn = (if (cur < goal) plus else minus)
                ?: throw Fail(Protocol.CODE_FAIL, "희망 온도 +/- 버튼을 찾지 못했어요")
            click(btn)
            Thread.sleep(250)
        }
        visibleNodes().firstOrNull { exact(it, "적용") }?.let { click(clickableOf(it) ?: it); Thread.sleep(800) }
    }

    private fun fmtTemp(v: Double) = when {
        v == -1000.0 -> "Low"
        v == 1000.0 -> "High"
        v % 1.0 == 0.0 -> v.toInt().toString()
        else -> v.toString()
    }

    /** 팝업 가운데 큰 온도 표시 (같은 문구가 여러 개면 글자 영역이 가장 큰 것) */
    private fun bigTempValue(): Pair<AccessibilityNodeInfo, Double>? {
        val r = Rect()
        return visibleNodes().mapNotNull { n ->
            val t = n.text?.toString() ?: return@mapNotNull null
            val v = tempValue(t) ?: return@mapNotNull null
            n.getBoundsInScreen(r)
            Triple(n, v, r.height())
        }.maxByOrNull { it.third }?.let { it.first to it.second }
    }

    /** 값 표시와 같은 줄에 있는 왼쪽(-)/오른쪽(+) 버튼 */
    private fun plusMinusButtons(value: AccessibilityNodeInfo): Pair<AccessibilityNodeInfo?, AccessibilityNodeInfo?> {
        val vr = Rect().also { value.getBoundsInScreen(it) }
        val r = Rect()
        val same = visibleNodes().mapNotNull { clickableOf(it) }.distinct().filter { c ->
            c.getBoundsInScreen(r)
            kotlin.math.abs(r.centerY() - vr.centerY()) < vr.height() && !r.contains(vr.centerX(), vr.centerY())
        }
        val byDesc = { words: List<String> ->
            same.firstOrNull { c -> val d = (c.contentDescription ?: c.text ?: "").toString(); words.any { d.contains(it) } }
        }
        val minus = byDesc(listOf("감소", "내리", "낮추", "-", "−", "minus")) ?: same.filter { c ->
            c.getBoundsInScreen(r); r.centerX() < vr.left
        }.maxByOrNull { c -> c.getBoundsInScreen(r); r.centerX() }
        val plus = byDesc(listOf("증가", "올리", "높이", "+", "plus")) ?: same.filter { c ->
            c.getBoundsInScreen(r); r.centerX() > vr.right
        }.minByOrNull { c -> c.getBoundsInScreen(r); r.centerX() }
        return minus to plus
    }

    /** 라벨과 같은 줄의 스위치를 원하는 상태로 (want: 1 켬, 0 끔, -1 그대로) */
    private fun setSwitch(label: String, want: Int) {
        if (want < 0) return
        val row = findWithScroll { exact(it, label) } ?: throw Fail(Protocol.CODE_FAIL, "'$label' 항목을 찾지 못했어요")
        val sw = switchFor(row) ?: throw Fail(Protocol.CODE_FAIL, "'$label' 스위치를 찾지 못했어요")
        val on = want == 1
        if (sw.isChecked == on) return
        click(clickableOf(sw) ?: sw)
        Thread.sleep(500)
        val again = switchFor(visibleNodes().firstOrNull { exact(it, label) } ?: row)
        log("$label → ${if (again?.isChecked == on) (if (on) "켬" else "끔") else "확인 불가"}")
        progress("$label ${if (on) "켬" else "끔"}")
    }

    private fun switchFor(label: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        clickableOf(label)?.let { if (it.isCheckable) return it }
        val lr = Rect().also { label.getBoundsInScreen(it) }
        val r = Rect()
        val tol = (40 * resources.displayMetrics.density).toInt()
        return visibleNodes().filter { it.isCheckable }.filter { n ->
            n.getBoundsInScreen(r); kotlin.math.abs(r.centerY() - lr.centerY()) < tol
        }.minByOrNull { n -> n.getBoundsInScreen(r); kotlin.math.abs(r.centerY() - lr.centerY()) }
    }

    /** 하위 화면에서 문구 고르기 (예: 시동 유지 시간 → "10분") */
    private fun selectOption(rowLabel: String, option: String) {
        val row = findWithScroll { exact(it, rowLabel) } ?: throw Fail(Protocol.CODE_FAIL, "'$rowLabel' 항목을 찾지 못했어요")
        click(clickableOf(row) ?: row)
        Thread.sleep(1000)
        val opt = waitNode(6) {
            visibleNodes().firstOrNull { exact(it, option) && !exact(it, rowLabel) }
        } ?: run {
            performGlobalAction(GLOBAL_ACTION_BACK)
            throw Fail(Protocol.CODE_FAIL, "'$rowLabel'에서 '$option' 선택지를 찾지 못했어요 (프리셋 문구 확인)")
        }
        click(clickableOf(opt) ?: opt)
        Thread.sleep(700)
        visibleNodes().firstOrNull { n -> listOf("적용", "확인", "완료", "저장").any { exact(n, it) } }
            ?.let { click(clickableOf(it) ?: it); Thread.sleep(700) }
        if (visibleNodes().none { exact(it, "희망 온도") }) {
            performGlobalAction(GLOBAL_ACTION_BACK); Thread.sleep(800)
        }
        log("$rowLabel → $option")
        progress("$rowLabel → $option")
    }

    // ═════════════════════════ 접근성 도우미 ═════════════════════════

    private fun norm(s: String) = s.replace(Regex("\\s+"), "").lowercase()

    private fun nodeText(n: AccessibilityNodeInfo) = (n.text ?: n.contentDescription)?.toString()

    private fun exact(n: AccessibilityNodeInfo, label: String): Boolean {
        val t = n.text?.toString()
        val d = n.contentDescription?.toString()
        val l = norm(label)
        return (t != null && norm(t) == l) || (d != null && norm(d) == l)
    }

    private fun roots(): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        try {
            windows.sortedByDescending { it.layer }.forEach { w -> w.root?.let { list += it } }
        } catch (_: Exception) {
        }
        if (list.isEmpty()) rootInActiveWindow?.let { list += it }
        return list
    }

    private fun walk(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        n ?: return
        out += n
        for (i in 0 until n.childCount) walk(n.getChild(i), out)
    }

    private fun allNodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        roots().forEach { walk(it, out) }
        return out
    }

    private fun visibleNodes() = allNodes().filter { it.isVisibleToUser }

    private fun allTexts(): List<String> = visibleNodes().mapNotNull { nodeText(it)?.trim()?.ifEmpty { null } }

    private fun subtreeTexts(n: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        walk(n, out)
        return out.mapNotNull { nodeText(it)?.trim()?.ifEmpty { null } }
    }

    private fun clickableOf(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        return c
    }

    private fun <T> waitNode(timeoutSec: Int, find: () -> T?): T? {
        val end = System.currentTimeMillis() + timeoutSec * 1000L
        while (true) {
            find()?.let { return it }
            if (System.currentTimeMillis() >= end) return null
            Thread.sleep(300)
        }
    }

    /** 화면에 없으면 스크롤하면서 찾기 */
    private fun findWithScroll(pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        visibleNodes().firstOrNull(pred)?.let { return it }
        val r = Rect()
        val scroller = allNodes().filter { it.isScrollable }
            .maxByOrNull { it.getBoundsInScreen(r); r.width() * r.height() } ?: return null
        for (action in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            repeat(6) {
                if (!scroller.performAction(action)) return@repeat
                Thread.sleep(500)
                visibleNodes().firstOrNull(pred)?.let { return it }
            }
        }
        return null
    }

    private fun click(n: AccessibilityNodeInfo) {
        if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        val r = Rect().also { n.getBoundsInScreen(it) }
        tap(r.exactCenterX(), r.exactCenterY())
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val latch = CountDownLatch(1)
        main.post {
            val sent = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) = latch.countDown()
                override fun onCancelled(d: GestureDescription?) = latch.countDown()
            }, null)
            if (!sent) latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
    }

    private fun log(s: String) = AppLog.i(this, "[기아] $s")

    // ═════════════════════════ 화면 분석 ═════════════════════════

    fun dumpScreen(): String {
        val sb = StringBuilder()
        sb.append("앱 패키지: ${rootInActiveWindow?.packageName ?: "?"}\n")
        sb.append("── 화면 요소 (★=누를 수 있음, ☑/☐=스위치, [x,y]=중심 좌표) ──\n")
        val r = Rect()
        for (n in allNodes()) {
            if (!n.isVisibleToUser) continue
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            val id = n.viewIdResourceName?.substringAfter(":id/").orEmpty()
            if (t.isEmpty() && d.isEmpty() && id.isEmpty() && !n.isCheckable) continue
            n.getBoundsInScreen(r)
            sb.append(if (clickableOf(n) != null) "★ " else "  ")
            if (n.isCheckable) sb.append(if (n.isChecked) "☑ " else "☐ ")
            if (t.isNotEmpty()) sb.append("\"$t\" ")
            if (d.isNotEmpty() && d != t) sb.append("(설명: $d) ")
            if (id.isNotEmpty()) sb.append("id:$id ")
            sb.append("[${r.centerX()},${r.centerY()}]\n")
        }
        sb.append("── 최근 알림/토스트/창 제목 ──\n")
        synchronized(events) {
            events.toList().takeLast(25).forEach { e ->
                sb.append("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date(e.time))} ${e.pkg}: ${e.text}\n")
            }
        }
        return sb.toString()
    }

    companion object {
        @Volatile var instance: MacroAccessibilityService? = null

        /** 앱 시작 시 뜨는 공지/광고 팝업 닫기 문구 ('닫기'는 창문 닫기 버튼과 겹쳐서 쓰지 않음) */
        private val POPUP_DISMISS = listOf("오늘 하루 보지 않기", "다시 보지 않기", "다음에 하기", "나중에", "오늘 그만 보기")
        private val SAVE_WORDS = listOf("저장", "적용", "확인", "완료", "설정 완료")
        private val OK_WORDS = listOf("열렸습니다", "잠겼습니다", "켜졌습니다", "꺼졌습니다", "걸렸습니다", "시작되었습니다", "완료되었습니다", "성공")
        private val FAIL_WORDS = listOf("실패", "할 수 없", "없습니다", "오류", "불가", "다시 시도", "확인해 주세요", "응답이 없")
    }
}
