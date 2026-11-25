package com.mytv.launcher.lock

import android.icu.util.ChineseCalendar

object LunarDate {

    fun text(millis: Long): String {
        val cc = ChineseCalendar()
        cc.timeInMillis = millis
        val month = cc.get(ChineseCalendar.MONTH).coerceIn(0, 11)
        val day = cc.get(ChineseCalendar.DAY_OF_MONTH).coerceIn(1, 30)
        val leap = cc.get(ChineseCalendar.IS_LEAP_MONTH) == 1
        val monthNames = arrayOf("正月", "二月", "三月", "四月", "五月", "六月",
            "七月", "八月", "九月", "十月", "冬月", "腊月")
        return "农历" + (if (leap) "闰" else "") + monthNames[month] + dayText(day)
    }

    private fun dayText(day: Int): String {
        val digits = arrayOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十")
        return when (day) {
            in 1..10 -> "初" + digits[day]
            20 -> "二十"
            30 -> "三十"
            in 11..19 -> "十" + digits[day - 10]
            in 21..29 -> "廿" + digits[day - 20]
            else -> "初十"
        }
    }
}
