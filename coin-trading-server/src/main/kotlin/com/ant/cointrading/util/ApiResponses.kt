package com.ant.cointrading.util

typealias ApiResponse = Map<String, Any?>

fun apiSuccess(vararg entries: Pair<String, Any?>): ApiResponse {
    return buildMap {
        put("success", true)
        entries.forEach { (key, value) -> put(key, value) }
    }
}

fun apiFailure(error: String, vararg entries: Pair<String, Any?>): ApiResponse {
    return buildMap {
        put("success", false)
        put("error", error)
        entries.forEach { (key, value) -> put(key, value) }
    }
}
