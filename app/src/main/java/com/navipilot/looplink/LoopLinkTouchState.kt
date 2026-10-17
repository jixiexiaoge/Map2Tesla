package com.navipilot.looplink

import com.shilapi.xcertplay.airplay.AirPlayContact

internal class LoopLinkTouchState(
    private val send: (List<AirPlayContact>) -> Boolean,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    private var pressedAt = 0L
    private var updatedAt = 0L
    private var slots = List(2) { AirPlayContact(it, 0.0, 0.0, false) }

    @Synchronized fun submit(contacts: List<AirPlayContact>, now: Long): Boolean {
        require(contacts.size <= 2 && contacts.map { it.id }.distinct().size == contacts.size)
        require(contacts.all { it.id in 0..1 && it.x.isFinite() && it.y.isFinite() &&
            it.x in 0.0..1.0 && it.y in 0.0..1.0 && it.down })
        val pressed = slots.any { it.down }
        if (contacts.isEmpty() && pressed && now - pressedAt < 30) sleep(30 - (now - pressedAt))
        val next = slots.map { old -> contacts.firstOrNull { it.id == old.id } ?: old.copy(down = false) }
        if (!send(next)) return false
        if (!pressed && contacts.isNotEmpty()) pressedAt = now
        slots = next
        updatedAt = now
        return true
    }

    @Synchronized fun release() {
        if (slots.none { it.down }) return
        val next = slots.map { it.copy(down = false) }
        if (send(next)) slots = next
    }

    @Synchronized fun expire(now: Long) {
        if (now - updatedAt >= 1_500) release()
    }
}
