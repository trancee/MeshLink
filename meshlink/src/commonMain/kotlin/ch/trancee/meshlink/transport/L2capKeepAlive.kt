package ch.trancee.meshlink.transport

// Physical BLE testing surfaced a chronic ~10 second idle-close cycle on otherwise healthy L2CAP
// links: with no traffic flowing, the peer's platform BLE stack (observed on iOS CoreBluetooth
// peripherals acting as the L2CAP CoC listener) eventually tears the channel down, forcing a
// reconnect. Most of the time the automatic reconnect (see L2capReconnectGuard) hides this from
// the application, but a send that races the teardown can report local success (the socket write
// did not throw) while the bytes are actually lost with the closing channel -- silently dropping a
// message with no visible error. Writing a small, empty keepalive control frame on any otherwise
// idle link periodically resets the peer's inactivity clock, keeping the channel open under normal
// operation and shrinking how often that race window is hit. The interval is chosen well under the
// ~10s idle-close observed on real devices to leave comfortable margin for scheduling jitter.
internal const val L2CAP_KEEPALIVE_INTERVAL_MILLIS: Long = 4_000L
