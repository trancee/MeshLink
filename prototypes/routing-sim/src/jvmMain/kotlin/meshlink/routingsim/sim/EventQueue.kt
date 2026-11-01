package meshlink.routingsim.sim

abstract class Event {
    internal var time = 0L
    internal var seq = 0L

    abstract fun fire()
}

/** Binary min-heap ordered by (time, insertion order), so equal-time events run deterministically. */
class EventQueue {
    private var heap = arrayOfNulls<Event>(1024)
    var size = 0
        private set
    private var nextSeq = 0L
    var processed = 0L
        private set

    fun push(
        time: Long,
        event: Event,
    ) {
        event.time = time
        event.seq = nextSeq++
        if (size == heap.size) heap = heap.copyOf(size * 2)
        var i = size++
        while (i > 0) {
            val parent = (i - 1) ushr 1
            val p = heap[parent]!!
            if (!less(event, p)) break
            heap[i] = p
            i = parent
        }
        heap[i] = event
    }

    fun peekTime(): Long = heap[0]!!.time

    fun pop(): Event {
        val top = heap[0]!!
        val last = heap[--size]!!
        heap[size] = null
        if (size > 0) {
            var i = 0
            while (true) {
                val l = 2 * i + 1
                if (l >= size) break
                val r = l + 1
                val c = if (r < size && less(heap[r]!!, heap[l]!!)) r else l
                if (!less(heap[c]!!, last)) break
                heap[i] = heap[c]
                i = c
            }
            heap[i] = last
        }
        processed++
        return top
    }

    private fun less(
        a: Event,
        b: Event,
    ): Boolean = a.time < b.time || (a.time == b.time && a.seq < b.seq)
}
