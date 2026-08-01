package com.genersoft.iot.vmp.media.abl;

import com.genersoft.iot.vmp.media.abl.bean.ABLMedia;
import com.genersoft.iot.vmp.media.abl.bean.ABLResult;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.bean.MediaStreamCountResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ABLMediaNodeServerServiceTest {

    @Test
    void countActiveStreamsCountsUniqueStreams() {
        ABLRESTfulUtils utils = mock(ABLRESTfulUtils.class);
        ABLMediaNodeServerService service = service(utils);
        ABLMedia first = media("stream-1");
        ABLMedia duplicate = media("stream-1");
        ABLMedia second = media("stream-2");
        ABLResult result = new ABLResult();
        result.setCode(0);
        result.setMediaList(List.of(first, duplicate, second));
        when(utils.getMediaList(mediaServer(), null, null, 3)).thenReturn(result);

        MediaStreamCountResult count = service.countActiveStreams(mediaServer());

        assertTrue(count.isSuccess());
        assertEquals(2, count.getCount());
    }

    @Test
    void countActiveStreamsReturnsFailureForApiFailure() {
        ABLRESTfulUtils utils = mock(ABLRESTfulUtils.class);
        ABLMediaNodeServerService service = service(utils);
        when(utils.getMediaList(mediaServer(), null, null, 3)).thenReturn(null);

        assertTrue(service.countActiveStreams(mediaServer()).isFailure());
    }

    private static ABLMediaNodeServerService service(ABLRESTfulUtils utils) {
        ABLMediaNodeServerService service = new ABLMediaNodeServerService();
        ReflectionTestUtils.setField(service, "ablresTfulUtils", utils);
        return service;
    }

    private static MediaServer mediaServer() {
        MediaServer mediaServer = new MediaServer();
        mediaServer.setId("media-1");
        return mediaServer;
    }

    private static ABLMedia media(String stream) {
        ABLMedia media = new ABLMedia();
        media.setStream(stream);
        return media;
    }
}
