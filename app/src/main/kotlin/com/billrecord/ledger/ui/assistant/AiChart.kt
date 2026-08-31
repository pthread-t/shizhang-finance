package com.billrecord.ledger.ui.assistant

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.billrecord.ledger.ai.AiChartKind
import com.billrecord.ledger.ai.ChartDescriptor
import com.billrecord.ledger.ui.components.formatMoney
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.ByteArrayInputStream

@Composable
fun AiChart(descriptor: ChartDescriptor, modifier: Modifier = Modifier, onPoint: (String) -> Unit) {
    Column(modifier.semantics { contentDescription = descriptor.accessibilitySummary }) {
        Text(descriptor.title, style = MaterialTheme.typography.titleMedium)
        if (descriptor.kind != AiChartKind.KPI && descriptor.labels.isNotEmpty()) {
            val chartHeight = when (descriptor.kind) {
                AiChartKind.BAR -> (220 + descriptor.labels.size.coerceAtMost(10) * 12).dp
                AiChartKind.HEATMAP -> (260 + descriptor.series.size.coerceAtMost(8) * 10).dp
                else -> 260.dp
            }
            EChartWebView(descriptor, Modifier.fillMaxWidth().height(chartHeight), onPoint)
        }
        if (descriptor.kind == AiChartKind.KPI && descriptor.series.size > 1) {
            descriptor.series.forEach { series ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(series.name, Modifier.weight(1f))
                    Text(formatChartValue(descriptor.copy(series = listOf(series)), series.values.firstOrNull() ?: 0L))
                }
            }
        } else if (descriptor.kind == AiChartKind.HEATMAP) {
            descriptor.heatmapCells.forEachIndexed { index, cell ->
                val point = descriptor.points.firstOrNull { it.pointId == cell.pointId }
                Row(
                    Modifier.fillMaxWidth().then(if (point == null) Modifier else Modifier.clickable { onPoint(point.pointId) }).padding(vertical = 8.dp),
                ) {
                    Text("${descriptor.labels.getOrNull(cell.xIndex).orEmpty()} · ${descriptor.series.getOrNull(cell.yIndex)?.name.orEmpty()}", Modifier.weight(1f), maxLines = 2)
                    Text(formatChartValue(descriptor, cell.value), style = MaterialTheme.typography.bodyMedium)
                }
                if (index < descriptor.heatmapCells.lastIndex) HorizontalDivider()
            }
        } else descriptor.labels.forEachIndexed { index, label ->
            val point = descriptor.points.getOrNull(index)
            Row(
                Modifier.fillMaxWidth().then(if (point == null) Modifier else Modifier.clickable { onPoint(point.pointId) }).padding(vertical = 8.dp),
            ) {
                Text(label, Modifier.weight(1f), maxLines = 2)
                Column {
                    descriptor.series.forEach { series ->
                        Text("${series.name} ${formatChartValue(descriptor.copy(series = listOf(series)), series.values.getOrElse(index) { 0L })}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (index < descriptor.labels.lastIndex) HorizontalDivider()
        }
        if (descriptor.labels.isEmpty()) Text("当前条件没有可展示的数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EChartWebView(descriptor: ChartDescriptor, modifier: Modifier, onPoint: (String) -> Unit) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val background = MaterialTheme.colorScheme.surfaceVariant.toArgb()
    val payload = remember(descriptor) { Json.encodeToString(descriptor) }
    val allowedPoints = remember(descriptor) { descriptor.points.map { it.pointId }.toSet() }
    var snapshot by remember(descriptor) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    Box(modifier) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
            WebView(context).apply {
                setBackgroundColor(background)
                setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.setSupportMultipleWindows(false)
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                webViewClient = object : WebViewClientCompat() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        return if (request.url.host == WebViewAssetLoader.DEFAULT_DOMAIN) loader.shouldInterceptRequest(request.url)
                        else WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.host != WebViewAssetLoader.DEFAULT_DOMAIN

                    override fun onPageFinished(view: WebView, url: String) {
                        view.post { renderChart(view, payload, dark) }
                    }
                }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    WebViewCompat.addWebMessageListener(this, "BillChart", setOf("https://${WebViewAssetLoader.DEFAULT_DOMAIN}")) { _, message, sourceOrigin, isMainFrame, _ ->
                        if (isMainFrame && sourceOrigin.host == WebViewAssetLoader.DEFAULT_DOMAIN) {
                            message.data?.let { data ->
                                runCatching { JSONObject(data) }.getOrNull()?.let { value ->
                                    value.optString("pointId").takeIf(allowedPoints::contains)?.let(onPoint)
                                    value.optString("snapshot").takeIf { it.startsWith("data:image/png;base64,") && it.length <= 2_000_000 }?.let { image ->
                                        runCatching {
                                            val bytes = Base64.decode(image.substringAfter(','), Base64.DEFAULT)
                                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                        }.getOrNull()?.let { decoded -> snapshot = decoded }
                                    }
                                }
                            }
                        }
                    }
                }
                loadUrl("https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/ai_chart.html")
            }
        },
        update = { webView -> webView.post { renderChart(webView, payload, dark) } },
        onRelease = { webView -> webView.stopLoading(); webView.destroy() },
    )
    snapshot?.let { image ->
        Image(
            image,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().pointerInput(descriptor) {
                detectTapGestures { position ->
                    descriptor.pointAt(position.x, position.y, size.width.toFloat(), size.height.toFloat())?.let(onPoint)
                }
            },
            contentScale = ContentScale.FillBounds,
        )
    }
    }
}

private fun ChartDescriptor.pointAt(x: Float, y: Float, width: Float, height: Float): String? {
    if (points.isEmpty() || width <= 0f || height <= 0f) return null
    val index = when (kind) {
        AiChartKind.LINE -> {
            val start = width * .16f
            val end = width * .94f
            (((x - start) / (end - start)).coerceIn(0f, 1f) * (points.size - 1)).toInt()
        }
        AiChartKind.BAR -> {
            val start = height * .12f
            val end = height * .92f
            (((y - start) / (end - start)).coerceIn(0f, .999f) * points.size).toInt()
        }
        AiChartKind.DONUT -> {
            val centerX = width * .5f
            val centerY = height * .48f
            val distance = kotlin.math.hypot(x - centerX, y - centerY)
            if (distance < minOf(width, height) * .22f || distance > minOf(width, height) * .48f) return null
            val values = series.firstOrNull()?.values?.map { it.coerceAtLeast(0L) }.orEmpty()
            val total = values.sum().takeIf { it > 0 } ?: return null
            val angle = Math.toDegrees(kotlin.math.atan2((x - centerX).toDouble(), (centerY - y).toDouble())).let { if (it < 0) it + 360 else it }
            var boundary = 0.0
            values.indexOfFirst { value ->
                boundary += value.toDouble() / total * 360.0
                angle <= boundary
            }.takeIf { it >= 0 } ?: values.lastIndex
        }
        AiChartKind.HEATMAP -> {
            val xIndex = ((x / width).coerceIn(0f, .999f) * labels.size).toInt()
            val yIndex = ((y / height).coerceIn(0f, .999f) * series.size).toInt()
            return heatmapCells.firstOrNull { it.xIndex == xIndex && it.yIndex == yIndex }?.pointId
        }
        AiChartKind.KPI -> return null
    }
    return points.getOrNull(index)?.pointId
}

private fun renderChart(webView: WebView, payload: String, dark: Boolean) {
    val density = webView.resources.displayMetrics.density.coerceAtLeast(1f)
    val cssHeight = (webView.height / density).toInt().coerceAtLeast(180)
    webView.evaluateJavascript(
        "if(typeof window.renderChart==='function'){window.renderChart(${JSONObject.quote(payload)},$dark,$cssHeight)}",
        null,
    )
}

private fun formatChartValue(descriptor: ChartDescriptor, value: Long): String =
    when {
        descriptor.valueIsCount -> "$value 笔"
        descriptor.currency == "CNY" -> formatMoney(value)
        else -> "${descriptor.currency} ${java.math.BigDecimal.valueOf(value, 2).toPlainString()}"
    }
