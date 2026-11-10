package io.github.icexxl.etax.ui.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.icexxl.etax.R
import io.github.icexxl.etax.ui.components.AdaptiveTopAppBar
import io.github.icexxl.etax.ui.components.AuroraBackground
import io.github.icexxl.etax.ui.components.rememberAuroraEnabled
import io.github.icexxl.etax.ui.components.CharacterAvatar
import io.github.icexxl.etax.channel.openclaw.HomeMode
import io.github.icexxl.etax.ui.components.ConversationSidePaneScaffold
import io.github.icexxl.etax.ui.components.MiuixBackButton
import io.github.icexxl.etax.ui.components.RoleplayChatUi
import io.github.icexxl.etax.ui.components.TopBarBackdrop
import io.github.icexxl.etax.ui.components.captureForTopBar
import io.github.icexxl.etax.ui.components.rememberTopBarBackdrop
import io.github.icexxl.etax.ui.components.topBarContainerColor
import io.github.icexxl.etax.ui.model.ConversationPaneUiState
import io.github.icexxl.etax.ui.model.ConversationSummaryUi
import io.github.icexxl.etax.ui.navigation.AppRoute
import io.github.icexxl.etax.data.model.TtsProvider
import io.github.icexxl.etax.data.repository.SpeechSettingsRepository
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.SmartToy

import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material.icons.rounded.Code
import com.itos.coui.icons.CouiIcons
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Scaffold as CouiLayoutScaffold
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import com.itos.coui.theme.CouiTheme
import com.itos.coui.theme.LocalCouiTextStyle
import com.itos.coui.topappbar.CouiTopAppBarDefaults
import com.itos.coui.topappbar.TopAppBar as CouiTopAppBar
import com.itos.coui.topappbar.TopAppBarButtonGroup
import io.github.icexxl.etax.data.model.AppearanceUiStyle
import io.github.icexxl.etax.ui.components.coui.CouiBackButton
import io.github.icexxl.etax.ui.components.coui.CouiTopAppBarIconButton

/**
 * Agent App 统一壳层。
 *
 * - 负责全局 Scaffold、状态栏/横向安全边距、顶层工具栏。
 * - 首页工具栏只保留历史入口与溢出菜单，保持聊天舞台干净。
 * - 非首页子路由统一提供返回按钮与标题，避免每个页面各自像独立设置页。
 * - Settings 由标准二级页骨架自己提供 TopAppBar，壳层在此路由不重复绘制。
 */
@Composable
internal fun AgentAppShell(
    currentRoute: AppRoute?,
    isCurrentRoute: Boolean,
    conversationPaneState: ConversationPaneUiState?,
    isConversationPaneOpen: Boolean,
    onBack: () -> Unit,
    onOpenConversationPane: () -> Unit,
    onDismissConversationPane: () -> Unit,
    onSearchConversations: (String) -> Unit,
    onNewConversation: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onConversationTogglePin: (ConversationSummaryUi) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationExport: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    homeMode: HomeMode,
    onHomeModeChange: (HomeMode) -> Unit,
    openClawHome: Boolean = false,
    homeTitle: String? = null,
    openClawMenu: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    openClawPane: (@Composable (androidx.compose.ui.unit.Dp) -> Unit)? = null,
    homeCharacter: RoleplayChatUi? = null,
    onOpenCharacter: () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val uiStyle = LocalUiStyle.current
    val isHome = currentRoute is AppRoute.Home
    val auroraEnabled = rememberAuroraEnabled()
    val translucentHome = auroraEnabled && isHome
    val onToggleHomeMode = {
        onHomeModeChange(if (homeMode == HomeMode.OPENCLAW) HomeMode.ETA else HomeMode.OPENCLAW)
    }
    val pageContent: @Composable () -> Unit = {
        Box(modifier = Modifier.fillMaxSize()) {
            AuroraBackground(modifier = Modifier.matchParentSize(), enabled = auroraEnabled)
            when (uiStyle) {
                AppearanceUiStyle.COUI -> {
                    val dark = CouiTheme.isDark
                    val colors = CouiPageDefaults.themeColors(dark)
                    CouiLayoutScaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = if (translucentHome) Color.Transparent else MiuixTheme.colorScheme.surface,
                        windowInsets = WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                        ),
                        topBar = {
                            if (currentRoute !is AppRoute.Settings) {
                                CouiTopAppBar(
                                    title = {
                                        BasicText(
                                            text = when {
                                                isHome && !homeTitle.isNullOrBlank() -> homeTitle
                                                isHome && !openClawHome && homeCharacter == null -> "EtaX"
                                                else -> titleForRoute(currentRoute)
                                            },
                                            style = LocalCouiTextStyle.current,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    navigationIcon = {
                                        if (isHome) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                CouiTopAppBarIconButton(
                                                    icon = Icons.AutoMirrored.Outlined.ShortText,
                                                    contentDescription = stringResource(R.string.action_conversation_history),
                                                    onClick = onOpenConversationPane,
                                                    dark = dark,
                                                    colors = colors,
                                                )
                                                CouiTopAppBarIconButton(
                                                    icon = if (openClawHome) Icons.Rounded.Forum else Icons.Rounded.SmartToy,
                                                    contentDescription = if (openClawHome) "切换到模型对话" else "切换到 OpenClaw 对话",
                                                    onClick = onToggleHomeMode,
                                                    dark = dark,
                                                    colors = colors,
                                                )
                                                homeCharacter?.takeIf { !openClawHome }?.let { character ->
                                                    CharacterTopBarIdentity(character = character, onClick = onOpenCharacter)
                                                }
                                            }
                                        } else {
                                            CouiBackButton(onClick = onBack, dark = dark, colors = colors)
                                        }
                                    },
                                    actions = {
                                        if (isHome && openClawHome) {
                                            openClawMenu?.invoke()
                                        } else if (isHome) {
                                            TopAppBarButtonGroup {
                                                CouiTopBarOverflowMenu(
                                                    dark = dark,
                                                    colors = colors,
                                                    onNewConversation = onNewConversation,
                                                    onOpenTerminal = onOpenTerminal,
                                                    onLaunchKimiWeb = onLaunchKimiWeb,
                                                    kimiWebLabel = kimiWebLabel,
                                                    canStopKimiWeb = canStopKimiWeb,
                                                    onStopKimiWeb = onStopKimiWeb,
                                                    onRefreshKimiWeb = onRefreshKimiWeb,
                                                    onOpenBrowser = onOpenBrowser,
                                                )
                                            }
                                        }
                                    },
                                    dark = dark,
                                    colors = CouiTopAppBarDefaults.colors(
                                        backgroundColor = if (translucentHome) Color.Transparent else MiuixTheme.colorScheme.surface,
                                        titleColor = colors.title,
                                        dark = dark,
                                    ),
                                )
                            }
                        },
                    ) { padding ->
                        Box(modifier = Modifier.fillMaxSize()) {
                            content(padding)
                        }
                    }
                }
                AppearanceUiStyle.MIUIX -> {
                    val scrollBehavior = MiuixScrollBehavior()
                    val backdrop = rememberTopBarBackdrop()
                    val topBarColor = if (translucentHome) Color.Transparent else topBarContainerColor(backdrop)
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = if (translucentHome) Color.Transparent else MiuixTheme.colorScheme.surface,
                        contentWindowInsets = WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                        ),
                        topBar = {
                            if (currentRoute !is AppRoute.Settings) {
                                val barContent: @Composable () -> Unit = {
                                    AgentTopBar(
                                        route = currentRoute,
                                        scrollBehavior = scrollBehavior,
                                        color = topBarColor,
                                        onBack = onBack,
                                        onNewConversation = onNewConversation,
                                        onOpenTerminal = onOpenTerminal,
                                        onLaunchKimiWeb = onLaunchKimiWeb,
                                        kimiWebLabel = kimiWebLabel,
                                        canStopKimiWeb = canStopKimiWeb,
                                        onStopKimiWeb = onStopKimiWeb,
                                        onRefreshKimiWeb = onRefreshKimiWeb,
                                        onOpenBrowser = onOpenBrowser,
                                        homeCharacter = homeCharacter,
                                        onOpenCharacter = onOpenCharacter,
                                        openClawHome = openClawHome,
                                        homeTitle = homeTitle,
                                        openClawMenu = openClawMenu,
                                        onToggleHomeMode = onToggleHomeMode,
                                    )
                                }
                                if (translucentHome) barContent() else TopBarBackdrop(backdrop) { barContent() }
                            }
                        },
                    ) { padding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .captureForTopBar(backdrop)
                                .nestedScroll(scrollBehavior.nestedScrollConnection),
                        ) {
                            content(padding)
                        }
                    }
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (conversationPaneState != null && currentRoute is AppRoute.Home) {
            ConversationSidePaneScaffold(
                state = conversationPaneState,
                visible = isConversationPaneOpen,
                backHandlerEnabled = isCurrentRoute,
                onOpen = onOpenConversationPane,
                onDismiss = onDismissConversationPane,
                onSearchChange = onSearchConversations,
                onConversationSelected = onSelectConversation,
                onConversationTogglePin = onConversationTogglePin,
                onConversationRename = onConversationRename,
                onConversationExport = onConversationExport,
                onConversationDelete = onConversationDelete,
                onOpenSettings = onOpenSettings,
                onOpenModelProviders = onOpenModelProviders,
                onOpenTools = onOpenTools,
                onOpenSkills = onOpenSkills,
                onOpenCharacters = onOpenCharacters,
                onOpenPermissions = onOpenPermissions,
                homeMode = homeMode,
                onHomeModeChange = onHomeModeChange,
                paneContent = openClawPane,
            ) {
                pageContent()
            }
        } else {
            pageContent()
        }
    }
}

@Composable
private fun CouiTopBarOverflowMenu(
    dark: Boolean,
    colors: com.itos.coui.layout.CouiPageColors,
    onNewConversation: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val speechSettings by remember { SpeechSettingsRepository.settingsFlow() }.collectAsState(initial = null)
    val hasSpeechPlayback = speechSettings?.let { it.tts != TtsProvider.NONE } == true
    val autoReadEnabled = speechSettings?.chatAutoSpeak == true
    val anchor = rememberCouiMenuAnchor()

    val newConversationText = stringResource(R.string.action_new_conversation)
    val openTerminalText = stringResource(R.string.action_open_terminal)
    val stopKimiWebText = stringResource(R.string.capability_kimi_stop)
    val openBrowserText = stringResource(R.string.action_open_browser)
    val autoReadText = stringResource(
        if (autoReadEnabled) R.string.speech_disable_auto_read else R.string.speech_enable_auto_read,
    )
    val menuItems = remember(
        newConversationText,
        openTerminalText,
        kimiWebLabel,
        openBrowserText,
        stopKimiWebText,
        canStopKimiWeb,
        autoReadText,
        hasSpeechPlayback,
        autoReadEnabled,
    ) {
        buildList {
            add(CouiMenuItem(value = 0, text = newConversationText, icon = Icons.Rounded.AddComment))
            add(CouiMenuItem(value = 1, text = openTerminalText, icon = Icons.Rounded.Terminal))
            add(CouiMenuItem(value = 2, text = kimiWebLabel, icon = Icons.Rounded.Code))
            add(CouiMenuItem(value = 3, text = openBrowserText, icon = Icons.Rounded.Language))
            if (canStopKimiWeb) {
                add(CouiMenuItem(value = 4, text = stopKimiWebText, icon = Icons.Rounded.Stop))
            }
            if (hasSpeechPlayback) {
                add(
                    CouiMenuItem(
                        value = 5,
                        text = autoReadText,
                        icon = if (autoReadEnabled) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                    ),
                )
            }
        }
    }

    Box {
        CouiTopAppBarIconButton(
            icon = CouiIcons.More,
            contentDescription = stringResource(R.string.action_more),
            modifier = Modifier.couiMenuAnchor(anchor),
            dark = dark,
            colors = colors,
            onClick = {
                onRefreshKimiWeb()
                anchor.open { showMenu = true }
            },
        )
        Menu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            items = menuItems,
            onItemClick = { item ->
                showMenu = false
                when (item.value) {
                    0 -> onNewConversation()
                    1 -> onOpenTerminal()
                    2 -> onLaunchKimiWeb()
                    3 -> onOpenBrowser()
                    4 -> onStopKimiWeb()
                    5 -> speechSettings?.let { current ->
                        scope.launch {
                            SpeechSettingsRepository.save(current.copy(chatAutoSpeak = !current.chatAutoSpeak))
                        }
                    }
                }
            },
            anchor = anchor,
        )
    }
}

@Composable
private fun AgentTopBar(
    route: AppRoute?,
    scrollBehavior: ScrollBehavior,
    color: Color,
    onBack: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
    homeCharacter: RoleplayChatUi?,
    onOpenCharacter: () -> Unit,
    openClawHome: Boolean = false,
    homeTitle: String? = null,
    openClawMenu: (@Composable () -> Unit)? = null,
    onToggleHomeMode: () -> Unit = {},
) {
    val isHome = route is AppRoute.Home
    val navigationIcon: @Composable () -> Unit = {
        if (isHome) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 会话侧栏改为左滑打开，左上角改成在「模型对话」和「OpenClaw 对话」之间切换。
                // 图标显示的是"点了会切到哪"：在模型对话时显示 OpenClaw 图标，反之亦然。
                IconButton(onClick = onToggleHomeMode) {
                    Icon(
                        imageVector = if (openClawHome) Icons.Rounded.Forum else Icons.Rounded.SmartToy,
                        modifier = Modifier.size(22.dp),
                        contentDescription = if (openClawHome) "切换到模型对话" else "切换到 OpenClaw 对话",
                    )
                }
                // 角色对话的身份标识放在顶栏左侧，点按进入角色详情；普通对话保持空标题。
                homeCharacter?.let { character ->
                    CharacterTopBarIdentity(character = character, onClick = onOpenCharacter)
                }
            }
        } else {
            MiuixBackButton(onClick = onBack)
        }
    }
    val actions: @Composable RowScope.() -> Unit = {
        // OpenClaw 模式下不展示 ETA 的新建对话/朗读/工具入口，避免误操作落到 ETA 会话
        if (isHome && !openClawHome) {
            TopBarOverflowMenu(
                onNewConversation = onNewConversation,
                onOpenTerminal = onOpenTerminal,
                onLaunchKimiWeb = onLaunchKimiWeb,
                kimiWebLabel = kimiWebLabel,
                canStopKimiWeb = canStopKimiWeb,
                onStopKimiWeb = onStopKimiWeb,
                onRefreshKimiWeb = onRefreshKimiWeb,
                onOpenBrowser = onOpenBrowser,
            )
        }
        // OpenClaw 页：同一位置放「切换 agent / 会话」按钮
        if (isHome && openClawHome) openClawMenu?.invoke()
    }

    // OpenClaw 首页用 agent 名作标题，与模型对话页共用同一行顶栏，不再另起一行遮挡内容。
    val resolvedTitle = when {
        isHome && !homeTitle.isNullOrBlank() -> homeTitle
        // 模型对话首页顶端中间显示应用名；角色对话左侧已有角色身份，不再重复
        isHome && !openClawHome && homeCharacter == null -> "EtaX"
        else -> titleForRoute(route)
    }

    // 首页聊天舞台与终端这类整屏工具页没有可驱动折叠的滚动列表，大标题会一直展开、
    // 白占一屏顶部；它们使用紧凑标题，其余二级内容页统一使用可折叠大标题。
    val compactTitle = isHome || route is AppRoute.Terminal
    if (compactTitle) {
        SmallTopAppBar(
            title = resolvedTitle,
            color = color,
            scrollBehavior = scrollBehavior,
            navigationIcon = navigationIcon,
            actions = actions,
        )
    } else {
        AdaptiveTopAppBar(
            title = resolvedTitle,
            color = color,
            scrollBehavior = scrollBehavior,
            navigationIcon = navigationIcon,
            actions = actions,
        )
    }
}

private val TopBarMenuIconSize = 20.dp

/**
 * 首页顶栏溢出菜单。WindowListPopup 以父布局为锚点，因此与触发按钮包在同一个 Box 中，
 * 弹层从按钮下方右对齐展开。
 */
@Composable
private fun TopBarOverflowMenu(
    onNewConversation: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val speechSettings by remember { SpeechSettingsRepository.settingsFlow() }.collectAsState(initial = null)
    val hasSpeechPlayback = speechSettings?.let { it.tts != TtsProvider.NONE } == true
    val autoReadEnabled = speechSettings?.chatAutoSpeak == true
    Box {
        IconButton(onClick = { onRefreshKimiWeb(); showMenu = true }) {
            Icon(
                imageVector = Icons.Outlined.MoreHoriz,
                modifier = Modifier.size(24.dp),
                contentDescription = stringResource(R.string.action_more),
            )
        }
        WindowListPopup(
            show = showMenu,
            alignment = PopupPositionProvider.Align.End,
            onDismissRequest = { showMenu = false },
        ) {
            val newConversationText = stringResource(R.string.action_new_conversation)
            val openTerminalText = stringResource(R.string.action_open_terminal)
            val launchKimiWebText = kimiWebLabel
            val stopKimiWebText = stringResource(R.string.capability_kimi_stop)
            val openBrowserText = stringResource(R.string.action_open_browser)
            val autoReadText = stringResource(
                if (autoReadEnabled) R.string.speech_disable_auto_read else R.string.speech_enable_auto_read,
            )
            val menuItems = remember(
                newConversationText,
                openTerminalText,
                launchKimiWebText,
                openBrowserText,
                stopKimiWebText,
                canStopKimiWeb,
                autoReadText,
                hasSpeechPlayback,
                autoReadEnabled,
            ) {
                listOf(
                    DropdownItem(
                        text = newConversationText,
                        icon = { modifier ->
                            Icon(
                                imageVector = Icons.Rounded.AddComment,
                                contentDescription = null,
                                modifier = modifier.size(TopBarMenuIconSize),
                            )
                        },
                    ),
                    DropdownItem(
                        text = openTerminalText,
                        icon = { modifier ->
                            Icon(
                                imageVector = Icons.Rounded.Terminal,
                                contentDescription = null,
                                modifier = modifier.size(TopBarMenuIconSize),
                            )
                        },
                    ),
                    DropdownItem(
                        text = launchKimiWebText,
                        icon = { modifier ->
                            Icon(
                                painter = painterResource(R.drawable.ic_kimi_code),
                                contentDescription = null,
                                modifier = modifier.size(TopBarMenuIconSize),
                            )
                        },
                    ),
                    DropdownItem(
                        text = openBrowserText,
                        icon = { modifier ->
                            Icon(
                                imageVector = Icons.Rounded.Language,
                                contentDescription = null,
                                modifier = modifier.size(TopBarMenuIconSize),
                            )
                        },
                    ),
                ) + (if (canStopKimiWeb) {
                    listOf(
                        DropdownItem(
                            text = stopKimiWebText,
                            icon = { modifier ->
                                Icon(
                                    imageVector = Icons.Rounded.Stop,
                                    contentDescription = null,
                                    modifier = modifier.size(TopBarMenuIconSize),
                                )
                            },
                        ),
                    )
                } else {
                    emptyList()
                }) + if (hasSpeechPlayback) {
                    listOf(
                        DropdownItem(
                            text = autoReadText,
                            icon = { modifier ->
                                Icon(
                                    imageVector = if (autoReadEnabled) Icons.AutoMirrored.Rounded.VolumeUp
                                        else Icons.AutoMirrored.Rounded.VolumeOff,
                                    contentDescription = null,
                                    modifier = modifier.size(TopBarMenuIconSize),
                                )
                            },
                        ),
                    )
                } else emptyList()
            }
            ListPopupColumn {
                menuItems.forEachIndexed { index, item ->
                    DropdownImpl(
                        item = item,
                        optionSize = menuItems.size,
                        isSelected = false,
                        index = index,
                        onSelectedIndexChange = {
                            showMenu = false
                            if (hasSpeechPlayback && index == menuItems.lastIndex) {
                                speechSettings?.let { current ->
                                    scope.launch {
                                        SpeechSettingsRepository.save(current.copy(chatAutoSpeak = !current.chatAutoSpeak))
                                    }
                                }
                            } else when (index) {
                                0 -> onNewConversation()
                                1 -> onOpenTerminal()
                                2 -> onLaunchKimiWeb()
                                3 -> onOpenBrowser()
                                4 -> onStopKimiWeb()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun titleForRoute(route: AppRoute?): String = when (route) {
    is AppRoute.Home, is AppRoute.Browser -> ""
    is AppRoute.OpenClaw -> "OpenClaw"
    is AppRoute.VirtualScreen -> "免 Root 虚拟屏"
    is AppRoute.Terminal -> stringResource(R.string.route_terminal)
    is AppRoute.Tools -> stringResource(R.string.route_tools)
    is AppRoute.WebToolsSettings -> stringResource(R.string.web_tools_settings_title)
    is AppRoute.Skills -> stringResource(R.string.route_skills)
    is AppRoute.Subagents -> "Subagent"
    is AppRoute.Characters -> "角色"
    is AppRoute.CharacterDetail -> "角色详情"
    is AppRoute.CharacterEditor -> "编辑角色"
    is AppRoute.CharacterPersona -> "我的人设"
    is AppRoute.CharacterMemory -> "剧情记忆"
    is AppRoute.Permissions -> stringResource(R.string.route_permissions)
    is AppRoute.SystemEnhance -> stringResource(R.string.route_system_enhancements)
    is AppRoute.Settings -> stringResource(R.string.route_settings)
    is AppRoute.SpeechSettings -> stringResource(R.string.speech_settings_title)
    is AppRoute.SpeechRecognition -> stringResource(R.string.speech_recognition_title)
    is AppRoute.SpeechSynthesis -> stringResource(R.string.speech_synthesis_title)
    is AppRoute.SpeechOss -> stringResource(R.string.speech_oss_title)
    is AppRoute.AppearanceSettings -> stringResource(R.string.appearance_title)
    is AppRoute.DataBackup -> stringResource(R.string.data_backup_title)
    is AppRoute.Memory -> stringResource(R.string.route_memory)
    is AppRoute.LinuxEnvironment -> stringResource(R.string.route_linux_environment)
    is AppRoute.Workspace -> stringResource(R.string.capability_workspace)
    is AppRoute.SharedFolders -> stringResource(R.string.route_shared_folders)
    is AppRoute.LinuxFiles -> stringResource(R.string.route_linux_files)
    is AppRoute.ModelProviders -> stringResource(R.string.route_model_providers)
    is AppRoute.CommunityCatalog -> "从 models.dev 添加"
    is AppRoute.CommunityCatalogProvider -> "选择模型"
    is AppRoute.McpServers -> stringResource(R.string.route_mcp_servers)
    is AppRoute.McpServerDetail -> stringResource(R.string.route_mcp_server_detail)
    is AppRoute.ModelProviderDetail -> stringResource(R.string.route_provider_details)
    is AppRoute.ModelProviderNew -> stringResource(R.string.route_new_provider)
    null -> stringResource(R.string.app_name)
}

@Composable
private fun CharacterTopBarIdentity(character: RoleplayChatUi, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CharacterAvatar(
            name = character.characterName,
            avatarPath = character.avatarPath,
            size = 32.dp,
            shape = CircleShape,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = character.characterName,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp),
        )
    }
}
