package ch.trancee.meshlink.transfer

class ChunkSizePolicy private constructor(val size: Int) {
    companion object {
        val GATT = ChunkSizePolicy(244)
        val L2CAP = ChunkSizePolicy(4096)

        fun fixed(size: Int): ChunkSizePolicy = ChunkSizePolicy(size)
    }
}
