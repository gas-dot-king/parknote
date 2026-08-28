package com.ohdduck.parknote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;

/** 시각 표시. */
public class FmtTest {

    @Test
    public void relative_구간별_문구() {
        long now = System.currentTimeMillis();
        assertEquals("방금 전", Fmt.relative(now));
        assertEquals("5분 전", Fmt.relative(now - 5 * 60_000L));
        assertEquals("3시간 전", Fmt.relative(now - 3 * 60 * 60_000L));
        assertEquals("2일 전", Fmt.relative(now - 2 * 24 * 60 * 60_000L));
    }

    @Test
    public void relative_미래_시각도_같은_단위로_읽는다() {
        // 기록 수정에서 주차 시각을 앞으로 당길 수 있어 실제로 생기는 경우다.
        long now = System.currentTimeMillis();
        assertEquals("10분 후", Fmt.relative(now + 10 * 60_000L + 500));
        assertEquals("2시간 후", Fmt.relative(now + 2 * 60 * 60_000L + 500));
    }

    @Test
    public void duration_단위가_커질수록_아래_단위는_남는_만큼만() {
        assertEquals("0분", Fmt.duration(0));
        assertEquals("45분", Fmt.duration(45 * 60_000L));
        assertEquals("2시간", Fmt.duration(2 * 60 * 60_000L));
        assertEquals("2시간 13분", Fmt.duration((2 * 60 + 13) * 60_000L));
        assertEquals("3일 2시간", Fmt.duration((3 * 24 + 2) * 60 * 60_000L));
        assertEquals("0분", Fmt.duration(-5000)); // 시계가 뒤로 갔어도 음수는 안 보인다
    }

    @Test
    public void minutesOfDay_오전_오후_경계() {
        assertEquals("오전 12:00", Fmt.minutesOfDay(0));
        assertEquals("오전 9:05", Fmt.minutesOfDay(9 * 60 + 5));
        assertEquals("오후 12:00", Fmt.minutesOfDay(12 * 60));
        assertEquals("오후 9:00", Fmt.minutesOfDay(21 * 60));
        assertEquals("오후 11:59", Fmt.minutesOfDay(23 * 60 + 59));
    }

    @Test
    public void full_과_time_은_같은_시각을_가리킨다() {
        long t = System.currentTimeMillis();
        String full = Fmt.full(t);
        assertTrue("full=" + full + " time=" + Fmt.time(t), full.endsWith(Fmt.time(t)));
    }

    @Test
    public void full_은_올해가_아닐_때만_연도를_붙인다() {
        long now = System.currentTimeMillis();
        assertFalse(Fmt.full(now).contains("년"));
        String lastYear = Fmt.full(now - 366L * 24 * 60 * 60_000L);
        assertTrue(lastYear, lastYear.startsWith((LocalDate.now().getYear() - 1) + "년"));
    }

    @Test
    public void today_는_저장_형식과_같은_문자열() {
        assertEquals(LocalDate.now().toString(), Fmt.today());
        assertEquals(10, Fmt.today().length()); // yyyy-MM-dd
    }
}
