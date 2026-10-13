package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.conf.AsyncConfig;
import com.genersoft.iot.vmp.gb28181.service.IDeviceService;
import com.genersoft.iot.vmp.gb28181.service.IPlatformService;
import com.genersoft.iot.vmp.gb28181.service.IPlayService;
import com.genersoft.iot.vmp.gb28181.transmit.event.request.impl.message.MessageRequestProcessor;
import com.genersoft.iot.vmp.gb28181.transmit.event.request.impl.message.response.ResponseMessageHandler;
import com.genersoft.iot.vmp.gb28181.transmit.event.request.impl.message.response.cmd.CatalogResponseMessageHandler;
import com.genersoft.iot.vmp.service.redisMsg.RedisAlarmMsgListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.AopTestUtils;
import org.springframework.util.ReflectionUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class InviteStartupContextTest {
    private static final List<Class<?>> PRODUCTION_BEANS = List.of(
            RedisAlarmMsgListener.class, DeviceServiceImpl.class, CatalogResponseMessageHandler.class,
            ResponseMessageHandler.class, MessageRequestProcessor.class, PlatformServiceImpl.class,
            PlayServiceImpl.class);

    @Test
    void alarmEntryCanStartWithCircularReferencesDisabled() {
        runner(PRODUCTION_BEANS).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(IDeviceService.class)
                    .hasSingleBean(IPlatformService.class).hasSingleBean(IPlayService.class);
            ResponseMessageHandler response = AopTestUtils.getUltimateTargetObject(context.getBean(ResponseMessageHandler.class));
            assertThat(response.messageHandlerMap.get("Catalog"))
                    .isSameAs(AopTestUtils.getUltimateTargetObject(context.getBean(CatalogResponseMessageHandler.class)));
        });
    }

    @Test
    void playEntryCanStartRegardlessOfRegistrationOrder() {
        List<Class<?>> reverse = PRODUCTION_BEANS.reversed();
        runner(reverse).run(context -> assertThat(context).hasNotFailed());
    }

    private ApplicationContextRunner runner(List<Class<?>> beans) {
        return new ApplicationContextRunner().withAllowCircularReferences(false)
                .withUserConfiguration(AsyncConfig.class)
                .withUserConfiguration(beans.toArray(Class<?>[]::new))
                .withInitializer(context -> {
                    // Keep every edge of the reported cycle real; mock only dependencies outside it.
                    Set<Class<?>> boundaries = new LinkedHashSet<>();
                    for (Class<?> bean : beans) {
                        ReflectionUtils.doWithFields(bean, field -> {
                            Class<?> dependency = field.getType();
                            if (field.isAnnotationPresent(Autowired.class)
                                    && dependency != ObjectProvider.class && dependency != TaskExecutor.class
                                    && beans.stream().noneMatch(dependency::isAssignableFrom)) {
                                boundaries.add(dependency);
                            }
                        });
                    }
                    for (Class<?> boundary : boundaries) {
                        context.getBeanFactory().registerSingleton(boundary.getName(), mock(boundary));
                    }
                });
    }
}
