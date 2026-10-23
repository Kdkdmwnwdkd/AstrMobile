package com.astrmobile.core.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 8 点栅格。
 *
 * 这个文件存在的唯一原因：之前有 394 处硬编码 `.dp`、29 种不同取值
 * （4/6/8/10/12/14/16/18/20…），每个页面自己定间距。单看每页都还行，
 * 连起来翻就觉得"不整齐"——因为节奏对不上。
 *
 * 以后只允许用这几档。要新的档位先想清楚为什么 8 的倍数不够用。
 */
object Spacing {
    /** 图标与文字之间。 */
    val xs = 4.dp

    /** 紧密相关元素之间。 */
    val sm = 8.dp

    /** 同一组内的行距。 */
    val md = 12.dp

    /** 卡片内边距、常规段落间隔。 */
    val lg = 16.dp

    /** 卡片内边距（大卡片）、组与组之间。 */
    val xl = 20.dp

    /** 页面左右留白、大段落之间。 */
    val page = 20.dp

    /** 大区块之间（弹窗内容分区、长表单的段落）。 */
    val xxl = 24.dp

    /** 区块与区块之间。 */
    val section = 28.dp
}

/**
 * 圆角。
 *
 * 统一用三档：小（chip/输入框）、中（按钮）、大（卡片）。
 * 大卡片用 26dp 而不是 16dp —— 深色底上圆角越大越"软"，
 * 是拉开"工具"和"产品"观感最省力的一招。
 */
object Radius {
    val sm = 12.dp
    val md = 16.dp
    val lg = 22.dp
    val xl = 26.dp
    val pill = 999.dp
}

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.sm),
    small = RoundedCornerShape(Radius.sm),
    medium = RoundedCornerShape(Radius.md),
    large = RoundedCornerShape(Radius.lg),
    extraLarge = RoundedCornerShape(Radius.xl),
)
