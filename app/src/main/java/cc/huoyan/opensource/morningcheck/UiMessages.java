package cc.huoyan.opensource.morningcheck;

import android.content.Context;
import android.content.res.Resources;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在展示边界翻译旧领域文案；不修改持久化值、接口协议或用户填写的姓名与部门。
 */
public final class UiMessages {
    private static final Map<Resources, Catalog> CATALOGS = new WeakHashMap<>();

    private UiMessages() {
    }

    public static boolean isEnglish(Context context) {
        // 读取实际选中的语言资源，系统首选语言不受支持时也能与后备英文界面一致。
        return context != null && context.getResources().getBoolean(R.bool.ui_language_english);
    }

    public static String text(Context context, String value) {
        if (value == null || value.isEmpty() || !isEnglish(context)) return value;
        return translate(catalog(context.getResources()), value, 0);
    }

    private static synchronized Catalog catalog(Resources resources) {
        Catalog result = CATALOGS.get(resources);
        if (result == null) {
            result = new Catalog(resources);
            CATALOGS.put(resources, result);
        }
        return result;
    }

    private static String translate(Catalog catalog, String value, int depth) {
        if (depth > 8 || value.isEmpty()) return value;
        String exact = catalog.exact.get(value);
        if (exact != null) return exact;
        if (value.indexOf('\n') >= 0) {
            String[] lines = value.split("\\n", -1);
            for (int index = 0; index < lines.length; index++)
                lines[index] = translate(catalog, lines[index], depth + 1);
            return String.join("\n", lines);
        }
        for (Rule rule : catalog.rules) {
            String converted = rule.apply(catalog, value, depth);
            if (converted != null) return converted;
        }
        // 仅处理全部由已知检测项组成的摘要，任一未知中文项都会保留原文。
        for (String separator : new String[]{"；", "、", " · "}) {
            if (!value.contains(separator)) continue;
            String[] parts = value.split(Pattern.quote(separator), -1);
            boolean changed = false;
            boolean complete = true;
            for (int index = 0; index < parts.length; index++) {
                String original = parts[index];
                parts[index] = translate(catalog, original, depth + 1);
                changed |= !original.equals(parts[index]);
                if (original.equals(parts[index]) && containsChinese(original)) complete = false;
            }
            if (changed && complete)
                return String.join("；".equals(separator) ? "; " : "、".equals(separator) ? ", " : " · ", parts);
        }
        return value;
    }

    private static boolean containsChinese(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character >= '\u3400' && character <= '\u9fff') return true;
        }
        return false;
    }

    private static final class Catalog {
        final Map<String, String> exact = new HashMap<>();
        final List<Rule> rules = new ArrayList<>();

        Catalog(Resources resources) {
            String[] sources = resources.getStringArray(R.array.ui_message_sources);
            String[] values = resources.getStringArray(R.array.ui_message_values);
            if (sources.length != values.length) throw new IllegalStateException("文案翻译数量不一致");
            for (int index = 0; index < sources.length; index++) exact.put(sources[index], values[index]);
            String[] templates = resources.getStringArray(R.array.ui_message_template_sources);
            String[] translated = resources.getStringArray(R.array.ui_message_template_values);
            if (templates.length != translated.length) throw new IllegalStateException("文案模板数量不一致");
            for (int index = 0; index < templates.length; index++) rules.add(new Rule(templates[index], translated[index]));
        }
    }

    private static final class Rule {
        private static final Pattern ARGUMENT = Pattern.compile("%([1-9][0-9]*)\\$s");
        final Pattern pattern;
        final String prefix;
        final String translated;
        final boolean findingSummary;
        final boolean certificateValue;
        final List<Integer> arguments = new ArrayList<>();

        Rule(String source, String translated) {
            this.translated = translated;
            findingSummary = source.startsWith("手背：") || source.startsWith("手心：")
                    || source.startsWith("手背检查 · ") || source.startsWith("手心检查 · ")
                    || source.equals("%1$s等%2$s项");
            certificateValue = source.equals("激活码：%1$s") || source.equals("授权类型：%1$s")
                    || source.equals("模型：%1$s") || source.equals("SDK 版本：%1$s")
                    || source.equals("激活时间：%1$s") || source.equals("签发时间：%1$s")
                    || source.equals("最近续期：%1$s") || source.equals("下次续期：%1$s")
                    || source.equals("到期时间：%1$s");
            Matcher placeholders = ARGUMENT.matcher(source);
            StringBuilder expression = new StringBuilder("\\A");
            int offset = 0;
            int first = source.indexOf('%');
            prefix = first < 0 ? source : source.substring(0, first);
            while (placeholders.find()) {
                expression.append(Pattern.quote(source.substring(offset, placeholders.start()))).append("([\\s\\S]*?)");
                arguments.add(Integer.parseInt(placeholders.group(1)) - 1);
                offset = placeholders.end();
            }
            expression.append(Pattern.quote(source.substring(offset))).append("\\z");
            pattern = Pattern.compile(expression.toString());
        }

        String apply(Catalog catalog, String value, int depth) {
            if (!prefix.isEmpty() && !value.startsWith(prefix)) return null;
            Matcher matched = pattern.matcher(value);
            if (!matched.matches()) return null;
            Object[] values = new Object[arguments.size()];
            for (int index = 0; index < arguments.size(); index++) {
                String original = matched.group(index + 1);
                // 证书编码可能恰与“手背”等界面词相同，只翻译固定标签和明确的缺省占位。
                String converted = certificateValue ? certificateArgument(catalog, original)
                        : translate(catalog, original, depth + 1);
                // 检测摘要只能包含已知检测项；未知中文可能来自用户内容，不能只翻译标签。
                if (index == 0 && findingSummary && containsChinese(converted)) return null;
                // 以动态文字开头的模板只接受已知操作/检测项或无中文的编码，避免误改用户内容。
                if (index == 0 && prefix.isEmpty() && original.equals(converted) && containsChinese(original))
                    return null;
                values[arguments.get(index)] = converted;
            }
            return String.format(Locale.US, translated, values);
        }

        private String certificateArgument(Catalog catalog, String value) {
            // 模型行由编码与通道拼成，逐项识别 formatter 的缺省值，不递归翻译真实标识。
            String[] parts = value.split(Pattern.quote(" · "), -1);
            for (int index = 0; index < parts.length; index++) {
                String part = parts[index];
                if (part.equals("待确认") || part.equals("未记录")
                        || part.equals("尚无续期记录") || part.equals("尚未安排"))
                    parts[index] = catalog.exact.getOrDefault(part, part);
            }
            return String.join(" · ", parts);
        }
    }
}
