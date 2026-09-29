package com.jtdev.routelisttotesla.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/** Serializes TAP grant replacement with conditional grant removal. */
@Configuration
class TapAuthorizedClientServiceConfiguration {
    @Bean
    OAuth2AuthorizedClientService authorizedClientService(ClientRegistrationRepository registrations) {
        return new SynchronizedAuthorizedClientService(new InMemoryOAuth2AuthorizedClientService(registrations));
    }

    private static final class SynchronizedAuthorizedClientService implements OAuth2AuthorizedClientService {
        private final OAuth2AuthorizedClientService delegate;

        private SynchronizedAuthorizedClientService(OAuth2AuthorizedClientService delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String registrationId,
                                                                                      String principalName) {
            return delegate.loadAuthorizedClient(registrationId, principalName);
        }

        @Override
        public synchronized void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient,
                                                     Authentication principal) {
            delegate.saveAuthorizedClient(authorizedClient, principal);
        }

        @Override
        public synchronized void removeAuthorizedClient(String registrationId, String principalName) {
            delegate.removeAuthorizedClient(registrationId, principalName);
        }
    }
}
