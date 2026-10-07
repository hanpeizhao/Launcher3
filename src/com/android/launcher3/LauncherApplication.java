/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.launcher3;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import com.android.launcher3.dagger.DaggerLauncherAppComponent;
import com.android.launcher3.dagger.LauncherAppComponent;
import com.android.launcher3.dagger.LauncherBaseAppComponent;
import com.android.launcher3.util.TraceHelper;

/**
 * Main application class for Launcher
 */
public class LauncherApplication extends Application {

    private volatile LauncherBaseAppComponent mAppComponent;
    @Override
    public void onCreate() {
        super.onCreate();
        MainProcessInitializer.initialize(this);
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // AOSP 桌面大量使用平台隐藏 API；在零售机（如 HyperOS）上以第三方应用身份运行时
        // 会被隐藏 API 强拦截导致闪退。在进程内把自身加入豁免名单（"L" 前缀覆盖所有类）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.setHiddenApiExemptions("L");
            } catch (Throwable ignored) {
            }
        }
    }

    public LauncherAppComponent getAppComponent() {
        if (mAppComponent == null) {
            synchronized (this) {
                // Check for null again, as it may have been assigned on a different thread. This
                // avoids holding synchronization locks everytime.
                if (mAppComponent == null) {
                    // Initialize the dagger component on demand as content providers can get
                    // accessed before the Launcher application (b/36917845#comment4)
                    initDaggerComponent(DaggerLauncherAppComponent.builder()
                            .iconsDbName(LauncherFiles.APP_ICONS_DB));
                }
            }
        }
        // Since supertype setters will return a supertype.builder and @Component.Builder types
        // must not have any generic types.
        // We need to cast mAppComponent to {@link LauncherAppComponent} since appContext()
        // method is defined in the super class LauncherBaseComponent#Builder.
        return (LauncherAppComponent) mAppComponent;
    }

    /**
     * Init with the desired dagger component.
     */
    public void initDaggerComponent(LauncherBaseAppComponent.Builder componentBuilder) {
        mAppComponent = componentBuilder
                .appContext(this)
                .setSafeModeEnabled(TraceHelper.allowIpcs(
                        "isSafeMode", () -> getPackageManager().isSafeMode()))
                .build();
    }
}
