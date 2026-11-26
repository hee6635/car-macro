package com.hee.carmacro

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 앱 화면에서 볼 수 있는 간단한 파일 로그 (최근 300줄) */
object AppLog {
    private const val MAX = 300
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA)
    private var file: File? = null

    fun init(ctx: Context) {
        if (file == null) file = File(ctx.applicationContext.filesDir, "log.txt")
    }

    @Synchronized
    fun i(ctx: Context, msg: String) {
        init(ctx)
        Log.i("CarMacro", msg)
        val f = file ?: return
        val lines = (if (f.exists()) f.readLines() else emptyList()) + "${fmt.format(Date())}  $msg"
        f.writeText(lines.takeLast(MAX).joinToString("\n"))
    }

    @Synchronized
    fun read(ctx: Context): String {
        init(ctx)
        val f = file ?: return ""
        return if (f.exists()) f.readLines().reversed().joinToString("\n") else ""
    }

    @Synchronized
    fun clear(ctx: Context) {
        init(ctx)
        file?.delete()
    }
}
