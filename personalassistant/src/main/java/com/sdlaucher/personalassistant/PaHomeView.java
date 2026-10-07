/*
 * 负一屏信息流根视图：渲染在 companion 进程，经 SurfaceControlViewHost 嵌入桌面。
 *
 * 结构 = 问候头 + 卡片流 RecyclerView（本地 mock 数据）。触摸事件由系统直接路由到
 * 本视图树，滚动/点击在本进程内处理；"右滑回主屏"手势在本类检测，
 * 经 PaService 的 IPaCallback 请求桌面翻页。
 */
package com.sdlaucher.personalassistant;

import android.content.Context;
import android.app.WallpaperManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class PaHomeView extends FrameLayout {

    /** 用户在负一屏右滑，请求桌面切回主屏。 */
    interface SwipeToHomeListener {
        void onSwipeToHome();
    }

    private static final int CARD_WEATHER = 0;
    private static final int CARD_NEWS = 1;
    private static final int CARD_ACTIONS = 2;

    private static final String[][] NEWS_ITEMS = {
            {"公司团建活动报名已开启，本周五截止", "行政中心 · 10分钟前"},
            {"新版 SdLauncher3 发布：负一屏跨进程架构上线", "工程团队 · 1小时前"},
            {"深呼吸：工作间隙来一组桌面拉伸", "健康频道 · 2小时前"},
            {"今日已有 3 条日程待处理，点击查看详情", "日历 · 3小时前"},
    };

    private final SwipeToHomeListener mSwipeListener;
    private final GestureDetector mGestureDetector;
    private final TextView mGreetingView;
    private final TextView mDateView;

    public PaHomeView(Context context, SwipeToHomeListener swipeListener) {
        super(context);
        mSwipeListener = swipeListener;
        applyWallpaperBackground();
        inflate(context, R.layout.pa_home, this /* root, merge 布局挂到自身 */);

        mGreetingView = findViewById(R.id.pa_greeting);
        mDateView = findViewById(R.id.pa_date);
        refreshHeader();

        RecyclerView feed = findViewById(R.id.pa_feed);
        feed.setLayoutManager(new LinearLayoutManager(context));
        feed.setAdapter(new FeedAdapter());
        feed.setOverScrollMode(OVER_SCROLL_NEVER);

        mGestureDetector = new GestureDetector(context, new GestureListener());
    }

    /** 桌面可见性变化入口（经 PaService 转发），当前仅刷新问候语时间。 */
    public void onHostResumed() {
        refreshHeader();
    }

    /**
     * 壁纸感背景，三级策略：
     * 1) 静态壁纸 → 降采样 48px → 双线性放大到全屏（等效模糊）；
     * 2) 动态壁纸（无静态图可取）→ 反射取 WallpaperColors 主色，生成色调渐变——
     *    framework.jar 桩缺 getWallpaperColors，故走反射；
     * 3) 均失败 → 深色底。上层再叠 pa_home 半透明渐变遮罩保证文字可读。
     */
    private void applyWallpaperBackground() {
        Bitmap blurred = tryBlurWallpaper();
        if (blurred != null) {
            setBackground(new BitmapDrawable(getResources(), blurred));
            return;
        }
        Integer tint = tryWallpaperColor();
        if (tint != null) {
            setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{adjustColor(tint, 0.45f, 64), adjustColor(tint, 0.35f, 16)}));
            return;
        }
        setBackground(new ColorDrawable(0xFF141416));
    }

    /** 静态壁纸 → 模糊 bitmap；动态壁纸拿不到静态图，返回 null。 */
    private Bitmap tryBlurWallpaper() {
        try {
            Drawable wallpaper = WallpaperManager.getInstance(getContext()).getDrawable();
            if (wallpaper == null || wallpaper.getIntrinsicWidth() <= 0
                    || wallpaper.getIntrinsicHeight() <= 0) {
                return null;
            }
            Bitmap src = Bitmap.createBitmap(wallpaper.getIntrinsicWidth(),
                    wallpaper.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
            wallpaper.setBounds(0, 0, src.getWidth(), src.getHeight());
            wallpaper.draw(new Canvas(src));
            Bitmap small = Bitmap.createScaledBitmap(src, 48,
                    Math.max(1, 48 * src.getHeight() / src.getWidth()), true);
            DisplayMetrics dm = getResources().getDisplayMetrics();
            Bitmap blurred = Bitmap.createScaledBitmap(small, dm.widthPixels, dm.heightPixels, true);
            src.recycle();
            small.recycle();
            return blurred;
        } catch (Exception e) {
            Log.w("PaHomeView", "blur wallpaper failed", e);
            return null;
        }
    }

    /** 动态壁纸色调：反射 WallpaperManager.getWallpaperColors(FLAG_SYSTEM).getPrimaryColor()。 */
    private Integer tryWallpaperColor() {
        try {
            Object colors = WallpaperManager.class
                    .getMethod("getWallpaperColors", int.class)
                    .invoke(WallpaperManager.getInstance(getContext()), 1 /* FLAG_SYSTEM */);
            if (colors == null) {
                return null;
            }
            Object primary = colors.getClass().getMethod("getPrimaryColor").invoke(colors);
            return (int) primary.getClass().getMethod("toArgb").invoke(primary);
        } catch (Exception e) {
            Log.w("PaHomeView", "wallpaper color failed", e);
            return null;
        }
    }

    /** 主色按 mul 缩放并加 add 提亮/压暗，钳位到合法范围。 */
    private static int adjustColor(int color, float mul, int add) {
        int r = Math.max(0, Math.min(255, (int) (Color.red(color) * mul) + add));
        int g = Math.max(0, Math.min(255, (int) (Color.green(color) * mul) + add));
        int b = Math.max(0, Math.min(255, (int) (Color.blue(color) * mul) + add));
        return Color.rgb(r, g, b);
    }

    public void onHostPaused() {
    }

    private void refreshHeader() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int greetingRes = hour < 6 ? R.string.pa_greeting_night
                : hour < 12 ? R.string.pa_greeting_morning
                : hour < 18 ? R.string.pa_greeting_noon
                : R.string.pa_greeting_evening;
        mGreetingView.setText(greetingRes);
        String date = new SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(new Date());
        mDateView.setText(date);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // 喂给手势检测器；子视图(RecyclerView)纵向滚动不受影响，
        // 横向右滑（慢拖过 1/3 屏宽或快速甩动）命中时通知宿主关闭覆盖层
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mSwipeStartX = ev.getX();
            mSwipeStartY = ev.getY();
            mSwipeFired = false;
        }
        mGestureDetector.onTouchEvent(ev);
        return super.onInterceptTouchEvent(ev);
    }

    /** 本条手势流是否已触发过退出（一次滑动只触发一次） */
    private boolean mSwipeFired;
    private float mSwipeStartX;
    private float mSwipeStartY;

    private void fireSwipeToHome() {
        android.util.Log.d("PaHomeView", "fireSwipeToHome fired=" + mSwipeFired);
        if (!mSwipeFired) {
            mSwipeFired = true;
            mSwipeListener.onSwipeToHome();
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            if (mSwipeListener == null || e1 == null || e2 == null) {
                return false;
            }
            // 横向快速甩动（不分方向）→ 退出负一屏
            float dx = Math.abs(e2.getX() - e1.getX());
            if (dx > 120 && Math.abs(velocityX) > 800
                    && Math.abs(velocityX) > Math.abs(velocityY)) {
                fireSwipeToHome();
                return true;
            }
            return false;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
            // 慢拖：横向累计位移超过 1/3 屏宽且横向占优（不分方向）→ 退出负一屏
            if (mSwipeListener == null || e2 == null) {
                return false;
            }
            float totalDx = Math.abs(e2.getX() - mSwipeStartX);
            float totalDy = Math.abs(e2.getY() - mSwipeStartY);
            if (totalDx > getWidth() / 3f && totalDx > totalDy) {
                fireSwipeToHome();
                return true;
            }
            return false;
        }
    }

    /** 卡片流适配器：天气卡 / 新闻卡 / 快捷入口行，全部本地 mock。 */
    private class FeedAdapter extends RecyclerView.Adapter<FeedHolder> {

        @Override
        public int getItemCount() {
            return 2 + NEWS_ITEMS.length;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) return CARD_WEATHER;
            if (position == 1 + NEWS_ITEMS.length) return CARD_ACTIONS;
            return CARD_NEWS;
        }

        @Override
        public FeedHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            int layoutRes = viewType == CARD_ACTIONS ? R.layout.pa_card_actions : R.layout.pa_card;
            // attachToRoot 必须为 false：ViewHolder 由 RecyclerView 负责挂载
            return new FeedHolder(LayoutInflater.from(getContext())
                    .inflate(layoutRes, parent, false));
        }

        @Override
        public void onBindViewHolder(FeedHolder holder, int position) {
            int type = getItemViewType(position);
            if (type == CARD_WEATHER) {
                holder.title.setText(R.string.pa_weather_title);
                holder.subtitle.setText(R.string.pa_weather_desc);
            } else if (type == CARD_NEWS) {
                String[] item = NEWS_ITEMS[position - 1];
                holder.title.setText(item[0]);
                holder.subtitle.setText(item[1]);
            } else {
                holder.actionsView.setOnClickListener(v -> { /* 快捷入口占位 */ });
            }
        }
    }

    private static class FeedHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;
        final View actionsView;

        FeedHolder(android.view.View v) {
            super(v);
            title = v.findViewById(R.id.pa_card_title);
            subtitle = v.findViewById(R.id.pa_card_subtitle);
            actionsView = v.findViewById(R.id.pa_actions_row);
        }
    }
}
