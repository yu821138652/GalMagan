package com.jev.probe.capture

/** Candidates arrive in foreground order; an expected app never overrides that order. */
internal object ChatWindowSelection {
    data class Candidate(val windowId: Int, val pkg: String, val children: Int)

    fun select(candidates: List<Candidate>, ownPackage: String, expectedPackage: String?): Int? {
        val foreground = candidates.firstOrNull() ?: return null
        if (foreground.pkg.isBlank() || foreground.pkg == ownPackage ||
            expectedPackage != null && foreground.pkg != expectedPackage) return null
        if (foreground.children > 0 || foreground.windowId < 0) return 0
        // rootInActiveWindow and window.root may return different snapshots of
        // the same window. Keep the populated one instead of dropping it by ID.
        return candidates.indexOfFirst {
            it.pkg == foreground.pkg && it.windowId == foreground.windowId && it.children > 0
        }.takeIf { it >= 0 } ?: 0
    }
}
