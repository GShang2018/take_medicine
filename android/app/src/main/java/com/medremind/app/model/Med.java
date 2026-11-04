package com.medremind.app.model;

import java.util.ArrayList;
import java.util.List;

/** 一种药品及其服药方案。字段保持 public 便于 Gson 序列化。 */
public class Med {
    public String id = "";
    /** 所属账户；旧数据升级后统一归到 default。 */
    public String accountId = "";
    public String name = "";
    public String dose = "";
    public String note = "";
    /** 开始日期 yyyy-MM-dd */
    public String startDate = "";
    /** 服药时段：morning / noon / evening / night */
    public List<String> slots = new ArrayList<>();
    /** DAILY / WEEKLY / MONTHLY */
    public String freqType = "DAILY";
    public int interval = 1;
    /** ISO 星期：1=周一 ... 7=周日 */
    public List<Integer> weekDays = new ArrayList<>();
    public int monthDay = 1;
    /** NEVER / UNTIL / COUNT */
    public String endType = "NEVER";
    public String endDate = "";
    public int endCount = 10;
    public boolean enabled = true;
    public long createdAt = System.currentTimeMillis();

    public Med copy() {
        Med m = new Med();
        m.id = id;
        m.accountId = accountId;
        m.name = name;
        m.dose = dose;
        m.note = note;
        m.startDate = startDate;
        m.slots = new ArrayList<>(slots);
        m.freqType = freqType;
        m.interval = interval;
        m.weekDays = new ArrayList<>(weekDays);
        m.monthDay = monthDay;
        m.endType = endType;
        m.endDate = endDate;
        m.endCount = endCount;
        m.enabled = enabled;
        m.createdAt = createdAt;
        return m;
    }
}