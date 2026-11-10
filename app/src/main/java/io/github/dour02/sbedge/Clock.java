package io.github.dour02.sbedge;

import android.content.res.Resources;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * 状态栏双行时钟（日期一行、时间一行）。
 *
 * 不替换 One UI 的时钟逻辑：每次 QSClockIndicatorView.notifyTimeChanged 执行完后，
 * 按设定的格式重写文字，并把控件改成两行、缩小字号。
 *
 *   settings put global sbedge_clock_format 'M月d日 E\nHH:mm'   # \n 表示换行；删除这项即恢复原样
 *   settings put global sbedge_clock_scale 0.6                  # 相对原字号的比例，默认 0.6
 *   settings put global sbedge_clock_line1_scale 0.5            # 第一行单独设置（可选）
 *   settings put global sbedge_clock_line2_scale 0.7            # 第二行单独设置（可选）
 */
final class Clock {
    private static final String TAG = "SbEdge";
    private static final String CLOCK_CLASS = "com.android.systemui.statusbar.policy.QSClockIndicatorView";
    static final String KEY_FORMAT = "sbedge_clock_format";
    static final String KEY_SCALE = "sbedge_clock_scale";
    static final String KEY_LINE1_SCALE = "sbedge_clock_line1_scale";
    static final String KEY_LINE2_SCALE = "sbedge_clock_line2_scale";
    private static final float DEFAULT_SCALE = 0.6f;

    private final XposedModule module;
    /** 每个时钟控件的原始字号（px），避免每分钟重复缩小。 */
    private final Map<TextView, Float> baseSize = Collections.synchronizedMap(new WeakHashMap<>());
    /** 原始的单行状态，用于删除设置后恢复。 */
    private final Map<TextView, Boolean> modified = Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    Clock(XposedModule module) {
        this.module = module;
    }

    void install(ClassLoader cl) {
        Class<?> clazz;
        try {
            clazz = Class.forName(CLOCK_CLASS, false, cl);
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "clock class not found: " + CLOCK_CLASS);
            return;
        }
        int count = 0;
        for (Method m : clazz.getDeclaredMethods()) {
            if (!m.getName().equals("notifyTimeChanged")) continue;
            module.hook(m).intercept(chain -> {
                Object result = chain.proceed();
                Object view = chain.getThisObject();
                if (view instanceof TextView) {
                    try {
                        apply((TextView) view);
                    } catch (Throwable t) {
                        module.log(Log.ERROR, TAG, "clock apply failed", t);
                    }
                }
                return result;
            });
            count++;
            module.log(Log.INFO, TAG, "hooked " + m);
        }
        if (count == 0) module.log(Log.WARN, TAG, "notifyTimeChanged not found in " + CLOCK_CLASS);
    }

    private void apply(TextView tv) {
        String format = Main.readString(KEY_FORMAT);
        if (format == null || format.trim().isEmpty()) {
            restore(tv);
            return;
        }
        String text;
        try {
            text = new SimpleDateFormat(format.replace("\\n", "\n"), Locale.getDefault()).format(new Date());
        } catch (IllegalArgumentException e) {
            if (reported.add("bad:" + format)) module.log(Log.WARN, TAG, "bad clock format: " + format);
            return;
        }

        Float base = baseSize.get(tv);
        if (base == null) {
            base = tv.getTextSize();
            baseSize.put(tv, base);
        }
        // 两行可分别设置；没单独设置的行用 sbedge_clock_scale
        float scale = sanitize(Main.readFloat(KEY_SCALE, DEFAULT_SCALE), DEFAULT_SCALE);
        float scale1 = sanitize(Main.readFloat(KEY_LINE1_SCALE, scale), scale);
        float scale2 = sanitize(Main.readFloat(KEY_LINE2_SCALE, scale), scale);
        int newline = text.indexOf('\n');
        float maxScale = newline >= 0 ? Math.max(scale1, scale2) : scale1;
        float size = base * maxScale;

        CharSequence shown = text;
        if (newline >= 0 && scale1 != scale2) {
            SpannableString span = new SpannableString(text);
            span.setSpan(new RelativeSizeSpan(scale1 / maxScale), 0, newline,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(new RelativeSizeSpan(scale2 / maxScale), newline + 1, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            shown = span;
        }

        if (modified.get(tv) == null) {
            modified.put(tv, Boolean.TRUE);
            tv.setSingleLine(false);
            tv.setHorizontallyScrolling(false);
            tv.setEllipsize(null);
            tv.setIncludeFontPadding(false);
            tv.setLineSpacing(0f, 0.9f);
            tv.setGravity(Gravity.CENTER);
        }
        tv.setMaxLines(newline >= 0 ? 2 : 1);
        if (Math.abs(tv.getTextSize() - size) > 0.5f) tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        tv.setText(shown);
        tv.setContentDescription(text.replace('\n', ' '));

        String id = idName(tv);
        if (reported.add("view:" + id)) {
            module.log(Log.INFO, TAG, "two-line clock applied to " + id + " size " + base + "px, line1 x"
                    + scale1 + ", line2 x" + scale2);
        }
    }

    private static float sanitize(float value, float fallback) {
        return value > 0f && value <= 3f ? value : fallback;
    }

    /** 删除设置后恢复单行和原字号（文字由系统在下一次更新时写回）。 */
    private void restore(TextView tv) {
        if (modified.remove(tv) == null) return;
        tv.setMaxLines(1);
        tv.setSingleLine(true);
        Float base = baseSize.remove(tv);
        if (base != null) tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, base);
    }

    private static String idName(TextView tv) {
        try {
            int id = tv.getId();
            if (id == -1) return "no-id";
            Resources res = tv.getResources();
            return res.getResourceEntryName(id);
        } catch (Throwable t) {
            return String.valueOf(tv.getId());
        }
    }
}
