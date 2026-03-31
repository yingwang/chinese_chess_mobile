package com.yingwang.chinesechess.ai

import com.yingwang.chinesechess.model.Move

/**
 * Transposition table for caching board evaluations
 */
class TranspositionTable(private val maxSize: Int = 1_000_000) {

    enum class EntryType {
        EXACT,      // Exact score
        LOWER_BOUND, // Alpha cutoff (fail-high)
        UPPER_BOUND  // Beta cutoff (fail-low)
    }

    data class Entry(
        val depth: Int,
        val score: Int,
        val type: EntryType,
        val bestMove: Move?
    )

    private val table = mutableMapOf<Long, Entry>()

    fun store(hash: Long, depth: Int, score: Int, type: EntryType, bestMove: Move?) {
        val existing = table[hash]
        // Replace if: no existing entry, or new search is at same or greater depth
        if (existing == null || depth >= existing.depth) {
            if (table.size >= maxSize && existing == null) {
                // Table full and inserting new key: remove a random entry
                val keyToRemove = table.keys.first()
                table.remove(keyToRemove)
            }
            table[hash] = Entry(depth, score, type, bestMove)
        }
    }

    fun probe(hash: Long): Entry? = table[hash]

    fun clear() {
        table.clear()
    }

    fun size(): Int = table.size
}
