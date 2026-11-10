package io.github.dour02.sbedge;

import android.app.Application;
import android.content.ContentResolver;
import android.content.res.Resources;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * One UI 状态栏左右边界。
 *
 * One UI 的状态栏（IndicatorGarden）按挖孔类型选用不同的布局算法类，
 * 每个算法类用 calculateLeftPadding / calculateRightPadding 算出内容到屏幕边缘的总距离，
 * 其中包括系统在两侧留出的那条空白边。这里把这两个方法的返回值直接替换成设定值，
 * 所以设为 0 时内容会真正贴到屏幕边缘，而不仅仅是贴到那条空白边上。
 *
 * 设定值通过 Settings.Global 读取（单位 dp），修改后重启 SystemUI 生效：
 *   settings put global sbedge_left_dp 0
 *   settings put global sbedge_right_dp 0
 * 不设置时默认 0；设为负数（如 -1）表示这一侧保持系统原值。
 */
public class Main extends XposedModule {
    private static final String TAG = "SbEdge";
    private static final String SYSTEMUI = "com.android.systemui";
    private static final String PREFIX = "com.android.systemui.statusbar.phone.";
    private static final String[] ALGORITHMS = {
            "IndicatorGardenAlgorithm",
            "IndicatorGardenAlgorithmNoCutout",
            "IndicatorGardenAlgorithmCenterCutout",
            "IndicatorGardenAlgorithmSidelingCenterCutout",
    };
    static final String KEY_LEFT = "sbedge_left_dp";
    static final String KEY_RIGHT = "sbedge_right_dp";
    private static final float DEFAULT_DP = 0f;

    private static volatile ContentResolver sResolver;

    private final Set<Method> hooked = new HashSet<>();
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!SYSTEMUI.equals(param.getPackageName()) || !param.isFirstPackage()) return;
        ClassLoader cl = param.getClassLoader();
        for (String simpleName : ALGORITHMS) {
            Class<?> clazz;
            try {
                clazz = Class.forName(PREFIX + simpleName, false, cl);
            } catch (Throwable t) {
                log(Log.INFO, TAG, "class not found: " + simpleName);
                continue;
            }
            dumpMethods(clazz);
            try {
                hookSide(clazz, "calculateLeftPadding", KEY_LEFT);
                hookSide(clazz, "calculateRightPadding", KEY_RIGHT);
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "hook failed: " + simpleName, t);
            }
        }
        log(hooked.isEmpty() ? Log.WARN : Log.INFO, TAG, "hooked " + hooked.size() + " method(s)");

        try {
            new Clock(this).install(cl);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "clock hook failed", t);
        }
    }

    /** 在 clazz 及其父类中找到第一个实现了 name 的类，hook 其中所有返回 int/float 的同名方法。 */
    private void hookSide(Class<?> clazz, String name, String key) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            boolean found = false;
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || Modifier.isAbstract(m.getModifiers())) continue;
                final Class<?> returnType = m.getReturnType();
                if (returnType != int.class && returnType != float.class) continue;
                found = true;
                if (!hooked.add(m)) continue;
                final String label = c.getSimpleName() + "." + name;
                hook(m).intercept(chain -> {
                    Object original = chain.proceed();
                    Float dp = readDp(key);
                    if (dp == null) return original;
                    float px = dp * density(chain.getThisObject());
                    Object result = returnType == int.class
                            ? (Object) Math.round(px)
                            : (Object) px;
                    if (reported.add(label)) {
                        log(Log.INFO, TAG, label + ": " + original + " -> " + result);
                    }
                    return result;
                });
                log(Log.INFO, TAG, "hooked " + m);
            }
            if (found) return;
        }
        // 抽象基类本身没有实现是正常的，只对具体算法类报警告
        log(Modifier.isAbstract(clazz.getModifiers()) ? Log.DEBUG : Log.WARN, TAG,
                name + " not found in " + clazz.getName());
    }

    /** 把类里声明的方法写进 LSPosed 日志，便于在不同 One UI 版本上排查。 */
    private void dumpMethods(Class<?> clazz) {
        StringBuilder sb = new StringBuilder(clazz.getName()).append(" extends ")
                .append(clazz.getSuperclass() == null ? "-" : clazz.getSuperclass().getName())
                .append(':');
        try {
            for (Method m : clazz.getDeclaredMethods()) {
                sb.append(' ').append(m.getName()).append('(').append(m.getParameterCount())
                        .append(")->").append(m.getReturnType().getSimpleName());
            }
        } catch (Throwable t) {
            sb.append(" <").append(t).append('>');
        }
        log(Log.DEBUG, TAG, sb.toString());
    }

    /** 读设定值（dp）。返回 null 表示保持系统原值。 */
    private static Float readDp(String key) {
        String value = readString(key);
        if (value == null || value.trim().isEmpty()) return DEFAULT_DP;
        try {
            float dp = Float.parseFloat(value.trim());
            return dp < 0 ? null : dp;
        } catch (NumberFormatException e) {
            return DEFAULT_DP;
        }
    }

    /** 读 Settings.Global 里的字符串，读不到返回 null。 */
    static String readString(String key) {
        try {
            ContentResolver cr = resolver();
            return cr == null ? null : Settings.Global.getString(cr, key);
        } catch (Throwable t) {
            return null;
        }
    }

    static float readFloat(String key, float def) {
        String value = readString(key);
        if (value == null) return def;
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static ContentResolver resolver() {
        ContentResolver cr = sResolver;
        if (cr != null) return cr;
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Application app = (Application) activityThread.getMethod("currentApplication").invoke(null);
            if (app != null) {
                cr = app.getContentResolver();
                sResolver = cr;
            }
        } catch (Throwable ignored) {
        }
        return cr;
    }

    /** 与 OneUIX 一致：优先用算法自身 inputProperties.density，取不到再用系统 density。 */
    private static float density(Object algorithm) {
        Object props = algorithm == null ? null : getField(algorithm, "inputProperties");
        Object d = props == null ? null : getField(props, "density");
        if (d instanceof Float && (Float) d > 0f) return (Float) d;
        return Resources.getSystem().getDisplayMetrics().density;
    }

    private static Object getField(Object obj, String name) {
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {
                // 继续找父类
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }
}
