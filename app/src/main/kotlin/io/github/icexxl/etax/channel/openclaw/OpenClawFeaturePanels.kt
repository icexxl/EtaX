package io.github.icexxl.etax.channel.openclaw

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalGlassPageBackdrop
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.EtaTopLayer
import io.github.mangi.eta.ui.components.coui.CouiMenu
import io.github.mangi.eta.ui.components.coui.CouiMenuItem
import io.github.mangi.eta.ui.components.coui.couiMenuAnchor
import io.github.mangi.eta.ui.components.coui.rememberCouiMenuAnchor
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassPopup
import top.yukonga.miuix.kmp.glass.GlassPopupSizing
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class ThinkingLevelOption(
    val value: String,
    val label: String,
    val shortLabel: String,
    val summary: String,
)

internal val OpenClawThinkingOptions = listOf(
    ThinkingLevelOption("default", "跟随默认", "默认", "使用模型或助手默认思考配置"),
    ThinkingLevelOption("off", "关闭思考", "关闭", "直接回复，不进行深度推理"),
    ThinkingLevelOption("low", "轻度思考", "低", "快速推理，低延迟"),
    ThinkingLevelOption("medium", "标准思考", "中", "平衡速度与推理深度"),
    ThinkingLevelOption("high", "深度思考", "高", "复杂任务深度推演"),
    ThinkingLevelOption("adaptive", "自适应", "自动", "由模型按问题复杂度自动决定"),
)

internal fun thinkingShortLabel(level: String?): String {
    val norm = level?.trim()?.lowercase().orEmpty()
    if (norm.isEmpty() || norm == "default") return "思考"
    return OpenClawThinkingOptions.firstOrNull { it.value == norm }?.shortLabel ?: norm
}

/** 助手回复内可折叠的思考过程块（流式阶段自动展开，完成后支持点击收起/展开）。 */
@Composable
internal fun OpenClawReasoningBlock(
    reasoning: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
) {
    if (reasoning.isBlank()) return
    var expanded by remember(streaming) { mutableStateOf(streaming) }
    val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val ink = MiuixTheme.colorScheme.onSurface
    val cardBg = ink.copy(alpha = if (isDark) 0.06f else 0.04f)
    val borderColor = ink.copy(alpha = if (isDark) 0.10f else 0.07f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cardBg)
            .border(0.5.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MiuixTheme.colorScheme.primary.copy(alpha = 0.85f),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (streaming) "正在思考…" else "思考过程",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = ink.copy(alpha = 0.72f),
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = ink.copy(alpha = 0.48f),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Text(
                text = reasoning.trim(),
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = ink.copy(alpha = 0.66f),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 助手执行过程中的工具调用进度条（点击可展开步骤详情）。 */
@Composable
internal fun OpenClawToolsStrip(
    tools: List<OpenClawToolStep>,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    if (tools.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val ink = MiuixTheme.colorScheme.onSurface
    val latest = tools.last()
    val header = if (running) {
        "正在执行工具 · ${latest.name}（共 ${tools.size} 步）"
    } else {
        "已调用 ${tools.size} 步工具 · ${latest.name}"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ink.copy(alpha = if (isDark) 0.055f else 0.035f))
            .border(0.5.dp, ink.copy(alpha = if (isDark) 0.09f else 0.06f), RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Rounded.Build,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MiuixTheme.colorScheme.primary.copy(alpha = 0.8f),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = header,
                fontSize = 12.sp,
                color = ink.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = ink.copy(alpha = 0.45f),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier.padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                tools.forEachIndexed { idx, step ->
                    val detail = if (step.summary.isNotBlank()) "${step.name}: ${step.summary}" else step.name
                    Text(
                        text = "${idx + 1}. $detail",
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = ink.copy(alpha = 0.62f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 输入框工具栏里的思考强度切换按钮（支持 Miuix 玻璃弹窗与 COUI 菜单）。 */
@Composable
internal fun OpenClawThinkingChip(
    currentLevel: String?,
    enabled: Boolean,
    switching: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiStyle = LocalUiStyle.current
    val isCoui = uiStyle == AppearanceUiStyle.COUI
    val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val ink = MiuixTheme.colorScheme.onSurface
    val norm = currentLevel?.trim()?.lowercase().orEmpty()
    val isCustomActive = norm.isNotEmpty() && norm != "default" && norm != "off"
    var showMenu by remember { mutableStateOf(false) }
    var anchorBounds by remember { mutableStateOf(Rect.Zero) }
    val couiAnchor = rememberCouiMenuAnchor()

    val chipBg = when {
        isCustomActive -> MiuixTheme.colorScheme.primary.copy(alpha = if (isDark) 0.18f else 0.12f)
        else -> ink.copy(alpha = if (isDark) 0.07f else 0.045f)
    }
    val chipFg = when {
        !enabled -> MiuixTheme.colorScheme.disabledOnSurface
        isCustomActive -> MiuixTheme.colorScheme.primary
        else -> ink.copy(alpha = 0.72f)
    }

    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .onGloballyPositioned { anchorBounds = it.boundsInWindow() }
                .couiMenuAnchor(couiAnchor)
                .clip(CircleShape)
                .background(chipBg)
                .clickable(enabled = enabled && !switching) {
                    if (isCoui) couiAnchor.open { showMenu = true } else showMenu = true
                }
                .padding(horizontal = 9.dp, vertical = 5.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = "思考强度",
                modifier = Modifier.size(14.dp),
                tint = chipFg,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (switching) "切换中" else thinkingShortLabel(currentLevel),
                fontSize = 12.sp,
                fontWeight = if (isCustomActive) FontWeight.Medium else FontWeight.Normal,
                color = chipFg,
                maxLines = 1,
            )
        }

        val effectiveSelected = norm.ifEmpty { "default" }
        if (isCoui) {
            val items = remember(effectiveSelected) {
                OpenClawThinkingOptions.map { opt ->
                    CouiMenuItem(
                        value = opt.value,
                        text = opt.label,
                        selected = opt.value == effectiveSelected,
                    )
                }
            }
            CouiMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                items = items,
                onItemClick = { item ->
                    showMenu = false
                    onSelect(item.value)
                },
                anchor = couiAnchor,
            )
        } else {
            val overlayBackdrop = currentGlassOverlayBackdrop() ?: LocalGlassPageBackdrop.current
            EtaTopLayer {
                GlassPopup(
                    show = showMenu,
                    onDismissRequest = { showMenu = false },
                    anchorBounds = anchorBounds,
                    backdrop = overlayBackdrop,
                    sizing = GlassPopupSizing(
                        minWidth = 210.dp,
                        maxWidth = 270.dp,
                        maxHeight = 340.dp,
                    ),
                ) {
                    OpenClawThinkingOptions.forEach { opt ->
                        GlassSelectablePopupItem(
                            title = opt.label,
                            summary = opt.summary,
                            selected = opt.value == effectiveSelected,
                            onClick = {
                                showMenu = false
                                onSelect(opt.value)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 输入框键入 `/` 时弹出的斜杠指令与 Skills 补全浮层。 */
@Composable
internal fun OpenClawSlashCommandSheet(
    query: String,
    commands: List<CommandOptionDto>,
    onPick: (CommandOptionDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val needle = query.trim().lowercase()
    val filtered = remember(needle, commands) {
        val source = commands.ifEmpty { fallbackSlashCommands() }
        if (needle == "/") {
            source
        } else {
            val bare = needle.removePrefix("/")
            source.filter { item ->
                item.command.lowercase().contains(bare) ||
                    item.name.lowercase().contains(bare) ||
                    item.description.lowercase().contains(bare)
            }
        }
    }
    if (filtered.isEmpty()) return

    val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val ink = MiuixTheme.colorScheme.onSurface
    val surface = MiuixTheme.colorScheme.surfaceContainer

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(surface.copy(alpha = if (isDark) 0.96f else 0.98f))
            .border(0.5.dp, ink.copy(alpha = if (isDark) 0.14f else 0.09f), RoundedCornerShape(18.dp))
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
    ) {
        filtered.take(12).forEach { cmd ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(cmd) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = cmd.command,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.primary,
                    maxLines = 1,
                )
                if (cmd.category == "skill") {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Skill",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                if (cmd.description.isNotBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = cmd.description,
                        fontSize = 12.sp,
                        color = ink.copy(alpha = 0.62f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 轻量状态提示条（自动压缩中 / 压缩完成 / 重置完成）。 */
@Composable
internal fun OpenClawStatusBanner(
    text: String,
    dismissible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = if (isDark) 0.16f else 0.10f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (dismissible) {
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "关闭提示",
                modifier = Modifier
                    .size(16.dp)
                    .clickable(onClick = onDismiss),
                tint = MiuixTheme.colorScheme.primary,
            )
        }
    }
}

private fun fallbackSlashCommands(): List<CommandOptionDto> = listOf(
    CommandOptionDto("/compact", "compact", "压缩当前会话上下文", acceptsArgs = true, category = "command"),
    CommandOptionDto("/new", "new", "重置当前会话，开始新上下文", acceptsArgs = false, category = "command"),
    CommandOptionDto("/status", "status", "查看当前会话与模型状态", acceptsArgs = false, category = "command"),
    CommandOptionDto("/think", "think", "设置思考深度（off/low/medium/high）", acceptsArgs = true, category = "command"),
    CommandOptionDto("/model", "model", "查看或切换模型", acceptsArgs = true, category = "command"),
    CommandOptionDto("/stop", "stop", "停止当前正在执行的任务", acceptsArgs = false, category = "command"),
    CommandOptionDto("/help", "help", "查看全部可用指令", acceptsArgs = false, category = "command"),
)
