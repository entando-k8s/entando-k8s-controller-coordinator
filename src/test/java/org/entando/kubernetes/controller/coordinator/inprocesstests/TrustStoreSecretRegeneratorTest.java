/*
 *
 * Copyright 2015-Present Entando Inc. (http://www.entando.com) All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 *  This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 */

package org.entando.kubernetes.controller.coordinator.inprocesstests;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;

import java.nio.file.Paths;
import org.entando.kubernetes.controller.coordinator.TrustStoreSecretRegenerator;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfigProperty;
import org.entando.kubernetes.controller.spi.common.TrustStoreHelper;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfigProperty;
import org.entando.kubernetes.test.legacy.CertificateSecretHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

@Tags({@Tag("in-process"), @Tag("unit"), @Tag("pre-deployment")})
class TrustStoreSecretRegeneratorTest {

    @Test
    void testRegenerate() {
        final SimpleKubernetesClientDouble client = new SimpleKubernetesClientDouble();
        CertificateSecretHelper.buildCertificateSecretsFromDirectory(
                client.getControllerNamespace(),
                Paths.get("src", "test", "resources", "tls", "ampie.dynu.net")
        ).stream().peek(s -> s.getMetadata().setResourceVersion("1")).forEach(client::overwriteControllerSecret);
        TrustStoreSecretRegenerator.regenerateIfNecessary(client);
        assertThat(client.loadControllerSecret(TrustStoreHelper.DEFAULT_TRUSTSTORE_SECRET), notNullValue());
    }

    @AfterEach
    void resetSystemProperties() {
        System.clearProperty(EntandoOperatorSpiConfigProperty.ENTANDO_CA_SECRET_NAME.getJvmSystemProperty());
        System.clearProperty(EntandoOperatorConfigProperty.ENTANDO_TLS_SECRET_NAME.getJvmSystemProperty());
    }
}
