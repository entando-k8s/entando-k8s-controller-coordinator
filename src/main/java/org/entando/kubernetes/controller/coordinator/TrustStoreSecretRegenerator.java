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
import org.entando.kubernetes.controller.support.client.SimpleK8SClient;

public class TrustStoreSecretRegenerator {

    private static AtomicReference<String> lastCaSecretResourceVersion = new AtomicReference<>("");

    private TrustStoreSecretRegenerator() {

    }

    public static void regenerateIfNecessary(SimpleK8SClient<?> client) {
        EntandoOperatorSpiConfig.getCertificateAuthoritySecretName()
                .map(client.secrets()::loadControllerSecret)
                .filter(TrustStoreSecretRegenerator::hasNewResourceVersion)
                .ifPresent(secret -> overwriteTrustStoreSecret(client, secret));
    }

    private static void overwriteTrustStoreSecret(SimpleK8SClient<?> client, Secret secret) {
        client.secrets().overwriteControllerSecret(TrustStoreHelper.newTrustStoreSecret(secret));
        lastCaSecretResourceVersion.set(secret.getMetadata().getResourceVersion());
    }

    private static boolean hasNewResourceVersion(Secret secret) {
        return !secret.getMetadata().getResourceVersion().equals(lastCaSecretResourceVersion.get());
    }
}
