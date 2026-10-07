/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.graphics

import android.content.Context
import android.content.res.Resources
import com.android.launcher3.EncryptionType
import com.android.launcher3.Item
import com.android.launcher3.LauncherPrefChangeListener
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.LauncherPrefs.Companion.backedUpItem
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppComponent
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.graphics.ShapeDelegate.Companion.pickBestShape
import com.android.launcher3.icons.IconThemeController
import com.android.launcher3.icons.mono.MonoIconThemeController
import com.android.launcher3.shapes.IconShapeModel
import com.android.launcher3.shapes.ShapesProvider
import com.android.launcher3.util.DaggerSingletonObject
import com.android.launcher3.util.DaggerSingletonTracker
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.SimpleBroadcastReceiver
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject

/** Centralized class for managing Launcher icon theming */
@LauncherAppSingleton
class ThemeManager
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val prefs: LauncherPrefs,
    private val iconControllerFactory: IconControllerFactory,
    lifecycle: DaggerSingletonTracker,
) {

    /** Representation of the current icon state */
    var iconState = parseIconState(null)
        private set

    var isMonoThemeEnabled
        set(value) = prefs.put(THEMED_ICONS, value)
        get() = prefs.get(THEMED_ICONS)

    val themeController
        get() = iconState.themeController

    val isIconThemeEnabled
        get() = themeController != null

    val iconShape
        get() = iconState.iconShape

    val folderShape
        get() = iconState.folderShape

    private val listeners = CopyOnWriteArrayList<ThemeChangeListener>()

    init {
        val receiver = SimpleBroadcastReceiver(context, MAIN_EXECUTOR) { verifyIconState() }
        receiver.registerPkgActions("android", ACTION_OVERLAY_CHANGED)

        val keys = (iconControllerFactory.prefKeys + PREF_ICON_SHAPE)

        val keysArray = keys.toTypedArray()
        val prefKeySet = keys.map { it.sharedPrefKey }
        val prefListener = LauncherPrefChangeListener { key ->
            if (prefKeySet.contains(key)) verifyIconState()
        }
        prefs.addListener(prefListener, *keysArray)
        lifecycle.addCloseable {
            receiver.unregisterReceiverSafely()
            prefs.removeListener(prefListener, *keysArray)
        }
    }

    private fun verifyIconState() {
        val newState = parseIconState(iconState)
        if (newState == iconState) return
        iconState = newState

        listeners.forEach { it.onThemeChanged() }
    }

    fun addChangeListener(listener: ThemeChangeListener) = listeners.add(listener)

    fun removeChangeListener(listener: ThemeChangeListener) = listeners.remove(listener)

    private fun parseIconState(oldState: IconState?): IconState {
        // 用户未手动选择形状时，默认使用小米风格超椭圆蒙版：
        // HyperOS 系统蒙版（config_icon_mask）圆角偏小，观感接近直角
        val shapeModel =
            prefs.get(PREF_ICON_SHAPE).let { shapeOverride ->
                ShapesProvider.iconShapes.firstOrNull { it.key == shapeOverride }
            } ?: DEFAULT_ICON_SHAPE
        val iconMask =
            when {
                shapeModel != null -> shapeModel.pathString
                CONFIG_ICON_MASK_RES_ID == Resources.ID_NULL -> ""
                else -> context.resources.getString(CONFIG_ICON_MASK_RES_ID)
            }

        val iconShape =
            if (oldState != null && oldState.iconMask == iconMask) oldState.iconShape
            else pickBestShape(iconMask)

        val folderShapeMask = shapeModel?.folderPathString ?: iconMask
        val folderShape =
            when {
                oldState != null && oldState.folderShapeMask == folderShapeMask ->
                    oldState.folderShape
                folderShapeMask == iconMask || folderShapeMask.isEmpty() -> iconShape
                else -> pickBestShape(folderShapeMask)
            }

        return IconState(
            iconMask = iconMask,
            folderShapeMask = folderShapeMask,
            themeController = iconControllerFactory.createThemeController(),
            iconScale = shapeModel?.iconScale ?: 1f,
            iconShape = iconShape,
            folderShape = folderShape,
        )
    }

    data class IconState(
        val iconMask: String,
        val folderShapeMask: String,
        val themeController: IconThemeController?,
        val themeCode: String = themeController?.themeID ?: "no-theme",
        val iconScale: Float = 1f,
        val iconShape: ShapeDelegate,
        val folderShape: ShapeDelegate,
    ) {
        fun toUniqueId() = "${iconMask.hashCode()},$themeCode"
    }

    /** Interface for receiving theme change events */
    fun interface ThemeChangeListener {
        fun onThemeChanged()
    }

    open class IconControllerFactory @Inject constructor(protected val prefs: LauncherPrefs) {

        open val prefKeys: List<Item> = listOf(THEMED_ICONS)

        open fun createThemeController(): IconThemeController? {
            return if (prefs.get(THEMED_ICONS)) MONO_THEME_CONTROLLER else null
        }
    }

    companion object {

        @JvmField val INSTANCE = DaggerSingletonObject(LauncherAppComponent::getThemeManager)
        const val KEY_ICON_SHAPE = "icon_shape_model"

        const val KEY_THEMED_ICONS = "themed_icons"
        @JvmField val THEMED_ICONS = backedUpItem(KEY_THEMED_ICONS, false, EncryptionType.ENCRYPTED)
        @JvmField val PREF_ICON_SHAPE = backedUpItem(KEY_ICON_SHAPE, "", EncryptionType.ENCRYPTED)

        private const val ACTION_OVERLAY_CHANGED = "android.intent.action.OVERLAY_CHANGED"
        private val CONFIG_ICON_MASK_RES_ID: Int =
            Resources.getSystem().getIdentifier("config_icon_mask", "string", "android")

        /**
         * 默认图标形状：n=4 超椭圆（小米风格），坐标系 [0,100]。
         * 仅当用户未通过 icon_shape_model 偏好主动选择形状时生效
         */
        val DEFAULT_ICON_SHAPE =
            IconShapeModel(
                key = "miui_squircle",
                title = "squircle",
                pathString =
                    "M100.00 50.00L99.97 61.08L99.88 65.65L99.73 69.15L99.52 72.08L99.25 74.65L98.91 76.94L98.52 79.02L98.06 80.93L97.54 82.69L96.96 84.33L96.31 85.85L95.59 87.27L94.81 88.59L93.96 89.82L93.04 90.97L92.04 92.04L90.97 93.04L89.82 93.96L88.59 94.81L87.27 95.59L85.85 96.31L84.33 96.96L82.69 97.54L80.93 98.06L79.02 98.52L76.94 98.91L74.65 99.25L72.08 99.52L69.15 99.73L65.65 99.88L61.08 99.97L50.00 100.00L38.92 99.97L34.35 99.88L30.85 99.73L27.92 99.52L25.35 99.25L23.06 98.91L20.98 98.52L19.07 98.06L17.31 97.54L15.67 96.96L14.15 96.31L12.73 95.59L11.41 94.81L10.18 93.96L9.03 93.04L7.96 92.04L6.96 90.97L6.04 89.82L5.19 88.59L4.41 87.27L3.69 85.85L3.04 84.33L2.46 82.69L1.94 80.93L1.48 79.02L1.09 76.94L0.75 74.65L0.48 72.08L0.27 69.15L0.12 65.65L0.03 61.08L0.00 50.00L0.03 38.92L0.12 34.35L0.27 30.85L0.48 27.92L0.75 25.35L1.09 23.06L1.48 20.98L1.94 19.07L2.46 17.31L3.04 15.67L3.69 14.15L4.41 12.73L5.19 11.41L6.04 10.18L6.96 9.03L7.96 7.96L9.03 6.96L10.18 6.04L11.41 5.19L12.73 4.41L14.15 3.69L15.67 3.04L17.31 2.46L19.07 1.94L20.98 1.48L23.06 1.09L25.35 0.75L27.92 0.48L30.85 0.27L34.35 0.12L38.92 0.03L50.00 0.00L61.08 0.03L65.65 0.12L69.15 0.27L72.08 0.48L74.65 0.75L76.94 1.09L79.02 1.48L80.93 1.94L82.69 2.46L84.33 3.04L85.85 3.69L87.27 4.41L88.59 5.19L89.82 6.04L90.97 6.96L92.04 7.96L93.04 9.03L93.96 10.18L94.81 11.41L95.59 12.73L96.31 14.15L96.96 15.67L97.54 17.31L98.06 19.07L98.52 20.98L98.91 23.06L99.25 25.35L99.52 27.92L99.73 30.85L99.88 34.35L99.97 38.92Z",
            )

        // Use a constant to allow equality check in verifyIconState
        private val MONO_THEME_CONTROLLER = MonoIconThemeController()
    }
}
