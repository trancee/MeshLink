package io.meshlink.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals

class Field25519Test {

    // A non-trivial field element for testing (arbitrary bytes)
    private fun sampleA(): LongArray = Field25519.feUnpack(
        byteArrayOf(
            0x6A, 0x09, 0x46, 0x67, 0x53, 0x3C, 0x49, 0x08,
            0x32, 0x5B, 0x13, 0x66, 0x6A, 0x09, 0x46, 0x67,
            0x53, 0x3C, 0x49, 0x08, 0x32, 0x5B, 0x13, 0x66,
            0x6A, 0x09, 0x46, 0x67, 0x53, 0x3C, 0x49, 0x08,
        )
    )

    private fun sampleB(): LongArray = Field25519.feUnpack(
        byteArrayOf(
            0x3B, 0x67, 0x2E, 0x05, 0x04, 0x4A, 0x27, 0x3B,
            0x3C, 0x6E, 0x73, 0x72, 0x7E, 0x14, 0x26, 0x4F,
            0x08, 0x54, 0x16, 0x36, 0x33, 0x07, 0x43, 0x62,
            0x18, 0x0B, 0x17, 0x2B, 0x55, 0x6B, 0x68, 0x0F,
        )
    )

    // ── Identity properties ──────────────────────────────────────

    @Test
    fun zeroIsAdditiveIdentity() {
        val a = sampleA()
        val result = Field25519.feAdd(a, Field25519.feZero())
        assertContentEquals(Field25519.fePack(a), Field25519.fePack(result))
    }

    @Test
    fun oneIsMultiplicativeIdentity() {
        val a = sampleA()
        val result = Field25519.feMul(a, Field25519.feOne())
        assertContentEquals(Field25519.fePack(a), Field25519.fePack(result))
    }

    // ── Commutativity ────────────────────────────────────────────

    @Test
    fun additionIsCommutative() {
        val a = sampleA()
        val b = sampleB()
        val ab = Field25519.fePack(Field25519.feAdd(a, b))
        val ba = Field25519.fePack(Field25519.feAdd(b, a))
        assertContentEquals(ab, ba)
    }

    @Test
    fun multiplicationIsCommutative() {
        val a = sampleA()
        val b = sampleB()
        val ab = Field25519.fePack(Field25519.feMul(a, b))
        val ba = Field25519.fePack(Field25519.feMul(b, a))
        assertContentEquals(ab, ba)
    }

    // ── Inverse operations ───────────────────────────────────────

    @Test
    fun subtractionIsInverseOfAddition() {
        val a = sampleA()
        val b = sampleB()
        val sum = Field25519.feAdd(a, b)
        val result = Field25519.feSub(sum, b)
        assertContentEquals(Field25519.fePack(a), Field25519.fePack(result))
    }

    @Test
    fun negationPlusOriginalIsZero() {
        val a = sampleA()
        val neg = Field25519.feNeg(a)
        val result = Field25519.feAdd(a, neg)
        assertTrue(Field25519.feIsZero(result), "a + (-a) should be zero")
    }

    // ── Pack/unpack ──────────────────────────────────────────────

    @Test
    fun packUnpackRoundTrip() {
        val a = sampleA()
        val packed = Field25519.fePack(a)
        val unpacked = Field25519.feUnpack(packed)
        val repacked = Field25519.fePack(unpacked)
        assertContentEquals(packed, repacked)
    }

    @Test
    fun packZeroIsAllZeros() {
        val packed = Field25519.fePack(Field25519.feZero())
        assertContentEquals(ByteArray(32), packed)
    }

    // ── Inversion ────────────────────────────────────────────────

    @Test
    fun invertTimesOriginalIsOne() {
        val a = sampleA()
        val inv = Field25519.feInvert(a)
        val product = Field25519.feMul(a, inv)
        assertContentEquals(
            Field25519.fePack(Field25519.feOne()),
            Field25519.fePack(product),
            "a * a^(-1) should equal 1",
        )
    }

    @Test
    fun invertOfOneIsOne() {
        val one = Field25519.feOne()
        val inv = Field25519.feInvert(one)
        assertContentEquals(Field25519.fePack(one), Field25519.fePack(inv))
    }

    // ── Constant-time operations ─────────────────────────────────

    @Test
    fun conditionalSwapSwapsWhenFlagIsOne() {
        val a = Field25519.feCopy(sampleA())
        val b = Field25519.feCopy(sampleB())
        val origA = Field25519.fePack(a)
        val origB = Field25519.fePack(b)
        Field25519.feConditionalSwap(a, b, 1)
        assertContentEquals(origB, Field25519.fePack(a), "a should now hold original b")
        assertContentEquals(origA, Field25519.fePack(b), "b should now hold original a")
    }

    @Test
    fun conditionalSwapNoOpWhenFlagIsZero() {
        val a = Field25519.feCopy(sampleA())
        val b = Field25519.feCopy(sampleB())
        val origA = Field25519.fePack(a)
        val origB = Field25519.fePack(b)
        Field25519.feConditionalSwap(a, b, 0)
        assertContentEquals(origA, Field25519.fePack(a), "a should be unchanged")
        assertContentEquals(origB, Field25519.fePack(b), "b should be unchanged")
    }

    @Test
    fun conditionalMoveMovesWhenFlagIsOne() {
        val a = Field25519.feCopy(sampleA())
        val b = Field25519.feCopy(sampleB())
        val origB = Field25519.fePack(b)
        Field25519.feConditionalMove(a, b, 1)
        assertContentEquals(origB, Field25519.fePack(a), "a should now hold b's value")
    }

    @Test
    fun conditionalMoveNoOpWhenFlagIsZero() {
        val a = Field25519.feCopy(sampleA())
        val origA = Field25519.fePack(a)
        Field25519.feConditionalMove(a, sampleB(), 0)
        assertContentEquals(origA, Field25519.fePack(a), "a should be unchanged")
    }

    // ── Edge cases ───────────────────────────────────────────────

    @Test
    fun squareMatchesSelfMultiply() {
        val a = sampleA()
        val squared = Field25519.fePack(Field25519.feSquare(a))
        val selfMul = Field25519.fePack(Field25519.feMul(a, a))
        assertContentEquals(squared, selfMul, "feSquare(a) should equal feMul(a, a)")
    }

    @Test
    fun mul121666MatchesManualMultiply() {
        val a = sampleA()
        val constant = Field25519.feZero().also { it[0] = 121666L }
        val fast = Field25519.fePack(Field25519.feMul121666(a))
        val manual = Field25519.fePack(Field25519.feMul(a, constant))
        assertContentEquals(fast, manual, "feMul121666 should match feMul by constant")
    }

    @Test
    fun isNegativeReturnsLeastSignificantBit() {
        assertEquals(0, Field25519.feIsNegative(Field25519.feZero()))
        assertEquals(1, Field25519.feIsNegative(Field25519.feOne()))
    }

    @Test
    fun isZeroDetectsZeroElement() {
        assertTrue(Field25519.feIsZero(Field25519.feZero()))
        assertFalse(Field25519.feIsZero(Field25519.feOne()))
        assertFalse(Field25519.feIsZero(sampleA()))
    }
}
