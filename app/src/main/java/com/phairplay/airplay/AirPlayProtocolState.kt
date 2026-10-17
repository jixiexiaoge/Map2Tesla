package com.phairplay.airplay

import com.phairplay.service.ProtocolState

internal class AirPlayProtocolState {
    private var current = ProtocolState.DISABLED

    fun update(state: ProtocolState, fromDiscovery: Boolean = false): ProtocolState? {
        // Discovery registration can finish after SETUP, and restart emits DISABLED.
        // Neither event ends an established media session or stops the receiver.
        if (fromDiscovery && (state == ProtocolState.DISABLED || current == ProtocolState.CONNECTED)) {
            return null
        }
        if (state == current) return null
        current = state
        return state
    }
}
