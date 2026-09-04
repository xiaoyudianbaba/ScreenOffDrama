package com.screenoffdrama

/**
 * 第一阶段默认白名单：写死仅支持 YouTube 与哔哩哔哩。
 *
 * 本阶段只做常量声明，不实现白名单管理界面，也不做前台应用识别/高亮提示
 * （这些依赖无障碍或使用情况访问权限，属于后续阶段范围）。
 */
object Whitelist {
    val packages: Set<String> = setOf(
        "com.google.android.youtube",   // YouTube
        "tv.danmaku.bili"               // 哔哩哔哩
    )
}
