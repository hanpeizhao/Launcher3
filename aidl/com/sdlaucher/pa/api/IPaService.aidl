/*
 * 负一屏跨进程契约（桌面 ↔ 个人助理 companion 应用）。
 *
 * 本目录 (aidl/) 为共享源码目录，launcher 与 personalassistant 两个模块
 * 都把它加入 aidl.srcDirs，保证两端接口定义一致。
 */
package com.sdlaucher.pa.api;

import com.sdlaucher.pa.api.IPaCallback;
import android.os.IBinder;
import android.view.SurfaceControlViewHost.SurfacePackage;

interface IPaService {

    /**
     * 桌面（宿主）请求嵌入信息流界面。
     * companion 进程创建根视图 + SurfaceControlViewHost，返回可嵌入的 SurfacePackage。
     *
     * @param hostToken 桌面 SurfaceView 的 SurfaceControl.getHostToken()
     * @param displayId 桌面所在 display id
     * @param width/height SurfaceView 当前像素尺寸
     * @param callback 桌面侧回调（如右滑回桌面）
     */
    SurfacePackage bindSurface(
            in IBinder hostToken, int displayId, int width, int height, IPaCallback callback);

    /** 宿主 SurfaceView 尺寸变化时同步重新布局。 */
    oneway void relayout(int width, int height);

    /** 桌面可见，companion 可刷新内容。 */
    oneway void onHostResumed();

    /** 桌面不可见，companion 可暂停工作。 */
    oneway void onHostPaused();
}
