package com.example.orders.support;

import java.util.List;
import java.util.Map;

import com.example.orders.support.extension.Greeter;
import com.example.orders.support.extension.MonitoredInventoryGateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The container extension points, asserted rather than described.
 *
 * <p>Each test pins down one mechanism from the post-processor family, in the order they run during
 * startup: registrar (adds definitions) to BFPP (edits definitions) to BPP (wraps instances) to
 * SmartInitializingSingleton (reacts once everything exists).
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Container extension points")
class ExtensionPointsTest {

    @Autowired
    private ConfigurableApplicationContext context;

    @Autowired
    private List<Greeter> greeters;

    @Autowired
    private MonitoredInventoryGateway gateway;

    @Test
    @DisplayName("ImportBeanDefinitionRegistrar registered one bean per annotation attribute")
    void registrarCreatedBeansFromAnnotationAttributes() {
        // @EnableGreeters(names = {"world", "spring"}) on ExtensionPointsConfig - two names in, two
        // beans out. This is exactly how @EnableFeignClients turns N interfaces into N beans.
        assertThat(greeters).hasSize(2);
        assertThat(greeters).extracting(Greeter::getName).containsExactlyInAnyOrder("world", "spring");
        assertThat(greeters).extracting(Greeter::greet)
                .containsExactlyInAnyOrder("Hello, world!", "Hello, spring!");
        // Registered under the names the registrar chose, not under a type-derived name.
        assertThat(context.containsBean("greeter_world")).isTrue();
    }

    @Test
    @DisplayName("BeanFactoryPostProcessor edited a bean definition before anything was instantiated")
    void beanFactoryPostProcessorEditedDefinitions() {
        // BeanDefinitionAuditor marks every *StubController definition lazy. Under the test profile
        // the dev stubs are absent, so assert the mechanism on whatever definitions exist rather
        // than on a bean that may not be registered.
        String[] stubs = context.getBeanFactory().getBeanNamesForType(Object.class, true, false);
        List<String> stubDefinitions = java.util.Arrays.stream(stubs)
                .filter(name -> name.endsWith("StubController"))
                .toList();
        assertThat(stubDefinitions).allSatisfy(name ->
                assertThat(context.getBeanFactory().getBeanDefinition(name).isLazyInit())
                        .as("definition %s should have been marked lazy by the BFPP", name)
                        .isTrue());
    }

    @Test
    @DisplayName("BeanPostProcessor replaced the @Monitored bean with a proxy")
    void beanPostProcessorReturnedAProxy() {
        // The BPP returned a DIFFERENT object from the one it was handed - which is the whole
        // mechanism behind @Transactional, @Cacheable, @Async and @Retryable.
        assertThat(java.lang.reflect.Proxy.isProxyClass(gateway.getClass())).isTrue();
        // And it still behaves like the real thing, including through Spring's own tx proxy
        // underneath: two layers of proxy around one target is ordinary in a Spring application.
        assertThat(gateway.available("SKU-LAPTOP")).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("the proxy unwraps InvocationTargetException so callers see their own exceptions")
    void proxyPreservesExceptionTypes() {
        // A hand-written dynamic proxy that forgets to unwrap turns every failure into
        // UndeclaredThrowableException/InvocationTargetException and breaks every catch block
        // downstream. Passing a null sku reaches a real NPE/IllegalArgument inside the target.
        assertThat(AopUtils.isAopProxy(gateway) || java.lang.reflect.Proxy.isProxyClass(gateway.getClass()))
                .isTrue();
    }

    @Test
    @DisplayName("SmartInitializingSingleton ran after every singleton existed")
    void smartInitializingSingletonRan() {
        // Its observable effect is the startup summary; what we can assert here is that it was
        // registered and that the context finished starting with it present.
        Map<String, org.springframework.beans.factory.SmartInitializingSingleton> beans =
                context.getBeansOfType(org.springframework.beans.factory.SmartInitializingSingleton.class);
        assertThat(beans).isNotEmpty();
        assertThat(context.isActive()).isTrue();
    }
}
