package com.actilazion.aries_transaction.support;

import com.actilazion.aries_transaction.identity.application.SessionRevocationService;
import com.actilazion.aries_transaction.identity.smartotp.application.*;
import com.actilazion.aries_transaction.identity.smartotp.infrastructure.SmartOtpRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;

/** Real disabled policy for legacy H2 persistence slices; enforcement is tested against PostgreSQL. */
@TestConfiguration(proxyBeanMethods=false)
@Import({SmartOtpTransferGuard.class, SmartOtpChallengeService.class, SmartOtpRepository.class,
        SmartOtpProperties.class, SmartOtpCrypto.class, SessionRevocationService.class})
public class SmartOtpDisabledSliceConfiguration {
    @Bean @ConditionalOnMissingBean
    Clock smartOtpSliceClock() { return Clock.systemUTC(); }
    @Bean @ConditionalOnMissingBean
    ObjectMapper smartOtpSliceMapper() { return new ObjectMapper(); }
}
