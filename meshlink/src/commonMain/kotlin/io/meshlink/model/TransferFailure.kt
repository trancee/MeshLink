package io.meshlink.model

import io.meshlink.util.DeliveryOutcome

data class TransferFailure(
    val messageId: MessageId,
    val reason: DeliveryOutcome,
)
