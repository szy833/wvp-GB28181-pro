package com.genersoft.iot.vmp.media.zlm;

import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.bean.MediaStreamCountResult;
import com.genersoft.iot.vmp.media.zlm.dto.RtpServerResult;
import com.genersoft.iot.vmp.media.zlm.dto.ZLMResult;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ZLMMediaNodeServerServiceTest {

    @Test
    void failedQueryReturnsNull() {
        ZLMRESTfulUtils utils = mock(ZLMRESTfulUtils.class);
        ZLMMediaNodeServerService service = service(utils);
        MediaServer mediaServer = mediaServer();
        when(utils.listRtpServer(mediaServer)).thenReturn(null);

        assertNull(service.listRtpServer(mediaServer));
    }

    @Test
    void nonZeroApiCodeReturnsNull() {
        ZLMRESTfulUtils utils = mock(ZLMRESTfulUtils.class);
        ZLMMediaNodeServerService service = service(utils);
        ZLMResult<List<RtpServerResult>> result = new ZLMResult<>();
        result.setCode(-1);
        when(utils.listRtpServer(mediaServer())).thenReturn(result);

        assertNull(service.listRtpServer(mediaServer()));
    }

    @Test
    void successfulEmptyQueryReturnsEmptyList() {
        ZLMRESTfulUtils utils = mock(ZLMRESTfulUtils.class);
        ZLMMediaNodeServerService service = service(utils);
        ZLMResult<List<RtpServerResult>> result = new ZLMResult<>();
        result.setCode(0);
        result.setData(List.of());
        when(utils.listRtpServer(mediaServer())).thenReturn(result);

        assertEquals(List.of(), service.listRtpServer(mediaServer()));
    }

    @Test
    void successfulQueryReturnsActualZlmStreamIds() {
        ZLMRESTfulUtils utils = mock(ZLMRESTfulUtils.class);
        ZLMMediaNodeServerService service = service(utils);
        RtpServerResult first = new RtpServerResult();
        first.setStream_id("zlm-stream-1");
        RtpServerResult second = new RtpServerResult();
        second.setStream_id("zlm-stream-2");
        ZLMResult<List<RtpServerResult>> result = new ZLMResult<>();
        result.setCode(0);
        result.setData(List.of(first, second));
        when(utils.listRtpServer(mediaServer())).thenReturn(result);

        assertEquals(List.of("zlm-stream-1", "zlm-stream-2"), service.listRtpServer(mediaServer()));
    }

    @Test
    void countActiveStreamsCountsUniqueRtspStreams() {
        ZLMRESTfulUtils utils = mock(ZLMRESTfulUtils.class);
        ZLMMediaNodeServerService service = service(utils);
        JSONArray data = new JSONArray();
        data.add(media("rtsp", "app", "stream-1"));
        data.add(media("rtsp", "app", "stream-1"));
        data.add(media("rtsp", "app", "stream-2"));
        ZLMResult<JSONArray> result = new ZLMResult<>();
        result.setCode(0);
        result.setData(data);
        when(utils.getMediaList(mediaServer(), null, null, "rtsp", null, 3)).thenReturn(result);

        MediaStreamCountResult count = service.countActiveStreams(mediaServer());

        assertTrue(count.isSuccess());
        assertEquals(2, count.getCount());
    }

    @Test
    void countActiveStreamsReturnsFailureForApiFailure() {
        ZLMRESTfulUtils utils = mock(ZLMRESTfulUtils.class);
        ZLMMediaNodeServerService service = service(utils);
        when(utils.getMediaList(mediaServer(), null, null, "rtsp", null, 3)).thenReturn(null);

        MediaStreamCountResult count = service.countActiveStreams(mediaServer());

        assertTrue(count.isFailure());
    }

    private static JSONObject media(String schema, String app, String stream) {
        JSONObject media = new JSONObject();
        media.put("schema", schema);
        media.put("app", app);
        media.put("stream", stream);
        return media;
    }

    private static ZLMMediaNodeServerService service(ZLMRESTfulUtils utils) {
        ZLMMediaNodeServerService service = new ZLMMediaNodeServerService();
        ReflectionTestUtils.setField(service, "zlmresTfulUtils", utils);
        return service;
    }

    private static MediaServer mediaServer() {
        MediaServer mediaServer = new MediaServer();
        mediaServer.setId("media-1");
        return mediaServer;
    }
}
