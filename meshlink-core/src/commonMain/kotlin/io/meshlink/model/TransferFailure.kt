package io.meshlink.model

import io.meshlink.util.DeliveryOutcome
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class TransferFailure(
    val messageId: Uuid,
    val reason: DeliveryOutcome,
)
