package com.example.jusay

object SafetyWall {
    val blockedPackages = listOf(
        "com.android.settings",
        "com.android.systemui"
    )

    val blockedKeywords = listOf(
        "delete",
        "uninstall",
        "remove",
        "format",
        "pay"
    )

    val requiresConfirm = listOf(
        "send",
        "post",
        "submit"
    )
}
