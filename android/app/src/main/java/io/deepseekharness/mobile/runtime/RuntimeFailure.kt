package io.deepseekharness.mobile.runtime

class RuntimeFailure(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
