package ch.trancee.meshlink.messaging

import ch.trancee.meshlink.api.DiagnosticCode
import ch.trancee.meshlink.api.DiagnosticPayload
import ch.trancee.meshlink.api.DiagnosticSinkApi
import ch.trancee.meshlink.api.toPeerIdHex
import ch.trancee.meshlink.wire.Chunk
import ch.trancee.meshlink.wire.WireCodec

/**
 * Per-messageId relay pipelining buffer that begins forwarding chunks to the next hop as soon as
 * chunk 0 reveals the routing header — avoiding the full-reassembly delay of TransferEngine-based
 * relay. Falls back to TransferEngine on any parse or validation failure.
 *
 * **State machine:** [State.Pending] → [State.Active] → [State.Complete] / [State.TimedOut] /
 * [State.Fallback].
 *
 * **Chunk 0 byte surgery:** Rather than re-encoding the entire RoutedMessage (which extends beyond
 * chunk 0 for multi-chunk messages), CutThroughBuffer performs in-place byte modification on a copy
 * of chunk 0's payload: decrement hopLimit, append the relay's keyHash to visitedList, and update
 * the payload vector's uoffset_t to account for the 12-byte insertion.
 *
 * **Overflow split:** The modified chunk 0 grows by 12 bytes. When it exceeds [chunkSize], it is
 * split into two forwarded chunks and all subsequent seqNo values shift by +1.
 *
 * @param relayMessageId Fresh session ID for the forwarded chunks (NOT the original sender's ID).
 * @param nextHop Peer ID of the downstream relay or final destination.
 * @param localKeyHash 12-byte key hash of this relay node (appended to visitedList).
 * @param chunkSize Maximum payload size per chunk (e.g. 244 for GATT).
 * @param sendFn Callback to forward a wire-encoded chunk to [nextHop].
 * @param diagnosticSink Diagnostic event sink (emissions wired in T03).
 */
internal class CutThroughBuffer(
    private val relayMessageId: ByteArray,
    private val nextHop: ByteArray,
    private val localKeyHash: ByteArray,
    private val chunkSize: Int,
    private val sendFn: suspend (peerId: ByteArray, data: ByteArray) -> Boolean,
    private val diagnosticSink: DiagnosticSinkApi,
) {

    // ── State machine ─────────────────────────────────────────────────────────

    enum class State {
        Pending,
        Active,
        Complete,
        TimedOut,
        Fallback,
    }

    var state: State = State.Pending
        private set

    /** Total chunks to forward (adjusted for overflow split). */
    private var forwardTotalChunks: UShort = 0u

    /** seqNo offset for subsequent chunks (1 if overflow split occurred, else 0). */
    private var seqNoOffset: Int = 0

    /** Tracks how many chunks have been forwarded successfully or attempted. */
    private var forwardedCount: Int = 0

    /** Parsed routing header from chunk 0, available after activation. */
    var routingHeader: RoutingHeaderView? = null
        private set

    // ── Chunk 0 processing ────────────────────────────────────────────────────

    /**
     * Process chunk 0. Parses the routing header, validates relay eligibility, modifies the chunk 0
     * bytes, and begins forwarding. Returns `true` if cut-through activated, `false` if the buffer
     * fell back (caller should use TransferEngine).
     */
    suspend fun onChunk0(chunk: Chunk): Boolean {
        if (state != State.Pending) return false

        val header: RoutingHeaderView
        try {
            header = RoutingHeaderView(chunk.payload)
        } catch (_: Exception) {
            state = State.Fallback
            return false
        }
        routingHeader = header

        // Validate relay eligibility.
        if (header.hopLimit == 0.toUByte()) {
            state = State.Fallback
            return false
        }
        // Loop detection: if localKeyHash is already in visitedList, reject.
        for (visited in header.visitedListEntries) {
            if (visited.contentEquals(localKeyHash)) {
                state = State.Fallback
                return false
            }
        }

        // Perform byte surgery on chunk 0.
        val modifiedBytes: ByteArray
        try {
            modifiedBytes =
                modifyChunk0(
                    chunk0Payload = chunk.payload,
                    newHopLimit = (header.hopLimit - 1u).toUByte(),
                    appendKeyHash = localKeyHash,
                    header = header,
                )
        } catch (_: Exception) {
            state = State.Fallback
            return false
        }

        state = State.Active
        val originalTotalChunks = chunk.totalChunks.toInt()

        // Diagnostic: cut-through relay activated.
        val dstHex = header.destination.toPeerIdHex()
        val nextHopHex = nextHop.toPeerIdHex()
        diagnosticSink.emit(DiagnosticCode.ROUTE_CHANGED) {
            DiagnosticPayload.TextMessage(
                "cut-through relay activated: dst=$dstHex nextHop=$nextHopHex totalChunks=$originalTotalChunks"
            )
        }

        // Determine if overflow split is needed.
        if (modifiedBytes.size <= chunkSize) {
            // No split: single-chunk message or modified bytes still fit.
            seqNoOffset = 0
            forwardTotalChunks = chunk.totalChunks
            val ok = forwardChunk(0.toUShort(), forwardTotalChunks, modifiedBytes)
            forwardedCount++
            if (!ok) {
                val peerIdHex = nextHop.toPeerIdHex()
                diagnosticSink.emit(DiagnosticCode.SEND_FAILED) {
                    DiagnosticPayload.SendFailed(
                        peerId = peerIdHex,
                        reason = "cut-through chunk forwarding failed",
                    )
                }
            }
            if (forwardTotalChunks == 1.toUShort()) {
                state = State.Complete
            }
        } else {
            // Overflow split: modified bytes exceed chunkSize.
            seqNoOffset = 1
            forwardTotalChunks = (originalTotalChunks + 1).toUShort()

            val chunk0Bytes = modifiedBytes.copyOfRange(0, chunkSize)
            val overflowBytes = modifiedBytes.copyOfRange(chunkSize, modifiedBytes.size)

            val ok0 = forwardChunk(0.toUShort(), forwardTotalChunks, chunk0Bytes)
            forwardedCount++
            if (!ok0) {
                val peerIdHex = nextHop.toPeerIdHex()
                diagnosticSink.emit(DiagnosticCode.SEND_FAILED) {
                    DiagnosticPayload.SendFailed(
                        peerId = peerIdHex,
                        reason = "cut-through chunk forwarding failed",
                    )
                }
            }

            val ok1 = forwardChunk(1.toUShort(), forwardTotalChunks, overflowBytes)
            forwardedCount++
            if (!ok1) {
                val peerIdHex = nextHop.toPeerIdHex()
                diagnosticSink.emit(DiagnosticCode.SEND_FAILED) {
                    DiagnosticPayload.SendFailed(
                        peerId = peerIdHex,
                        reason = "cut-through chunk forwarding failed",
                    )
                }
            }

            if (originalTotalChunks == 1) {
                // Single original chunk that overflowed — we're done after forwarding both pieces.
                state = State.Complete
            }
        }

        return true
    }

    // ── Subsequent chunks ─────────────────────────────────────────────────────

    /**
     * Forward a subsequent chunk (seqNo > 0) with adjusted seqNo and totalChunks. The original
     * payload is forwarded as-is.
     */
    suspend fun onSubsequentChunk(chunk: Chunk) {
        if (state != State.Active) return

        val adjustedSeqNo = (chunk.seqNo.toInt() + seqNoOffset).toUShort()
        val ok = forwardChunk(adjustedSeqNo, forwardTotalChunks, chunk.payload)
        forwardedCount++
        if (!ok) {
            val peerIdHex = nextHop.toPeerIdHex()
            diagnosticSink.emit(DiagnosticCode.SEND_FAILED) {
                DiagnosticPayload.SendFailed(
                    peerId = peerIdHex,
                    reason = "cut-through chunk forwarding failed",
                )
            }
        }

        // Check for completion: total forwarded = forwardTotalChunks.
        val expectedForwards = forwardTotalChunks.toInt()
        if (forwardedCount >= expectedForwards) {
            state = State.Complete
        }
    }

    // ── Timeout / eviction ────────────────────────────────────────────────────

    /** Transition to [State.TimedOut]. Called by the owning DeliveryPipeline on inactivity. */
    fun markTimedOut() {
        if (state == State.Active || state == State.Pending) {
            state = State.TimedOut
        }
    }

    // ── Chunk forwarding ──────────────────────────────────────────────────────

    private suspend fun forwardChunk(
        seqNo: UShort,
        totalChunks: UShort,
        payload: ByteArray,
    ): Boolean {
        val chunk =
            Chunk(
                messageId = relayMessageId,
                seqNo = seqNo,
                totalChunks = totalChunks,
                payload = payload,
            )
        val wireBytes = WireCodec.encode(chunk)
        return sendFn(nextHop, wireBytes)
    }

    // ── Byte-level chunk 0 modification ───────────────────────────────────────

    companion object {
        /**
         * Perform byte-level surgery on chunk 0's payload to update routing fields for relay
         * forwarding. Operates on a copy of the original bytes.
         *
         * @param chunk0Payload Raw chunk 0 payload: `[1-byte type discriminator][FlatBuffer bytes]`
         * @param newHopLimit Decremented hop limit value.
         * @param appendKeyHash 12-byte key hash to append to visitedList.
         * @param header Pre-parsed [RoutingHeaderView] for field positions.
         * @return Modified bytes (original size + 12).
         */
        internal fun modifyChunk0(
            chunk0Payload: ByteArray,
            newHopLimit: UByte,
            appendKeyHash: ByteArray,
            header: RoutingHeaderView,
        ): ByteArray {
            require(appendKeyHash.size == 12) { "keyHash must be 12 bytes" }

            // All positions in RoutingHeaderView are relative to the FlatBuffer bytes (after the
            // type discriminator), so we add 1 (TYPE_BYTE_SIZE) to get absolute positions in the
            // chunk payload.
            val typeByteOffset = 1

            // 1. Copy the original bytes, inserting 12 bytes at the visitedList data end.
            //    visitedListDataEnd is the absolute position (in FlatBuffer) right after the
            //    existing visitedList data.
            val insertionPoint = header.visitedListDataEndAbs + typeByteOffset
            val result = ByteArray(chunk0Payload.size + 12)

            // Copy everything before insertion point.
            chunk0Payload.copyInto(result, 0, 0, insertionPoint)
            // Insert the 12-byte keyHash.
            appendKeyHash.copyInto(result, insertionPoint)
            // Copy everything after insertion point.
            chunk0Payload.copyInto(result, insertionPoint + 12, insertionPoint, chunk0Payload.size)

            // 2. Update hopLimit in-place.
            val hopLimitAbsPos = header.hopLimitAbs + typeByteOffset
            result[hopLimitAbsPos] = newHopLimit.toByte()

            // 3. Update visitedList vector length prefix: N*12 → (N+1)*12.
            val vecLenAbsPos = header.visitedListLengthPrefixAbs + typeByteOffset
            val oldLen = readUIntLE(result, vecLenAbsPos)
            writeUIntLE(result, vecLenAbsPos, oldLen + 12u)

            // 4. Update payload uoffset_t: add 12 to existing relative offset (because 12-byte
            //    insertion shifted all data after the visitedList by 12 bytes). This is needed only
            //    when the payload field exists and its absolute position in the FlatBuffer is
            // BEFORE
            //    the insertion point (i.e. the uoffset_t field itself is in the inline table, which
            //    is always before vectors).
            if (header.payloadUoffsetAbs > 0) {
                val payloadFieldAbsPos = header.payloadUoffsetAbs + typeByteOffset
                // Only update if the payload vector is AFTER the insertion point.
                // The uoffset_t is a relative offset from the field position to the vector start.
                // Since we inserted 12 bytes between the field and the vector, add 12.
                val oldOffset = readUIntLE(result, payloadFieldAbsPos)
                writeUIntLE(result, payloadFieldAbsPos, oldOffset + 12u)
            }

            // 5. Update any other vector uoffset_t fields that point past the insertion point.
            //    In the RoutedMessage layout, vectors are stored in field order after the inline
            //    table. The payload vector (field 7) is the last one. Any vector field whose
            //    uoffset_t field position (in the inline table) is BEFORE the insertion point AND
            //    whose target vector is AFTER the insertion point needs adjustment. However, the
            //    visitedList field (4) points to the visitedList vector itself — the insertion is
            // at
            //    the END of that vector's data, so vectors stored AFTER visitedList data also
            // shift.
            //    We need to update uoffset_t for fields whose vector data starts after the
            //    insertion point.
            for (fieldAbs in header.vectorFieldsAfterVisitedList) {
                val fieldAbsPos = fieldAbs + typeByteOffset
                val oldOffset = readUIntLE(result, fieldAbsPos)
                writeUIntLE(result, fieldAbsPos, oldOffset + 12u)
            }

            return result
        }

        // ── Little-endian helpers ─────────────────────────────────────────────

        internal fun readUIntLE(buf: ByteArray, pos: Int): UInt =
            ((buf[pos].toInt() and 0xFF) or
                    ((buf[pos + 1].toInt() and 0xFF) shl 8) or
                    ((buf[pos + 2].toInt() and 0xFF) shl 16) or
                    ((buf[pos + 3].toInt() and 0xFF) shl 24))
                .toUInt()

        internal fun writeUIntLE(buf: ByteArray, pos: Int, v: UInt) {
            val i = v.toInt()
            buf[pos] = (i and 0xFF).toByte()
            buf[pos + 1] = ((i ushr 8) and 0xFF).toByte()
            buf[pos + 2] = ((i ushr 16) and 0xFF).toByte()
            buf[pos + 3] = ((i ushr 24) and 0xFF).toByte()
        }
    }

    // ── RoutingHeaderView ─────────────────────────────────────────────────────

    /**
     * Parses the routing header fields from chunk 0's payload using direct FlatBuffer vtable
     * lookup. Exposes field values and byte positions needed for [modifyChunk0] surgery.
     *
     * All "Abs" positions are relative to the start of the FlatBuffer bytes (i.e. after the 1-byte
     * type discriminator), NOT relative to the chunk payload.
     *
     * @param chunk0Payload Raw chunk 0 payload: `[1-byte type discriminator][FlatBuffer bytes]`
     */
    internal class RoutingHeaderView(chunk0Payload: ByteArray) {

        // FlatBuffer bytes start after the type discriminator.
        private val fb: ByteArray = chunk0Payload.copyOfRange(1, chunk0Payload.size)

        // ── Vtable resolution (replicated from ReadBuffer) ────────────────────

        private val tableOffset: Int
        private val vtableOffset: Int
        private val vtableSize: Int

        init {
            if (fb.size < 4) throw IllegalArgumentException("FlatBuffer too short for root offset")
            tableOffset = readIntLE(fb, 0)
            if (tableOffset < 0 || tableOffset + 4 > fb.size) {
                throw IllegalArgumentException("Root offset out of bounds")
            }
            val soffset = readIntLE(fb, tableOffset)
            vtableOffset = tableOffset + soffset
            if (vtableOffset < 0 || vtableOffset + 4 > fb.size) {
                throw IllegalArgumentException("Vtable offset out of bounds")
            }
            vtableSize = readUShortLE(fb, vtableOffset).toInt()

            // Validate type discriminator.
            if (chunk0Payload.isEmpty()) throw IllegalArgumentException("Empty chunk payload")
            val typeCode = chunk0Payload[0].toUByte()
            if (typeCode != 0x0Au.toUByte()) {
                throw IllegalArgumentException(
                    "Not a RoutedMessage (type=0x${typeCode.toString(16)})"
                )
            }
        }

        /** Returns the ABSOLUTE position of field [fieldIndex] in [fb], or 0 if absent. */
        private fun fieldPosition(fieldIndex: Int): Int {
            val slotPos = vtableOffset + 4 + fieldIndex * 2
            if (slotPos + 2 > vtableOffset + vtableSize) return 0
            val offset = readUShortLE(fb, slotPos).toInt()
            return if (offset == 0) 0 else tableOffset + offset
        }

        // ── Parsed routing header fields ──────────────────────────────────────

        val messageId: ByteArray = getByteArray(0) ?: ByteArray(16)
        val origin: ByteArray = getByteArray(1) ?: ByteArray(12)
        val destination: ByteArray = getByteArray(2) ?: ByteArray(12)
        val hopLimit: UByte
        val priority: Byte
        val originationTime: ULong

        // ── Byte positions for surgery (all relative to fb, i.e. FlatBuffer start) ──

        /** Absolute position of the hopLimit scalar byte in [fb]. */
        val hopLimitAbs: Int

        /** Absolute position of the visitedList vector length prefix (uint32 LE) in [fb]. */
        val visitedListLengthPrefixAbs: Int

        /** Absolute position of the first byte AFTER all visitedList data in [fb]. */
        val visitedListDataEndAbs: Int

        /** Parsed visitedList entries (N × 12 bytes). */
        val visitedListEntries: List<ByteArray>

        /** Absolute position of the payload uoffset_t field in [fb], or 0 if absent. */
        val payloadUoffsetAbs: Int

        /**
         * List of absolute positions (in [fb]) of vector uoffset_t fields that point to vectors
         * stored AFTER the visitedList data (excluding visitedList itself and the payload field,
         * which is handled separately). These need their uoffset_t incremented by 12 when the
         * visitedList is extended.
         */
        val vectorFieldsAfterVisitedList: List<Int>

        init {
            // hopLimit: field 3, scalar (UByte).
            val hlPos = fieldPosition(3)
            if (hlPos == 0) throw IllegalArgumentException("hopLimit field absent")
            hopLimitAbs = hlPos
            hopLimit = fb[hlPos].toUByte()

            // priority: field 5, scalar (Byte).
            val priPos = fieldPosition(5)
            priority = if (priPos == 0) 0 else fb[priPos]

            // originationTime: field 6, scalar (ULong).
            val otPos = fieldPosition(6)
            originationTime = if (otPos == 0) 0u else readULongLE(fb, otPos)

            // visitedList: field 4, byte vector (N × 12 bytes flat).
            val vlFieldPos = fieldPosition(4)
            if (vlFieldPos == 0) throw IllegalArgumentException("visitedList field absent")
            val vlRelOffset = readUIntLE(fb, vlFieldPos).toInt()
            val vlVecStart = vlFieldPos + vlRelOffset
            if (vlVecStart + 4 > fb.size)
                throw IllegalArgumentException("visitedList vec out of bounds")
            val vlLength = readUIntLE(fb, vlVecStart).toInt()
            visitedListLengthPrefixAbs = vlVecStart
            visitedListDataEndAbs = vlVecStart + 4 + vlLength

            // Parse individual 12-byte entries.
            val peerCount = vlLength / 12
            visitedListEntries =
                List(peerCount) { i ->
                    fb.copyOfRange(vlVecStart + 4 + i * 12, vlVecStart + 4 + (i + 1) * 12)
                }

            // payload: field 7, byte vector.
            val plFieldPos = fieldPosition(7)
            payloadUoffsetAbs = plFieldPos

            // Identify vector fields whose target data is AFTER visitedListDataEndAbs.
            // RoutedMessage fields: 0=messageId(vec), 1=origin(vec), 2=destination(vec),
            // 3=hopLimit(scalar), 4=visitedList(vec), 5=priority(scalar),
            // 6=originationTime(scalar), 7=payload(vec).
            // Vector fields: 0, 1, 2, 4, 7. We skip field 4 (visitedList itself) and field 7
            // (payload, handled separately above).
            val vecFieldsToCheck = intArrayOf(0, 1, 2)
            val adjustList = mutableListOf<Int>()
            for (fi in vecFieldsToCheck) {
                val fPos = fieldPosition(fi)
                if (fPos == 0) continue
                // Resolve where the vector data starts.
                val relOff = readUIntLE(fb, fPos).toInt()
                val vecDataStart = fPos + relOff
                if (vecDataStart >= visitedListDataEndAbs) {
                    adjustList.add(fPos)
                }
            }
            vectorFieldsAfterVisitedList = adjustList
        }

        // ── Byte-array vector read (replicated from ReadBuffer) ───────────────

        private fun getByteArray(fieldIndex: Int): ByteArray? {
            val pos = fieldPosition(fieldIndex)
            if (pos == 0) return null
            val relOffset = readUIntLE(fb, pos).toInt()
            val vecStart = pos + relOffset
            if (vecStart + 4 > fb.size) throw IllegalArgumentException("Vector start out of bounds")
            val length = readUIntLE(fb, vecStart).toInt()
            if (vecStart + 4 + length > fb.size) {
                throw IllegalArgumentException("Vector data out of bounds")
            }
            return fb.copyOfRange(vecStart + 4, vecStart + 4 + length)
        }

        companion object {
            private fun readIntLE(buf: ByteArray, pos: Int): Int =
                (buf[pos].toInt() and 0xFF) or
                    ((buf[pos + 1].toInt() and 0xFF) shl 8) or
                    ((buf[pos + 2].toInt() and 0xFF) shl 16) or
                    ((buf[pos + 3].toInt() and 0xFF) shl 24)

            private fun readUShortLE(buf: ByteArray, pos: Int): UShort =
                ((buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8)).toUShort()

            private fun readUIntLE(buf: ByteArray, pos: Int): UInt = readIntLE(buf, pos).toUInt()

            private fun readULongLE(buf: ByteArray, pos: Int): ULong {
                var result = 0UL
                for (i in 0 until 8) {
                    result = result or ((buf[pos + i].toULong() and 0xFFUL) shl (i * 8))
                }
                return result
            }
        }
    }
}
