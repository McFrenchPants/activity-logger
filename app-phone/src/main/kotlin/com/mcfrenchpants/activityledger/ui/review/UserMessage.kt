package com.mcfrenchpants.activityledger.ui.review

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** A user-visible message: a string resource and its format arguments. Never the user's words. */
data class UserMessage(@param:StringRes val text: Int, val args: List<Any> = emptyList())

/** The message's text in the current configuration. */
@Composable
fun UserMessage.resolve(): String = stringResource(text, *args.toTypedArray())
