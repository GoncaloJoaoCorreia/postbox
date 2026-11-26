package com.postbox.api

internal const val MinPasswordLength = 8

private val emailPattern = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")

internal fun isValidEmail(email: String): Boolean = emailPattern.matches(email)

internal fun displayNameFor(email: String): String = email.substringBefore('@')
