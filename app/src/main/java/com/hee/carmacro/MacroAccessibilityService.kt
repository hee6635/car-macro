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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 스크립트 명령 (한 줄에 하나, # 은 주석, 대안 문구는 | 로 구분)
 *   restart                 대상 앱을 강제로 새로 시작 (홈 화면부터)
 *   launch                  대상 앱 실행 (이미 열려 있으면 그 화면으로)
 *   wait <초>               기다리기
 *   click [초] <문구>       문구/설명/리소스ID(id:xxx)가 보이면 누르기 (기본 15초 대기)
 *   clickif [초] <문구>     있으면 누르고 없으면 그냥 넘어가기 (팝업 닫기용, 기본 3초)
 *   waitfor [초] <문구>     문구가 나타날 때까지 기다리기 (없으면 실패)
 *   tap <x> <y>             좌표 터치 (화면 분석 결과의 좌표 참고)
 *   pin <숫자>              숫자 키패드 버튼을 차례로 누르기 (PIN 입력)
 *   failon <문구>           이후 expect 중 이 문구가 보이면 실패 처리
 *   expect [초] <문구>      화면/알림/토스트에 문구가 나오면 성공 (기본 60초)
 *   expectnoti [초] <문구>  알림/토스트에서만 찾기
 *   back / home             뒤로 / 홈
 * expect 없이 끝까지 실행되면 "버튼 누름 완료"로 성공 처리.
 */
class MacroAccessibilityService : AccessibilityService() {

    data class Result(val ok: Boolean, val message: String)

    private data class Ev(val time: Long, val pkg: String, val text: String)

    private val events = ArrayDeque<Ev>()
    private val busy = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

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
            events.addLast(Ev(System.currentTimeMillis(), e.packageName?.toString() ?: "", text))
            while (events.size > 60) events.removeFirst()
        }
    }

    // ───────────────────────── 스크립트 실행 ─────────────────────────

    private class Fail(msg: String) : Exception(msg)

    /** 백그라운드 스레드에서 호출할 것 (블로킹) */
    fun runScript(script: String, targetPkg: String): Result {
        if (!busy.compareAndSet(false, true)) return Result(false, "이미 다른 명령을 실행 중입니다")
        val started = System.currentTimeMillis()
        val pm = getSystemService(PowerManager::class.java)
        @Suppress("DEPRECATION")
        val screen = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "carmacro:screen"
        )
        screen.acquire(5 * 60_000L)
        var lastClicked = ""
        try {
            wakeAndUnlock()
            val failWords = mutableListOf<String>()
            var expectDone = false
            for ((lineNo, raw) in script.lines().withIndex()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val op = line.substringBefore(' ').lowercase()
                val arg = line.substringAfter(' ', "").trim()
                log("▶ ${lineNo + 1}: $line")
                when (op) {
                    "restart" -> launch(targetPkg, fresh = true)
                    "launch" -> launch(targetPkg, fresh = false)
                    "wait" -> Thread.sleep(((arg.toDoubleOrNull() ?: 1.0) * 1000).toLong())
                    "click" -> {
                        val (t, words) = timeoutAndWords(arg, 15)
                        val n = waitNode(words, t)
                            ?: throw Fail("'${words.joinToString("/")}' 버튼을 화면에서 찾지 못했습니다 (줄 ${lineNo + 1})")
                        lastClicked = describe(n)
                        click(n)
                        Thread.sleep(700)
                    }
                    "clickif" -> {
                        val (t, words) = timeoutAndWords(arg, 3)
                        waitNode(words, t)?.let { click(it); Thread.sleep(700) }
                    }
                    "waitfor" -> {
                        val (t, words) = timeoutAndWords(arg, 15)
                        waitNode(words, t)
                            ?: throw Fail("'${words.joinToString("/")}' 화면이 나타나지 않았습니다 (줄 ${lineNo + 1})")
                    }
                    "tap" -> {
                        val p = arg.split(Regex("[ ,]+")).mapNotNull { it.toFloatOrNull() }
                        if (p.size < 2) throw Fail("tap 좌표 형식 오류 (줄 ${lineNo + 1})")
                        tap(p[0], p[1]); Thread.sleep(700)
                    }
                    "pin" -> {
                        for (ch in arg.filter { it.isDigit() }) {
                            val n = waitNode(listOf("=$ch"), 5)
                                ?: throw Fail("PIN 키패드 숫자 '$ch' 를 찾지 못했습니다")
                            click(n); Thread.sleep(350)
                        }
                    }
                    "failon" -> failWords += split(arg)
                    "expect", "expectnoti" -> {
                        val (t, words) = timeoutAndWords(arg, 60)
                        val r = expect(words, failWords, t, started, screenToo = op == "expect")
                        if (r != null) return r
                        expectDone = true
                    }
                    "back" -> { performGlobalAction(GLOBAL_ACTION_BACK); Thread.sleep(500) }
                    "home" -> { performGlobalAction(GLOBAL_ACTION_HOME); Thread.sleep(500) }
                    else -> throw Fail("알 수 없는 명령 '$op' (줄 ${lineNo + 1})")
                }
            }
            return if (expectDone) Result(true, "완료")
            else Result(true, "버튼 누름 완료" + if (lastClicked.isNotEmpty()) " ($lastClicked)" else "")
        } catch (e: Fail) {
            log("✖ ${e.message}")
            return Result(false, e.message ?: "실패")
        } catch (e: Exception) {
            log("✖ 오류: $e")
            return Result(false, "오류: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            if (screen.isHeld) screen.release()
            busy.set(false)
        }
    }

    /** null = 성공 확인 후 계속 진행. Result = 실패(또는 즉시 종료) */
    private fun expect(
        ok: List<String>, fail: List<String>, timeoutSec: Int, since: Long, screenToo: Boolean,
    ): Result? {
        val end = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < end) {
            val evTexts = synchronized(events) { events.filter { it.time >= since }.map { it.text } }
            val screenTexts = if (screenToo) allTexts() else emptyList()
            val all = evTexts + screenTexts
            all.firstOrNull { t -> fail.any { matches(t, it) } }?.let {
                log("✖ 실패 문구 감지: $it")
                return Result(false, "기아 앱: ${it.take(80)}")
            }
            all.firstOrNull { t -> ok.any { matches(t, it) } }?.let {
                log("✔ 성공 문구 감지: $it")
                return null
            }
            Thread.sleep(500)
        }
        return Result(false, "${timeoutSec}초 안에 성공 메시지(${ok.joinToString("/")})를 확인하지 못했습니다")
    }

    // ───────────────────────── 동작 도우미 ─────────────────────────

    private fun wakeAndUnlock() {
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) return
        if (km.isDeviceSecure) {
            // 보안 잠금(PIN/패턴)은 자동으로 풀 수 없음
            log("잠금화면에 보안 잠금이 있어 해제할 수 없습니다")
        }
        startActivity(
            Intent(this, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val end = System.currentTimeMillis() + 4000
        while (km.isKeyguardLocked && System.currentTimeMillis() < end) Thread.sleep(200)
        if (km.isKeyguardLocked) throw Fail("수신기 잠금화면을 해제하지 못했습니다. 픽셀1의 화면 잠금을 '없음'으로 설정하세요.")
    }

    private fun launch(pkg: String, fresh: Boolean) {
        if (pkg.isEmpty()) throw Fail("대상 앱(기아 앱)이 설정되지 않았습니다")
        val intent = packageManager.getLaunchIntentForPackage(pkg)
            ?: throw Fail("대상 앱($pkg)을 찾을 수 없습니다")
        if (fresh) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            Thread.sleep(800)
            try {
                getSystemService(ActivityManager::class.java).killBackgroundProcesses(pkg)
            } catch (_: Exception) {
            }
            Thread.sleep(500)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        log("앱 실행: $pkg")
    }

    private fun split(arg: String) = arg.split('|').map { it.trim() }.filter { it.isNotEmpty() }

    private fun timeoutAndWords(arg: String, def: Int): Pair<Int, List<String>> {
        val first = arg.substringBefore(' ')
        val n = first.toIntOrNull()
        return if (n != null && arg.contains(' ')) n to split(arg.substringAfter(' '))
        else def to split(arg)
    }

    private fun norm(s: String) = s.replace(Regex("\\s+"), "").lowercase()

    /** "=x" 는 완전 일치, 그 외는 공백 무시 포함 검사 */
    private fun matches(text: String, word: String): Boolean =
        if (word.startsWith("=")) norm(text) == norm(word.substring(1))
        else norm(text).contains(norm(word))

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

    private fun allTexts(): List<String> = allNodes().mapNotNull { n ->
        listOfNotNull(n.text?.toString(), n.contentDescription?.toString())
            .joinToString(" ").trim().ifEmpty { null }
    }

    private fun nodeMatches(n: AccessibilityNodeInfo, word: String): Boolean {
        if (word.startsWith("id:")) {
            val id = n.viewIdResourceName ?: return false
            return id == word.substring(3) || id.endsWith("/" + word.substring(3))
        }
        val t = n.text?.toString()
        val d = n.contentDescription?.toString()
        return (t != null && matches(t, word)) || (d != null && matches(d, word))
    }

    private fun findNode(words: List<String>): AccessibilityNodeInfo? {
        val nodes = allNodes().filter { it.isVisibleToUser }
        // 완전 일치 우선, 그 다음 부분 일치
        for (w in words) {
            if (!w.startsWith("=") && !w.startsWith("id:")) {
                nodes.firstOrNull { nodeMatches(it, "=$w") }?.let { return it }
            }
        }
        for (w in words) nodes.firstOrNull { nodeMatches(it, w) }?.let { return it }
        return null
    }

    private fun waitNode(words: List<String>, timeoutSec: Int): AccessibilityNodeInfo? {
        val end = System.currentTimeMillis() + timeoutSec * 1000L
        while (true) {
            findNode(words)?.let { return it }
            if (System.currentTimeMillis() >= end) return null
            Thread.sleep(400)
        }
    }

    private fun describe(n: AccessibilityNodeInfo) =
        (n.text ?: n.contentDescription ?: n.viewIdResourceName ?: "").toString()

    private fun click(n: AccessibilityNodeInfo) {
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        log("누름: ${describe(n)}")
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        // 클릭 가능한 노드가 없으면 좌표로 직접 터치
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
        log("터치: (${x.toInt()}, ${y.toInt()})")
    }

    private fun log(s: String) = AppLog.i(this, "[매크로] $s")

    // ───────────────────────── 화면 분석 ─────────────────────────

    /** 현재 화면의 모든 텍스트/버튼과 최근 알림·토스트를 문자열로 정리 */
    fun dumpScreen(): String {
        val sb = StringBuilder()
        val pkg = rootInActiveWindow?.packageName ?: "?"
        sb.append("앱 패키지: $pkg\n")
        sb.append("── 화면 요소 (★=누를 수 있음, [x,y]=중심 좌표) ──\n")
        val r = Rect()
        for (n in allNodes()) {
            if (!n.isVisibleToUser) continue
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            val id = n.viewIdResourceName?.substringAfter(":id/").orEmpty()
            if (t.isEmpty() && d.isEmpty() && id.isEmpty()) continue
            n.getBoundsInScreen(r)
            var clickable = n.isClickable
            var p = n.parent
            while (!clickable && p != null) { clickable = p.isClickable; p = p.parent }
            sb.append(if (clickable) "★ " else "  ")
            if (t.isNotEmpty()) sb.append("\"$t\" ")
            if (d.isNotEmpty() && d != t) sb.append("(설명: $d) ")
            if (id.isNotEmpty()) sb.append("id:$id ")
            sb.append("[${r.centerX()},${r.centerY()}]\n")
        }
        sb.append("── 최근 알림/토스트/창 제목 ──\n")
        synchronized(events) {
            events.toList().takeLast(25).forEach { e ->
                sb.append("${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(e.time))} ${e.pkg}: ${e.text}\n")
            }
        }
        return sb.toString()
    }

    companion object {
        @Volatile var instance: MacroAccessibilityService? = null
    }
}
