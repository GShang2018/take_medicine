package com.medremind.app.calendar;

import android.content.Context;
import android.net.Uri;

import com.medremind.app.model.Med;
import com.medremind.app.util.Slots;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 解析网页版导出的 .ics（含 RRULE / DESCRIPTION）并合并为药品方案。 */
public final class IcsImporter {
    private static final DateTimeFormatter BASIC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int MAX_DOWNLOAD_BYTES = 3 * 1024 * 1024;

    private IcsImporter() {
    }

    public static String readAsset(Context context, String name) throws IOException {
        try (InputStream is = context.getAssets().open(name)) {
            return readStream(is);
        }
    }

    public static String readUri(Context context, Uri uri) throws IOException {
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) throw new IOException("无法读取文件");
            return readStream(is);
        }
    }

    private static String readStream(InputStream is) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        int total = 0;
        while ((read = is.read(buffer)) != -1) {
            total += read;
            if (total > MAX_DOWNLOAD_BYTES) throw new IOException("文件超过 3MB");
            out.write(buffer, 0, read);
        }
        return out.toString("UTF-8");
    }

    /** 从 http/https 链接下载 .ics。支持 webcal:// 和常见的 301/302 跳转。 */
    public static String readUrl(String urlText) throws IOException {
        String target = urlText == null ? "" : urlText.trim();
        if (target.startsWith("webcal://")) {
            target = "https://" + target.substring("webcal://".length());
        }
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            throw new IOException("只支持 http / https 链接");
        }
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(target);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(12_000);
                conn.setReadTimeout(20_000);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("Accept", "text/calendar, text/plain, */*");
                conn.setRequestProperty("User-Agent", "MediRemind/1.1 (Android)");
                int code = conn.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.isEmpty()) throw new IOException("跳转地址为空");
                    target = new URL(url, location).toString();
                    continue;
                }
                if (code != 200) throw new IOException("服务器返回 HTTP " + code);
                try (InputStream is = conn.getInputStream()) {
                    String body = readStream(is);
                    if (!body.contains("BEGIN:VCALENDAR") && !body.contains("BEGIN:VEVENT")) {
                        throw new IOException("链接内容不是 .ics 日历文件");
                    }
                    return body;
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw new IOException("跳转次数过多");
    }

    public static List<Med> parse(String raw) {
        List<Map<String, String>> events = splitEvents(raw);
        Map<String, Med> merged = new LinkedHashMap<>();
        for (Map<String, String> props : events) {
            Med med = toMed(props);
            if (med == null) continue;
            String key = med.name + "|" + med.dose;
            Med existing = merged.get(key);
            if (existing == null) {
                merged.put(key, med);
            } else {
                merge(existing, med);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private static void merge(Med target, Med extra) {
        if (extra.startDate.compareTo(target.startDate) < 0) target.startDate = extra.startDate;
        for (String slot : extra.slots) {
            if (!target.slots.contains(slot)) target.slots.add(slot);
        }
        if (target.note.isEmpty()) target.note = extra.note;
        if ("UNTIL".equals(extra.endType)) {
            if (!"UNTIL".equals(target.endType)
                    || extra.endDate.compareTo(target.endDate) > 0) {
                target.endType = "UNTIL";
                target.endDate = extra.endDate;
            }
        } else if ("COUNT".equals(extra.endType) && "NEVER".equals(target.endType)) {
            target.endType = "COUNT";
            target.endCount = extra.endCount;
        }
        if (target.weekDays.isEmpty()) target.weekDays = extra.weekDays;
        if (target.interval <= 0) target.interval = extra.interval;
    }

    private static List<Map<String, String>> splitEvents(String raw) {
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder unfolded = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && unfolded.length() > 0) {
                unfolded.append(line.substring(1));
            } else {
                if (unfolded.length() > 0) unfolded.append('\n');
                unfolded.append(line);
            }
        }

        List<Map<String, String>> events = new ArrayList<>();
        Map<String, String> current = null;
        boolean inAlarm = false;
        for (String line : unfolded.toString().split("\n")) {
            String trimmed = line.trim();
            if ("BEGIN:VEVENT".equals(trimmed)) {
                current = new LinkedHashMap<>();
                inAlarm = false;
                continue;
            }
            if ("END:VEVENT".equals(trimmed)) {
                if (current != null) events.add(current);
                current = null;
                continue;
            }
            if (current == null) continue;
            if ("BEGIN:VALARM".equals(trimmed)) {
                inAlarm = true;
                continue;
            }
            if ("END:VALARM".equals(trimmed)) {
                inAlarm = false;
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String property = line.substring(0, colon);
            String value = line.substring(colon + 1);
            String name = property;
            int semi = property.indexOf(';');
            if (semi > 0) name = property.substring(0, semi);
            if (inAlarm && !"TRIGGER".equals(name)) continue;
            current.putIfAbsent(name, value);
        }
        return events;
    }

    private static Med toMed(Map<String, String> props) {
        String summary = props.get("SUMMARY");
        if (summary == null) return null;
        summary = summary.replace("💊", "").trim();
        if (summary.isEmpty()) return null;

        String description = unescape(props.getOrDefault("DESCRIPTION", ""));
        String dose = field(description, "每次用量");
        String slotLabel = field(description, "服药时间");
        String note = field(description, "医嘱备注");

        LocalDateTime start = parseDate(props.get("DTSTART"));
        if (start == null) return null;

        Med med = new Med();
        med.id = UUID.randomUUID().toString();
        med.name = summary;
        med.dose = dose.isEmpty() ? "按医嘱" : dose;
        med.note = note;
        med.startDate = start.toLocalDate().toString();

        String slotKey = mapSlot(slotLabel, start.getHour());
        med.slots.add(slotKey);

        Rrule rule = parseRrule(props.get("RRULE"));
        med.freqType = rule.freq;
        med.interval = Math.max(1, rule.interval);
        med.weekDays = rule.weekDays;
        med.monthDay = rule.monthDay > 0 ? rule.monthDay : start.getDayOfMonth();
        if ("WEEKLY".equals(med.freqType) && med.weekDays.isEmpty()) {
            med.weekDays.add(start.getDayOfWeek().getValue());
        }
        med.endType = rule.endType;
        med.endDate = rule.endDate;
        med.endCount = rule.endCount > 0 ? rule.endCount : 10;
        if ("UNTIL".equals(med.endType)) {
            // ICS 的 UNTIL 通常在当天 23:59:59，这里保存结束日期即可。
            med.endDate = rule.endDate;
        }
        return med;
    }

    private static String mapSlot(String label, int hour) {
        if (label != null) {
            String s = label.trim();
            if (s.contains("早")) return Slots.MORNING;
            if (s.contains("午") && !s.contains("晚")) return Slots.NOON;
            if (s.contains("晚") && s.contains("饭")) return Slots.EVENING;
            if (s.contains("睡前") || s.contains("晚上")) return Slots.NIGHT;
            if (s.contains("夜")) return Slots.NIGHT;
        }
        return Slots.guessKey(hour);
    }

    private static String field(String description, String key) {
        for (String line : description.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(key)) {
                String rest = trimmed.substring(key.length()).trim();
                if (rest.startsWith("：") || rest.startsWith(":")) rest = rest.substring(1).trim();
                return rest;
            }
        }
        return "";
    }

    private static String unescape(String value) {
        return value.replace("\\n", "\n").replace("\\N", "\n")
                .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\");
    }

    private static LocalDateTime parseDate(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            if (value.endsWith("Z")) {
                String withoutZ = value.substring(0, value.length() - 1);
                LocalDateTime utc = LocalDateTime.parse(withoutZ, BASIC);
                return utc.atZone(ZoneId.of("UTC"))
                        .withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
            }
            if (value.length() == 8) {
                return LocalDate.parse(value, BASIC_DATE).atStartOfDay();
            }
            return LocalDateTime.parse(value, BASIC);
        } catch (Exception e) {
            return null;
        }
    }

    private static class Rrule {
        String freq = "DAILY";
        int interval = 1;
        List<Integer> weekDays = new ArrayList<>();
        int monthDay = 0;
        String endType = "NEVER";
        String endDate = "";
        int endCount = 0;
    }

    private static Rrule parseRrule(String raw) {
        Rrule rule = new Rrule();
        if (raw == null || raw.isEmpty()) return rule;
        for (String part : raw.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String key = part.substring(0, eq).trim().toUpperCase();
            String value = part.substring(eq + 1).trim();
            switch (key) {
                case "FREQ":
                    rule.freq = value.toUpperCase();
                    break;
                case "INTERVAL":
                    try {
                        rule.interval = Integer.parseInt(value);
                    } catch (Exception ignored) {
                    }
                    break;
                case "BYDAY":
                    for (String day : value.split(",")) {
                        int index = dayIndex(day.trim().toUpperCase());
                        if (index > 0 && !rule.weekDays.contains(index)) rule.weekDays.add(index);
                    }
                    break;
                case "BYMONTHDAY":
                    try {
                        rule.monthDay = Integer.parseInt(value);
                    } catch (Exception ignored) {
                    }
                    break;
                case "UNTIL": {
                    LocalDateTime until = parseDate(value);
                    if (until != null) {
                        rule.endType = "UNTIL";
                        rule.endDate = until.toLocalDate().toString();
                    }
                    break;
                }
                case "COUNT":
                    try {
                        rule.endCount = Integer.parseInt(value);
                        if (rule.endCount > 0) rule.endType = "COUNT";
                    } catch (Exception ignored) {
                    }
                    break;
                default:
                    break;
            }
        }
        if (!"DAILY".equals(rule.freq) && !"WEEKLY".equals(rule.freq) && !"MONTHLY".equals(rule.freq)) {
            rule.freq = "DAILY";
        }
        return rule;
    }

    private static int dayIndex(String code) {
        switch (code) {
            case "MO":
                return 1;
            case "TU":
                return 2;
            case "WE":
                return 3;
            case "TH":
                return 4;
            case "FR":
                return 5;
            case "SA":
                return 6;
            case "SU":
                return 7;
            default:
                int comma = code.indexOf(',');
                if (comma > 0) return dayIndex(code.substring(0, comma));
                return -1;
        }
    }
}