package ch.trancee.meshlink.api

/**
 * Governs how MeshLink reacts when a peer's key changes after a prior successful handshake.
 *
 * - [STRICT]: Key changes are always rejected. A key-change event is emitted on
 *   [MeshLinkApi.keyChanges] so the app can call [MeshLinkApi.acceptKeyChange] explicitly after
 *   out-of-band verification.
 * - [PROMPT]: Key changes are presented to the app via [MeshLinkApi.keyChanges]. The app may call
 *   [MeshLinkApi.acceptKeyChange] or [MeshLinkApi.rejectKeyChange] to resolve. If no response
 *   arrives within [SecurityConfig.keyChangeTimeoutMillis], the change is rejected automatically.
 *
 * Defaults to [STRICT] to protect against MITM attacks. Use [PROMPT] when your application UX can
 * present an out-of-band verification dialog to the user.
 */
public enum class TrustMode {
    STRICT,
    PROMPT,
}
