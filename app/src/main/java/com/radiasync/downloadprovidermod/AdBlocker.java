package com.radiasync.downloadprovidermod;

import java.util.ArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 系统下载器去广告（基于 OS4.0.0.15 真实逆向的类与方法）
 *
 * @author RadiAsync
 * @version 2.1
 */
public class AdBlocker {

    private static final String TAG = "DPM-AdBlock";

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;

        // ===== 1. 主页横幅广告（Banner Ad）=====
        hookStatic(cl, "com.android.providers.downloads.ui.recommend.HomePageRecommendApi",
                "getBannerAdAppList", true,
                long.class, String.class, String.class, boolean.class);

        // ===== 2. 主页推荐应用列表 =====
        hookStatic(cl, "com.android.providers.downloads.ui.recommend.HomePageRecommendApi",
                "getAppSubject", false, String.class, boolean.class);
        hookStatic(cl, "com.android.providers.downloads.ui.recommend.HomePageRecommendApi",
                "getDetailPageRecommend", true, String.class, String.class);

        // ===== 3. 排行榜 + 排行推荐 =====
        hookStatic(cl, "com.android.providers.downloads.ui.recommend.RankListRecommendApi",
                "getRankList", true, String.class, int.class);
        hookStatic(cl, "com.android.providers.downloads.ui.recommend.RankListRecommendApi",
                "getRankRecomendList", true, String.class, String.class);

        // ===== 4. AppRecommendManager 单例（主页/详情页推荐应用）=====
        hookStatic(cl, "com.android.providers.downloads.ui.recommend.AppRecommendManager",
                "getHomePageRecommendApps", true, boolean.class, boolean.class);
        hookStatic(cl, "com.android.providers.downloads.ui.recommend.AppRecommendManager",
                "getDetailPageRecommendApps", true, String.class, String.class, int.class);
        hookStatic(cl, "com.android.providers.downloads.ui.recommend.AppRecommendManager",
                "getAppSubject", false, boolean.class);

        // ===== 5. MIUI 广告 SDK 请求基类（兜底：广告接口地址置空）=====
        try {
            Class<?> baseReq = XposedHelpers.findClass(
                    "com.android.providers.downloads.ui.api.miuiad.BaseNewMiuiRequest", cl);
            XposedHelpers.findAndHookMethod(baseReq, "getBaseApiUrl",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            XposedBridge.log(TAG + ": blocked ad API url");
                            return "";
                        }
                    });
        } catch (Throwable t) {
            logErr("BaseNewMiuiRequest", t);
        }

        // ===== 6. 广告 WebView 页面（process=:ad）=====
        hookFinishOnCreate(cl, "com.android.providers.downloads.ui.activity.LpWebViewActivity", "ad webview");

        // ===== 7. 信息流设置页面 =====
        hookFinishOnCreate(cl, "com.android.providers.downloads.ui.activity.InfoFlowSettingActivity", "infoflow setting");

        // ===== 8. 排行页 → 实时网速曲线 =====
        injectSpeedCurve(cl);
    }

    /** 排行页 fragment（I0.q）注入实时网速曲线，替换热榜内容 */
    private static android.view.View sCurveView;

    private static void injectSpeedCurve(final ClassLoader cl) {
        try {
            Class<?> rankPage = XposedHelpers.findClass("I0.q", cl);
            XposedHelpers.findAndHookMethod(rankPage, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        final Object frag = param.thisObject;
                        android.view.View root = (android.view.View) XposedHelpers.callMethod(frag, "getView");
                        if (root == null) return;

                        // 隐藏原内容（RecyclerView f / EmptyStateView K）
                        try {
                            android.view.View rv = (android.view.View) XposedHelpers.getObjectField(frag, "f");
                            if (rv != null) rv.setVisibility(android.view.View.GONE);
                            android.view.View ev = (android.view.View) XposedHelpers.getObjectField(frag, "K");
                            if (ev != null) ev.setVisibility(android.view.View.GONE);
                        } catch (Throwable ignore) { }

                        if (!(root instanceof android.view.ViewGroup)) return;
                        android.view.ViewGroup vg = (android.view.ViewGroup) root;
                        if (sCurveView != null && sCurveView.getParent() == vg) return; // 已注入
                        if (sCurveView != null && sCurveView.getParent() != null) {
                            ((android.view.ViewGroup) sCurveView.getParent()).removeView(sCurveView);
                        }
                        SpeedCurveView curve = new SpeedCurveView(vg.getContext());
                        vg.addView(curve, new android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
                        curve.suppress(frag);
                        sCurveView = curve;
                        XposedBridge.log(TAG + ": speed curve injected into rank page");
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": inject speed curve fail: " + t.getMessage());
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked rank page (I0.q)");
        } catch (Throwable t) {
            logErr("I0.q", t);
        }
    }

    /**
     * Hook 方法：按参数类型定位，返回空列表 / null
     */
    private static void hookStatic(final ClassLoader cl, final String cls, final String method,
                                   final boolean returnEmptyList, final Class<?>... paramTypes) {
        try {
            Class<?> c = XposedHelpers.findClass(cls, cl);
            // 构造 findAndHookMethod 参数：paramTypes + callback
            Object[] args = new Object[paramTypes.length + 1];
            System.arraycopy(paramTypes, 0, args, 0, paramTypes.length);
            args[paramTypes.length] = new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    XposedBridge.log(TAG + ": blocked " + cls.substring(cls.lastIndexOf('.') + 1)
                            + "." + method);
                    return returnEmptyList ? new ArrayList<>() : null;
                }
            };
            XposedHelpers.findAndHookMethod(c, method, args);
            XposedBridge.log(TAG + ": hooked " + cls.substring(cls.lastIndexOf('.') + 1) + "." + method);
        } catch (Throwable t) {
            logErr(cls + "." + method, t);
        }
    }

    /**
     * Hook Activity.onCreate：立即 finish
     */
    private static void hookFinishOnCreate(final ClassLoader cl, final String cls, final String what) {
        try {
            Class<?> c = XposedHelpers.findClass(cls, cl);
            XposedHelpers.findAndHookMethod(c, "onCreate", "android.os.Bundle",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            XposedBridge.log(TAG + ": finished " + what);
                            ((android.app.Activity) param.thisObject).finish();
                        }
                    });
            XposedBridge.log(TAG + ": hooked " + cls.substring(cls.lastIndexOf('.') + 1));
        } catch (Throwable t) {
            logErr(cls, t);
        }
    }

    private static void logErr(String where, Throwable t) {
        XposedBridge.log(TAG + ": FAIL " + where + " -> " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }
}
