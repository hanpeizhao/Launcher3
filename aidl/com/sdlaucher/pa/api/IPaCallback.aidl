/*
 * 负一屏 companion → 桌面的反向回调。
 * 信息流的触摸事件被系统直接路由到 companion 进程，桌面收不到滑动手势，
 * 因此"右滑回主屏"由 companion 检测后经本回调请求桌面翻页。
 */
package com.sdlaucher.pa.api;

interface IPaCallback {
    /** 用户在负一屏内向右滑动，请求桌面切换到主屏。 */
    oneway void onSwipeToHome();
}
