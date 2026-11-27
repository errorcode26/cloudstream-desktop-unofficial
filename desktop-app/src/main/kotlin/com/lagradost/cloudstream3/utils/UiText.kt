package com.lagradost.cloudstream3.utils

sealed class UiText {
    data class DynamicString(val value: String) : UiText()

    fun asString(): String = when (this) {
        is DynamicString -> value
    }
}
