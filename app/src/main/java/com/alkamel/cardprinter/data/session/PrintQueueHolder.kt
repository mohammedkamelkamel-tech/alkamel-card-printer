package com.alkamel.cardprinter.data.session

import com.alkamel.cardprinter.data.local.entity.CardEntity
import com.alkamel.cardprinter.data.local.entity.CardTemplateEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PrintQueuePayload(
    val cards: List<CardEntity> = emptyList(),
    val template: CardTemplateEntity? = null
)

object PrintQueueHolder {
    private val _queue = MutableStateFlow(PrintQueuePayload())
    val queue: StateFlow<PrintQueuePayload> = _queue.asStateFlow()

    fun setQueue(cards: List<CardEntity>, template: CardTemplateEntity? = null) {
        _queue.value = PrintQueuePayload(cards = cards, template = template)
    }

    fun clear() {
        _queue.value = PrintQueuePayload()
    }
}
