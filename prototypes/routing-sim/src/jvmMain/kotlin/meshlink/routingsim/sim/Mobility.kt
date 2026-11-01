package meshlink.routingsim.sim

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Moves nodes in [Radio.xs]/[Radio.ys]. [extent] is the square side or the corridor length in metres. */
abstract class Mobility(
    protected val radio: Radio,
    protected val cal: Calibration,
    protected val extent: Double,
    protected val rng: Random,
) {
    abstract val moving: Boolean

    abstract fun place()

    abstract fun advance(
        nowMs: Long,
        dtMs: Long,
    )

    /** Group label per node for partition metrics, or null. */
    open fun groupOf(node: Int): Int = 0
}

class StaticCrowd(
    radio: Radio,
    cal: Calibration,
    side: Double,
    rng: Random,
) : Mobility(radio, cal, side, rng) {
    override val moving = false

    override fun place() {
        for (i in 0 until radio.n) {
            radio.xs[i] = rng.nextDouble() * extent
            radio.ys[i] = rng.nextDouble() * extent
        }
    }

    override fun advance(
        nowMs: Long,
        dtMs: Long,
    ) = Unit
}

/** Random waypoint in a width × height rectangle (square, or the corridor strip). */
open class RandomWaypoint(
    radio: Radio,
    cal: Calibration,
    private val width: Double,
    private val height: Double,
    rng: Random,
) : Mobility(radio, cal, width, rng) {
    override val moving = true
    private val tx = DoubleArray(radio.n)
    private val ty = DoubleArray(radio.n)
    private val speed = DoubleArray(radio.n)
    private val pauseUntil = LongArray(radio.n)

    override fun place() {
        for (i in 0 until radio.n) {
            radio.xs[i] = rng.nextDouble() * width
            radio.ys[i] = rng.nextDouble() * height
            pickTarget(i)
            pauseUntil[i] = (rng.nextDouble() * cal.pauseMaxMs).toLong()
        }
    }

    private fun pickTarget(i: Int) {
        tx[i] = rng.nextDouble() * width
        ty[i] = rng.nextDouble() * height
        speed[i] = cal.pedestrianSpeedMin + rng.nextDouble() * (cal.pedestrianSpeedMax - cal.pedestrianSpeedMin)
    }

    override fun advance(
        nowMs: Long,
        dtMs: Long,
    ) {
        for (i in 0 until radio.n) {
            if (nowMs < pauseUntil[i]) continue
            val dx = tx[i] - radio.xs[i]
            val dy = ty[i] - radio.ys[i]
            val dist = sqrt(dx * dx + dy * dy)
            val step = speed[i] * dtMs / 1000.0
            if (dist <= step) {
                radio.xs[i] = tx[i]
                radio.ys[i] = ty[i]
                pauseUntil[i] = nowMs + (rng.nextDouble() * cal.pauseMaxMs).toLong()
                pickTarget(i)
            } else {
                radio.xs[i] += dx / dist * step
                radio.ys[i] += dy / dist * step
            }
        }
    }
}

/** Reference-point group mobility: group centres do random waypoint, members keep fixed offsets. */
class GroupMobility(
    radio: Radio,
    cal: Calibration,
    side: Double,
    rng: Random,
) : Mobility(radio, cal, side, rng) {
    override val moving = true
    private val groups = (radio.n + cal.groupSize - 1) / cal.groupSize
    private val cx = DoubleArray(groups)
    private val cy = DoubleArray(groups)
    private val tx = DoubleArray(groups)
    private val ty = DoubleArray(groups)
    private val speed = DoubleArray(groups)
    private val pauseUntil = LongArray(groups)
    private val ox = DoubleArray(radio.n)
    private val oy = DoubleArray(radio.n)

    override fun groupOf(node: Int) = node / cal.groupSize

    override fun place() {
        for (g in 0 until groups) {
            cx[g] = rng.nextDouble() * extent
            cy[g] = rng.nextDouble() * extent
            pick(g)
        }
        for (i in 0 until radio.n) {
            val r = cal.groupRadiusM * sqrt(rng.nextDouble())
            val a = rng.nextDouble() * 2 * Math.PI
            ox[i] = r * cos(a)
            oy[i] = r * sin(a)
        }
        sync()
    }

    private fun pick(g: Int) {
        tx[g] = rng.nextDouble() * extent
        ty[g] = rng.nextDouble() * extent
        speed[g] = cal.pedestrianSpeedMin + rng.nextDouble() * (cal.pedestrianSpeedMax - cal.pedestrianSpeedMin)
    }

    private fun sync() {
        for (i in 0 until radio.n) {
            val g = groupOf(i)
            radio.xs[i] = cx[g] + ox[i]
            radio.ys[i] = cy[g] + oy[i]
        }
    }

    override fun advance(
        nowMs: Long,
        dtMs: Long,
    ) {
        for (g in 0 until groups) {
            if (nowMs < pauseUntil[g]) continue
            val dx = tx[g] - cx[g]
            val dy = ty[g] - cy[g]
            val dist = sqrt(dx * dx + dy * dy)
            val step = speed[g] * dtMs / 1000.0
            if (dist <= step) {
                cx[g] = tx[g]
                cy[g] = ty[g]
                pauseUntil[g] = nowMs + (rng.nextDouble() * cal.pauseMaxMs).toLong()
                pick(g)
            } else {
                cx[g] += dx / dist * step
                cy[g] += dy / dist * step
            }
        }
        sync()
    }
}

/**
 * Static crowd split into a left and a right half. From [Calibration.partitionSplitAtMs] the halves
 * walk apart until no pair can hear each other, wait, and from [Calibration.partitionReturnAtMs]
 * walk back to their original places.
 */
class PartitionMerge(
    radio: Radio,
    cal: Calibration,
    side: Double,
    rng: Random,
) : Mobility(radio, cal, side, rng) {
    override val moving = true
    private val baseX = DoubleArray(radio.n)
    private val left = BooleanArray(radio.n)
    private val walk = 0.55 * radio.maxRangeM
    val returnedAtMs: Long = cal.partitionReturnAtMs + (walk / cal.partitionWalkSpeed * 1000).toLong()

    override fun groupOf(node: Int) = if (left[node]) 0 else 1

    override fun place() {
        for (i in 0 until radio.n) {
            baseX[i] = rng.nextDouble() * extent
            radio.ys[i] = rng.nextDouble() * extent
            radio.xs[i] = baseX[i]
            left[i] = baseX[i] < extent / 2
        }
    }

    private fun offset(nowMs: Long): Double {
        val v = cal.partitionWalkSpeed / 1000.0
        return when {
            nowMs <= cal.partitionSplitAtMs -> 0.0
            nowMs <= cal.partitionReturnAtMs -> minOf(walk, (nowMs - cal.partitionSplitAtMs) * v)
            else -> maxOf(0.0, walk - (nowMs - cal.partitionReturnAtMs) * v)
        }
    }

    override fun advance(
        nowMs: Long,
        dtMs: Long,
    ) {
        val off = offset(nowMs)
        for (i in 0 until radio.n) radio.xs[i] = baseX[i] + if (left[i]) -off else off
    }
}

fun createMobility(
    kind: MobilityKind,
    radio: Radio,
    cal: Calibration,
    extent: Double,
    rng: Random,
): Mobility =
    when (kind) {
        MobilityKind.STATIC -> StaticCrowd(radio, cal, extent, rng)
        MobilityKind.RANDOM_WAYPOINT -> RandomWaypoint(radio, cal, extent, extent, rng)
        MobilityKind.GROUP -> GroupMobility(radio, cal, extent, rng)
        MobilityKind.CORRIDOR -> RandomWaypoint(radio, cal, extent, cal.corridorWidthM, rng)
        MobilityKind.PARTITION_MERGE -> PartitionMerge(radio, cal, extent, rng)
    }

/** Finds the square side (or corridor length) giving the target mean peers in radio range at placement. */
object AreaSizer {
    fun extentFor(
        kind: MobilityKind,
        nodes: Int,
        targetPeers: Double,
        cal: Calibration,
    ): Double {
        val range = 10.0.pow((cal.txPowerDbm - cal.pathLossAt1mDb - cal.sensitivityDbm) / (10 * cal.pathLossExponent))
        val roughArea = (nodes - 1) * Math.PI * range * range * 1.3 / targetPeers
        val rough = if (kind == MobilityKind.CORRIDOR) roughArea / cal.corridorWidthM else sqrt(roughArea)
        var lo = 1.0
        var hi = 4 * rough + 4 * range
        repeat(40) {
            val mid = sqrt(lo * hi)
            if (meanPeers(kind, nodes, mid, cal) > targetPeers) lo = mid else hi = mid
        }
        return sqrt(lo * hi)
    }

    fun meanPeers(
        kind: MobilityKind,
        nodes: Int,
        extent: Double,
        cal: Calibration,
    ): Double {
        var total = 0.0
        val reps = if (nodes < 100) 8 else 2
        for (rep in 0 until reps) {
            val radio = Radio(cal, nodes, seedOf("sizing", rep), BooleanArray(nodes))
            createMobility(kind, radio, cal, extent, Random(seedOf("sizing-place", rep))).place()
            radio.rebuild()
            total += radio.nbrCount.sum().toDouble() / nodes
        }
        return total / reps
    }
}
