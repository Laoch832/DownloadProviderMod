package com.radiasync.downloadprovidermod;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.ArrayDeque;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 实时网速曲线视图 —— 替换系统下载器「下载热榜」页
 *
 * @author RadiAsync
 * @version 1.0
 */
public class SpeedCurveView extends View {

    private static final String TAG = "DPM-Speed";
    private static final int MAX_POINTS = 90;
    private static final long SAMPLE_MS = 500L;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Float> mSpeeds = new ArrayDeque<>(); // KB/s
    private long mLastRx = -1;
    private long mLastTs = 0;
    private float mCurrentKbps;
    private float mPeakKbps;
    private float mAvgKbps;
    private long mSamples;

    private boolean mDark;
    private int mTextColor;
    private int mAccentColor;
    private int mGridColor;

    private final Paint mLinePaint = new Paint();
    private final Paint mFillPaint = new Paint();
    private final Paint mGridPaint = new Paint();
    private final Paint mTextPaint = new Paint();
    private final Paint mSubTextPaint = new Paint();

    /** 被替换页面的 fragment（用于压制原内容） */
    private Object mSuppressTarget;

    private final Runnable mSample = new Runnable() {
        @Override
        public void run() {
            sampleOnce();
            mHandler.postDelayed(this, SAMPLE_MS);
        }
    };

    public SpeedCurveView(Context context) {
        super(context);
        mDark = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        mTextColor = mDark ? 0xFFE8E8E8 : 0xFF1A1A1A;
        mAccentColor = 0xFF0D84FF;
        mGridColor = mDark ? 0x22FFFFFF : 0x22000000;

        mLinePaint.setAntiAlias(true);
        mLinePaint.setStyle(Paint.Style.STROKE);
        mLinePaint.setStrokeWidth(dp(1.6f));
        mLinePaint.setStrokeCap(Paint.Cap.ROUND);
        mLinePaint.setColor(mAccentColor);

        mFillPaint.setAntiAlias(true);
        mFillPaint.setStyle(Paint.Style.FILL);
        mFillPaint.setColor(mDark ? 0x1A0D84FF : 0x140D84FF);

        mGridPaint.setAntiAlias(true);
        mGridPaint.setStyle(Paint.Style.STROKE);
        mGridPaint.setStrokeWidth(1f);
        mGridPaint.setColor(mGridColor);

        mTextPaint.setAntiAlias(true);
        mTextPaint.setColor(mTextColor);
        mTextPaint.setTextSize(dp(15f));
        mTextPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        mSubTextPaint.setAntiAlias(true);
        mSubTextPaint.setColor(mDark ? 0x99E8E8E8 : 0x991A1A1A);
        mSubTextPaint.setTextSize(dp(12f));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mHandler.removeCallbacks(mSample);
        mHandler.post(mSample);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mHandler.removeCallbacks(mSample);
    }

    /**
     * 设置被替换的 fragment，每次采样时压制其原内容（列表/空状态）
     */
    public void suppress(Object fragment) {
        mSuppressTarget = fragment;
    }

    private void sampleOnce() {
        try {
            long rx = TrafficStats.getTotalRxBytes();
            long now = System.currentTimeMillis();
            if (mLastRx >= 0) {
                long dt = now - mLastTs;
                if (dt > 0) {
                    float kbps = (rx - mLastRx) * 1000f / 1024f / dt;
                    mCurrentKbps = kbps;
                    mSpeeds.addLast(kbps);
                    while (mSpeeds.size() > MAX_POINTS) mSpeeds.removeFirst();
                    if (kbps > mPeakKbps) mPeakKbps = kbps;
                    mAvgKbps = mAvgKbps + (kbps - mAvgKbps) / (++mSamples > 1000 ? 1000 : mSamples);
                }
            }
            mLastRx = rx;
            mLastTs = now;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": sample error: " + t.getMessage());
        }
        suppressOrigin();
        invalidate();
    }

    private void suppressOrigin() {
        if (mSuppressTarget == null) return;
        try {
            View rv = (View) XposedHelpers.getObjectField(mSuppressTarget, "f");
            if (rv != null && rv.getVisibility() != GONE) rv.setVisibility(GONE);
            View ev = (View) XposedHelpers.getObjectField(mSuppressTarget, "K");
            if (ev != null && ev.getVisibility() != GONE) ev.setVisibility(GONE);
        } catch (Throwable ignore) { }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float padL = dp(20), padR = dp(20), padT = dp(18), padB = dp(14);

        // 标题 + 当前速度
        canvas.drawText("实时网速", padL, padT + dp(18), mTextPaint);
        String cur = formatSpeed(mCurrentKbps);
        float curW = mTextPaint.measureText(cur);
        canvas.drawText(cur, w - padR - curW, padT + dp(18), mTextPaint);

        // 图表区域
        float chartTop = padT + dp(34);
        float chartBottom = h - padB - dp(22);
        float chartLeft = padL;
        float chartRight = w - padR;
        float chartH = chartBottom - chartTop;

        // 动态峰值（至少 1 KB/s）
        float maxK = Math.max(mPeakKbps, 64f);
        // 网格线（4 条横线）
        for (int i = 0; i <= 4; i++) {
            float y = chartTop + chartH * i / 4f;
            canvas.drawLine(chartLeft, y, chartRight, y, mGridPaint);
            String label = formatSpeed(maxK * (4 - i) / 4f);
            canvas.drawText(label, chartLeft, y - dp(4), mSubTextPaint);
        }

        // 曲线
        int n = mSpeeds.size();
        if (n >= 2) {
            float stepX = (chartRight - chartLeft) / (float) (MAX_POINTS - 1);
            Path path = new Path();
            Path fill = new Path();
            int idx = 0;
            for (Float v : mSpeeds) {
                float x = chartLeft + (MAX_POINTS - n + idx) * stepX;
                float y = chartBottom - (v / maxK) * chartH;
                if (y < chartTop) y = chartTop;
                if (idx == 0) {
                    path.moveTo(x, y);
                    fill.moveTo(x, chartBottom);
                    fill.lineTo(x, y);
                } else {
                    path.lineTo(x, y);
                    fill.lineTo(x, y);
                }
                idx++;
            }
            float lastX = chartLeft + (MAX_POINTS - 1) * stepX;
            fill.lineTo(lastX, chartBottom);
            fill.close();
            canvas.drawPath(fill, mFillPaint);
            canvas.drawPath(path, mLinePaint);
        }

        // 底部统计
        String peak = "峰值 " + formatSpeed(mPeakKbps);
        String avg = "平均 " + formatSpeed(mAvgKbps);
        canvas.drawText(peak, chartLeft, h - padB, mSubTextPaint);
        float avgW = mSubTextPaint.measureText(avg);
        canvas.drawText(avg, chartRight - avgW, h - padB, mSubTextPaint);
    }

    private static String formatSpeed(float kbps) {
        if (kbps < 1024f) return String.format(java.util.Locale.US, "%.0f KB/s", kbps);
        if (kbps < 1024f * 1024f) return String.format(java.util.Locale.US, "%.1f MB/s", kbps / 1024f);
        return String.format(java.util.Locale.US, "%.2f GB/s", kbps / 1024f / 1024f);
    }
}
