package com.genersoft.iot.vmp.gb28181.session;

import com.genersoft.iot.vmp.conf.SipConfig;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.service.impl.InviteRepository;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.event.mediaServer.MediaServerOnlineEvent;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.media.zlm.ZLMRESTfulUtils;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SSRCFactoryLifecycleTest {
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final IMediaServerService mediaService = mock(IMediaServerService.class);

    @Test
    void beanInitializationDoesNotStartReconciliation() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            verifyNoInteractions(scheduler, mediaService);
        });
    }

    @Test
    void earlyOnlineEventDoesNotStartWorkBeforeApplicationReady() {
        runner().run(context -> {
            context.publishEvent(online());
            verifyNoInteractions(scheduler, mediaService);
        });
    }

    @Test
    void readyStartsOnePeriodicTaskAndOnlineSchedulesRetry() {
        runner().run(context -> {
            ready(context.getSourceApplicationContext());
            ready(context.getSourceApplicationContext());
            verify(scheduler, times(1)).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(5L), eq(TimeUnit.SECONDS));
            context.publishEvent(online());
            verify(scheduler).schedule(any(Runnable.class), eq(1L), eq(TimeUnit.SECONDS));
            verifyNoInteractions(mediaService);
        });
    }

    @Test
    void contextCloseStopsSchedulerAndPreventsLateCallbacks() {
        runner().run(context -> {
            ready(context.getSourceApplicationContext());
            ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).scheduleWithFixedDelay(callback.capture(), eq(0L), eq(5L), eq(TimeUnit.SECONDS));
            SSRCFactory factory = context.getBean(SSRCFactory.class);
            context.getSourceApplicationContext().close();
            factory.destroy();
            factory.onApplicationReady();
            factory.onMediaServerOnline(online());
            verify(scheduler, times(1)).shutdownNow();
            verify(scheduler, times(1)).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(5L), eq(TimeUnit.SECONDS));
            verify(scheduler, never()).schedule(any(Runnable.class), anyLong(), any());
            callback.getValue().run();
            verifyNoInteractions(mediaService);
        });
    }

    @Test
    void startupFailureDestroysSchedulerWithoutStartingWork() {
        runner().withBean("startupFailure", Object.class, () -> {
            throw new IllegalStateException("deliberate startup failure");
        }, definition -> definition.setDependsOn("ssrcFactory"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    verify(scheduler).shutdownNow();
                    verifyNoInteractions(mediaService);
                    verify(scheduler, never()).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any());
                });
    }

    private ApplicationContextRunner runner() {
        SipConfig sip = new SipConfig();
        sip.setDomain("3402000000");
        return new ApplicationContextRunner()
                .withBean(SipConfig.class, () -> sip)
                .withBean(UserSetting.class, UserSetting::new)
                .withBean(IMediaServerService.class, () -> mediaService)
                .withBean(ZLMRESTfulUtils.class, () -> mock(ZLMRESTfulUtils.class))
                .withBean(InviteRepository.class, () -> mock(InviteRepository.class))
                .withBean("ssrcFactory", SSRCFactory.class, () -> {
                    SSRCFactory factory = new SSRCFactory();
                    ReflectionTestUtils.setField(factory, "scheduler", scheduler);
                    return factory;
                });
    }

    private static void ready(ConfigurableApplicationContext context) {
        context.publishEvent(new ApplicationReadyEvent(new SpringApplication(SSRCFactory.class),
                new String[0], context, Duration.ZERO));
    }

    private MediaServerOnlineEvent online() {
        MediaServer server = new MediaServer();
        server.setId("test-node");
        server.setRtpEnable(true);
        MediaServerOnlineEvent event = new MediaServerOnlineEvent(this);
        event.setMediaServer(server);
        return event;
    }
}
