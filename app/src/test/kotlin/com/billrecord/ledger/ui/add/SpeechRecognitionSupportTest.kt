package com.billrecord.ledger.ui.add

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechRecognitionSupportTest {
    @Test
    fun `uses verified on-device recognizer when available`() {
        assertEquals(
            SpeechRecognitionMode.ON_DEVICE,
            chooseSpeechRecognitionMode(sdkInt = 36, onDeviceAvailable = true, systemRecognizerAvailable = true),
        )
    }

    @Test
    fun `falls back to system recognizer with explicit consent when on-device is unavailable`() {
        assertEquals(
            SpeechRecognitionMode.SYSTEM_WITH_CONSENT,
            chooseSpeechRecognitionMode(sdkInt = 30, onDeviceAvailable = false, systemRecognizerAvailable = true),
        )
    }

    @Test
    fun `reports unavailable only when no recognizer exists`() {
        assertEquals(
            SpeechRecognitionMode.UNAVAILABLE,
            chooseSpeechRecognitionMode(sdkInt = 36, onDeviceAvailable = false, systemRecognizerAvailable = false),
        )
    }

    @Test
    fun `turns recognizer error codes into actionable Chinese messages`() {
        assertEquals("没有听清，请靠近麦克风后重试", speechRecognitionErrorMessage(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals("缺少中文语音模型，请下载后重试", speechRecognitionErrorMessage(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
    }

    @Test
    fun `does not misreport recognizer permission error as missing app permission`() {
        assertEquals(
            "系统语音服务无法访问麦克风，请检查系统语音服务及厂商权限设置",
            speechRecognitionErrorMessage(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS),
        )
        assertEquals(
            "系统的麦克风隐私开关已关闭，请开启后重试",
            speechRecognitionErrorMessage(
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                "系统的麦克风隐私开关已关闭，请开启后重试",
            ),
        )
    }
}
