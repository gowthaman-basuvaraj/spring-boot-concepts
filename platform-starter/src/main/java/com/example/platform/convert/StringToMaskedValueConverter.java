package com.example.platform.convert;

import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;

/**
 * Teaches the binder about {@link MaskedValue}. Registered as a bean by
 * {@code PlatformAutoConfiguration} - a library class annotated {@code @Component} would never be
 * found, because the application only component-scans its own package.
 *
 * <p>{@code @ConfigurationPropertiesBinding} is the hook: without it the converter would only be
 * visible to MVC/web conversion, and property binding would fail with
 * "Failed to convert property value of type java.lang.String".
 */
@ConfigurationPropertiesBinding
public class StringToMaskedValueConverter implements Converter<String, MaskedValue> {

    @Override
    public MaskedValue convert(String source) {
        return new MaskedValue(source);
    }
}
