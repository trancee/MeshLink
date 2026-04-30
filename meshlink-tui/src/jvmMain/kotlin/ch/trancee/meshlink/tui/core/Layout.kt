package ch.trancee.meshlink.tui.core

/**
 * Layout direction and constraint solver.
 */
enum class Direction { VERTICAL, HORIZONTAL }

sealed interface Constraint {
    data class Length(val value: Int) : Constraint
    data class Min(val value: Int) : Constraint
    data class Percentage(val value: Int) : Constraint
    data class Fill(val weight: Int = 1) : Constraint
}

class Layout(
    val direction: Direction,
    val constraints: List<Constraint>,
) {
    fun split(area: Rect): List<Rect> {
        val available = when (direction) {
            Direction.VERTICAL -> area.height
            Direction.HORIZONTAL -> area.width
        }
        val sizes = solve(constraints, available)
        return buildList {
            var offset = 0
            for (size in sizes) {
                add(
                    when (direction) {
                        Direction.VERTICAL -> Rect(area.x, area.y + offset, area.width, size)
                        Direction.HORIZONTAL -> Rect(area.x + offset, area.y, size, area.height)
                    }
                )
                offset += size
            }
        }
    }

    companion object {
        fun vertical(vararg c: Constraint) = Layout(Direction.VERTICAL, c.toList())
        fun horizontal(vararg c: Constraint) = Layout(Direction.HORIZONTAL, c.toList())
    }
}

private fun solve(constraints: List<Constraint>, available: Int): List<Int> {
    val sizes = IntArray(constraints.size)
    var remaining = available
    var fillWeight = 0

    for ((i, c) in constraints.withIndex()) {
        when (c) {
            is Constraint.Fill -> fillWeight += c.weight
            is Constraint.Length -> {
                sizes[i] = c.value.coerceAtMost(remaining)
                remaining -= sizes[i]
            }
            is Constraint.Min -> {
                sizes[i] = c.value.coerceAtMost(remaining)
                remaining -= sizes[i]
            }
            is Constraint.Percentage -> {
                sizes[i] = (available * c.value / 100).coerceAtMost(remaining)
                remaining -= sizes[i]
            }
        }
    }

    if (fillWeight > 0 && remaining > 0) {
        var distributed = 0
        for ((i, c) in constraints.withIndex()) {
            if (c is Constraint.Fill) {
                sizes[i] = remaining * c.weight / fillWeight
                distributed += sizes[i]
            }
        }
        val rounding = remaining - distributed
        if (rounding > 0) {
            val lastFill = constraints.indexOfLast { it is Constraint.Fill }
            if (lastFill >= 0) sizes[lastFill] += rounding
        }
    }
    return sizes.toList()
}

// Destructuring support
operator fun List<Rect>.component1() = this[0]
operator fun List<Rect>.component2() = this[1]
operator fun List<Rect>.component3() = this[2]
operator fun List<Rect>.component4() = this[3]
