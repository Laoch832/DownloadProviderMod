package com.radiasync.downloadprovidermod;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 系统下载器优化模块 - 主入口
 *
 * 目标：
 * 1. com.android.providers.downloads.ui —— 去广告（AdBlocker）
 * 2. com.microsoft.emmx —— Edge 下载劫持到系统下载器（EdgeIntegration）
 *
 * @author RadiAsync
 * @version 2.0
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "DPM-Main";
    private static final String PKG_DOWNLOAD_UI = "com.android.providers.downloads.ui";
    private static final String PKG_EDGE = "com.microsoft.emmx";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        if (pkg == null) return;

        if (PKG_DOWNLOAD_UI.equals(pkg)) {
            XposedBridge.log(TAG + ": hooking " + pkg + " (process=" + lpparam.processName + ")");
            AdBlocker.hook(lpparam);
        } else if (PKG_EDGE.equals(pkg)) {
            XposedBridge.log(TAG + ": hooking " + pkg + " (process=" + lpparam.processName + ")");
            EdgeIntegration.hook(lpparam);
        }
    }
}
