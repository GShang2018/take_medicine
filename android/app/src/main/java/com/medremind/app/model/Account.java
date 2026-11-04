package com.medremind.app.model;

/** 一个账户 = 一个人（或一份独立的用药方案），拥有自己的药品、订阅和系统日历。 */
public class Account {
    public String id = "";
    public String name = "";
    /** 订阅链接；空表示没有订阅。 */
    public String subUrl = "";
    /** 自动更新间隔（分钟），0 = 手动。 */
    public int subInterval = 0;
    public String subHash = "";
    public long subLastSync = 0;
    public String subLastError = "";
    /** 该账户写入的系统日历；-1 表示尚未选择。 */
    public long calendarId = -1;
    public String calendarName = "";
    public int calendarCount = 0;
    public long calendarLastSync = 0;

    public Account copy() {
        Account a = new Account();
        a.id = id;
        a.name = name;
        a.subUrl = subUrl;
        a.subInterval = subInterval;
        a.subHash = subHash;
        a.subLastSync = subLastSync;
        a.subLastError = subLastError;
        a.calendarId = calendarId;
        a.calendarName = calendarName;
        a.calendarCount = calendarCount;
        a.calendarLastSync = calendarLastSync;
        return a;
    }
}