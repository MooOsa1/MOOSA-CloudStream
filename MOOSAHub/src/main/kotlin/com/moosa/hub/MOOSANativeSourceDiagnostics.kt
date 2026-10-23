package com.moosa.hub

import java.util.concurrent.ConcurrentHashMap

/**
 * Short, sanitized stages displayed by the TV Source Tester. Never store
 * decrypted stream URLs, API keys, query tokens or response bodies.
 */
internal object MOOSANativeSourceDiagnostics {
    private val status = ConcurrentHashMap<String, String>()

    fun clear(name: String) { status.remove(name) }

    fun mark(name: String, text: String) {
        status[name] = text.take(100)
    }

    fun note(name: String): String? = status[name]

    fun sourceTestLabel(apiName: String): String? =
        when {
            apiName == MOOSACineStreamVaPlayerPolicy.SOURCE_NAME ||
            apiName == MOOSACineStreamVidrockPolicy.SOURCE_NAME ||
            apiName == MOOSACineStreamVidzeePolicy.SOURCE_NAME ->
                "Better Call Saul S02E01"
            else -> null
        }
}
