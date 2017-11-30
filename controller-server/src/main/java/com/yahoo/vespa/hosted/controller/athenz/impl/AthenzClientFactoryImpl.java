// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.athenz.impl;

import com.google.inject.Inject;
import com.yahoo.athenz.auth.Principal;
import com.yahoo.athenz.auth.impl.PrincipalAuthority;
import com.yahoo.athenz.auth.impl.SimplePrincipal;
import com.yahoo.athenz.auth.impl.SimpleServiceIdentityProvider;
import com.yahoo.athenz.auth.token.PrincipalToken;
import com.yahoo.athenz.auth.util.Crypto;
import com.yahoo.athenz.zms.ZMSClient;
import com.yahoo.athenz.zts.ZTSClient;
import com.yahoo.jdisc.http.SecretStore;
import com.yahoo.vespa.hosted.controller.athenz.AthenzClientFactory;
import com.yahoo.vespa.hosted.controller.athenz.NToken;
import com.yahoo.vespa.hosted.controller.athenz.ZmsClient;
import com.yahoo.vespa.hosted.controller.athenz.ZtsClient;
import com.yahoo.vespa.hosted.controller.athenz.config.AthenzConfig;

import java.security.PrivateKey;
import java.util.concurrent.TimeUnit;

import static com.yahoo.vespa.hosted.controller.athenz.AthenzUtils.USER_PRINCIPAL_DOMAIN;

/**
 * @author bjorncs
 */
public class AthenzClientFactoryImpl implements AthenzClientFactory {

    private final SecretStore secretStore;
    private final AthenzConfig config;
    private final AthenzPrincipalAuthority athenzPrincipalAuthority;

    @Inject
    public AthenzClientFactoryImpl(SecretStore secretStore, AthenzConfig config) {
        this.secretStore = secretStore;
        this.config = config;
        this.athenzPrincipalAuthority = new AthenzPrincipalAuthority(config.principalHeaderName());
    }

    /**
     * @return A ZMS client instance with the service identity as principal.
     */
    @Override
    public ZmsClient createZmsClientWithServicePrincipal() {
        return new ZmsClientImpl(new ZMSClient(config.zmsUrl(), createServicePrincipal()), config);
    }

    /**
     * @return A ZTS client instance with the service identity as principal.
     */
    @Override
    public ZtsClient createZtsClientWithServicePrincipal() {
        return new ZtsClientImpl(new ZTSClient(config.ztsUrl(), createServicePrincipal()), config);
    }

    /**
     * @return A ZMS client created with a dual principal representing both the tenant admin and the service identity.
     */
    @Override
    public ZmsClient createZmsClientWithAuthorizedServiceToken(NToken authorizedServiceToken) {
        PrincipalToken signedToken = new PrincipalToken(authorizedServiceToken.getToken());
        AthenzConfig.Service service = config.service();
        signedToken.signForAuthorizedService(
                config.domain() + "." + service.name(), service.publicKeyId(), getServicePrivateKey());

        Principal dualPrincipal = SimplePrincipal.create(
                USER_PRINCIPAL_DOMAIN.id(), signedToken.getName(), signedToken.getSignedToken(), athenzPrincipalAuthority);
        return new ZmsClientImpl(new ZMSClient(config.zmsUrl(), dualPrincipal), config);

    }

    private Principal createServicePrincipal() {
        AthenzConfig.Service service = config.service();
        // TODO bjorncs: Cache principal token
        SimpleServiceIdentityProvider identityProvider =
                new SimpleServiceIdentityProvider(
                        athenzPrincipalAuthority, config.domain(), service.name(),
                        getServicePrivateKey(), service.publicKeyId(), /*tokenTimeout*/TimeUnit.HOURS.toSeconds(1));
        return identityProvider.getIdentity(config.domain(), service.name());
    }

    private PrivateKey getServicePrivateKey() {
        AthenzConfig.Service service = config.service();
        String privateKey = secretStore.getSecret(service.privateKeySecretName(), service.privateKeyVersion()).trim();
        return Crypto.loadPrivateKey(privateKey);
    }

    private static class AthenzPrincipalAuthority extends PrincipalAuthority {
        private final String principalHeaderName;

        public AthenzPrincipalAuthority(String principalHeaderName) {
            this.principalHeaderName = principalHeaderName;
        }

        @Override
        public String getHeader() {
            return principalHeaderName;
        }
    }


}
