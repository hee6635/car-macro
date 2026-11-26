package com.hee.carmacro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 재부팅/앱 업데이트 후: 수신기는 수신 서비스 재시작, 리모컨은 상태 확인 예약 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val p = Prefs(ctx)
        try {
            if (p.mode == Prefs.MODE_RECEIVER && p.receiverEnabled) ReceiverService.start(ctx)
            if (p.mode == Prefs.MODE_SENDER) StatusWorker.schedule(ctx)
        } catch (e: Exception) {
            AppLog.i(ctx, "부팅 후 시작 실패: ${e.message}")
        }
    }
}
