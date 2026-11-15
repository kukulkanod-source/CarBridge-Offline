package com.example.carbridge

/** Shared by persisted diagnostics and their regression checks. */
internal object DiagnosticMessageFilter {
    private val sensitive = Regex(
        "pair(?:ing)?[ _-]*(?:code|key)|код(?:\\s+для)?\\s+сопряж|-----BEGIN|adbkey|private.?key|certificate|identity",
        RegexOption.IGNORE_CASE
    )

    fun clean(message: String): String =
        if (sensitive.containsMatchIn(message)) "[sensitive authentication detail omitted]"
        else message.take(1500).replace('\r', ' ').replace('\n', ' ')
}
