package com.billrecord.ledger.ui.add

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.speech.SpeechRecognizer
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.core.content.ContextCompat

enum class SpeechRecognitionMode {
    ON_DEVICE,
    SYSTEM_WITH_CONSENT,
    UNAVAILABLE,
}

fun speechIntentAvailable(context: Context): Boolean =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(context.packageManager) != null

fun chooseSpeechRecognitionMode(
    sdkInt: Int,
    onDeviceAvailable: Boolean,
    systemRecognizerAvailable: Boolean,
): SpeechRecognitionMode = when {
    sdkInt >= 31 && onDeviceAvailable -> SpeechRecognitionMode.ON_DEVICE
    systemRecognizerAvailable -> SpeechRecognitionMode.SYSTEM_WITH_CONSENT
    else -> SpeechRecognitionMode.UNAVAILABLE
}

fun speechPermissionIssue(context: Context): String? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        return "未获得麦克风权限，请在系统设置中允许后重试"
    }
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    if (audioManager.isMicrophoneMute) {
        return "系统的麦克风隐私开关已关闭，请开启后重试"
    }
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= 29) {
        appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, android.os.Process.myUid(), context.packageName)
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, android.os.Process.myUid(), context.packageName)
    }
    val allowedModes = if (Build.VERSION.SDK_INT >= 29) {
        setOf(AppOpsManager.MODE_ALLOWED, AppOpsManager.MODE_DEFAULT, AppOpsManager.MODE_FOREGROUND)
    } else {
        setOf(AppOpsManager.MODE_ALLOWED, AppOpsManager.MODE_DEFAULT)
    }
    return if (mode !in allowedModes) {
        "系统或厂商权限管理阻止了麦克风访问，请在应用权限中允许录音"
    } else null
}

fun speechRecognitionErrorMessage(error: Int, permissionIssue: String? = null): String = when (error) {
    SpeechRecognizer.ERROR_AUDIO -> "麦克风录音失败，请关闭占用麦克风的应用后重试"
    SpeechRecognizer.ERROR_CLIENT -> "语音服务已中断，请重新点击麦克风"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> permissionIssue
        ?: "系统语音服务无法访问麦克风，请检查系统语音服务及厂商权限设置"
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "系统语音服务网络不可用；可下载中文离线语音包后重试"
    SpeechRecognizer.ERROR_NO_MATCH -> "没有听清，请靠近麦克风后重试"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "系统语音服务正忙，请稍后重试"
    SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "系统语音服务暂时不可用，请稍后重试"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有检测到语音，请重新点击后开始说话"
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "当前语音服务不支持中文（普通话）"
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "缺少中文语音模型，请下载后重试"
    else -> "语音识别失败（错误码 $error），请重试或改用文字输入"
}
