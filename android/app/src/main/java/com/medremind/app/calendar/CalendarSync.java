package com.medremind.app.calendar;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import com.medremind.app.AppPrefs;
import com.medremind.app.model.Account;
import com.medremind.app.model.Med;
import com.medremind.app.schedule.ScheduleEngine;
import com.medremind.app.util.Slots;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 把每个账户的服药方案写入各自独立的系统日历（CalendarProvider），
 * 这样多个人的日程不会混在一起，也能由系统日历提醒。
 */
public final class CalendarSync {
    public static final String URI_PREFIX = "medremind://med/";
    private static final String[] WEEK_CODES = {"MO", "TU", "WE", "TH", "FR", "SA", "SU"};

    private CalendarSync() {
    }

    public static class Cal {
        public long id;
        public String name;
        public String account;

        @Override
        public String toString() {
            return account == null || account.isEmpty() ? name : name + "（" + account + "）";
        }
    }

    public static List<Cal> writableCalendars(Context context) {
        List<Cal> result = new ArrayList<>();
        try (Cursor cursor = context.getContentResolver().query(
                CalendarContract.Calendars.CONTENT_URI,
                new String[]{CalendarContract.Calendars._ID,
                        CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                        CalendarContract.Calendars.ACCOUNT_NAME,
                        CalendarContract.Calendars.ACCOUNT_TYPE,
                        CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                        CalendarContract.Calendars.VISIBLE},
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + ">=?",
                new String[]{String.valueOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)},
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " ASC")) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    Cal cal = new Cal();
                    cal.id = cursor.getLong(0);
                    cal.name = cursor.getString(1);
                    cal.account = cursor.getString(2);
                    result.add(cal);
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    /** 为指定账户创建一个本机专用日历（账号类型 LOCAL），失败抛异常由界面提示。 */
    public static long createLocalCalendar(Context context, String displayName) {
        String name = displayName == null || displayName.trim().isEmpty()
                ? "吃药提醒" : displayName.trim();
        ContentResolver cr = context.getContentResolver();
        String account = "吃药提醒";
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Calendars.ACCOUNT_NAME, account);
        values.put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL);
        values.put(CalendarContract.Calendars.NAME, name);
        values.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, name);
        values.put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF10B981);
        values.put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER);
        values.put(CalendarContract.Calendars.OWNER_ACCOUNT, account);
        values.put(CalendarContract.Calendars.VISIBLE, 1);
        values.put(CalendarContract.Calendars.SYNC_EVENTS, 1);
        values.put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, ZoneId.systemDefault().getId());

        Uri syncUri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                .build();
        Uri uri;
        try {
            uri = cr.insert(syncUri, values);
        } catch (Exception e) {
            uri = null;
        }
        if (uri == null) {
            // 有些 ROM 不认 LOCAL 账号，退而求其次：不指定账号，交给系统分配。
            ContentValues fallback = new ContentValues();
            fallback.put(CalendarContract.Calendars.NAME, name);
            fallback.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, name);
            fallback.put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF10B981);
            fallback.put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER);
            fallback.put(CalendarContract.Calendars.OWNER_ACCOUNT, account);
            fallback.put(CalendarContract.Calendars.VISIBLE, 1);
            fallback.put(CalendarContract.Calendars.SYNC_EVENTS, 1);
            fallback.put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, ZoneId.systemDefault().getId());
            uri = cr.insert(CalendarContract.Calendars.CONTENT_URI, fallback);
        }
        if (uri == null) throw new IllegalStateException("无法创建本地日历");
        return ContentUris.parseId(uri);
    }

    /** 删除某个日历里由本应用写入的全部事件，返回删除条数。 */
    public static int deleteAppEvents(Context context, long calendarId) {
        try {
            String selection = CalendarContract.Events.CUSTOM_APP_URI + " LIKE ?";
            String[] args = new String[]{"medremind://%"};
            if (calendarId > 0) {
                selection += " AND " + CalendarContract.Events.CALENDAR_ID + "=?";
                args = new String[]{"medremind://%", String.valueOf(calendarId)};
            }
            return context.getContentResolver().delete(CalendarContract.Events.CONTENT_URI, selection, args);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 只删除某个账户写在指定日历里的事件（同账户共用日历时也安全）。 */
    public static int deleteAccountEvents(Context context, long calendarId, String accountId) {
        try {
            String selection = CalendarContract.Events.CUSTOM_APP_URI + " LIKE ?";
            List<String> args = new ArrayList<>();
            args.add(URI_PREFIX + accountId + "/%");
            if (calendarId > 0) {
                selection += " AND " + CalendarContract.Events.CALENDAR_ID + "=?";
                args.add(String.valueOf(calendarId));
            }
            return context.getContentResolver().delete(CalendarContract.Events.CONTENT_URI,
                    selection, args.toArray(new String[0]));
        } catch (Exception e) {
            return 0;
        }
    }

    /** 删除旧版本写入的、URI 里没有账户段的日程（仅 default 账户迁移时用）。 */
    public static int deleteLegacyEvents(Context context, long calendarId) {
        try {
            String selection = CalendarContract.Events.CUSTOM_APP_URI + " LIKE ?"
                    + " AND " + CalendarContract.Events.CUSTOM_APP_URI + " NOT LIKE ?";
            String[] args = new String[]{"medremind://med/%",
                    URI_PREFIX + AppPrefs.DEFAULT_ACCOUNT_ID + "/%"};
            if (calendarId > 0) {
                selection += " AND " + CalendarContract.Events.CALENDAR_ID + "=?";
                args = new String[]{"medremind://med/%",
                        URI_PREFIX + AppPrefs.DEFAULT_ACCOUNT_ID + "/%", String.valueOf(calendarId)};
            }
            return context.getContentResolver().delete(CalendarContract.Events.CONTENT_URI, selection, args);
        } catch (Exception e) {
            return 0;
        }
    }

    public static int countAppEvents(Context context, long calendarId) {
        String selection = CalendarContract.Events.CUSTOM_APP_URI + " LIKE ?";
        String[] args = new String[]{"medremind://%"};
        if (calendarId > 0) {
            selection += " AND " + CalendarContract.Events.CALENDAR_ID + "=?";
            args = new String[]{"medremind://%", String.valueOf(calendarId)};
        }
        try (Cursor cursor = context.getContentResolver().query(CalendarContract.Events.CONTENT_URI,
                new String[]{CalendarContract.Events._ID}, selection, args, null)) {
            return cursor == null ? 0 : cursor.getCount();
        } catch (Exception e) {
            return 0;
        }
    }

    public static boolean calendarUsedByAnother(Context context, long calendarId, String exceptAccountId) {
        for (Account account : AppPrefs.get(context).getAccounts()) {
            if (!account.id.equals(exceptAccountId) && account.calendarId == calendarId) return true;
        }
        return false;
    }

    /** 将一个账户的药品同步到它自己的日历（每个时段一条 RRULE 事件）。 */
    public static int sync(Context context, String accountId, long calendarId, int leadMinutes) {
        AppPrefs prefs = AppPrefs.get(context);
        Account account = prefs.findAccount(accountId);
        if (account == null) return 0;
        ContentResolver cr = context.getContentResolver();
        ZoneId zone = ZoneId.systemDefault();
        // 先清掉本账户在这个日历里的旧事件；同账户共用日历时不会误删别人。
        if (AppPrefs.DEFAULT_ACCOUNT_ID.equals(accountId)) {
            deleteLegacyEvents(context, calendarId);
        }
        deleteAccountEvents(context, calendarId, accountId);

        int inserted = 0;
        LocalDateTime from = LocalDate.now().atStartOfDay().minusSeconds(1);
        for (Med med : prefs.getMeds(accountId)) {
            if (!med.enabled || med.slots == null) continue;
            for (String slot : med.slots) {
                LocalDateTime first = ScheduleEngine.nextOccurrence(med, slot, from, prefs::slotTime);
                if (first == null) continue;
                long startMillis = first.atZone(zone).toInstant().toEpochMilli();
                long endMillis = startMillis + 5 * 60_000L;

                ContentValues values = new ContentValues();
                values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
                values.put(CalendarContract.Events.TITLE, "💊 " + med.name);
                values.put(CalendarContract.Events.DESCRIPTION, description(med, slot, account.name));
                values.put(CalendarContract.Events.EVENT_LOCATION, "");
                values.put(CalendarContract.Events.DTSTART, startMillis);
                values.put(CalendarContract.Events.DTEND, endMillis);
                values.put(CalendarContract.Events.EVENT_TIMEZONE, zone.getId());
                values.put(CalendarContract.Events.ALL_DAY, 0);
                values.put(CalendarContract.Events.HAS_ALARM, 1);
                values.put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED);
                values.put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY);
                values.put(CalendarContract.Events.RRULE, rrule(med, zone));
                values.put(CalendarContract.Events.CUSTOM_APP_URI,
                        URI_PREFIX + accountId + "/" + med.id + "/" + slot);

                Uri uri;
                try {
                    uri = cr.insert(CalendarContract.Events.CONTENT_URI, values);
                } catch (Exception e) {
                    continue;
                }
                if (uri == null) continue;
                long eventId = ContentUris.parseId(uri);
                inserted++;

                try {
                    ContentValues reminder = new ContentValues();
                    reminder.put(CalendarContract.Reminders.EVENT_ID, eventId);
                    reminder.put(CalendarContract.Reminders.MINUTES, -Math.abs(leadMinutes));
                    reminder.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
                    cr.insert(CalendarContract.Reminders.CONTENT_URI, reminder);
                } catch (Exception ignored) {
                }
            }
        }

        Account latest = prefs.findAccount(accountId);
        if (latest != null) {
            Account copy = latest.copy();
            copy.calendarCount = inserted;
            copy.calendarLastSync = System.currentTimeMillis();
            prefs.upsertAccount(copy);
        }
        return inserted;
    }

    private static String description(Med med, String slot, String accountName) {
        StringBuilder sb = new StringBuilder();
        sb.append("账户：").append(accountName == null ? "" : accountName).append('\n');
        sb.append("每次用量：").append(med.dose == null || med.dose.isEmpty() ? "按医嘱" : med.dose);
        sb.append("\n服药时段：").append(Slots.labelOf(slot));
        if (med.note != null && !med.note.isEmpty()) {
            sb.append("\n医嘱备注：").append(med.note);
        }
        sb.append("\n\n由「吃药提醒」自动同步");
        return sb.toString();
    }

    private static String rrule(Med med, ZoneId zone) {
        StringBuilder rule = new StringBuilder("FREQ=").append(
                "WEEKLY".equals(med.freqType) ? "WEEKLY"
                        : "MONTHLY".equals(med.freqType) ? "MONTHLY" : "DAILY");
        rule.append(";INTERVAL=").append(Math.max(1, med.interval));
        if ("WEEKLY".equals(med.freqType) && med.weekDays != null && !med.weekDays.isEmpty()) {
            rule.append(";BYDAY=");
            for (int i = 0; i < med.weekDays.size(); i++) {
                int day = med.weekDays.get(i);
                if (day < 1 || day > 7) continue;
                if (i > 0) rule.append(",");
                rule.append(WEEK_CODES[day - 1]);
            }
        }
        if ("MONTHLY".equals(med.freqType)) {
            rule.append(";BYMONTHDAY=").append(Math.max(1, Math.min(31, med.monthDay)));
        }
        if ("UNTIL".equals(med.endType)) {
            LocalDate end = ScheduleEngine.parseDate(med.endDate, null);
            if (end != null) {
                String until = end.atTime(23, 59, 59).atZone(zone)
                        .withZoneSameInstant(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));
                rule.append(";UNTIL=").append(until);
            }
        } else if ("COUNT".equals(med.endType)) {
            rule.append(";COUNT=").append(Math.max(1, med.endCount));
        }
        return rule.toString();
    }

    /** 新建 / 更新 / 删除药品后，自动更新所有已配置日期的账户。 */
    public static int autoSyncIfEnabled(Context context) {
        AppPrefs prefs = AppPrefs.get(context);
        if (!prefs.autoSync()) return -1;
        int total = 0;
        boolean any = false;
        for (Account account : prefs.getAccounts()) {
            if (account.calendarId <= 0) continue;
            any = true;
            try {
                total += sync(context, account.id, account.calendarId, prefs.calendarLead());
            } catch (Exception ignored) {
            }
        }
        return any ? total : -1;
    }

    public static int autoSyncIfEnabled(Context context, String accountId) {
        AppPrefs prefs = AppPrefs.get(context);
        if (!prefs.autoSync()) return -1;
        Account account = prefs.findAccount(accountId);
        if (account == null || account.calendarId <= 0) return -1;
        try {
            return sync(context, account.id, account.calendarId, prefs.calendarLead());
        } catch (Exception e) {
            return -1;
        }
    }

    public static String calendarName(Context context, long id) {
        try (Cursor cursor = context.getContentResolver().query(
                ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id),
                new String[]{CalendarContract.Calendars.CALENDAR_DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) {
        }
        return "";
    }
}