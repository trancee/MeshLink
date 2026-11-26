package ch.trancee.meshlink.internal

import ch.trancee.meshlink.Preset

/**
 * The preset of the line and mesh tests that route, hold and expire messages: [Preset.OfflineChat] with
 * the shortest lifetime, so expiry comes soon in virtual time, and read receipts off, so only the
 * tests that ask for them see them.
 */
internal val LINE_PRESET: Preset =
    Preset.Custom("Line", Preset.OfflineChat) {
        messageLifetimeSeconds = 120
        readReceipts.requestByDefault = false
        readReceipts.send = false
    }
