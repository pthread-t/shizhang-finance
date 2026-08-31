package com.billrecord.ledger.ui.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.billrecord.ledger.data.LedgerRepository
import com.billrecord.ledger.data.local.TagUsage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TagManagementState(
    val message: String? = null,
    val busyTagId: String? = null,
)

@HiltViewModel
class TagManagementViewModel @Inject constructor(
    private val repository: LedgerRepository,
) : ViewModel() {
    private val selectedBookId = repository.observeSelectedBookId().filterNotNull()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    val tags = selectedBookId.flatMapLatest(repository::observeTagUsage)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<TagUsage>())
    private val _state = MutableStateFlow(TagManagementState())
    val state: StateFlow<TagManagementState> = _state

    fun create(name: String) = launch(null) { repository.createTag(selectedBookId.value, name); "标签已创建" }
    fun rename(id: String, name: String) = launch(id) { repository.renameTag(id, name); "标签已重命名" }
    fun delete(id: String) = launch(id) { "已删除标签，并清除 ${repository.deleteTag(id)} 笔账单的关联" }
    fun clearMessage() { _state.value = _state.value.copy(message = null) }

    private fun launch(tagId: String?, block: suspend () -> String) {
        if (_state.value.busyTagId != null) return
        viewModelScope.launch {
            _state.value = TagManagementState(busyTagId = tagId ?: "new")
            _state.value = runCatching { TagManagementState(message = block()) }
                .getOrElse { TagManagementState(message = it.message ?: "操作失败") }
        }
    }
}
