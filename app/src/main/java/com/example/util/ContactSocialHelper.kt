package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri

object ContactSocialHelper {

    enum class CustomFieldType {
        INSTAGRAM_ID,
        INSTAGRAM_LINK,
        SNAPCHAT_ID,
        SNAPCHAT_LINK,
        GENERAL_LINK,
        TEXT
    }

    data class RecognizedField(
        val key: String,
        val value: String,
        val type: CustomFieldType,
        val actionUrl: String? = null,
        val displayHandle: String = value
    )

    /**
     * Parses the stored additionalFieldsJson string safely into a list of key-value pairs.
     * Supports both standard "key:value;key2:value2" formats and multi-colon values (like URLs).
     */
    fun parseCustomFields(rawString: String): List<Pair<String, String>> {
        if (rawString.isBlank()) return emptyList()
        val result = mutableListOf<Pair<String, String>>()
        val pairs = rawString.split(";")
        for (pair in pairs) {
            val colonIdx = pair.indexOf(':')
            if (colonIdx > 0) {
                val key = pair.substring(0, colonIdx).trim()
                val value = pair.substring(colonIdx + 1).trim()
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    result.add(key to value)
                }
            }
        }
        return result
    }

    /**
     * Serializes a list of key-value pairs into a clean string.
     */
    fun serializeCustomFields(fields: List<Pair<String, String>>): String {
        return fields
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }
            .joinToString(";") { "${it.first.trim()}:${it.second.trim()}" }
    }

    /**
     * Analyzes a key-value pair and classifies it as Instagram, Snapchat, URL, or general Text.
     */
    fun classifyField(key: String, value: String): RecognizedField {
        val lowerKey = key.lowercase().trim()
        val lowerVal = value.lowercase().trim()

        // 1. Instagram Link recognition
        if (lowerKey.contains("insta") && (lowerKey.contains("link") || lowerKey.contains("url") || lowerKey.contains("profile") || lowerKey.contains("dp")) ||
            lowerVal.contains("instagram.com") || lowerVal.contains("instagr.am")
        ) {
            val url = formatUrl(value, "https://instagram.com/")
            val handle = extractHandleFromUrl(url) ?: value
            return RecognizedField(key, value, CustomFieldType.INSTAGRAM_LINK, url, handle)
        }

        // 2. Instagram ID recognition
        if (lowerKey.contains("insta") || lowerKey == "ig" || lowerKey.contains("instagram")) {
            val cleanHandle = value.trim().removePrefix("@")
            val url = "https://instagram.com/$cleanHandle"
            return RecognizedField(key, value, CustomFieldType.INSTAGRAM_ID, url, "@$cleanHandle")
        }

        // 3. Snapchat Link recognition
        if (lowerKey.contains("snap") && (lowerKey.contains("link") || lowerKey.contains("url") || lowerKey.contains("profile")) ||
            lowerVal.contains("snapchat.com")
        ) {
            val url = formatUrl(value, "https://snapchat.com/add/")
            val handle = extractHandleFromUrl(url) ?: value
            return RecognizedField(key, value, CustomFieldType.SNAPCHAT_LINK, url, handle)
        }

        // 4. Snapchat ID recognition
        if (lowerKey.contains("snap") || lowerKey.contains("snapchat")) {
            val cleanHandle = value.trim().removePrefix("@")
            val url = "https://snapchat.com/add/$cleanHandle"
            return RecognizedField(key, value, CustomFieldType.SNAPCHAT_ID, url, "@$cleanHandle")
        }

        // 5. General Web Link
        if (lowerVal.startsWith("http://") || lowerVal.startsWith("https://") || lowerVal.startsWith("www.")) {
            val url = if (lowerVal.startsWith("www.")) "https://$value" else value
            return RecognizedField(key, value, CustomFieldType.GENERAL_LINK, url, value)
        }

        // 6. Generic Text
        return RecognizedField(key, value, CustomFieldType.TEXT, null, value)
    }

    private fun formatUrl(input: String, defaultBaseUrl: String): String {
        val trimmed = input.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("www.") -> "https://$trimmed"
            trimmed.contains(".com/") -> "https://$trimmed"
            else -> defaultBaseUrl + trimmed.removePrefix("@")
        }
    }

    private fun extractHandleFromUrl(url: String): String? {
        return try {
            val uri = Uri.parse(url)
            val path = uri.lastPathSegment
            if (!path.isNullOrBlank()) "@$path" else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Attempts to open the Instagram profile in the Instagram app first, falling back to browser.
     */
    fun openInstagram(context: Context, handleOrUrl: String) {
        val cleanHandle = handleOrUrl.trim()
            .substringAfterLast("instagram.com/")
            .substringAfterLast("/")
            .removePrefix("@")
            .trimEnd('/')

        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("http://instagram.com/_u/$cleanHandle")).apply {
            setPackage("com.instagram.android")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(appIntent)
        } catch (e: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://instagram.com/$cleanHandle")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(webIntent)
            } catch (e2: Exception) {
                android.widget.Toast.makeText(context, "Unable to open Instagram link", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Attempts to open Snapchat in the Snapchat app first, falling back to browser.
     */
    fun openSnapchat(context: Context, handleOrUrl: String) {
        val cleanHandle = handleOrUrl.trim()
            .substringAfterLast("snapchat.com/add/")
            .substringAfterLast("/")
            .removePrefix("@")
            .trimEnd('/')

        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("snapchat://add/$cleanHandle")).apply {
            setPackage("com.snapchat.android")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(appIntent)
        } catch (e: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://snapchat.com/add/$cleanHandle")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(webIntent)
            } catch (e2: Exception) {
                android.widget.Toast.makeText(context, "Unable to open Snapchat link", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Opens general web URL.
     */
    fun openWebUrl(context: Context, url: String) {
        val cleanUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Unable to open link", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
