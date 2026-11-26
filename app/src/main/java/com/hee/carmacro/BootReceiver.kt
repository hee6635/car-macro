package com.hee.carmacro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 픽셀1 재부팅/앱 업데이트 후 수신 서비스 자동 재시작 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val p = Prefs(ctx)
        if (p.mode == Prefs.MODE_RECEIVER && p.receiverEnabled) {
            try {
                ReceiverService.start(ctx)
            } catch (e: Exception) {
                AppLog.i(ctx, "부팅 후 서비스 시작 실패: ${e.message}")
            }
        }
    }
}
