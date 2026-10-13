package com.jev.probe.core.kb

object ChatEnvironment {
    const val WECHAT = "com.tencent.mm"
    const val XHS = "com.xingin.xhs"
    val supported = listOf(WECHAT, XHS, "com.tencent.mobileqq", "com.ss.android.lark", "com.twitter.android")

    fun label(pkg: String): String = when (pkg) {
        WECHAT -> "微信"
        XHS -> "小红书"
        "com.tencent.mobileqq" -> "QQ"
        "com.ss.android.lark" -> "飞书"
        "com.twitter.android" -> "X"
        "" -> "来源未知"
        else -> pkg
    }
}
