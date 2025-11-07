package com.example.demo.service;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.cglib.proxy.Enhancer;
import org.springframework.cglib.proxy.InvocationHandler;
import org.springframework.stereotype.Component;
import java.lang.reflect.Method;

@Component
public class PerformanceMonitoringBeanPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName)
            throws BeansException {

        // Only proxy services for performance monitoring
        if (bean.getClass().getSimpleName().endsWith("Service")) {
            return createProxy(bean, beanName);
        }

        return bean;
    }

    private Object createProxy(Object target, String beanName) {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(target.getClass());
        enhancer.setCallback((InvocationHandler) (proxy, method, args) -> {
            long startTime = System.currentTimeMillis();

            try {
                Object result = method.invoke(target, args);
                return result;
            } finally {
                long endTime = System.currentTimeMillis();
                System.out.println("Method " + method.getName() +
                                   " of " + beanName +
                                   " executed in " + (endTime - startTime) + "ms");
            }
        });

        return enhancer.create();
    }
}
