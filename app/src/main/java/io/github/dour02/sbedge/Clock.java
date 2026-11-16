package io.github.dour02.sbedge;

import android.content.res.Resources;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
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
 *   settings put global sbedge_clock_bold 1                     # 两行都加粗（可选）
 *   settings put global sbedge_clock_line2_bold 1               # 只加粗第二行（可选，覆盖上一项）
 *   settings put global sbedge_clock_line_spacing 0.9           # 行间距倍数，默认 0.9（1.0 为正常）
 *   settings put global sbedge_clock_line_extra_dp -1           # 再额外加减的行距（dp，可为负）
 */
final class Clock {
    private static final String TAG = "SbEdge";
    private static final String CLOCK_CLASS = "com.android.systemui.statusbar.policy.QSClockIndicatorView";
    static final String KEY_FORMAT = "sbedge_clock_format";
    static final String KEY_SCALE = "sbedge_clock_scale";
    static final String KEY_LINE1_SCALE = "sbedge_clock_line1_scale";
    static final String KEY_LINE2_SCALE = "sbedge_clock_line2_scale";
    static final String KEY_BOLD = "sbedge_clock_bold";
    static final String KEY_LINE1_BOLD = "sbedge_clock_line1_bold";
    static final String KEY_LINE2_BOLD = "sbedge_clock_line2_bold";
    private static final float DEFAULT_SCALE = 0.6f;
    static final String KEY_LINE_SPACING = "sbedge_clock_line_spacing";
    static final String KEY_LINE_EXTRA_DP = "sbedge_clock_line_extra_dp";
    private static final float DEFAULT_LINE_SPACING = 0.9f;

    private final XposedModule module;
    /** 每个时钟控件的原始字号（px），避免每分钟重复缩小。 */
    private final Map<TextView, Float> baseSize = Collections.synchronizedMap(new WeakHashMap<>());
    /** 被改过的控件及其原始行间距 {extra, multiplier}，用于删除设置后恢复。 */
    private final Map<TextView, float[]> modified = Collections.synchronizedMap(new WeakHashMap<>());
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

        // 粗体同样可分行设置；没单独设置的行用 sbedge_clock_bold
        boolean bold = readBool(KEY_BOLD, false);
        boolean bold1 = readBool(KEY_LINE1_BOLD, bold);
        boolean bold2 = readBool(KEY_LINE2_BOLD, bold);

        // 第一行 [0, end1)，第二行 [start2, length)；只有一行时整段按第一行处理
        int end1 = newline >= 0 ? newline : text.length();
        int start2 = newline >= 0 ? newline + 1 : text.length();
        boolean sizeSpans = newline >= 0 && scale1 != scale2;
        CharSequence shown = text;
        if (sizeSpans || bold1 || (bold2 && start2 < text.length())) {
            SpannableString span = new SpannableString(text);
            if (sizeSpans) {
                span.setSpan(new RelativeSizeSpan(scale1 / maxScale), 0, end1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                span.setSpan(new RelativeSizeSpan(scale2 / maxScale), start2, text.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (bold1 && end1 > 0) {
                span.setSpan(new StyleSpan(Typeface.BOLD), 0, end1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (bold2 && start2 < text.length()) {
                span.setSpan(new StyleSpan(Typeface.BOLD), start2, text.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            shown = span;
        }

        if (modified.get(tv) == null) {
            modified.put(tv, new float[]{tv.getLineSpacingExtra(), tv.getLineSpacingMultiplier()});
            tv.setSingleLine(false);
            tv.setHorizontallyScrolling(false);
            tv.setEllipsize(null);
            tv.setIncludeFontPadding(false);
            tv.setGravity(Gravity.CENTER);
        }

        // 行间距：倍数（1.0 为正常）加上额外的 dp（可为负）
        float multiplier = Main.readFloat(KEY_LINE_SPACING, DEFAULT_LINE_SPACING);
        if (multiplier <= 0f || multiplier > 3f) multiplier = DEFAULT_LINE_SPACING;
        float extraDp = Main.readFloat(KEY_LINE_EXTRA_DP, 0f);
        if (Float.isNaN(extraDp) || Math.abs(extraDp) > 50f) extraDp = 0f;
        float extraPx = extraDp * tv.getResources().getDisplayMetrics().density;
        if (Math.abs(tv.getLineSpacingMultiplier() - multiplier) > 0.001f
                || Math.abs(tv.getLineSpacingExtra() - extraPx) > 0.5f) {
            tv.setLineSpacing(extraPx, multiplier);
        }
        tv.setMaxLines(newline >= 0 ? 2 : 1);
        if (Math.abs(tv.getTextSize() - size) > 0.5f) tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        tv.setText(shown);
        tv.setContentDescription(text.replace('\n', ' '));

        String id = idName(tv);
        if (reported.add("view:" + id)) {
            module.log(Log.INFO, TAG, "two-line clock applied to " + id + " size " + base + "px, line1 x"
                    + scale1 + (bold1 ? " bold" : "") + ", line2 x" + scale2 + (bold2 ? " bold" : ""));
        }
    }

    private static float sanitize(float value, float fallback) {
        return value > 0f && value <= 3f ? value : fallback;
    }

    /** 1/true/on/yes 为开，0/false/off/no 为关，没设置或无法识别时用 def。 */
    private static boolean readBool(String key, boolean def) {
        String value = Main.readString(key);
        if (value == null) return def;
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1": case "true": case "on": case "yes":
                return true;
            case "0": case "false": case "off": case "no":
                return false;
            default:
                return def;
        }
    }

    /** 删除设置后恢复单行和原字号（文字由系统在下一次更新时写回）。 */
    private void restore(TextView tv) {
        float[] spacing = modified.remove(tv);
        if (spacing == null) return;
        tv.setLineSpacing(spacing[0], spacing[1]);
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
