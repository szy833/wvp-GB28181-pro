package com.genersoft.iot.vmp.media.service.bean;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaStreamCountResultTest {

    @Test
    void successfulZeroCountIsDifferentFromFailure() {
        MediaStreamCountResult result = MediaStreamCountResult.success(0);

        assertTrue(result.isSuccess());
        assertEquals(0, result.getCount());
        assertFalse(result.isFailure());
    }

    @Test
    void failureDoesNotExposeAUsableCount() {
        MediaStreamCountResult result = MediaStreamCountResult.failure("timeout");

        assertFalse(result.isSuccess());
        assertTrue(result.isFailure());
        assertEquals(0, result.getCount());
        assertEquals("timeout", result.getReason());
    }
}
