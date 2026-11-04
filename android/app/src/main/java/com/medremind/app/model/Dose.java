package com.medremind.app.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 一次具体的服药事件。 */
public class Dose {
    public String medId = "";
    public String name = "";
    public String dose = "";
    public String note = "";
    public String slotKey = "";
    public String slotLabel = "";
    public LocalDate date;
    public LocalTime time;
    public boolean taken;

    public LocalDateTime dateTime() {
        return LocalDateTime.of(date, time);
    }

    public String key() {
        return medId + "|" + date + "|" + slotKey;
    }

    public String timeLabel() {
        return String.format(java.util.Locale.CHINA, "%02d:%02d", time.getHour(), time.getMinute());
    }
}