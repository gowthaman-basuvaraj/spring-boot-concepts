package com.example.platform.autoconfigure;

import com.example.platform.convert.StringToMaskedValueConverter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers property-binding converters.
 *
 * <p>This lives in its OWN auto-configuration, with a {@code static} factory method, for a reason
 * worth remembering: a {@code @ConfigurationPropertiesBinding} converter is needed in order to
 * BIND {@code @ConfigurationProperties}. If it were declared inside
 * {@code PlatformAutoConfiguration} - whose constructor takes {@code PlatformProperties} - Spring
 * would have to instantiate that configuration class to create the converter, which it needs to
 * bind the properties, which it needs to instantiate the configuration class. The result is
 *
 * <pre>BeanCurrentlyInCreationException: Requested bean is currently in creation</pre>
 *
 * and it also poisons unrelated binding: the first symptom is usually the baffling
 * "Could not bind properties to 'ServerProperties'".
 *
 * <p>Rule: anything required for property binding - converters, {@code Validator}s - must be
 * declared {@code static} and must not sit next to beans that consume bound properties.
 */
@AutoConfiguration
public class PlatformConversionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    static StringToMaskedValueConverter stringToMaskedValueConverter() {
        return new StringToMaskedValueConverter();
    }
}
