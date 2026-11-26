package com.hee.carmacro

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast

/**
 * 앱 동작(차 문 열기, 차 시동 · 난방 …)이 여는 투명 화면.
 * 갤럭시 루틴 '앱 동작 실행', 빅스비, 앱 아이콘 길게 누르기 메뉴에서 쓴다.
 * 화면 없이 신호만 보내고 바로 닫힌다.
 *  - 문 열기: 폰 잠금이 풀려 있어야 함 (잠겨 있으면 잠금 해제 요청)
 *  - 나머지: 잠금 상태에서도 실행
 *  - 퇴근 신호: 진동만 (루틴 조건 '앱이 열렸을 때'용)
 */
class TriggerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        val cmd = intent?.getStringExtra("cmd")
        if (cmd == null || (cmd != Prefs.CMD_SIGNAL && cmd !in Prefs.CAR_CMDS)) { finishQuiet(); return }
        if (Prefs(this).mode != Prefs.MODE_SENDER) {
            toast("수신기(픽셀1)에서는 앱 동작을 쓸 수 없어요"); finishQuiet(); return
        }

        if (cmd == Prefs.CMD_SIGNAL) {
            vibrate(longArrayOf(0, 60, 80, 60))
            AppLog.i(this, "[리모컨] 퇴근 신호")
            finishQuiet(); return
        }
        val km = getSystemService(KeyguardManager::class.java)
        if (cmd == Prefs.CMD_UNLOCK && km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { go(cmd) }
                override fun onDismissCancelled() { toast("문 열기는 폰 잠금을 풀어야 실행돼요"); finishQuiet() }
                override fun onDismissError() { toast("문 열기는 폰 잠금을 풀어야 실행돼요"); finishQuiet() }
            })
            return
        }
        go(cmd)
    }

    private fun go(cmd: String) {
        try {
            SenderService.send(this, cmd, "앱 동작")
            vibrate(longArrayOf(0, 120))
        } catch (e: Exception) {
            toast("전송 실패: ${e.message}")
        }
        finishQuiet()
    }

    private fun finishQuiet() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun vibrate(pattern: LongArray) {
        try {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {
        }
    }

    private fun toast(s: String) = Toast.makeText(applicationContext, s, Toast.LENGTH_SHORT).show()
}
