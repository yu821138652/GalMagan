package com.jev.probe.core

/** Resizing the chat for an IME can hide older rows without changing its latest turn. */
fun ChatSnapshot.hasSameLatestMessages(other: ChatSnapshot): Boolean {
    val count = minOf(6, messages.size, other.messages.size)
    return count > 0 && atLatest && other.atLatest && messages.takeLast(count) == other.messages.takeLast(count)
}
