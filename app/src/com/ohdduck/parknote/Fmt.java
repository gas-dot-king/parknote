package com.ohdduck.parknote;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 시각 표시.
 *
 * <p>여기 있는 한국어 조각("분 전", "오전")은 일부러 strings.xml로 빼지 않았다.
 * 날짜 패턴("M월 d일 (E) a h:mm")과 한 덩어리로 움직이는 로케일 포맷이고, Context를
 * 받지 않아야 유닛 테스트에서 그대로 검증할 수 있다. 다국어를 하게 되면 이 클래스를
 * 통째로 옮긴다.
 *
 * <p>java.time은 불변이라 스레드 세이프하다. 백업 I/O 워커 스레드에서도 그대로 쓴다.
 */
final class Fmt {

    private static final DateTimeFormatter FULL =
            DateTimeFormatter.ofPattern("M월 d일 (E) a h:mm", Locale.KOREAN);
    /** 해가 다른 기록에는 연도를 붙인다. 240개까지 보관하니 작년 8월과 올해 8월이 섞인다. */
    private static final DateTimeFormatter FULL_WITH_YEAR =
            DateTimeFormatter.ofPattern("yyyy년 M월 d일 (E) a h:mm", Locale.KOREAN);
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN);

    private Fmt() {
    }

    private static ZonedDateTime at(long t) {
        return Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault());
    }

    /** "8월 28일 (목) 오후 3:12". 올해가 아니면 앞에 연도. */
    static String full(long t) {
        ZonedDateTime when = at(t);
        return (when.getYear() == LocalDate.now().getYear() ? FULL : FULL_WITH_YEAR).format(when);
    }

    /** "오후 3:12" */
    static String time(long t) {
        return TIME.format(at(t));
    }

    /** "5분 전", "3시간 전", "2일 전". 미래(기록 시각을 앞으로 당긴 경우)는 "n분 후". */
    static String relative(long t) {
        long min = (System.currentTimeMillis() - t) / 60000;
        if (min == 0) return "방금 전";
        String suffix = min < 0 ? " 후" : " 전";
        long m = Math.abs(min);
        if (m < 60) return m + "분" + suffix;
        long hour = m / 60;
        if (hour < 24) return hour + "시간" + suffix;
        return (hour / 24) + "일" + suffix;
    }

    /** 0시 기준 분 → "오후 9:05" */
    static String minutesOfDay(int m) {
        int h = m / 60;
        String ap = h < 12 ? "오전" : "오후";
        int h12 = h % 12;
        if (h12 == 0) h12 = 12;
        return ap + " " + h12 + ":" + String.format(Locale.US, "%02d", m % 60);
    }

    /** 습관 기록의 날짜 키. LocalDate.toString()과 같은 yyyy-MM-dd다. */
    static String today() {
        return LocalDate.now().toString();
    }
}
