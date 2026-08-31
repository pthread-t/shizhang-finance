package com.billrecord.ledger.automation

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class LocalOcrService @Inject constructor(@ApplicationContext private val context: Context) {
    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

    suspend fun recognize(uri: Uri): String = suspendCancellableCoroutine { continuation ->
        val image = runCatching { InputImage.fromFilePath(context, uri) }
            .getOrElse { continuation.resumeWithException(it); return@suspendCancellableCoroutine }
        recognizer.process(image)
            .addOnSuccessListener { if (continuation.isActive) continuation.resume(it.text) }
            .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    }
}

