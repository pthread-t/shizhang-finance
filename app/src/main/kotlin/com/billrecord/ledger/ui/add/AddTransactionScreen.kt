package com.billrecord.ledger.ui.add

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.billrecord.ledger.data.local.AccountEntity
import com.billrecord.ledger.data.local.CategoryEntity
import com.billrecord.ledger.ui.components.categoryVisualColor
import com.billrecord.shared.TransactionType
import kotlinx.coroutines.delay

private enum class DimensionPicker { TAGS, MERCHANTS, PROJECTS }

private data class DimensionOption(
    val id: String,
    val name: String,
    val usageCount: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionScreen(
    sharedIntent: Intent?,
    onClose: () -> Unit,
    onManageTags: () -> Unit,
    viewModel: AddTransactionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val recentExpenses by viewModel.recentExpenses.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val merchants by viewModel.merchants.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val speechConsentAccepted by viewModel.speechConsentAccepted.collectAsStateWithLifecycle()
    var createDimension by remember { mutableStateOf<String?>(null) }
    var dimensionPicker by remember { mutableStateOf<DimensionPicker?>(null) }
    var dimensionSearch by remember { mutableStateOf("") }
    var showAllCategories by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    val tagOptions = tags.map { DimensionOption(it.id, it.name, it.usageCount) }
    val merchantOptions = merchants.map { DimensionOption(it.id, it.name, it.usageCount) }
    val projectOptions = projects.map { DimensionOption(it.id, it.name, it.usageCount) }
    fun openDimensionPicker(value: DimensionPicker) {
        dimensionSearch = ""
        dimensionPicker = value
    }
    val context = LocalContext.current
    val speechMode = remember(context) {
        chooseSpeechRecognitionMode(
            sdkInt = Build.VERSION.SDK_INT,
            onDeviceAvailable = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context),
            systemRecognizerAvailable = SpeechRecognizer.isRecognitionAvailable(context) ||
                speechIntentAvailable(context),
        )
    }
    val speechRecognizer = remember(context, speechMode) {
        runCatching {
            when (speechMode) {
                SpeechRecognitionMode.ON_DEVICE -> if (Build.VERSION.SDK_INT >= 31) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    null
                }
                SpeechRecognitionMode.SYSTEM_WITH_CONSENT, SpeechRecognitionMode.UNAVAILABLE -> null
            }
        }.getOrNull()
    }
    var showSystemSpeechConsent by remember { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }
    fun speechIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
    val systemSpeechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        isListening = false
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let(viewModel::parseText)
                ?: viewModel.reportError("系统语音服务没有返回文字，请重试")
        } else if (result.resultCode != Activity.RESULT_CANCELED) {
            viewModel.reportError("系统语音识别未完成，请重试或改用文字输入")
        }
    }
    fun beginSpeech() {
        speechPermissionIssue(context)?.let { issue -> viewModel.reportError(issue); return }
        when (speechMode) {
            SpeechRecognitionMode.ON_DEVICE -> {
                val recognizer = speechRecognizer ?: run {
                    viewModel.reportError("端侧语音识别器不可用，请启用系统语音服务")
                    return
                }
                isListening = true
                runCatching { recognizer.startListening(speechIntent()) }
                    .onFailure {
                        isListening = false
                        viewModel.reportError("无法启动端侧语音服务：${it.message ?: "请稍后重试"}")
                    }
            }
            SpeechRecognitionMode.SYSTEM_WITH_CONSENT -> {
                isListening = true
                runCatching { systemSpeechLauncher.launch(speechIntent()) }
                    .onFailure {
                        isListening = false
                        viewModel.reportError("无法打开系统语音识别界面，请安装或启用系统语音服务")
                    }
            }
            SpeechRecognitionMode.UNAVAILABLE -> viewModel.reportError("系统没有可用的语音识别服务，请先安装或启用系统语音服务")
        }
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) viewModel.reportError("未获得麦克风权限，语音内容未被采集")
        else if (speechMode == SpeechRecognitionMode.SYSTEM_WITH_CONSENT && !speechConsentAccepted) showSystemSpeechConsent = true
        else beginSpeech()
    }
    DisposableEffect(speechRecognizer) {
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                isListening = false
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(viewModel::parseText)
                    ?: viewModel.reportError("语音服务没有返回文字，请重试")
            }
            override fun onError(error: Int) {
                isListening = false
                if (Build.VERSION.SDK_INT >= 33 && error in setOf(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
                    runCatching { speechRecognizer.triggerModelDownload(speechIntent()) }
                }
                viewModel.reportError(speechRecognitionErrorMessage(error, speechPermissionIssue(context)))
            }
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        onDispose { speechRecognizer?.cancel(); speechRecognizer?.destroy() }
    }
    LaunchedEffect(isListening, speechMode) {
        if (isListening && speechMode == SpeechRecognitionMode.ON_DEVICE) {
            delay(15_000)
            if (isListening) {
                speechRecognizer?.cancel()
                isListening = false
                if (Build.VERSION.SDK_INT >= 33) runCatching { speechRecognizer?.triggerModelDownload(speechIntent()) }
                viewModel.reportError("系统语音服务未返回结果；已请求下载中文语音模型，请稍后重试")
            }
        }
    }
    fun requestSpeech() {
        if (speechMode == SpeechRecognitionMode.UNAVAILABLE) {
            viewModel.reportError("系统没有可用的语音识别服务，请先安装或启用系统语音服务")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else if (speechMode == SpeechRecognitionMode.SYSTEM_WITH_CONSENT && !speechConsentAccepted) {
            showSystemSpeechConsent = true
        }
        else beginSpeech()
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::parseImage) }
    val attachmentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { values -> viewModel.addAttachments(values) }
    LaunchedEffect(sharedIntent) {
        if (sharedIntent?.action == Intent.ACTION_SEND) {
            sharedIntent.getStringExtra(Intent.EXTRA_TEXT)?.let(viewModel::parseText)
            @Suppress("DEPRECATION")
            (sharedIntent.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let(viewModel::parseImage)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记一笔") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Outlined.ArrowBack, contentDescription = "返回") } },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                Button(
                    onClick = { viewModel.save(onClose) },
                    enabled = !state.saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .height(52.dp),
                ) {
                    Text(
                        when {
                            state.saving -> "正在保存"
                            state.savedTransactionId != null -> "重试剩余附件"
                            else -> "保存账单"
                        },
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item { TypeSelector(state.type, viewModel::setType) }
            item {
                OutlinedTextField(
                    value = state.amount,
                    onValueChange = viewModel::setAmount,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("金额") },
                    prefix = { Text("${state.currency.ifBlank { "---" }} ") },
                    textStyle = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.SansSerif, fontFeatureSettings = "tnum"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
            }
            if (showMore) item {
                androidx.compose.material3.OutlinedButton(
                    onClick = viewModel::toggleCurrencyExpanded,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.currency == state.baseCurrency) "币种 ${state.currency} · 基础币种"
                        else "币种 ${state.currency} · 1 ${state.currency} = ${state.exchangeRate.ifBlank { "待填写" }} ${state.baseCurrency}",
                        modifier = Modifier.weight(1f),
                    )
                    Icon(if (state.currencyExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = if (state.currencyExpanded) "折叠币种和汇率" else "展开币种和汇率")
                }
                if (state.currencyExpanded) {
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = state.currency,
                            onValueChange = viewModel::setCurrency,
                            modifier = Modifier.weight(0.7f),
                            label = { Text("原币币种") },
                            supportingText = { Text("跟随所选账户") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = state.exchangeRate,
                            onValueChange = viewModel::setExchangeRate,
                            modifier = Modifier.weight(1.3f),
                            label = { Text("兑 ${state.baseCurrency} 汇率") },
                            supportingText = { Text("1 原币折合 ${state.baseCurrency}") },
                            enabled = state.currency != state.baseCurrency,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = { imageLauncher.launch(arrayOf("image/*")) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(Icons.Outlined.ImageSearch, contentDescription = null)
                        Text("选择截图识别", Modifier.padding(start = 6.dp))
                    }
                    FilledTonalButton(
                        onClick = ::requestSpeech,
                        enabled = !isListening,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(Icons.Outlined.Mic, contentDescription = null)
                        Text(if (isListening) "正在听…" else "语音记账", Modifier.padding(start = 6.dp))
                    }
                }
                state.error?.let { message ->
                    Text(message, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
                }
            }
            if (showMore) item {
                androidx.compose.material3.OutlinedButton(onClick = { attachmentLauncher.launch(arrayOf("image/*", "application/pdf")) }) {
                    Icon(Icons.Outlined.AttachFile, contentDescription = null)
                    Text("票据 / PDF 附件", Modifier.padding(start = 6.dp))
                }
                if (state.attachmentUris.isNotEmpty()) Text("已选择 ${state.attachmentUris.size} 个附件；保存后先留在本机，联网时断点同步。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.type != TransactionType.TRANSFER && state.type != TransactionType.ADJUSTMENT) {
                item {
                    Text("分类", style = MaterialTheme.typography.titleMedium)
                    CategoryGrid(
                        categories = categories,
                        selectedId = state.selectedCategoryId,
                        expanded = showAllCategories,
                        onSelect = viewModel::setCategory,
                        onToggleExpanded = { showAllCategories = !showAllCategories },
                        onCreate = { createDimension = "CATEGORY" },
                    )
                }
                if (showMore) item {
                    if (state.splits.isEmpty()) {
                        androidx.compose.material3.OutlinedButton(onClick = viewModel::beginSplit) {
                            Icon(Icons.Outlined.AddCircleOutline, contentDescription = null)
                            Text("拆分到多个分类", Modifier.padding(start = 6.dp))
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("拆分明细", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                androidx.compose.material3.TextButton(onClick = viewModel::addSplitLine) { Text("添加一项") }
                                androidx.compose.material3.TextButton(onClick = viewModel::stopSplit) { Text("取消拆分") }
                            }
                            state.splits.forEachIndexed { index, line ->
                                SplitLineEditor(
                                    index = index,
                                    line = line,
                                    categories = categories,
                                    onCategory = { viewModel.setSplitCategory(line.id, it) },
                                    onAmount = { viewModel.setSplitAmount(line.id, it) },
                                    onRemove = { viewModel.removeSplitLine(line.id) },
                                )
                            }
                            val splitTotal = state.splits.mapNotNull { it.amount.toBigDecimalOrNull() }.fold(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
                            Text("拆分合计 ¥${splitTotal.stripTrailingZeros().toPlainString()} / 账单 ¥${state.amount.ifBlank { "0" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (state.type == TransactionType.REFUND) {
                item {
                    Text("关联原支出", style = MaterialTheme.typography.titleMedium)
                    Text("关联后报表会从原分类冲减；也可以保留为未关联退款。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyRow(contentPadding = PaddingValues(top = 8.dp)) {
                        item { AssistChip(onClick = { viewModel.setRefundOf(null) }, label = { Text(if (state.refundOfTransactionId == null) "✓ 不关联" else "不关联") }, modifier = Modifier.padding(end = 8.dp)) }
                        items(recentExpenses, key = { "refund-${it.id}" }) { expense ->
                            val label = "${expense.note.ifBlank { "支出" }.take(10)} ¥${java.math.BigDecimal(expense.amountMinor).movePointLeft(2).stripTrailingZeros().toPlainString()}"
                            AssistChip(onClick = { viewModel.setRefundOf(expense.id) }, label = { Text(if (state.refundOfTransactionId == expense.id) "✓ $label" else label) }, modifier = Modifier.padding(end = 8.dp))
                        }
                    }
                }
            }
            item { AccountSelector("账户", accounts, state.selectedAccountId, viewModel::setAccount) }
            if (state.type == TransactionType.TRANSFER) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AccountSelector("转入账户", accounts.filterNot { it.id == state.selectedAccountId }, state.selectedDestinationAccountId, viewModel::setDestinationAccount)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = state.destinationAmount,
                                onValueChange = viewModel::setDestinationAmount,
                                modifier = Modifier.weight(1.3f),
                                label = { Text("转入金额（留空则同额）") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = state.destinationCurrency,
                                onValueChange = viewModel::setDestinationCurrency,
                                modifier = Modifier.weight(0.7f),
                                label = { Text("转入币种") },
                                singleLine = true,
                            )
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = state.note,
                    onValueChange = viewModel::setNote,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("备注") },
                    placeholder = { Text("例如：午饭、房租、差旅打车") },
                    minLines = 2,
                    trailingIcon = {
                        IconButton(onClick = {
                            requestSpeech()
                        }, enabled = !isListening) { Icon(Icons.Outlined.Mic, contentDescription = "语音记账") }
                    },
                )
            }
            item {
                AdvancedInformationToggle(showMore) { showMore = !showMore }
            }
            if (showMore) item {
                Text("标签", style = MaterialTheme.typography.titleMedium)
                LazyRow(contentPadding = PaddingValues(top = 8.dp)) {
                    items(compactDimensionOptions(tagOptions, state.selectedTagIds), key = { "tag-${it.id}" }) { tag ->
                        AssistChip(onClick = { viewModel.toggleTag(tag.id) }, label = { Text(if (tag.id in state.selectedTagIds) "✓ ${tag.name}" else tag.name) }, modifier = Modifier.padding(end = 8.dp))
                    }
                    item {
                        AssistChip(
                            onClick = { openDimensionPicker(DimensionPicker.TAGS) },
                            label = { Text("全部标签 (${tags.size})") },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }
            if (showMore) item {
                Text("商家 / 对象", style = MaterialTheme.typography.titleMedium)
                LazyRow(contentPadding = PaddingValues(top = 8.dp)) {
                    item { AssistChip(onClick = { viewModel.setMerchant(null) }, label = { Text(if (state.selectedMerchantId == null) "✓ 未指定" else "未指定") }, modifier = Modifier.padding(end = 8.dp)) }
                    items(compactDimensionOptions(merchantOptions, setOfNotNull(state.selectedMerchantId)), key = { "merchant-${it.id}" }) { merchant ->
                        AssistChip(onClick = { viewModel.setMerchant(merchant.id) }, label = { Text(if (state.selectedMerchantId == merchant.id) "✓ ${merchant.name}" else merchant.name) }, modifier = Modifier.padding(end = 8.dp))
                    }
                    item { AssistChip(onClick = { openDimensionPicker(DimensionPicker.MERCHANTS) }, label = { Text("全部商家 / 对象 (${merchants.size})") }) }
                }
            }
            if (showMore) item {
                Text("项目", style = MaterialTheme.typography.titleMedium)
                LazyRow(contentPadding = PaddingValues(top = 8.dp)) {
                    item { AssistChip(onClick = { viewModel.setProject(null) }, label = { Text(if (state.selectedProjectId == null) "✓ 未指定" else "未指定") }, modifier = Modifier.padding(end = 8.dp)) }
                    items(compactDimensionOptions(projectOptions, setOfNotNull(state.selectedProjectId)), key = { "project-${it.id}" }) { project ->
                        AssistChip(onClick = { viewModel.setProject(project.id) }, label = { Text(if (state.selectedProjectId == project.id) "✓ ${project.name}" else project.name) }, modifier = Modifier.padding(end = 8.dp))
                    }
                    item { AssistChip(onClick = { openDimensionPicker(DimensionPicker.PROJECTS) }, label = { Text("全部项目 (${projects.size})") }) }
                }
            }
            if (showMore && state.type == TransactionType.EXPENSE) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("待报销", style = MaterialTheme.typography.titleMedium)
                            Text("报销后可关联收入流水", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = state.reimbursable, onCheckedChange = viewModel::setReimbursable)
                    }
                }
            }
            if (showMore) state.recognizedText?.let { text ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ReceiptLong, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("已在本机识别：${text.take(80)}", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    when (createDimension) {
        "CATEGORY" -> CreateCategoryDialog(categories, onDismiss = { createDimension = null }) { name, parentId -> viewModel.createCategory(name, parentId); createDimension = null }
        "TAG" -> CreateNameDialog("新建标签", onDismiss = { createDimension = null }) { viewModel.createTag(it); createDimension = null }
        "MERCHANT" -> CreateNameDialog("新建商家 / 对象", onDismiss = { createDimension = null }) { viewModel.createMerchant(it); createDimension = null }
        "PROJECT" -> CreateNameDialog("新建项目", onDismiss = { createDimension = null }) { viewModel.createProject(it); createDimension = null }
        else -> Unit
    }
    if (showSystemSpeechConsent) {
        AlertDialog(
            onDismissRequest = { showSystemSpeechConsent = false },
            title = { Text("使用系统语音服务？") },
            text = { Text("此手机未提供可验证的端侧识别器。拾账会请求离线识别，但系统语音服务仍可能按其自身设置联网处理音频。") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showSystemSpeechConsent = false
                    viewModel.acceptSpeechConsent()
                    beginSpeech()
                }) { Text("继续") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { showSystemSpeechConsent = false }) { Text("取消") } },
        )
    }
    dimensionPicker?.let { picker ->
        val options = when (picker) {
            DimensionPicker.TAGS -> tagOptions
            DimensionPicker.MERCHANTS -> merchantOptions
            DimensionPicker.PROJECTS -> projectOptions
        }
        val selectedIds = when (picker) {
            DimensionPicker.TAGS -> state.selectedTagIds
            DimensionPicker.MERCHANTS -> setOfNotNull(state.selectedMerchantId)
            DimensionPicker.PROJECTS -> setOfNotNull(state.selectedProjectId)
        }
        DimensionPickerSheet(
            title = when (picker) {
                DimensionPicker.TAGS -> "全部标签"
                DimensionPicker.MERCHANTS -> "全部商家 / 对象"
                DimensionPicker.PROJECTS -> "全部项目"
            },
            searchLabel = when (picker) {
                DimensionPicker.TAGS -> "搜索标签"
                DimensionPicker.MERCHANTS -> "搜索商家 / 对象"
                DimensionPicker.PROJECTS -> "搜索项目"
            },
            options = options,
            selectedIds = selectedIds,
            search = dimensionSearch,
            onSearch = { dimensionSearch = it.take(40) },
            noneLabel = if (picker == DimensionPicker.TAGS) null else "未指定",
            onClear = when (picker) {
                DimensionPicker.TAGS -> null
                DimensionPicker.MERCHANTS -> { { viewModel.setMerchant(null); dimensionPicker = null } }
                DimensionPicker.PROJECTS -> { { viewModel.setProject(null); dimensionPicker = null } }
            },
            onSelect = { id ->
                when (picker) {
                    DimensionPicker.TAGS -> viewModel.toggleTag(id)
                    DimensionPicker.MERCHANTS -> { viewModel.setMerchant(id); dimensionPicker = null }
                    DimensionPicker.PROJECTS -> { viewModel.setProject(id); dimensionPicker = null }
                }
            },
            onCreate = {
                dimensionPicker = null
                createDimension = when (picker) {
                    DimensionPicker.TAGS -> "TAG"
                    DimensionPicker.MERCHANTS -> "MERCHANT"
                    DimensionPicker.PROJECTS -> "PROJECT"
                }
            },
            onManage = if (picker == DimensionPicker.TAGS) { { dimensionPicker = null; onManageTags() } } else null,
            onDismiss = { dimensionPicker = null },
        )
    }
}

internal fun compactDimensionIds(
    orderedIds: List<String>,
    selectedIds: Set<String>,
    limit: Int = 8,
): List<String> {
    require(limit > 0)
    val knownSelected = orderedIds.filter { it in selectedIds }
    val remainingSlots = (limit - knownSelected.size).coerceAtLeast(0)
    return knownSelected + orderedIds.asSequence().filterNot { it in selectedIds }.take(remainingSlots).toList()
}

private fun compactDimensionOptions(
    options: List<DimensionOption>,
    selectedIds: Set<String>,
): List<DimensionOption> {
    val byId = options.associateBy(DimensionOption::id)
    return compactDimensionIds(options.map(DimensionOption::id), selectedIds).mapNotNull(byId::get)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DimensionPickerSheet(
    title: String,
    searchLabel: String,
    options: List<DimensionOption>,
    selectedIds: Set<String>,
    search: String,
    onSearch: (String) -> Unit,
    noneLabel: String?,
    onClear: (() -> Unit)?,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onManage: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val filtered = options.filter { search.isBlank() || it.name.contains(search, ignoreCase = true) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = onCreate) { Text("新建") }
                onManage?.let { manage -> androidx.compose.material3.TextButton(onClick = manage) { Text("管理") } }
            }
            OutlinedTextField(
                value = search,
                onValueChange = onSearch,
                label = { Text(searchLabel) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                singleLine = true,
            )
            LazyColumn(Modifier.fillMaxWidth().height(360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (noneLabel != null && onClear != null) {
                    item(key = "dimension-none") {
                        DimensionPickerRow(
                            name = noneLabel,
                            supportingText = null,
                            selected = selectedIds.isEmpty(),
                            onClick = onClear,
                        )
                    }
                }
                items(filtered, key = { "dimension-${it.id}" }) { option ->
                    DimensionPickerRow(
                        name = option.name,
                        supportingText = "使用 ${option.usageCount} 次",
                        selected = option.id in selectedIds,
                        onClick = { onSelect(option.id) },
                    )
                }
                if (filtered.isEmpty()) {
                    item { Text("没有匹配项，可点击右上角新建。", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun DimensionPickerRow(
    name: String,
    supportingText: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                supportingText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (selected) Text("✓ 已选", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun CreateNameDialog(title: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it.take(80) }, label = { Text("名称") }, singleLine = true) },
        confirmButton = { androidx.compose.material3.TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) { Text("保存") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun CreateCategoryDialog(categories: List<CategoryEntity>, onDismiss: () -> Unit, onSave: (String, String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    val parents = categories.filter { it.parentId == null }
    var parentIndex by remember { mutableStateOf(-1) }
    val parent = parents.getOrNull(parentIndex)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建分类") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(40) }, label = { Text("分类名称") }, singleLine = true)
            androidx.compose.material3.OutlinedButton(onClick = { parentIndex = if (parentIndex + 1 >= parents.size) -1 else parentIndex + 1 }, Modifier.fillMaxWidth()) {
                Text("上级：${parent?.name ?: "一级分类"}")
            }
        } },
        confirmButton = { androidx.compose.material3.TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), parent?.id) }) { Text("保存") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SplitLineEditor(
    index: Int,
    line: SplitLineUi,
    categories: List<CategoryEntity>,
    onCategory: (String) -> Unit,
    onAmount: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = categories.firstOrNull { it.id == line.categoryId }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = selected?.name ?: "未分类",
                onValueChange = {},
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                readOnly = true,
                label = { Text("第 ${index + 1} 项分类") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                categories.forEach { category ->
                    DropdownMenuItem(text = { Text(category.name) }, onClick = { onCategory(category.id); expanded = false })
                }
            }
        }
        OutlinedTextField(
            value = line.amount,
            onValueChange = onAmount,
            modifier = Modifier.weight(0.72f),
            label = { Text("金额") },
            prefix = { Text("¥") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
        )
        IconButton(onClick = onRemove) { Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = "删除第 ${index + 1} 个拆分项") }
    }
}

@Composable
internal fun AdvancedInformationToggle(expanded: Boolean, onToggle: () -> Unit) {
    FilledTonalButton(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
        Text(if (expanded) "收起更多信息" else "更多信息", Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun TypeSelector(selected: TransactionType, onSelect: (TransactionType) -> Unit) {
    val options = listOf(TransactionType.EXPENSE to "支出", TransactionType.INCOME to "收入", TransactionType.TRANSFER to "转账", TransactionType.REFUND to "退款")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (type, label) ->
            Surface(
                onClick = { onSelect(type) },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.small,
                color = if (selected == type) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (selected == type) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(label, Modifier.padding(vertical = 11.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun CategoryGrid(
    categories: List<CategoryEntity>,
    selectedId: String?,
    expanded: Boolean,
    onSelect: (String) -> Unit,
    onToggleExpanded: () -> Unit,
    onCreate: () -> Unit,
) {
    val visible = if (expanded) categories else categories.take(8)
    Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        visible.chunked(4).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { category ->
                    val color = categoryVisualColor(category.name)
                    Surface(
                        onClick = { onSelect(category.id) },
                        modifier = Modifier.weight(1f).height(78.dp),
                        shape = MaterialTheme.shapes.small,
                        color = if (selectedId == category.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    ) {
                        Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(modifier = Modifier.size(34.dp), color = color.copy(alpha = 0.14f), shape = MaterialTheme.shapes.extraLarge) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(category.name.take(1), color = color, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            Text(category.name, Modifier.padding(top = 5.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                repeat(4 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (categories.size > 8) androidx.compose.material3.TextButton(onClick = onToggleExpanded) { Text(if (expanded) "收起分类" else "全部分类") }
            androidx.compose.material3.TextButton(onClick = onCreate) { Text("新建分类") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountSelector(
    label: String,
    accounts: List<AccountEntity>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = accounts.firstOrNull { it.id == selectedId } ?: accounts.firstOrNull()
    LaunchedEffect(selected?.id, selectedId) { if (selectedId == null && selected != null) onSelect(selected.id) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.name ?: "",
            onValueChange = {},
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            accounts.forEach { account ->
                DropdownMenuItem(text = { Text(account.name) }, onClick = { onSelect(account.id); expanded = false })
            }
        }
    }
}
