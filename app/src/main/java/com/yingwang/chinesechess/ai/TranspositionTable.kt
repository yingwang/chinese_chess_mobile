package com.yingwang.chinesechess.ai

import com.yingwang.chinesechess.model.Move

/**
 * Transposition table for caching board evaluations.
 * Uses array-based storage with depth-preferred replacement policy.
 */
class TranspositionTable(private val maxSize: Int = 1_000_000) {

    enum class EntryType {
        EXACT,      // Exact score
        LOWER_BOUND, // Alpha cutoff (fail-high)
        UPPER_BOUND  // Beta cutoff (fail-low)
    }

    data class Entry(
        val hash: Long,
        val depth: Int,
        val score: Int,
        val type: EntryType,
        val bestMove: Move?
    )

    // Two-bucket table: each slot has a depth-preferred and an always-replace entry
    private val depthTable = arrayOfNulls<Entry>(maxSize)
    private val alwaysTable = arrayOfNulls<Entry>(maxSize)
    private var entryCount = 0

    private fun index(hash: Long): Int = (hash.ushr(1) % maxSize).toInt()

    fun store(hash: Long, depth: Int, score: Int, type: EntryType, bestMove: Move?) {
        val idx = index(hash)
        val entry = Entry(hash, depth, score, type, bestMove)

        val existing = depthTable[idx]
        if (existing == null || existing.hash == hash || depth >= existing.depth) {
            // Depth-preferred: replace if same position, deeper, or empty
            if (existing != null && existing.hash != hash) {
                // Demote old entry to always-replace bucket
                alwaysTable[idx] = existing
            }
            depthTable[idx] = entry
        } else {
            // Doesn't qualify for depth bucket, use always-replace
            alwaysTable[idx] = entry
        }
        entryCount++
    }

    fun probe(hash: Long): Entry? {
        val idx = index(hash)
        depthTable[idx]?.let { if (it.hash == hash) return it }
        alwaysTable[idx]?.let { if (it.hash == hash) return it }
        return null
    }

    fun clear() {
        depthTable.fill(null)
        alwaysTable.fill(null)
        entryCount = 0
    }

    fun size(): Int = entryCount
}
