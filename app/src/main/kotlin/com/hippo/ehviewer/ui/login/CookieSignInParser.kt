package com.hippo.ehviewer.ui.login

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 解析后的身份 Cookie 三元组。
 */
data class ParsedIdentityCookies(
    val ipbMemberId: String? = null,
    val ipbPassHash: String? = null,
    val igneous: String? = null,
) {
    val isEmpty: Boolean
        get() = ipbMemberId == null && ipbPassHash == null && igneous == null
}

private const val KEY_MEMBER_ID = "ipb_member_id"
private const val KEY_PASS_HASH = "ipb_pass_hash"
private const val KEY_IGNEOUS = "igneous"

private val IGNORED_IGNEOUS_VALUES = setOf("null", "mystery", "expired")
private val TEXT_COOKIE_REGEX = Regex(
    """(?i)(?:^|[\s;,{"'])(ipb_member_id|ipb_pass_hash|igneous)\s*[:=]\s*["']?([^\s;,\]}"']+)["']?""",
)

/**
 * 清理单个 Cookie 输入值（去除首尾空白、引号与末尾多余标点）。
 */
fun sanitizeCookieValue(raw: String): String =
    raw.trim().trim('"', '\'', ',', ';')

/**
 * 校验清理后的 Cookie 值是否合法（非空且不含空白、分号、逗号及控制字符）。
 */
fun isValidCookieValue(value: String): Boolean {
    if (value.isEmpty()) return false
    return value.none { ch ->
        ch.isWhitespace() || ch == ';' || ch == ',' || ch == '=' || ch.code < 0x20 || ch.code == 0x7f
    }
}

/**
 * 从多种常见文本或 JSON 粘贴格式中智能识别 `ipb_member_id`、`ipb_pass_hash`、`igneous`：
 * 1. EhViewer 设置页多行冒号格式（`ipb_member_id: xxx`）
 * 2. 浏览器 `Cookie:` 请求头或分号格式（`ipb_member_id=xxx; ipb_pass_hash=yyy`）
 * 3. 多行 `key=value` 格式
 * 4. Cookie-Editor / EditThisCookie 导出的 JSON 数组或 JSON 对象
 */
fun parseIdentityCookies(raw: String): ParsedIdentityCookies {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ParsedIdentityCookies()

    val extracted = linkedMapOf<String, String>()

    // 优先尝试解析 JSON 数组或对象（支持 Cookie-Editor 等插件导出）
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
        runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()?.let { element ->
            when (element) {
                is JsonArray -> {
                    for (item in element) {
                        val obj = item as? JsonObject ?: continue
                        val name = (obj["name"] ?: obj["key"])?.let { (it as? JsonPrimitive)?.contentOrNull }?.lowercase() ?: continue
                        val value = obj["value"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: continue
                        recordCookieCandidate(extracted, name, value)
                    }
                }
                is JsonObject -> {
                    for ((k, v) in element) {
                        val primitive = v as? JsonPrimitive ?: continue
                        val value = primitive.jsonPrimitive.contentOrNull ?: continue
                        recordCookieCandidate(extracted, k.lowercase(), value)
                    }
                }
                else -> Unit
            }
        }
    }

    // 文本格式兜底解析（兼容多行冒号、Cookie 请求头与键值对）
    if (extracted.isEmpty()) {
        for (match in TEXT_COOKIE_REGEX.findAll(trimmed)) {
            val key = match.groupValues[1].lowercase()
            val value = match.groupValues[2]
            recordCookieCandidate(extracted, key, value)
        }
    }

    return ParsedIdentityCookies(
        ipbMemberId = extracted[KEY_MEMBER_ID],
        ipbPassHash = extracted[KEY_PASS_HASH],
        igneous = extracted[KEY_IGNEOUS],
    )
}

private fun recordCookieCandidate(
    target: MutableMap<String, String>,
    key: String,
    rawValue: String,
) {
    if (key != KEY_MEMBER_ID && key != KEY_PASS_HASH && key != KEY_IGNEOUS) return
    val clean = sanitizeCookieValue(rawValue)
    if (!isValidCookieValue(clean)) return
    if (key == KEY_IGNEOUS && clean.lowercase() in IGNORED_IGNEOUS_VALUES) return
    target[key] = clean
}
