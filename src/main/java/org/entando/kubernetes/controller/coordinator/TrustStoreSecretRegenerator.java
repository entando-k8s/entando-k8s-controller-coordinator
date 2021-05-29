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

package org.entando.kubernetes.controller.coordinator;

import io.fabric8.kubernetes.api.model.Secret;
import java.util.concurrent.atomic.AtomicReference;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfig;
import org.entando.kubernetes.controller.spi.common.TrustStoreHelper;

public class TrustStoreSecretRegenerator {

    private static final AtomicReference<String> LAST_CA_SECRET_RESOURCE_VERSION = new AtomicReference<>("");

    private TrustStoreSecretRegenerator() {

    }

    public static void regenerateIfNecessary(SimpleKubernetesClient client) {
        EntandoOperatorSpiConfig.getCertificateAuthoritySecretName()
                .map(client::loadControllerSecret)
                .filter(TrustStoreSecretRegenerator::hasNewResourceVersion)
                .ifPresent(secret -> overwriteTrustStoreSecret(client, secret));
    }

    private static void overwriteTrustStoreSecret(SimpleKubernetesClient client, Secret secret) {
        client.overwriteControllerSecret(TrustStoreHelper.newTrustStoreSecret(secret));
        LAST_CA_SECRET_RESOURCE_VERSION.set(secret.getMetadata().getResourceVersion());
    }

    private static boolean hasNewResourceVersion(Secret secret) {
        return !secret.getMetadata().getResourceVersion().equals(LAST_CA_SECRET_RESOURCE_VERSION.get());
    }
}
