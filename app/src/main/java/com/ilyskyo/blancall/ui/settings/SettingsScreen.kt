// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.settings

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.ilyskyo.blancall.ui.theme.isBlancallDark
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.ilyskyo.blancall.ui.common.AppIcon
import com.ilyskyo.blancall.ui.common.AppLocale

import com.ilyskyo.blancall.ui.common.AnimatedOverlayPresence
import com.ilyskyo.blancall.ui.common.AppIconKind
import com.ilyskyo.blancall.ui.common.BlancallAlertDialog
import com.ilyskyo.blancall.ui.common.GlassDropdownMenu
import com.ilyskyo.blancall.ui.common.GlassMenuItem
import com.ilyskyo.blancall.ui.common.GlassButton
import com.ilyskyo.blancall.ui.common.GlassCard
import com.ilyskyo.blancall.ui.common.GlassSwitch
import com.ilyskyo.blancall.ui.common.Motion
import com.ilyskyo.blancall.ui.common.MotionFade
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.compose.ui.draw.clip
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ilyskyo.blancall.MainActivity
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.algorithm.ReviewTemplate
import com.ilyskyo.blancall.data.ai.AiConfigStore
import com.ilyskyo.blancall.data.backup.BackupManager
import com.ilyskyo.blancall.data.repository.InkStore
import com.ilyskyo.blancall.data.repository.RecordRepository
import com.ilyskyo.blancall.data.model.PracticeRecord
import com.ilyskyo.blancall.data.backup.LearningDataReloader
import com.ilyskyo.blancall.notification.ReminderWorker
import com.ilyskyo.blancall.ui.common.BackButton
import com.ilyskyo.blancall.ui.common.PressTier
import com.ilyskyo.blancall.ui.common.pressClick
import com.ilyskyo.blancall.ui.theme.AccentPresets
import com.ilyskyo.blancall.ui.theme.AppPrefs
import com.ilyskyo.blancall.ui.theme.ReminderFrequency
import com.ilyskyo.blancall.ui.theme.ReminderPrefs
import com.ilyskyo.blancall.ui.theme.ThemeManager
import com.ilyskyo.blancall.ui.theme.ThemeMode
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(navController: NavController) {
    val themeMode by ThemeManager.themeMode.collectAsState()
    // 首页副标题编辑弹窗（首页品牌栏收起时也可从这里修改）
    var showSubtitleDialog by remember { mutableStateOf(false) }
    val homeSubtitle by AppPrefs.subtitleFlow.collectAsState()

    // ── 学习数据一键备份/恢复 ──
    val backupContext = LocalContext.current
    val backupResources = LocalResources.current
    val backupScope = rememberCoroutineScope()
    // 待确认的导入文件（选定后先弹覆盖确认，确认才落盘）
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    // 导入成功后的重启提示；非空即弹窗
    var backupRestartMessage by remember { mutableStateOf<String?>(null) }
    // ── 练习记录体积可见性（审计 2.2-15）──
    // records.json 是无界追加的学习档案：用户看不到它有多大，也就无从判断该不该归档。
    // 这里只做「可见性 + 超预算提示」，不做任何静默淘汰 —— 删用户的学习记录必须由用户自己发起。
    val recordsFile = remember { File(backupContext.filesDir, "records.json") }
    // null = 还没有记录文件（新装/刚清过）：这时不该显示「当前 0 B」，
    // 那会被读成「有记录但很小」，而事实是「还没有记录」。
    var recordsSizeBytes by remember { mutableStateOf<Long?>(null) }
    // 刷新触发器必须是**数据版本**，不能借某条 UI 文案当 key：早先挂在 backupRestartMessage 上，
    // 语义是错位的（改文案会触发重算、数据变了不一定触发），而且练完一轮回到设置页时体积根本不更新。
    // 导入备份会整体替换文件，所以导入成功时 +1 就够覆盖真正的变化。
    var recordsVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(recordsVersion) {
        recordsSizeBytes = withContext(Dispatchers.IO) {
            if (recordsFile.exists()) recordsFile.length() else null
        }
    }

    // ── 赞赏区（会话级隐藏 + 保存赞赏码）──
    val donationContext = LocalContext.current
    var showDonationMenu by remember { mutableStateOf(false) }
    // 保存完成后的结果目录；非空即弹「保存成功」弹窗
    var donationSavedDir by remember { mutableStateOf<String?>(null) }
    // 保存动作的触发计数：权限回调与直接保存都汇到这一条路径，避免两处逻辑分叉
    var donationSaveTrigger by remember { mutableIntStateOf(0) }
    val donationSavePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) donationSaveTrigger++
        else Toast.makeText(
            donationContext, R.string.donation_save_failed, Toast.LENGTH_SHORT
        ).show()
    }
    LaunchedEffect(donationSaveTrigger) {
        if (donationSaveTrigger == 0) return@LaunchedEffect
        val dir = withContext(Dispatchers.IO) { saveDonationQrToGallery(donationContext) }
        if (dir != null) donationSavedDir = dir
        else Toast.makeText(
            donationContext, R.string.donation_save_failed, Toast.LENGTH_SHORT
        ).show()
    }
    // 发起保存。Android 9 及以下写公共相册需要存储权限，先申请；Android 10+ 走 MediaStore 无需权限。
    val startSaveDonationQr: () -> Unit = {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                donationContext, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            donationSavePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            donationSaveTrigger++
        }
    }

    val backupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            backupScope.launch {
                val failure = withContext(Dispatchers.IO) {
                    runCatching {
                        backupContext.contentResolver.openOutputStream(uri)?.use { out ->
                            BackupManager.exportZip(backupContext.filesDir, out)
                        } ?: throw BackupManager.BackupError.CannotWrite()
                    }.exceptionOrNull()
                }
                Toast.makeText(
                    backupContext,
                    if (failure == null) backupResources.getString(R.string.backup_export_done)
                    else backupResources.getString(
                        R.string.backup_export_failed, backupErrorMessage(backupContext, failure)
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    // ── 「导出并归档旧记录」（审计 2.2-15）：确认 → 选文件 → 导出成功后才裁剪 ──
    // 两步之间任何一步失败都不删数据：宁可归档不成，不可静默淘汰用户履历。
    val archiveRecordRepo = remember { RecordRepository.getInstance(recordsFile.absolutePath) }
    var archiveDialogVisible by remember { mutableStateOf(false) }
    var archiveCandidateCount by remember { mutableIntStateOf(0) }
    var pendingArchive by remember { mutableStateOf<List<PracticeRecord>?>(null) }
    val recordsArchiveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val toArchive = pendingArchive
        pendingArchive = null
        if (uri == null || toArchive == null) return@rememberLauncherForActivityResult
        backupScope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching {
                    backupContext.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(archiveRecordRepo.archiveJson(toArchive).toByteArray(Charsets.UTF_8))
                    } ?: throw BackupManager.BackupError.CannotWrite()
                    // 导出成功后才裁剪；上面任何一步失败都会走 catch，一条都不删
                    archiveRecordRepo.removeByIds(toArchive.map { it.id }.toSet())
                }.exceptionOrNull()
            }
            if (failure == null) {
                recordsVersion++
                Toast.makeText(
                    backupContext,
                    backupResources.getString(com.ilyskyo.blancall.R.string.data_archive_done, toArchive.size),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    backupContext,
                    backupResources.getString(com.ilyskyo.blancall.R.string.data_archive_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val backupImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            // 底部系统导航条让位（与导入/阅读等子页面一致）：缺少它时设置页
            // 滚动内容会伸到导航条区域之下，底部露出一段白区且内容被截断。
            .navigationBarsPadding()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
        // 顶部返回按钮 + 标题
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackButton(onClick = { navController.popBackStack() })
            Spacer(Modifier.width(12.dp))
            Text(stringResource(com.ilyskyo.blancall.R.string.settings), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        }

        Spacer(Modifier.height(24.dp))

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 外观 ──
            Text(stringResource(com.ilyskyo.blancall.R.string.appearance), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.padding(4.dp)) {
                    // 浅色米黄底色开关（深色模式始终纯黑，不受此开关影响）
                    val lightBeige by AppPrefs.lightBeigeBackgroundFlow.collectAsState()

                    // 主题模式：固定 3 行列表（与米黄底色开关无关）
                    OptionRow(
                        label = stringResource(com.ilyskyo.blancall.R.string.theme_light),
                        selected = themeMode == ThemeMode.LIGHT,
                        onClick = { ThemeManager.setThemeMode(ThemeMode.LIGHT) }
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    OptionRow(
                        label = stringResource(com.ilyskyo.blancall.R.string.theme_dark),
                        selected = themeMode == ThemeMode.DARK,
                        onClick = { ThemeManager.setThemeMode(ThemeMode.DARK) }
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    OptionRow(
                        label = stringResource(com.ilyskyo.blancall.R.string.theme_system),
                        selected = themeMode == ThemeMode.SYSTEM,
                        onClick = { ThemeManager.setThemeMode(ThemeMode.SYSTEM) }
                    )

                    HorizontalDivider(Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    // ── 界面语言 ──
                    // 语言名用各自语言的写法（见 AppLocale 注释）：切换入口若跟着当前语言走，
                    // 换错一次就找不到回来的那一项。「跟随系统」是模式不是语言，跟着界面走。
                    // 13 以上走框架 LocaleManager，13 以下走本地 tag + attachBaseContext 注入，
                    // 两条通道都能切，所以不按版本设门。
                    // 七个选项不再整列摊在设置页里（那会占掉大半屏）：
                    // 点这一行就地弹出选项卡，选完立刻收起。
                    val localeContext = LocalContext.current
                    val currentLocale = AppLocale.current(localeContext)
                    var showLocaleMenu by remember { mutableStateOf(false) }
                    val localeLabel: @Composable (AppLocale) -> String = { choice: AppLocale ->
                        if (choice == AppLocale.FollowSystem)
                            stringResource(com.ilyskyo.blancall.R.string.theme_system) else choice.nativeName
                    }
                    Box {
                        Surface(
                            onClick = { showLocaleMenu = true },
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(com.ilyskyo.blancall.R.string.ui_language),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface)
                                    Spacer(Modifier.height(2.dp))
                                    Text(stringResource(com.ilyskyo.blancall.R.string.ui_language_desc),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(localeLabel(currentLocale),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        GlassDropdownMenu(
                            expanded = showLocaleMenu,
                            onDismissRequest = { showLocaleMenu = false }
                        ) {
                            AppLocale.entries.forEach { choice ->
                                val picked = currentLocale == choice
                                GlassMenuItem(
                                    onClick = {
                                        showLocaleMenu = false
                                        // 13+ 由框架重建，13 以下由 AppLocale.apply 自己 recreate
                                        AppLocale.apply(localeContext, choice)
                                    },
                                    label = {
                                        Text(localeLabel(choice),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (picked) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface)
                                    },
                                    trailing = {
                                        if (picked) AppIcon(
                                            kind = AppIconKind.Check,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                )
                            }
                        }
                    }

                    HorizontalDivider(Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(com.ilyskyo.blancall.R.string.beige_background), style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(stringResource(com.ilyskyo.blancall.R.string.beige_background_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        GlassSwitch(
                            checked = lightBeige,
                            onCheckedChange = { AppPrefs.lightBeigeBackgroundEnabled = it }
                        )
                    }

                    HorizontalDivider(Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    // ── 导航栏液态玻璃开关（关闭后底部导航栏回退纯色底）──
                    val navGlass by AppPrefs.navLiquidGlassFlow.collectAsState()
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(com.ilyskyo.blancall.R.string.nav_glass), style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(stringResource(com.ilyskyo.blancall.R.string.nav_glass_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        GlassSwitch(
                            checked = navGlass,
                            onCheckedChange = { AppPrefs.navLiquidGlassEnabled = it }
                        )
                    }

                    HorizontalDivider(Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    // ── 触感强度（本 app 自己的四档）──
                    // 不去镜像系统的全局触感开关：直振本来就是为绕开「定制 ROM 把触感
                    // 弱化到无感」而写的（见 ui/common/Haptics.kt），镜像开关等于把那个
                    // 老问题重新请回来。想关，就用这里的「关」。
                    val hapticLevel by AppPrefs.hapticLevelFlow.collectAsState()
                    var showHapticMenu by remember { mutableStateOf(false) }
                    // 存进偏好的是字符串档名；认不出来时按「标准」显示，不给空标签
                    val hapticLabelRes = hapticLevelChoices
                        .firstOrNull { it.first == hapticLevel }?.second
                        ?: hapticLevelChoices[1].second
                    Box {
                        Surface(
                            onClick = { showHapticMenu = true },
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(com.ilyskyo.blancall.R.string.haptic_strength), style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface)
                                    Spacer(Modifier.height(2.dp))
                                    Text(stringResource(com.ilyskyo.blancall.R.string.haptic_strength_desc),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(stringResource(hapticLabelRes),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        GlassDropdownMenu(
                            expanded = showHapticMenu,
                            onDismissRequest = { showHapticMenu = false }
                        ) {
                            hapticLevelChoices.forEach { (value, labelId) ->
                                val picked = hapticLevel == value
                                GlassMenuItem(
                                    onClick = {
                                        showHapticMenu = false
                                        AppPrefs.setHapticLevel(value)
                                    },
                                    label = {
                                        Text(stringResource(labelId),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (picked) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface)
                                    },
                                    trailing = {
                                        if (picked) AppIcon(
                                            kind = AppIconKind.Check,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                )
                            }
                        }
                    }

                    HorizontalDivider(Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    // ── 首页副标题（点击编辑；首页品牌栏收起时也能从这里修改）──
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .pressClick { showSubtitleDialog = true }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(com.ilyskyo.blancall.R.string.home_subtitle), style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(stringResource(com.ilyskyo.blancall.R.string.home_subtitle_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(stringResource(com.ilyskyo.blancall.R.string.edit), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }

                    // 内置素材库已移至下方「拓展功能」分组
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 主题色 ──
            Text(stringResource(com.ilyskyo.blancall.R.string.theme_color), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                val accentIndex by AppPrefs.accentColorFlow.collectAsState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 根据系统深色模式选择 primaryDark/primaryLight，保证深色下对比度
                    val isDark = isBlancallDark()
                    AccentPresets.forEachIndexed { index, preset ->
                        val isSelected = index == accentIndex
                        val accentColor = if (isDark) preset.primaryDark else preset.primaryLight
                        Surface(
                            onClick = { AppPrefs.accentColorIndex = index },
                            shape = RoundedCornerShape(50),
                            color = if (isSelected) accentColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
                            modifier = Modifier.size(44.dp),
                            border = if (isSelected)
                                BorderStroke(2.5.dp, accentColor)
                            else
                                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    modifier = Modifier.size(24.dp),
                                    shape = RoundedCornerShape(50),
                                    color = accentColor,
                                    content = {}
                                )
                                if (isSelected) {
                                    AppIcon(
                                        kind = AppIconKind.Check,
                                        tint = accentColor,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 特性 ──
            Text(stringResource(com.ilyskyo.blancall.R.string.features), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                // 段落首行缩进开关（导入时给未缩进段落补两格；关闭后不再新增缩进）
                val autoIndentEnabled by AppPrefs.autoIndentEnabledFlow.collectAsState()
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(com.ilyskyo.blancall.R.string.indent_first_line), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(com.ilyskyo.blancall.R.string.indent_first_line_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 16.dp))
                    }
                    GlassSwitch(
                        checked = autoIndentEnabled,
                        onCheckedChange = { AppPrefs.autoIndentEnabled = it }
                    )
                }

                HorizontalDivider(
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )

                // 错题墨迹保留（错题回顾）：学习统计的练习历史中回看答错空的手写笔迹
                val keepInk by AppPrefs.keepInkEnabledFlow.collectAsState()
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(com.ilyskyo.blancall.R.string.keep_wrong_ink), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface)
                        Text(stringResource(com.ilyskyo.blancall.R.string.keep_wrong_ink_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 16.dp))
                    }
                    GlassSwitch(
                        checked = keepInk,
                        onCheckedChange = { AppPrefs.keepInkEnabled = it }
                    )
                }

                HorizontalDivider(
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )

                // 清空已存墨迹（开关只拦新增，已存数据需手动清）
                val inkContext = LocalContext.current
                val inkResources = LocalResources.current
                val inkScope = rememberCoroutineScope()
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(com.ilyskyo.blancall.R.string.clear_saved_ink), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        inkScope.launch {
                            withContext(Dispatchers.IO) {
                                InkStore.getInstance(
                                    inkContext.filesDir.resolve("ink").absolutePath
                                ).clearAll()
                            }
                            Toast.makeText(
                                inkContext,
                                inkResources.getString(com.ilyskyo.blancall.R.string.ink_cleared_toast),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }) {
                        Text(stringResource(com.ilyskyo.blancall.R.string.clear))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── AI（独立分组：母项开关 + 子项设置，层级一目了然） ──
            Text("AI", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                // AI 开关（关闭后所有 AI 入口隐藏）
                val aiEnabled by AppPrefs.aiEnabledFlow.collectAsState()
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(com.ilyskyo.blancall.R.string.ai_enable), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                    GlassSwitch(
                        checked = aiEnabled,
                        onCheckedChange = { AppPrefs.aiEnabled = it }
                    )
                }

                // AI 配置管理（仅开启时显示）：多配置 + 开关式选择
                // 展开走词表：尺寸=弹簧、alpha=tween，减动效时才会整体坍缩成 snap
                AnimatedVisibility(
                    visible = aiEnabled,
                    enter = fadeIn(MotionFade.alpha(MotionFade.enter)) +
                        expandVertically(Motion.contentSize()),
                    exit = fadeOut(MotionFade.alpha(MotionFade.exit)) +
                        shrinkVertically(Motion.contentSize())
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        HorizontalDivider(
                            Modifier.padding(bottom = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )

                        // 子项统一收纳进嵌套玻璃面板（backdrop=false 避免双重模糊）：
                        // 半透明染色 + 1dp 细描边延续全 App 的玻璃语言，与「启用 AI 功能」母项形成从属层次
                        val isDark = isBlancallDark()
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            containerColor = if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh
                            else MaterialTheme.colorScheme.surface,
                            containerAlpha = if (isDark) 0.90f else 0.85f,
                            backdrop = false
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                                Text(stringResource(com.ilyskyo.blancall.R.string.ai_settings), style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 6.dp))

                                // ── 使用AI挖空（关闭后挖空走本地算法，采集页只采策略与段落）──
                                val useAiCloze by AppPrefs.useAiClozeFlow.collectAsState()
                                Row(
                                    Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(stringResource(com.ilyskyo.blancall.R.string.ai_cloze), style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface)
                                    GlassSwitch(
                                        checked = useAiCloze,
                                        onCheckedChange = { AppPrefs.useAiCloze = it }
                                    )
                                }

                                // ── AI 等价复核（Pro 旁路，默认关；只影响分析输入，不改判分）──
                                val aiEquivalenceReview by AppPrefs.aiEquivalenceReviewFlow.collectAsState()
                                Row(
                                    Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            stringResource(com.ilyskyo.blancall.R.string.ai_equivalence_review),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            stringResource(com.ilyskyo.blancall.R.string.ai_equivalence_review_desc),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    GlassSwitch(
                                        checked = aiEquivalenceReview,
                                        onCheckedChange = { AppPrefs.aiEquivalenceReview = it }
                                    )
                                }

                                // ── AI 对话配置入口（点击进入配置管理页） ──
                                val chatProfiles by AiConfigStore.chatProfilesFlow.collectAsState()
                                val activeChatId by AiConfigStore.activeChatIdFlow.collectAsState()
                                val activeChat = chatProfiles.find { it.id == activeChatId }
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .pressClick { navController.navigate("ai_config") }
                                        .padding(vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(com.ilyskyo.blancall.R.string.ai_chat_profile), style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface)
                                        Text(
                                            if (activeChat != null) stringResource(com.ilyskyo.blancall.R.string.profile_in_use, activeChat.name) else stringResource(com.ilyskyo.blancall.R.string.not_selected_yet),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (activeChat != null) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(stringResource(com.ilyskyo.blancall.R.string.manage_arrow), style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                                if (chatProfiles.isEmpty()) {
                                    OutlinedButton(
                                        onClick = { navController.navigate("ai_profile_edit/chat") },
                                        modifier = Modifier.fillMaxWidth().height(40.dp),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text(stringResource(com.ilyskyo.blancall.R.string.ai_tap_to_create), style = MaterialTheme.typography.labelMedium)
                                    }
                                }

                                Spacer(Modifier.height(8.dp))
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                )
                                // 保存与 AI 的对话开关（开启后在「我的文章」页显示历史入口）
                                val aiHistoryEnabled by AppPrefs.aiHistoryEnabledFlow.collectAsState()
                                Row(
                                    Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(com.ilyskyo.blancall.R.string.ai_save_chats), style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface)
                                        Text(stringResource(com.ilyskyo.blancall.R.string.ai_save_chats_desc),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(end = 16.dp))
                                    }
                                    GlassSwitch(
                                        checked = aiHistoryEnabled,
                                        onCheckedChange = { AppPrefs.aiHistoryEnabled = it }
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                )
                                // 联网搜索开关（开启后提问自动联网搜索核验）
                                val aiSearchEnabled by AppPrefs.aiSearchEnabledFlow.collectAsState()
                                Row(
                                    Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(com.ilyskyo.blancall.R.string.ai_web_search), style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface)
                                        Text(stringResource(com.ilyskyo.blancall.R.string.ai_web_search_desc),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    GlassSwitch(
                                        checked = aiSearchEnabled,
                                        onCheckedChange = { AppPrefs.aiSearchEnabled = it }
                                    )
                                }

                                // ── 联网搜索配置入口（点击进入配置管理页） ──
                                val searchProfiles by AiConfigStore.searchProfilesFlow.collectAsState()
                                val activeSearchId by AiConfigStore.activeSearchIdFlow.collectAsState()
                                val activeSearch = searchProfiles.find { it.id == activeSearchId }
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .pressClick { navController.navigate("ai_config") }
                                        .padding(vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(com.ilyskyo.blancall.R.string.ai_search_profile), style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface)
                                        Text(
                                            if (activeSearch != null) stringResource(com.ilyskyo.blancall.R.string.profile_in_use, activeSearch.name) else stringResource(com.ilyskyo.blancall.R.string.not_selected_yet),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (activeSearch != null) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(stringResource(com.ilyskyo.blancall.R.string.manage_arrow), style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                                if (searchProfiles.isEmpty()) {
                                    OutlinedButton(
                                        onClick = { navController.navigate("ai_profile_edit/search") },
                                        modifier = Modifier.fillMaxWidth().height(40.dp),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text(stringResource(com.ilyskyo.blancall.R.string.ai_tap_to_create), style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 复习 ──
            Text(stringResource(com.ilyskyo.blancall.R.string.memory_plan), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                val templateId by AppPrefs.reviewTemplateFlow.collectAsState()
                val templates = listOf(ReviewTemplate.SPRINT, ReviewTemplate.STANDARD, ReviewTemplate.DEEP)
                Column(Modifier.padding(4.dp)) {
                    templates.forEachIndexed { idx, template ->
                        val isSelected = template.id == templateId
                        OptionRow(
                            // B2-1/S3：计划名按 id 取资源（name 字段已从算法层移除）
                            label = when (template.id) {
                                "sprint" -> stringResource(com.ilyskyo.blancall.R.string.plan_name_sprint)
                                "deep" -> stringResource(com.ilyskyo.blancall.R.string.plan_name_deep)
                                else -> stringResource(com.ilyskyo.blancall.R.string.plan_name_standard)
                            },
                            description = when (template.id) {
                                "sprint" -> stringResource(com.ilyskyo.blancall.R.string.plan_aggressive)
                                "deep" -> stringResource(com.ilyskyo.blancall.R.string.plan_relaxed)
                                else -> stringResource(com.ilyskyo.blancall.R.string.plan_balanced)
                            },
                            selected = isSelected,
                            onClick = { AppPrefs.reviewTemplateId = template.id }
                        )
                        if (idx < templates.size - 1) {
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 学习提醒 ──
            Text(stringResource(com.ilyskyo.blancall.R.string.study_reminder), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)

            ReminderSettingsCard()

            Spacer(Modifier.height(8.dp))

            // ── 帮助 ──
            Text(stringResource(com.ilyskyo.blancall.R.string.help_section), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Surface(
                    onClick = { navController.navigate("onboarding") },
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🎓", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(com.ilyskyo.blancall.R.string.guided_tour), style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                        Text("→", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )
                Surface(
                    // 一次性引导看过后就再也不会弹了，必须有出口能重来 ——
                    // 否则用户误关一次，等于永久失去「长按可以做什么」这条信息
                    onClick = { AppPrefs.resetCoachMarks() },
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(
                                kind = AppIconKind.Refresh,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    stringResource(com.ilyskyo.blancall.R.string.coach_reset),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    stringResource(com.ilyskyo.blancall.R.string.coach_reset_desc),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(
                    Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )
                Surface(
                    onClick = { navController.navigate("help") },
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("📖", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(com.ilyskyo.blancall.R.string.help_about), style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                        Text("→", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 内容管理 ──
            Text(stringResource(com.ilyskyo.blancall.R.string.content_management), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Surface(
                    onClick = { navController.navigate("tag_manager") },
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(
                                kind = AppIconKind.Tag,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(stringResource(com.ilyskyo.blancall.R.string.article_tags), style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface)
                                Text(stringResource(com.ilyskyo.blancall.R.string.article_tags_desc),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text("→", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 数据（学习数据整机备份，换机/重装可一键迁移） ──
            Text(stringResource(com.ilyskyo.blancall.R.string.data_section), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.padding(4.dp)) {
                    // 体积可见性：只报数 + 超预算时给一句提示，不做任何删除动作。
                    // 没有记录文件时整行连同分隔线一起不出现 —— 显示「当前 0 B」会被读成
                    // 「有记录但很小」，而事实是「还没有记录」。
                    recordsSizeBytes?.let { size ->
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(com.ilyskyo.blancall.R.string.data_records_size_title),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    stringResource(com.ilyskyo.blancall.R.string.data_records_size_value,
                                        formatFileSize(size)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (size >= RECORDS_SIZE_BUDGET_BYTES) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(stringResource(com.ilyskyo.blancall.R.string.data_records_size_warn),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary)
                                }
                            }
                            // 归档出口（审计 2.2-15）：只在记录多到能归档时才做动作；
                            // 数量不足时按钮仍在（教用户有这个能力），点击给一句「暂无需归档」
                            TextButton(onClick = {
                                backupScope.launch {
                                    val total = withContext(Dispatchers.IO) {
                                        archiveRecordRepo.awaitLoaded()
                                        archiveRecordRepo.records.value.size
                                    }
                                    archiveCandidateCount =
                                        (total - RecordRepository.ARCHIVE_KEEP_RECENT).coerceAtLeast(0)
                                    if (archiveCandidateCount <= 0) {
                                        Toast.makeText(
                                            backupContext,
                                            backupResources.getString(com.ilyskyo.blancall.R.string.data_archive_none),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        archiveDialogVisible = true
                                    }
                                }
                            }) {
                                Text(stringResource(com.ilyskyo.blancall.R.string.data_archive_action))
                            }
                        }

                        HorizontalDivider(
                            Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                    }

                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(com.ilyskyo.blancall.R.string.export_backup), style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(stringResource(com.ilyskyo.blancall.R.string.export_backup_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = {
                            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                            backupExportLauncher.launch("blancall_backup_$stamp.zip")
                        }) { Text(stringResource(com.ilyskyo.blancall.R.string.export_action)) }
                    }

                    HorizontalDivider(
                        Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(com.ilyskyo.blancall.R.string.import_backup), style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(stringResource(com.ilyskyo.blancall.R.string.import_backup_desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = {
                            // 放开 MIME 过滤：部分文件管理器返回的备份类型不固定，
                            // 内容合法性由导入时的备份清单校验兜底
                            backupImportLauncher.launch(arrayOf("*/*"))
                        }) { Text(stringResource(com.ilyskyo.blancall.R.string.import_action)) }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 拓展功能 ──
            Text(stringResource(com.ilyskyo.blancall.R.string.extensions), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                // ── 内置素材库：多库各自可启用，未来可继续追加 ──
                Text(stringResource(com.ilyskyo.blancall.R.string.built_in_library), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                val enabledSet by AppPrefs.builtInLibraryKeysFlow.collectAsState()
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(com.ilyskyo.blancall.R.string.western_thoughts), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                    GlassSwitch(
                        checked = "western" in enabledSet,
                        onCheckedChange = { AppPrefs.setLibraryEnabled("western", it) }
                    )
                }
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(com.ilyskyo.blancall.R.string.library_gaokao), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                    GlassSwitch(
                        checked = "gaokao" in enabledSet,
                        onCheckedChange = { AppPrefs.setLibraryEnabled("gaokao", it) }
                    )
                }
                // 预留更多库：在此 Row 之后追加即可（如化学句子大全）

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                )
                // 内容包：用户自己的 zip（txt/md + 清单），可浏览、可一键全文导入
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.content_pack_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.content_pack_settings_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    GlassButton(
                        onClick = { navController.navigate("content_packs") },
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.open),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                // 数据互换：人类可读 JSON 导出 + CSV 回灌
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.interchange_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.interchange_settings_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    GlassButton(
                        onClick = { navController.navigate("data_interchange") },
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text(
                            stringResource(com.ilyskyo.blancall.R.string.open),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 关于 ──
            Text(stringResource(com.ilyskyo.blancall.R.string.about_section), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Blancall", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(2.dp))
                    Text("Fill the blank, recall the knowledge.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(com.ilyskyo.blancall.R.string.app_tagline),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(com.ilyskyo.blancall.R.string.app_features_line),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 赞赏区 ──
            // 整块可被「隐藏该区域」收起。隐藏态是**会话级**的（SettingsSessionState，
            // 只在内存里，不落盘）：本次使用不再出现，软件重新启动后自动恢复。
            if (!SettingsSessionState.donationHidden) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(com.ilyskyo.blancall.R.string.donation_title), style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary)
                    // 圆圈按钮：右边缘与下方卡片的右边框对齐，提示这一行可以点
                    Box {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                                    CircleShape
                                )
                                .pressClick(tier = PressTier.Icon) { showDonationMenu = true },
                            contentAlignment = Alignment.Center
                        ) {
                            AppIcon(
                                kind = AppIconKind.MoreVert,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                                contentDescription = stringResource(com.ilyskyo.blancall.R.string.more)
                            )
                        }
                        GlassDropdownMenu(
                            expanded = showDonationMenu,
                            onDismissRequest = { showDonationMenu = false }
                        ) {
                            GlassMenuItem(
                                onClick = {
                                    showDonationMenu = false
                                    SettingsSessionState.donationHidden = true
                                },
                                label = {
                                    Column {
                                        Text(stringResource(com.ilyskyo.blancall.R.string.donation_hide),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface)
                                        Spacer(Modifier.height(2.dp))
                                        Text(stringResource(com.ilyskyo.blancall.R.string.donation_hide_hint),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            )
                            GlassMenuItem(
                                onClick = {
                                    showDonationMenu = false
                                    startSaveDonationQr()
                                },
                                label = {
                                    Text(stringResource(com.ilyskyo.blancall.R.string.donation_save_qr),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface)
                                }
                            )
                        }
                    }
                }

                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(stringResource(com.ilyskyo.blancall.R.string.donation_ask),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(com.ilyskyo.blancall.R.string.donation_motive),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Image(
                            painter = painterResource(id = R.drawable.appreciation_qr),
                            contentDescription = stringResource(com.ilyskyo.blancall.R.string.donation_qr),
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .aspectRatio(1f),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }

            // ── 赞赏码保存完成弹窗 ──
            AnimatedOverlayPresence(target = donationSavedDir) { dir ->
                BlancallAlertDialog(
                    onDismissRequest = { donationSavedDir = null },
                    title = {
                        Text(stringResource(com.ilyskyo.blancall.R.string.donation_saved_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface)
                    },
                    text = {
                        Text(stringResource(com.ilyskyo.blancall.R.string.donation_saved_body, dir),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            donationSavedDir = null
                            launchWeChat(donationContext)
                        }) { Text(stringResource(com.ilyskyo.blancall.R.string.donation_open_wechat)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { donationSavedDir = null }) {
                            Text(stringResource(com.ilyskyo.blancall.R.string.done))
                        }
                    }
                )
            }

            // ── 版本号 ──
            val context = LocalContext.current
            val versionName = remember {
                runCatching {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull() ?: ""
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Blancall v$versionName",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }

    // ── 首页副标题编辑弹窗 ──
    AnimatedOverlayPresence(visible = showSubtitleDialog) {
        var editText by remember(homeSubtitle) { mutableStateOf(homeSubtitle) }
        BlancallAlertDialog(
            onDismissRequest = { showSubtitleDialog = false },
            title = { Text(stringResource(com.ilyskyo.blancall.R.string.edit_home_subtitle)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = editText,
                        // 默认副标题 38 字 > 旧 30 字限制，编辑会被拦截；取消限制
                        onValueChange = { editText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(com.ilyskyo.blancall.R.string.custom_subtitle)) },
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        // 直接保存（允许清空：清空即移除副标题）
                        AppPrefs.subtitle = editText
                        showSubtitleDialog = false
                    },
                    shape = RoundedCornerShape(14.dp)
                ) { Text(stringResource(com.ilyskyo.blancall.R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showSubtitleDialog = false }) {
                    Text(stringResource(com.ilyskyo.blancall.R.string.cancel))
                }
            }
        )
    }

    // ── 归档确认弹窗：确认后才导出并裁剪 ──
    AnimatedOverlayPresence(visible = archiveDialogVisible) {
        BlancallAlertDialog(
            onDismissRequest = { archiveDialogVisible = false },
            title = { Text(stringResource(com.ilyskyo.blancall.R.string.data_archive_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        com.ilyskyo.blancall.R.string.data_archive_confirm_body,
                        archiveCandidateCount, RecordRepository.ARCHIVE_KEEP_RECENT
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        archiveDialogVisible = false
                        backupScope.launch {
                            val olds = withContext(Dispatchers.IO) {
                                archiveRecordRepo.oldestRecords(archiveCandidateCount)
                            }
                            if (olds.isEmpty()) return@launch
                            pendingArchive = olds
                            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                            recordsArchiveLauncher.launch("blancall_records_archive_$stamp.json")
                        }
                    },
                    shape = RoundedCornerShape(14.dp)
                ) { Text(stringResource(com.ilyskyo.blancall.R.string.data_archive_action)) }
            },
            dismissButton = {
                TextButton(onClick = { archiveDialogVisible = false }) {
                    Text(stringResource(com.ilyskyo.blancall.R.string.cancel))
                }
            }
        )
    }

    // ── 导入备份确认弹窗：确认后才覆盖当前学习数据 ──
    AnimatedOverlayPresence(target = pendingImportUri) { importUri ->
        BlancallAlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text(stringResource(com.ilyskyo.blancall.R.string.import_backup_title)) },
            text = {
                Text(stringResource(com.ilyskyo.blancall.R.string.import_backup_warning))
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingImportUri = null
                        // 导入是破坏性整库替换 + 全量重载：不能被「离开设置页/旋转屏幕」取消，
                        // 否则文件已换、内存未重载，下一次任意保存会把恢复的数据覆盖回旧库。
                        // NonCancellable 让这次 job 不受 rememberCoroutineScope 生命周期影响。
                        backupScope.launch(NonCancellable) {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    // 先落到缓存临时包：ZipFile 需随机读取，且避免边读 SAF 边写盘
                                    val temp = File(backupContext.cacheDir, "backup_import.zip")
                                    backupContext.contentResolver.openInputStream(importUri)?.use { input ->
                                        temp.outputStream().use { output -> input.copyTo(output) }
                                    } ?: throw BackupManager.BackupError.CannotRead()
                                    BackupManager.restoreZip(temp, backupContext.filesDir).also { temp.delete() }
                                }
                            }
                            result.onSuccess {
                                recordsVersion++   // 文件被整体替换 → 让体积那一行重算
                                // 全量重载各 Store 的内存镜像（B1-3）：漏一个就会在下次写盘时
                                // 用旧内存覆盖刚恢复的数据 —— 清单完整性由 LearningDataReloaderTest 钉死
                                val reloadFailures = withContext(Dispatchers.IO) {
                                    LearningDataReloader.reloadAll(backupContext)
                                }
                                val doneText = if (it.removed > 0) {
                                    backupResources.getString(
                                        R.string.backup_restore_done_cleaned, it.restored, it.removed
                                    )
                                } else {
                                    backupResources.getString(R.string.backup_restore_done, it.restored)
                                }
                                backupRestartMessage = doneText +
                                    backupResources.getString(R.string.backup_restore_applied_hint) +
                                    if (reloadFailures > 0) {
                                        // 个别 Store 重载失败：数据已在盘上、重启后必然生效 —— 如实说明
                                        backupResources.getString(
                                            R.string.backup_restore_reload_failed, reloadFailures
                                        )
                                    } else ""
                            }.onFailure {
                                Toast.makeText(
                                    backupContext,
                                    backupResources.getString(
                                        R.string.backup_import_failed,
                                        backupErrorMessage(backupContext, it)
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    shape = RoundedCornerShape(14.dp)
                ) { Text(stringResource(com.ilyskyo.blancall.R.string.import_action)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportUri = null }) { Text(stringResource(com.ilyskyo.blancall.R.string.cancel)) }
            }
        )
    }

    // ── 导入成功提示：各 Store 已全量重载（B1-3），无需重启 ──
    AnimatedOverlayPresence(target = backupRestartMessage) { message ->
        BlancallAlertDialog(
            onDismissRequest = { backupRestartMessage = null },
            title = { Text(stringResource(com.ilyskyo.blancall.R.string.import_success)) },
            text = { Text(message) },
            confirmButton = {
                Button(
                    onClick = { backupRestartMessage = null },
                    shape = RoundedCornerShape(14.dp)
                ) { Text(stringResource(com.ilyskyo.blancall.R.string.done)) }
            }
        )
    }
    }
}

/**
 * 练习记录体积预算：超过就在设置页给一句「建议导出备份」。
 *
 * 为什么是 20 MB：`records.json` 是全量常驻内存的 JSONL，审计估 1 万条约 15–25 MB，
 * 到那个量级时冷启动解析已经开始可感。这里只在用户看得见的地方点出来 ——
 * **不做静默淘汰**：删掉的是用户自己练出来的履历，只能由用户自己决定。
 */
private const val RECORDS_SIZE_BUDGET_BYTES = 20L * 1024 * 1024

/**
 * 文件大小的人类可读写法（B/KB/MB/GB，一位小数）。
 * 用 [Locale.US] 固定小数点，避免在逗号做小数分隔符的语言里出现「1,5 MB」这种和别处不一致的写法。
 */
private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}

/**
 * 备份错误 → 本地化展示文案。
 *
 * Toast 在协程里弹（`backupScope.launch`），取不到 @Composable 上下文，所以用
 * `Context.getString` 而不是 `stringResource`；数据层只给错误类型
 * （[BackupManager.BackupError]），文案归属这条线不断（键 `backup_err_*`）。
 */
private fun backupErrorMessage(context: Context, e: Throwable): String = when (e) {
    is BackupManager.BackupError.NotABackup -> context.getString(R.string.backup_err_not_backup)
    is BackupManager.BackupError.CorruptManifest -> context.getString(R.string.backup_err_corrupt)
    is BackupManager.BackupError.UnsupportedVersion -> context.getString(R.string.backup_err_version)
    is BackupManager.BackupError.Incomplete -> context.getString(R.string.backup_err_incomplete, e.entryName)
    is BackupManager.BackupError.TooLarge -> context.getString(R.string.backup_err_too_large)
    is BackupManager.BackupError.UnsafeEntry -> context.getString(R.string.backup_err_unsafe, e.entryName)
    is BackupManager.BackupError.EmptyData -> context.getString(R.string.backup_err_empty)
    is BackupManager.BackupError.NothingToExport -> context.getString(R.string.backup_err_nothing)
    is BackupManager.BackupError.CannotWrite -> context.getString(R.string.backup_err_cannot_write)
    is BackupManager.BackupError.CannotRead -> context.getString(R.string.backup_err_cannot_read)
    else -> context.getString(R.string.backup_err_unknown)
}

/**
 * 触感强度的四个可选项：`value` 存进偏好（与 ui.common.HapticLevel 的字符串一一对应），
 * `label` 上屏。顺序就是屏幕上从左到右 —— 「关」放最后，避免手滑点掉全部反馈。
 */
// 顶层数据表不是组合上下文，所以存 @StringRes，由渲染处解出来（与底栏 tab 同一做法）
private val hapticLevelChoices = listOf(
    "light" to com.ilyskyo.blancall.R.string.haptic_weak,
    "standard" to com.ilyskyo.blancall.R.string.haptic_standard,
    "strong" to com.ilyskyo.blancall.R.string.haptic_strong,
    "off" to com.ilyskyo.blancall.R.string.haptic_off,
)

@Composable
private fun OptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    description: String? = null
) {
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface)
                if (description != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(description,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (selected) {
                AppIcon(
                    kind = AppIconKind.Check,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ========== 学习提醒设置卡片 ==========

@Composable
private fun ReminderSettingsCard() {
    val context = LocalContext.current
    val enabled by ReminderPrefs.enabledFlow.collectAsState()
    val hour by ReminderPrefs.hourFlow.collectAsState()
    val minute by ReminderPrefs.minuteFlow.collectAsState()
    val goalMinutes by ReminderPrefs.goalMinutesFlow.collectAsState()
    val frequency by ReminderPrefs.frequencyFlow.collectAsState()

    var showTimePicker by remember { mutableStateOf(false) }
    // 自定义目标分钟数输入
    var showCustomMinutesDialog by remember { mutableStateOf(false) }
    var customMinutesInput by remember { mutableStateOf("") }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(4.dp)) {
            // ── 开关行 ──
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(com.ilyskyo.blancall.R.string.reminder_enable), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (enabled) stringResource(com.ilyskyo.blancall.R.string.reminder_on_at, String.format(Locale.getDefault(), "%02d:%02d", hour, minute))
                        else stringResource(com.ilyskyo.blancall.R.string.reminder_off_state),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                GlassSwitch(
                    checked = enabled,
                    onCheckedChange = { newEnabled ->
                        ReminderPrefs.enabled = newEnabled
                        if (newEnabled) {
                            (context as? MainActivity)?.requestNotificationPermissionIfNeeded()
                        } else {
                            ReminderWorker.cancel(context)
                        }
                    }
                )
            }

            // ── 展开设置（仅开启时显示）──
            AnimatedVisibility(
                visible = enabled,
                enter = fadeIn(MotionFade.alpha(MotionFade.enter)) +
                    expandVertically(Motion.contentSize()),
                exit = fadeOut(MotionFade.alpha(MotionFade.exit)) +
                    shrinkVertically(Motion.contentSize())
            ) {
                Column {
                    HorizontalDivider(
                        Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    // 提醒时间
                    Surface(
                        onClick = { showTimePicker = true },
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(com.ilyskyo.blancall.R.string.reminder_time), style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    String.format(Locale.getDefault(), "%02d:%02d", hour, minute),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("▸", style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    HorizontalDivider(
                        Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    // 每日学习目标
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(stringResource(com.ilyskyo.blancall.R.string.daily_goal), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(5, 10, 20).forEach { mins ->
                                FilterChip(
                                    selected = goalMinutes == mins,
                                    onClick = { ReminderPrefs.goalMinutes = mins },
                                    label = { AdaptiveChipLabel(stringResource(com.ilyskyo.blancall.R.string.minutes_unit, mins)) },
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }
                            // 自定义目标分钟数：已自定义时按钮显示实际分钟数
                            FilterChip(
                                selected = goalMinutes !in listOf(5, 10, 20),
                                onClick = {
                                    customMinutesInput = if (goalMinutes in listOf(5, 10, 20)) "" else goalMinutes.toString()
                                    showCustomMinutesDialog = true
                                },
                                label = {
                                    AdaptiveChipLabel(
                                        if (goalMinutes in listOf(5, 10, 20)) stringResource(com.ilyskyo.blancall.R.string.custom) else stringResource(com.ilyskyo.blancall.R.string.minutes_unit, goalMinutes)
                                    )
                                },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }

                    HorizontalDivider(
                        Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    // 提醒频率
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(stringResource(com.ilyskyo.blancall.R.string.reminder_frequency), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ReminderFrequency.entries.forEach { freq ->
                                FilterChip(
                                    selected = frequency == freq,
                                    onClick = { ReminderPrefs.frequency = freq },
                                    label = { Text(stringResource(freq.labelRes), style = MaterialTheme.typography.labelSmall) },
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 时间选择弹窗 ──
    AnimatedOverlayPresence(visible = showTimePicker) {
        TimePickerDialog(
            currentHour = hour,
            currentMinute = minute,
            onConfirm = { h, m ->
                ReminderPrefs.hour = h
                ReminderPrefs.minute = m
                showTimePicker = false
                ReminderWorker.scheduleNext(context)
            },
            onDismiss = { showTimePicker = false }
        )
    }

    // ── 自定义目标分钟数输入对话框 ──
    AnimatedOverlayPresence(visible = showCustomMinutesDialog) {
        BlancallAlertDialog(
            onDismissRequest = { showCustomMinutesDialog = false },
            title = { Text(stringResource(com.ilyskyo.blancall.R.string.custom_daily_goal_title)) },
            text = {
                OutlinedTextField(
                    value = customMinutesInput,
                    onValueChange = { input ->
                        customMinutesInput = input.filter { it.isDigit() }.take(3)
                    },
                    label = { Text(stringResource(com.ilyskyo.blancall.R.string.minutes_field_label)) },
                    suffix = { Text(stringResource(com.ilyskyo.blancall.R.string.minutes_word)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(
                    enabled = customMinutesInput.toIntOrNull() != null,
                    onClick = {
                        customMinutesInput.toIntOrNull()?.let { ReminderPrefs.goalMinutes = it.coerceIn(1, 600) }
                        showCustomMinutesDialog = false
                    }
                ) { Text(stringResource(com.ilyskyo.blancall.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showCustomMinutesDialog = false }) { Text(stringResource(com.ilyskyo.blancall.R.string.cancel)) }
            }
        )
    }
}

// ========== 简易时间选择弹窗 ==========

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimePickerDialog(
    currentHour: Int,
    currentMinute: Int,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit
) {
    // 以 currentHour/currentMinute 为 key，避免外部时间变化时输入框仍显示旧值
    var hourText by remember(currentHour) { mutableStateOf(currentHour.toString().padStart(2, '0')) }
    var minuteText by remember(currentMinute) { mutableStateOf(currentMinute.toString().padStart(2, '0')) }

    BlancallAlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = { Text(stringResource(com.ilyskyo.blancall.R.string.set_reminder_time), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 手动输入区域
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(com.ilyskyo.blancall.R.string.hours_unit), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = hourText,
                            onValueChange = { v ->
                                if (v.length <= 2 && v.all { it.isDigit() }) {
                                    hourText = v
                                }
                            },
                            modifier = Modifier.width(80.dp),
                            singleLine = true,
                            placeholder = { Text("0-23", style = MaterialTheme.typography.bodySmall) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                    Text(" : ", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(com.ilyskyo.blancall.R.string.minutes_short), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = minuteText,
                            onValueChange = { v ->
                                if (v.length <= 2 && v.all { it.isDigit() }) {
                                    minuteText = v
                                }
                            },
                            modifier = Modifier.width(80.dp),
                            singleLine = true,
                            placeholder = { Text("0-59", style = MaterialTheme.typography.bodySmall) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 快速预设（FlowRow 自动换行，避免挤压叠压）
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        stringResource(com.ilyskyo.blancall.R.string.preset_8am) to Pair(8, 0),
                        stringResource(com.ilyskyo.blancall.R.string.preset_1230pm) to Pair(12, 30),
                        stringResource(com.ilyskyo.blancall.R.string.preset_6pm) to Pair(18, 0),
                        stringResource(com.ilyskyo.blancall.R.string.preset_8pm) to Pair(20, 0),
                        stringResource(com.ilyskyo.blancall.R.string.preset_9pm) to Pair(21, 0)
                    ).forEach { (label, time) ->
                        val h = hourText.toIntOrNull() ?: -1
                        val m = minuteText.toIntOrNull() ?: -1
                        val isActive = h == time.first && m == time.second
                        AssistChip(
                            onClick = {
                                hourText = time.first.toString().padStart(2, '0')
                                minuteText = time.second.toString().padStart(2, '0')
                            },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            shape = RoundedCornerShape(8.dp),
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (isActive)
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val h = (hourText.toIntOrNull() ?: currentHour).coerceIn(0, 23)
                    val m = (minuteText.toIntOrNull() ?: currentMinute).coerceIn(0, 59)
                    onConfirm(h, m)
                },
                shape = RoundedCornerShape(10.dp)
            ) { Text(stringResource(com.ilyskyo.blancall.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(com.ilyskyo.blancall.R.string.cancel)) }
        }
    )
}

/**
 * 自适应单行文字：放不下时自动缩小字号，保证永远排在同一行（不换行）。
 * 用于选项卡/FilterChip 的 label，避免不同设备/字号下文字被挤成两行。
 */
@Composable
private fun AdaptiveChipLabel(text: String) {
    val style = MaterialTheme.typography.labelSmall
    var fontSize by remember(text) { mutableStateOf(style.fontSize) }
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        style = style.copy(fontSize = fontSize),
        onTextLayout = { result ->
            // 溢出时逐步缩小字号（下限 8sp），直到能单行放下
            if (result.hasVisualOverflow && fontSize.value > 8f) {
                fontSize = (fontSize.value - 0.5f).sp
            }
        }
    )
}

// ========== 赞赏区：会话级隐藏状态 / 赞赏码保存 ==========

/**
 * 设置页的**会话级**界面状态（只存在于内存，不随任何偏好落盘）。
 *
 * 赞赏区「隐藏该区域」按用户要求只对本次运行生效：藏起来之后本次使用不再出现，
 * 软件重新启动就自动恢复。所以它不能写进 AppPrefs（那会变成永久隐藏），也不能用
 * `remember`（离开设置页再回来就会被重新组合出来，与「本次使用」的承诺不符）。
 * 复位时机见 `MainActivity.onCreate`：只有真正重新启动（savedInstanceState == null）
 * 才清；系统因配置变化（主题、深色模式等）重建 Activity 时不清，免得刚藏好又冒出来。
 */
internal object SettingsSessionState {
    /** 赞赏区是否在本次运行中被隐藏 */
    var donationHidden by mutableStateOf(false)
}

/** 微信包名 */
private const val WECHAT_PACKAGE = "com.tencent.mm"

/**
 * 跳转微信。相册里的赞赏码要靠微信「扫一扫 → 从相册选取」才用得上，这里只负责把微信拉起来
 * —— 不硬编码「扫一扫」页的组件名（各版本组件名并不稳定，匹配不上会直接抛 ActivityNotFound，
 * 比停在微信首页更糟）。未安装微信时给一句提示，而不是静默失败。
 */
private fun launchWeChat(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(WECHAT_PACKAGE)
    if (intent == null) {
        Toast.makeText(context, R.string.donation_wechat_missing, Toast.LENGTH_SHORT).show()
        return
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, R.string.donation_wechat_missing, Toast.LENGTH_SHORT).show()
    }
}

/**
 * 把赞赏码写进系统相册的 `Pictures/Blancall/`，成功返回给用户看的目录名，失败返回 null。
 *
 * Android 10+ 走 MediaStore：`IS_PENDING` 两段写（写完才对外可见，半张图不会进相册），无需任何权限；
 * Android 9 及以下没有 RELATIVE_PATH，只能直接写公共目录，因此需要 WRITE_EXTERNAL_STORAGE
 * （清单里已按 maxSdkVersion=28 声明，界面在调用前申请）。
 */
private fun saveDonationQrToGallery(context: Context): String? {
    val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.appreciation_qr) ?: return null
    val fileName = "blancall_donation_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.png"
    val shownDir = "${Environment.DIRECTORY_PICTURES}/Blancall"
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, shownDir)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            val out = resolver.openOutputStream(uri) ?: return null
            out.use {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) return null
            }
            // 写入完成才解除 pending，此时这张图才会出现在相册里
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "Blancall"
            )
            if (!dir.exists() && !dir.mkdirs()) return null
            val file = File(dir, fileName)
            FileOutputStream(file).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
            }
            // 10 以下写公共目录不会被媒体库自动收录，得主动通知扫描
            @Suppress("DEPRECATION")
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
        }
        shownDir
    } catch (e: Exception) {
        // 日志串保持 ASCII：中文串字面量在 cjk-baseline.tsv 里是只减不增的棘轮，这条不是界面文案
        Log.w(TAG_DONATION, "donation image save to gallery failed", e)
        null
    } finally {
        bitmap.recycle()
    }
}

private const val TAG_DONATION = "DonationQr"
