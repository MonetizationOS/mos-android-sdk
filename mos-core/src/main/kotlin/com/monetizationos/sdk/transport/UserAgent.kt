package com.monetizationos.sdk.transport

import com.monetizationos.sdk.AppInfo
import com.monetizationos.sdk.DeviceInfo
import com.monetizationos.sdk.MOS_VERSION

private val nonTokenChars = Regex("""[^A-Za-z0-9!#$%&'*+.^_`|~-]+""")
private val nonCommentChars = Regex("""[^\x20-\x7E]|[();\\]""")

/**
 * `<app>/<version> (<platform> <os>; <device>) MOS-Android/<sdk>`. Set explicitly because MOS tags
 * bot-like agents and drops their anonymous-session plans; it also tells app traffic from web traffic.
 */
internal fun buildUserAgent(app: AppInfo, device: DeviceInfo): String {
    val product = "${token(app.name)}/${token(app.version)}"
    val os = listOf(device.platform, device.osVersion).map(::comment).filter { it.isNotEmpty() }.joinToString(" ")
    return "$product ($os; ${comment(device.device)}) MOS-Android/$MOS_VERSION"
}

private fun token(value: String): String = value.replace(nonTokenChars, "-").trim('-').ifEmpty { "app" }

private fun comment(value: String): String = value.replace(nonCommentChars, "").trim()
