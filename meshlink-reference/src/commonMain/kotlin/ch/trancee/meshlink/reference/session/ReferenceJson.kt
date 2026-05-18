package ch.trancee.meshlink.reference.session

import kotlinx.serialization.json.Json

/** Shared JSON configuration for retained session history and exported session artifacts. */
public object ReferenceJson {
    public val codec: Json = Json {
        prettyPrint = true
        explicitNulls = false
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
}
