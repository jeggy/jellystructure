package dev.jellystructure

internal fun encodeURIComponent(value: String): String = js("encodeURIComponent(value)")
internal fun decodeURIComponent(value: String): String = js("decodeURIComponent(value)")
