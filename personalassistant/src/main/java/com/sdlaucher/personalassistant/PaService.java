/*
 * 负一屏 companion 服务：接收桌面的跨进程嵌入请求。
 *
 * 桌面 bind 本服务后调用 bindSurface()，本进程在此创建信息流根视图
 * (PaHomeView) 并用 SurfaceControlViewHost 渲染，把 SurfacePackage 返回给
 * 桌面贴到其 SurfaceView 上。视图树、触摸处理、数据都隔离在本进程。
 */
package com.sdlaucher.personalassistant;

import android.app.Service;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceView;

import com.sdlaucher.pa.api.IPaCallback;
import com.sdlaucher.pa.api.IPaService;

import java.util.concurrent.CountDownLatch;

public class PaService extends Service {

    private static final String TAG = "PaService";

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private PaHomeView mRootView;
    private SurfaceControlViewHost mViewHost;
    private IPaCallback mCallback;

    private final IPaService.Stub mBinder = new IPaService.Stub() {

        @Override
        public SurfaceControlViewHost.SurfacePackage bindSurface(IBinder hostToken, int displayId,
                int width, int height, IPaCallback callback) {
            return runOnMainAndWait(
                    () -> createSurfacePackage(hostToken, displayId, width, height, callback));
        }

        @Override
        public void relayout(int width, int height) {
            mMainHandler.post(() -> {
                if (mViewHost != null) {
                    try {
                        mViewHost.relayout(width, height);
                    } catch (Exception e) {
                        Log.e(TAG, "relayout failed", e);
                    }
                }
            });
        }

        @Override
        public void onHostResumed() {
            mMainHandler.post(() -> {
                if (mRootView != null) {
                    mRootView.onHostResumed();
                }
            });
        }

        @Override
        public void onHostPaused() {
            mMainHandler.post(() -> {
                if (mRootView != null) {
                    mRootView.onHostPaused();
                }
            });
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "bound by launcher");
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        mMainHandler.post(this::releaseHost);
        return false;
    }

    /** 在主线程创建/重建远程视图树，返回可跨进程传递的 SurfacePackage。 */
    private SurfaceControlViewHost.SurfacePackage createSurfacePackage(IBinder hostToken,
            int displayId, int width, int height, IPaCallback callback) {
        releaseHost();
        try {
            Display display = getSystemService(DisplayManager.class).getDisplay(displayId);
            if (display == null) {
                Log.e(TAG, "no display " + displayId);
                return null;
            }
            mCallback = callback;
            mRootView = new PaHomeView(this, this::notifySwipeToHome);
            mViewHost = new SurfaceControlViewHost(this, display, hostToken);
            mViewHost.setView(mRootView, width, height);
            return mViewHost.getSurfacePackage();
        } catch (Exception e) {
            Log.e(TAG, "createSurfacePackage failed", e);
            releaseHost();
            return null;
        }
    }

    private void releaseHost() {
        if (mViewHost != null) {
            try {
                mViewHost.release();
            } catch (Exception e) {
                Log.e(TAG, "release failed", e);
            }
            mViewHost = null;
        }
        mRootView = null;
        mCallback = null;
    }

    private void notifySwipeToHome() {
        IPaCallback cb = mCallback;
        if (cb != null) {
            try {
                cb.onSwipeToHome();
            } catch (Exception e) {
                Log.e(TAG, "onSwipeToHome failed", e);
            }
        }
    }

    private interface Job<T> {
        T run();
    }

    private <T> T runOnMainAndWait(Job<T> job) {
        Object[] out = new Object[1];
        CountDownLatch latch = new CountDownLatch(1);
        mMainHandler.post(() -> {
            try {
                out[0] = job.run();
            } catch (Exception e) {
                Log.e(TAG, "job failed", e);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        //noinspection unchecked
        return (T) out[0];
    }

    private void runOnMain(Job<Void> job) {
        mMainHandler.post(() -> job.run());
    }
}
