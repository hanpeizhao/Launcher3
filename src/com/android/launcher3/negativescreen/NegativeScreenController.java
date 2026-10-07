/*
 * 负一屏宿主控制器（Launcher 进程内）——DragLayer 覆盖层 + 手势驱动。
 *
 * 职责边界：Launcher 只负责手势和本控制器；信息流界面的渲染、触摸处理、
 * 数据全部在 companion 应用（personalassistant 模块，独立 APK/进程）内完成。
 *
 * 交互模型（主屏静止的覆盖层）：
 *  - 主屏上右滑（dx 超过 touchSlop 且横向占优）→ 拦截手势，主屏不参与
 *  - 负一屏整层（全屏 SurfaceView）从屏幕左缘外跟手推入覆盖主屏，松手按
 *    半屏阈值决定滑入或弹回
 *  - 覆盖层开启时触摸直达 companion 视图；companion 内右滑回调本控制器，
 *    播放滑出动画
 *  - Home 键关闭覆盖层；进入概览/拖拽态时立即隐藏
 *
 * 组成：
 *  - 监听 launcher_preferences.xml 的 negative_screen 开关（LauncherPrefs）
 *  - 把 SurfaceView 挂到 DragLayer 顶层，把 hostToken 交给 companion，
 *    收到 SurfacePackage 后贴上（系统负责把触摸路由到远程进程）
 *  - 通过 DragLayer 的 onInterceptTouchEvent/onTouchEvent 前置钩子驱动手势
 */
package com.android.launcher3.negativescreen;

import static com.android.launcher3.util.Executors.MODEL_EXECUTOR;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.LauncherPrefChangeListener;
import com.android.launcher3.LauncherState;
import com.android.launcher3.pageindicators.PageIndicatorDots;
import com.android.launcher3.statemanager.StateManager;
import com.sdlaucher.pa.api.IPaCallback;
import com.sdlaucher.pa.api.IPaService;

public class NegativeScreenController implements LauncherPrefChangeListener,
        StateManager.StateListener<LauncherState> {

    private static final String TAG = "NegativeScreenController";

    private static final String PA_PACKAGE = "com.sdlaucher.personalassistant";
    private static final String PA_ACTION = "com.sdlaucher.pa.api.BIND";

    /** launcher_preferences.xml 中的开关 key，与 LauncherPrefs.NEGATIVE_SCREEN_ENABLED 一致 */
    private static final String PREF_KEY = "pref_negative_screen";

    /** 滑入动画时长（ms） */
    private static final long DURATION_OPEN = 250;
    /** 滑出/弹回动画时长（ms） */
    private static final long DURATION_CLOSE = 200;

    private final Launcher mLauncher;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final int mTouchSlop;

    private SurfaceView mSurfaceView;
    private IPaService mService;
    private boolean mBindRequested;
    private boolean mSurfaceRequested;
    /** companion 嵌入面是否已就绪（未就绪时不得显示覆盖层，避免黑屏） */
    private boolean mSurfaceBound;

    /** 覆盖层当前平移量：0 = 全屏覆盖，-pageWidth = 完全收起（屏外左侧） */
    private float mOverlayOffset = 0f;

    // ---- 手势状态机 ----
    private static final int STATE_HIDDEN = 0;    // 收起（INVISIBLE，tx=-pageWidth）
    private static final int STATE_TRACKING = 1;  // 手指跟手拖动中
    private static final int STATE_OPEN = 2;      // 全屏覆盖
    private static final int STATE_ANIMATING = 3; // 动画中（触摸被吞掉）
    private int mGestureState = STATE_HIDDEN;

    /** 手势起点（DRAG 激活后用于计算 dx） */
    private float mDownX;
    private float mDownY;
    /** 本次拖动的起始平移量与激活时的 dx：tx = start + (dx - startDx)，激活瞬间无跳变 */
    private float mDragStartOffset;
    private float mDragStartDx;
    private ValueAnimator mAnimator;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mService = IPaService.Stub.asInterface(binder);
            requestSurfaceIfNeeded();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // companion 进程被杀：清引用，BIND_AUTO_CREATE 会自动重建服务并再次回调 connected
            mService = null;
            mSurfaceRequested = false;
            mSurfaceBound = false;
        }
    };

    private final IPaCallback.Stub mCallback = new IPaCallback.Stub() {
        @Override
        public void onSwipeToHome() {
            // companion 检测到右滑手势：整层滑出
            Log.d(TAG, "onSwipeToHome from companion");
            mMainHandler.post(() -> animateClose());
        }
    };

    public NegativeScreenController(Launcher launcher) {
        mLauncher = launcher;
        mTouchSlop = ViewConfiguration.get(launcher).getScaledTouchSlop();
        LauncherPrefs.get(launcher).addListener(this, LauncherPrefs.NEGATIVE_SCREEN_ENABLED);
        // 非 NORMAL 态（概览/拖拽）立即隐藏覆盖层，回 NORMAL 后保持收起
        launcher.getStateManager().addStateListener(this);
        launcher.getDragLayer().setNegativeScreenController(this);
    }

    // ---- DragLayer 手势钩子（由 DragLayer.onInterceptTouchEvent/onTouchEvent 前置调用）----

    /**
     * DragLayer.onInterceptTouchEvent 前置钩子。
     * 覆盖层收起时检测主屏右滑（横向占优 + 超过 touchSlop），命中则拦截整条触摸流，
     * 子视图（Workspace 等）收到 CANCEL，主屏保持静止；覆盖层开启/动画中吞掉手势，
     * 防止拖动覆盖层时误触发主屏翻页。
     */
    public boolean onInterceptTouch(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                mDownX = ev.getRawX();
                mDownY = ev.getRawY();
                if (mGestureState == STATE_TRACKING) {
                    cancelAnimation();
                    mGestureState = STATE_HIDDEN;
                    updateVisibility(false);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mGestureState == STATE_HIDDEN && mSurfaceBound && inNormalState()) {
                    // 仅首页右滑拉出负一屏；其余页右滑是正常翻页，不得拦截
                    if (mLauncher.getWorkspace().getNextPage() != 0) {
                        return false;
                    }
                    float dx = ev.getRawX() - mDownX;
                    float dy = ev.getRawY() - mDownY;
                    if (Math.abs(dx) > mTouchSlop && Math.abs(dx) > Math.abs(dy) && dx > 0) {
                        Log.d(TAG, "drag start (open)");
                        mGestureState = STATE_TRACKING;
                        mDragStartOffset = -mSurfaceView.getWidth();
                        mDragStartDx = dx;
                        updateVisibility(true);
                        return true;
                    }
                } else if (mGestureState == STATE_OPEN) {
                    // 负一屏内横向滑动（左滑/右滑）：宿主直接拦截并跟手拖出，
                    // 不依赖跨进程手势回调（remote input 不可靠）
                    float dx = ev.getRawX() - mDownX;
                    float dy = ev.getRawY() - mDownY;
                    if (Math.abs(dx) > mTouchSlop && Math.abs(dx) > Math.abs(dy)) {
                        Log.d(TAG, "drag start (close)");
                        mGestureState = STATE_TRACKING;
                        mDragStartOffset = 0f;
                        mDragStartDx = dx;
                        return true;
                    }
                } else if (mGestureState == STATE_ANIMATING) {
                    return true;
                }
                break;
            }
            default: {
                if (mGestureState == STATE_ANIMATING) {
                    return true;
                }
                break;
            }
        }
        return false;
    }

    /**
     * DragLayer.onTouchEvent 前置钩子：拦截激活后接管整条触摸流，驱动覆盖层跟手。
     */
    public boolean onHostTouch(MotionEvent ev) {
        if (mGestureState != STATE_TRACKING) {
            // ANIMATING 时吞掉整条流，避免动画期间误触
            return mGestureState == STATE_ANIMATING;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getRawX() - mDownX;
                float pageWidth = mSurfaceView.getWidth();
                // 从激活点连续跟手（拉出：-pageWidth→0；拖出：0→-pageWidth），双向 clamp
                float offset = mDragStartOffset + (dx - mDragStartDx);
                mOverlayOffset = Math.max(-pageWidth, Math.min(0f, offset));
                mSurfaceView.setTranslationX(mOverlayOffset);
                syncIndicator();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                // 越过半屏判定滑入，否则弹回
                if (mOverlayOffset > -mSurfaceView.getWidth() / 2f) {
                    animateOpen();
                } else {
                    animateClose();
                }
                return true;
            }
            default: {
                return true;
            }
        }
    }

    /** Home 键：覆盖层开启则关闭并消费本次 Home，返回是否已处理。 */
    public boolean onHomePressed() {
        Log.d(TAG, "onHomePressed state=" + mGestureState);
        if (mGestureState == STATE_OPEN || mGestureState == STATE_ANIMATING
                || mGestureState == STATE_TRACKING) {
            animateClose();
            return true;
        }
        return false;
    }

    @Override
    public void onStateTransitionStart(LauncherState toState) {
        Log.d(TAG, "onStateTransitionStart to=" + toState + " state=" + mGestureState);
        if (toState != LauncherState.NORMAL && mGestureState != STATE_HIDDEN) {
            cancelAnimation();
            mGestureState = STATE_HIDDEN;
            updateVisibility(false);
        }
    }

    @Override
    public void onStateTransitionComplete(LauncherState finalState) { }

    /** Loader 完成页面重建（bindScreens）后的挂钩：开关打开则确保覆盖层存在。 */
    public void onScreensBound() {
        if (isEnabled()) {
            enable();
        }
    }

    @Override
    public void onPrefChanged(String key) {
        if (PREF_KEY.equals(key)) {
            if (isEnabled()) {
                enable();
            } else {
                disable();
            }
        }
    }

    /** Launcher 销毁时清理：解除偏好监听与服务绑定。 */
    public void destroy() {
        LauncherPrefs.get(mLauncher).removeListener(this, LauncherPrefs.NEGATIVE_SCREEN_ENABLED);
        unbindService();
    }

    private boolean isEnabled() {
        return LauncherPrefs.get(mLauncher).get(LauncherPrefs.NEGATIVE_SCREEN_ENABLED);
    }

    private boolean inNormalState() {
        return mLauncher.getStateManager().getCurrentStableState() == LauncherState.NORMAL;
    }

    private void enable() {
        if (!mBindRequested) {
            bindService();
        }
        ensureOverlay();
        // 指示器视觉页数 +1，负一屏成为第 0 个 marker（原生动画体系）
        PageIndicatorDots indicator =
                (PageIndicatorDots) mLauncher.getWorkspace().getPageIndicator();
        if (indicator != null) {
            indicator.enableNegativePage(true);
        }
    }

    private void disable() {
        mSurfaceBound = false;
        cancelAnimation();
        mGestureState = STATE_HIDDEN;
        if (mSurfaceView != null && mSurfaceView.getParent() instanceof ViewGroup vg) {
            vg.removeView(mSurfaceView);
        }
        mSurfaceView = null;
        PageIndicatorDots indicator =
                (PageIndicatorDots) mLauncher.getWorkspace().getPageIndicator();
        if (indicator != null) {
            indicator.enableNegativePage(false);
        }
        unbindService();
    }

    private void bindService() {
        mBindRequested = true;
        Intent intent = new Intent(PA_ACTION).setPackage(PA_PACKAGE);
        try {
            mLauncher.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            // signature 权限不符（companion 版本不一致等）：负一屏降级为不可用
            Log.e(TAG, "bind PaService failed", e);
            mBindRequested = false;
        }
    }

    private void unbindService() {
        if (mBindRequested) {
            mBindRequested = false;
            mLauncher.unbindService(mConnection);
            mService = null;
            mSurfaceRequested = false;
        }
    }

    /**
     * 确保 DragLayer 顶层持有全屏 SurfaceView（覆盖主屏、hotseat 与搜索栏）。
     * 覆盖层不依赖 Workspace 页面：主屏不插负一屏页，翻页行为完全不变。
     */
    private void ensureOverlay() {
        if (mSurfaceView != null && mSurfaceView.getParent() == mLauncher.getDragLayer()) {
            return;
        }
        mSurfaceView = new SurfaceView(mLauncher);
        mSurfaceView.setId(View.generateViewId());
        mSurfaceView.setClickable(false);
        // 预热：先平移到屏外再 VISIBLE。surface 在启用时即建立、companion 提前渲染首帧，
        // 首次拉出负一屏只剩纯平移——若延迟到首次拉出才切 VISIBLE，
        // punch-through 建洞与合成器重排会并发发生，桌面窗口内容被短暂挖空（内容清空观感）。
        mOverlayOffset = -mLauncher.getResources().getDisplayMetrics().widthPixels;
        mSurfaceView.setTranslationX(mOverlayOffset);
        mSurfaceView.setVisibility(View.VISIBLE);
        mLauncher.getDragLayer().addView(mSurfaceView,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        mSurfaceRequested = false;
        mSurfaceView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                applyFullScreenLayout();
                v.getViewTreeObserver().addOnGlobalLayoutListener(
                        new ViewTreeObserver.OnGlobalLayoutListener() {
                            @Override
                            public void onGlobalLayout() {
                                if (v.getWidth() > 0 && v.getHeight() > 0) {
                                    v.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                                    requestSurfaceIfNeeded();
                                }
                            }
                        });
            }

            @Override
            public void onViewDetachedFromWindow(View v) { }
        });
        // 尺寸变化时同步 companion 重新布局，并保持覆盖状态栏区域
        mSurfaceView.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> {
                    int w = r - l, h = b - t, ow = or - ol, oh = ob - ot;
                    applyFullScreenLayout();
                    if ((w != ow || h != oh) && mService != null && w > 0 && h > 0) {
                        try {
                            mService.relayout(w, h);
                        } catch (Exception e) {
                            Log.e(TAG, "relayout failed", e);
                        }
                    }
                });
    }

    /**
     * 把覆盖层顶/底推到屏幕边缘：负 margin 抵掉状态栏与导航栏 inset，
     * FrameLayout 对 MATCH_PARENT 子视图按 margin 增高，覆盖层延伸到两栏后方（全屏沉浸）。
     */
    private void applyFullScreenLayout() {
        if (mSurfaceView == null || !mSurfaceView.isAttachedToWindow()) {
            return;
        }
        android.view.WindowInsets insets = mLauncher.getDragLayer().getRootWindowInsets();
        int statusBar = insets != null
                ? insets.getInsets(android.view.WindowInsets.Type.statusBars()).top : 0;
        int navBar = insets != null
                ? insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom : 0;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mSurfaceView.getLayoutParams();
        if (lp.topMargin != -statusBar || lp.bottomMargin != -navBar) {
            lp.topMargin = -statusBar;
            lp.bottomMargin = -navBar;
            mSurfaceView.setLayoutParams(lp);
        }
    }

    /** 服务已连接且 SurfaceView 已布局完成时，向 companion 请求嵌入面。 */
    private void requestSurfaceIfNeeded() {
        if (mService == null || mSurfaceRequested
                || mSurfaceView == null || !mSurfaceView.isAttachedToWindow()
                || mSurfaceView.getWidth() == 0 || mSurfaceView.getHeight() == 0) {
            return;
        }
        mSurfaceRequested = true;
        IBinder hostToken = getHostToken(mSurfaceView);
        if (hostToken == null) {
            Log.e(TAG, "host token unavailable, negative screen disabled");
            mSurfaceRequested = false;
            return;
        }
        int displayId = mLauncher.getDisplay().getDisplayId();
        int width = mSurfaceView.getWidth();
        int height = mSurfaceView.getHeight();

        MODEL_EXECUTOR.execute(() -> {
            SurfaceControlViewHost.SurfacePackage pkg = null;
            try {
                pkg = mService.bindSurface(hostToken, displayId, width, height, mCallback);
            } catch (Exception e) {
                Log.e(TAG, "bindSurface failed", e);
            }
            SurfaceControlViewHost.SurfacePackage result = pkg;
            mMainHandler.post(() -> {
                mSurfaceRequested = false;
                if (result != null && mSurfaceView != null) {
                    attachSurfacePackage(mSurfaceView, result);
                    mSurfaceBound = true;
                }
            });
        });
    }

    // ---- 显示与动画 ----

    /**
     * 指示器联动：把覆盖进度映射到负一屏标志点（0 = 主屏页，1 = 负一屏点）。
     * 跟手/动画时激活点在主屏页与负一屏点之间平滑滑动。
     */
    private void syncIndicator() {
        PageIndicatorDots indicator =
                (PageIndicatorDots) mLauncher.getWorkspace().getPageIndicator();
        if (indicator == null || mSurfaceView == null) {
            return;
        }
        float pageWidth = mSurfaceView.getWidth();
        float progress = pageWidth > 0 ? -mOverlayOffset / pageWidth : 0f;
        indicator.setNegativeProgress(Math.max(0f, Math.min(1f, progress)));
    }

    /** 按手势状态切换可见性：显示时确保 VISIBLE，收起时平移到屏外左缘。 */
    private void updateVisibility(boolean visible) {
        if (mSurfaceView == null) {
            return;
        }
        if (visible) {
            // 仅首次从 INVISIBLE 切 VISIBLE（建立 surface）；此后保持 VISIBLE，
            // 只用 translationX 控制位置——INVISIBLE 会销毁 surface，再次显示需重建而卡顿
            if (mSurfaceView.getVisibility() != View.VISIBLE) {
                mSurfaceView.setVisibility(View.VISIBLE);
            }
        } else {
            // 平移到屏外左缘：不可见、触摸不可达，且 surface 常驻渲染无重建开销
            mOverlayOffset = -mSurfaceView.getWidth();
            mSurfaceView.setTranslationX(mOverlayOffset);
            syncIndicator();
        }
    }

    private void animateOpen() {
        cancelAnimation();
        Log.d(TAG, "animateOpen from=" + mOverlayOffset);
        float from = mOverlayOffset;
        mAnimator = ValueAnimator.ofFloat(from, 0f);
        mAnimator.setDuration(DURATION_OPEN);
        mAnimator.setInterpolator(new DecelerateInterpolator());
        mAnimator.addUpdateListener(anim -> {
            mOverlayOffset = (float) anim.getAnimatedValue();
            if (mSurfaceView != null) {
                mSurfaceView.setTranslationX(mOverlayOffset);
            }
            syncIndicator();
        });
        mAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mAnimator = null;
                if (!mCancelled) {
                    mGestureState = STATE_OPEN;
                }
            }
        });
        mGestureState = STATE_ANIMATING;
        mAnimator.start();
    }

    private void animateClose() {
        if (mGestureState == STATE_HIDDEN) {
            return;
        }
        cancelAnimation();
        Log.d(TAG, "animateClose state=" + mGestureState + " from=" + mOverlayOffset
                + " width=" + (mSurfaceView != null ? mSurfaceView.getWidth() : -1));
        float from = mGestureState == STATE_ANIMATING ? mOverlayOffset
                : (mSurfaceView != null ? mSurfaceView.getTranslationX() : 0f);
        float pageWidth = mSurfaceView != null ? mSurfaceView.getWidth() : 0f;
        mAnimator = ValueAnimator.ofFloat(from, -pageWidth);
        mAnimator.setDuration(DURATION_CLOSE);
        mAnimator.setInterpolator(new DecelerateInterpolator());
        mAnimator.addUpdateListener(anim -> {
            mOverlayOffset = (float) anim.getAnimatedValue();
            if (mSurfaceView != null) {
                mSurfaceView.setTranslationX(mOverlayOffset);
            }
            syncIndicator();
        });
        mAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mAnimator = null;
                if (!mCancelled) {
                    mGestureState = STATE_HIDDEN;
                    updateVisibility(false);
                }
            }
        });
        mGestureState = STATE_ANIMATING;
        mAnimator.start();
    }

    private void cancelAnimation() {
        if (mAnimator != null) {
            mAnimator.cancel();
            mAnimator = null;
        }
    }

    // ---- 以下两个方法为反射调用 ----
    // 编译期 framework.jar（Android 16 源码树 prebuilt）缺少这两个 API，
    // 但目标设备（K70 / Android 16）运行时 framework 均已提供。

    /** SurfaceView.getHostToken()：SurfaceView 嵌入式窗口的 host token。 */
    private static IBinder getHostToken(SurfaceView view) {
        try {
            return (IBinder) SurfaceView.class.getMethod("getHostToken").invoke(view);
        } catch (Exception e) {
            Log.e(TAG, "getHostToken failed", e);
            return null;
        }
    }

    /** SurfaceView.setChildSurfacePackage(pkg)：把远程 SurfacePackage 贴到本视图。 */
    private static void attachSurfacePackage(SurfaceView view,
            SurfaceControlViewHost.SurfacePackage pkg) {
        try {
            SurfaceView.class
                    .getMethod("setChildSurfacePackage",
                            SurfaceControlViewHost.SurfacePackage.class)
                    .invoke(view, pkg);
        } catch (Exception e) {
            Log.e(TAG, "setChildSurfacePackage failed", e);
        }
    }
}
