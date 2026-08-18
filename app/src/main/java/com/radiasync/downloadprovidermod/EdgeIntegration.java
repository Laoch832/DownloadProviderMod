package com.radiasync.downloadprovidermod;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;

import java.io.File;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Edge Android (Chromium 151) 下载劫持 → 转发到小米系统下载器
 *
 * 核心 hook：org.chromium.components.download.DownloadCollectionBridge.a(String, String, String, String)
 * （native 调用、创建 Edge 自己的 pending 下载条目）
 * 在 Edge 下载开始的那一刻：
 *   1. 识别参数中的 URL / 文件名 / MIME
 *   2. 调用系统 DownloadManager.enqueue()（走小米 DownloadProvider 引擎）
 *   3. setResult(null) 使 Edge 自己的下载放弃 → 系统下载器成为唯一下载方
 *
 * 辅助 hook：DownloadManagerService.onDownloadItemCreated(DownloadItem)
 *   - 懒 hook 兜底 + 一次性清理旧版本误入队的任务
 *
 * 防误伤：
 *   - bridge.a 仅在「新建下载」时被 native 调用（历史恢复不经过），无需启动宽限期
 *   - 仅 http/https/ftp 转发（magnet/ed2k/blob 由 Edge 原生处理）
 *   - 60 秒内同一 URL 只转发一次（native 重试仍会继续杀 Edge 下载）
 *
 * @author RadiAsync
 * @version 3.0
 */
public class EdgeIntegration {

    private static final String TAG = "DPM-Edge";

    private static final String CLS_SERVICE = "org.chromium.chrome.browser.download.DownloadManagerService";
    private static final String CLS_ITEM = "org.chromium.chrome.browser.download.DownloadItem";
    private static final String CLS_BRIDGE = "org.chromium.components.download.DownloadCollectionBridge";

    /** 同一 URL 60 秒内只转发一次（native 可能重试多次） */
    private static final Map<String, Long> sForwardedAt = new HashMap<>();
    private static final long FORWARD_DEDUP_MS = 60_000L;

    /** 持久化记录：启动恢复期识别「已转发过的历史 URL」 */
    private static final String RECORD_FILE = "dpm_forwarded.txt";

    /** DownloadCollectionBridge 是否已 hook（懒加载类，需延迟 hook） */
    private static volatile boolean sBridgeHooked = false;

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;

        // ===== 1. 拦截下载条目创建：懒 hook + 一次性清理 =====
        try {
            Class<?> svc = XposedHelpers.findClass(CLS_SERVICE, cl);
            XposedHelpers.findAndHookMethod(svc, "onDownloadItemCreated", CLS_ITEM,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                ensureBridgeHooked(cl);
                                cleanupMisEnqueued(lpparam);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": item hook error: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked onDownloadItemCreated");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": FAIL hook onDownloadItemCreated: " + t.getMessage());
        }

        // 提前尝试 hook（若类已加载）
        ensureBridgeHooked(lpparam, cl);

        // 懒加载监听：Bridge 类被加载的瞬间立即 hook（保证在首次下载前生效）
        installBridgeLazyWatcher(lpparam);
    }

    /**
     * 监听 Edge classLoader 的 loadClass，DownloadCollectionBridge 一被加载就 hook 它。
     */
    private static void installBridgeLazyWatcher(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> loaderCls = lpparam.classLoader.getClass();
            XposedHelpers.findAndHookMethod(loaderCls, "loadClass", String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (CLS_BRIDGE.equals(param.args[0])) {
                                ensureBridgeHooked(lpparam, lpparam.classLoader);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": bridge watcher on " + loaderCls.getName());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": watcher fail: " + t.getMessage());
        }
    }

    /**
     * 核心拦截：bridge.a(displayName, mimeType, url, referer)
     * 识别 URL 后转发到系统下载器并阻止 Edge 自己的下载。
     */
    private static synchronized void ensureBridgeHooked(final XC_LoadPackage.LoadPackageParam lpparam,
                                                        final ClassLoader cl) {
        if (sBridgeHooked) return;
        try {
            Class<?> bridge = XposedHelpers.findClass(CLS_BRIDGE, cl);
            XposedBridge.hookAllMethods(bridge, "a", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        String url = null;
                        String fileName = null;
                        String mimeType = null;
                        for (Object arg : param.args) {
                            if (!(arg instanceof String)) continue;
                            String s = (String) arg;
                            if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("ftp://")) {
                                url = s;
                            } else if (isMimeType(s)) {
                                if (mimeType == null) mimeType = s;
                            } else if (!s.isEmpty() && !s.contains("://")) {
                                if (fileName == null) fileName = s;
                            }
                        }
                        if (url == null) return; // 非 http 下载（magnet 等）放行
                        // 注意：bridge.a 只在「新建下载」时被 native 调用，
                        // 历史恢复不会经过这里，因此无需启动宽限期。

                        // 去重（60s 内同一 URL 只转发一次，但每次都要杀 Edge 下载）
                        synchronized (sForwardedAt) {
                            Long last = sForwardedAt.get(url);
                            if (last != null && System.currentTimeMillis() - last < FORWARD_DEDUP_MS) {
                                XposedBridge.log(TAG + ": dup, still kill Edge: " + url);
                                param.setResult(null);
                                return;
                            }
                            sForwardedAt.put(url, System.currentTimeMillis());
                            if (sForwardedAt.size() > 128) {
                                sForwardedAt.clear();
                            }
                        }

                        XposedBridge.log(TAG + ": intercepted download: " + url);
                        enqueueSystemDownload(lpparam, url, fileName, mimeType);
                        // 阻止 Edge 自己的下载
                        param.setResult(null);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": bridge hook error: " + t);
                    }
                }
            });
            sBridgeHooked = true;
            XposedBridge.log(TAG + ": hooked DownloadCollectionBridge.a");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": bridge not ready (" + t.getClass().getSimpleName() + ")");
        }
    }

    private static void ensureBridgeHooked(final ClassLoader cl) {
        // 兼容旧调用（lpparam 为 null 时仅尝试加载类）
        try {
            Class<?> bridge = XposedHelpers.findClass(CLS_BRIDGE, cl);
            if (!sBridgeHooked) {
                XposedBridge.log(TAG + ": bridge loaded but not hooked yet");
            }
        } catch (Throwable ignore) { }
    }

    /**
     * 一次性清理 v2.3 在 Edge 启动时误转发的历史下载任务（仅执行一次）。
     */
    private static void cleanupMisEnqueued(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Context ctx = getAppContext(lpparam);
            if (ctx == null) return;
            if (ctx.getSharedPreferences("dpm_mod", Context.MODE_PRIVATE)
                    .getBoolean("cleanup_done", false)) return;

            DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) return;
            int removed = 0;
            for (long id = 1609; id <= 1650; id++) {
                try {
                    if (dm.remove(id) > 0) removed++;
                } catch (Throwable ignore) { }
            }
            ctx.getSharedPreferences("dpm_mod", Context.MODE_PRIVATE)
                    .edit().putBoolean("cleanup_done", true).apply();
            XposedBridge.log(TAG + ": cleanup removed " + removed + " mis-enqueued downloads");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": cleanup error: " + t.getMessage());
        }
    }

    /**
     * 获取 Edge 的 Application Context
     */
    private static Context getAppContext(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> ctxUtils = XposedHelpers.findClass("org.chromium.base.ContextUtils",
                    lpparam.classLoader);
            android.app.Application app = (android.app.Application)
                    XposedHelpers.callStaticMethod(ctxUtils, "getApplicationContext");
            if (app != null) return app;
        } catch (Throwable ignore) { }
        try {
            Class<?> at = XposedHelpers.findClass("android.app.ActivityThread",
                    lpparam.classLoader);
            return (Context) XposedHelpers.callStaticMethod(at, "currentApplication");
        } catch (Throwable ignore) { }
        return null;
    }

    /**
     * 调用系统 DownloadManager（走小米 DownloadProvider 引擎）
     */
    private static void enqueueSystemDownload(XC_LoadPackage.LoadPackageParam lpparam,
                                              String url, String fileName, String mimeType) {
        try {
            Context ctx = getAppContext(lpparam);
            if (ctx == null) {
                XposedBridge.log(TAG + ": no context, abort");
                return;
            }
            DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                XposedBridge.log(TAG + ": DownloadManager unavailable");
                return;
            }

            if (fileName == null || fileName.isEmpty()) {
                fileName = guessFileName(url);
            }

            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            if (mimeType != null && !mimeType.isEmpty()) {
                req.setMimeType(mimeType);
            }
            req.setTitle(fileName);
            req.setDescription("From Edge (RadiAsync mod)");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            req.setAllowedOverRoaming(true);
            req.setAllowedOverMetered(true);
            req.addRequestHeader("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 EdgA/120.0.0.0");

            long id = dm.enqueue(req);
            XposedBridge.log(TAG + ": enqueued system download id=" + id + " file=" + fileName);
            markForwardedPersist(ctx, url);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": enqueue error: " + t);
        }
    }

    /**
     * 记录已转发 URL（用于启动恢复期识别）
     */
    private static void markForwardedPersist(Context ctx, String url) {
        try {
            File f = new File(ctx.getFilesDir(), RECORD_FILE);
            FileWriter fw = new FileWriter(f, true);
            fw.write(Integer.toHexString(url.hashCode()) + " " + System.currentTimeMillis() + "\n");
            fw.close();
        } catch (Throwable t) { }
    }

    /**
     * 判断字符串是否像 MIME 类型（如 application/vnd.android.package-archive）
     */
    private static boolean isMimeType(String s) {
        if (!s.contains("/") || s.contains("://") || s.contains(" ")) return false;
        int slash = s.indexOf('/');
        String subtype = s.substring(slash + 1);
        // MIME 子类型通常不含 "."（文件名如 a/b.c 不是 MIME）
        return !subtype.contains(".");
    }

    private static String guessFileName(String url) {
        try {
            String u = url;
            int q = u.indexOf('?');
            if (q > 0) u = u.substring(0, q);
            String name = u.substring(u.lastIndexOf('/') + 1);
            if (name.isEmpty()) name = "download_" + System.currentTimeMillis();
            return name;
        } catch (Throwable t) {
            return "download_" + System.currentTimeMillis();
        }
    }
}
