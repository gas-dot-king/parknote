package com.ohdduck.parknote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.time.LocalDate;

/** 습관 연속 기록. */
public class HabitsTest {

    @Test
    public void streak_오늘부터_이어진_날을_센다() throws JSONException {
        assertEquals(3, Habits.streak(habit(0, 1, 2)));
    }

    @Test
    public void streak_오늘_안_했어도_어제까지는_살아_있다() throws JSONException {
        // 저녁에 앱을 열었을 때 "2일 연속"이 0으로 보이면 사용자는 기록이 끊긴 줄 안다.
        assertEquals(2, Habits.streak(habit(1, 2)));
    }

    @Test
    public void streak_이틀_전에서_끊기면_0() throws JSONException {
        assertEquals(0, Habits.streak(habit(2, 3, 4)));
    }

    @Test
    public void streak_중간에_빠지면_거기서_멈춘다() throws JSONException {
        assertEquals(2, Habits.streak(habit(0, 1, 3, 4)));
    }

    @Test
    public void streak_기록이_없으면_0() throws JSONException {
        assertEquals(0, Habits.streak(habit()));
        assertEquals(0, Habits.streak(new JSONObject()));
    }

    @Test
    public void last7Days_는_왼쪽이_6일_전_오른쪽이_오늘() throws JSONException {
        boolean[] week = Habits.last7Days(habit(0, 6));
        assertTrue("오늘", week[6]);
        assertTrue("6일 전", week[0]);
        assertFalse("어제", week[5]);
        assertEquals(7, week.length);
    }

    @Test
    public void last7Days_7일보다_오래된_기록은_무시한다() throws JSONException {
        boolean[] week = Habits.last7Days(habit(7, 8, 30));
        for (int i = 0; i < week.length; i++) {
            assertFalse("index " + i, week[i]);
        }
    }

    @Test
    public void checkedToday_는_맨_앞이_오늘일_때만_참() throws JSONException {
        assertTrue(Habits.checkedToday(habit(0, 1)));
        assertFalse(Habits.checkedToday(habit(1, 2)));
        assertFalse(Habits.checkedToday(habit()));
    }

    /** daysAgo에 적힌 날짜만 체크된 습관. 최신순으로 담는다. */
    private static JSONObject habit(int... daysAgo) throws JSONException {
        JSONArray days = new JSONArray();
        LocalDate today = LocalDate.now();
        for (int ago : daysAgo) days.put(today.minusDays(ago).toString());
        return new JSONObject().put("n", "영양제").put("days", days);
    }
}
