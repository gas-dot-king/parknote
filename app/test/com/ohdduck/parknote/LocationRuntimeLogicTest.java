package com.ohdduck.parknote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.hardware.SensorManager;
import android.view.Surface;

import org.junit.Test;

/** 위치 탭에서 네트워크나 Android 런타임 없이 검증할 수 있는 방어 로직. */
public class LocationRuntimeLogicTest {

    @Test
    public void coordinates_유효범위와유한값만허용() {
        assertTrue(Nearby.validCoordinates(-90.0, -180.0));
        assertTrue(Nearby.validCoordinates(90.0, 180.0));
        assertFalse(Nearby.validCoordinates(90.000001, 0.0));
        assertFalse(Nearby.validCoordinates(0.0, -180.000001));
        assertFalse(Nearby.validCoordinates(Double.NaN, 127.0));
        assertFalse(Nearby.validCoordinates(37.0, Double.POSITIVE_INFINITY));
    }

    @Test
    public void coordinateText_로케일과무관한위경도문자열() {
        assertEquals("37.123457, 127.765432",
                LocationTab.formatCoordinates(37.1234567, 127.7654321));
        assertEquals("", LocationTab.formatCoordinates(91.0, 127.0));
    }

    @Test
    public void storedFix_30분과50미터경계까지신뢰() {
        long fixTime = 1_000_000L;
        assertTrue(LocationTab.storedFixIsReliable(
                fixTime + 30 * 60 * 1000L, fixTime, 50f));
        assertFalse(LocationTab.storedFixIsReliable(
                fixTime + 30 * 60 * 1000L + 1L, fixTime, 50f));
        assertFalse(LocationTab.storedFixIsReliable(fixTime, fixTime, 50.01f));
    }

    @Test
    public void storedFix_메타데이터누락과과도한미래시각은거부() {
        long recordTime = 10_000_000L;
        assertFalse(LocationTab.storedFixIsReliable(recordTime, 0L, 10f));
        assertFalse(LocationTab.storedFixIsReliable(recordTime, recordTime, -1f));
        assertTrue(LocationTab.storedFixIsReliable(
                recordTime, recordTime + 2 * 60 * 1000L, 10f));
        assertFalse(LocationTab.storedFixIsReliable(
                recordTime, recordTime + 2 * 60 * 1000L + 1L, 10f));
    }

    @Test
    public void displayRotation_화면위쪽에맞춰센서축변환() {
        assertAxes(Surface.ROTATION_0, SensorManager.AXIS_X, SensorManager.AXIS_Y);
        assertAxes(Surface.ROTATION_90,
                SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X);
        assertAxes(Surface.ROTATION_180,
                SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y);
        assertAxes(Surface.ROTATION_270,
                SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X);
    }

    private static void assertAxes(int rotation, int expectedX, int expectedY) {
        assertEquals(expectedX, LocationTab.displayAxisX(rotation));
        assertEquals(expectedY, LocationTab.displayAxisY(rotation));
    }
}
