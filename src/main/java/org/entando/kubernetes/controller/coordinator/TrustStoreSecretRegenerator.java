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

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Secret;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfig;
import org.entando.kubernetes.controller.spi.common.TrustStoreHelper;

public class TrustStoreSecretRegenerator {

    private static final AtomicReference<Secret> LAST_CA_SECRET = new AtomicReference<>();

    private TrustStoreSecretRegenerator() {

    }

    public static void regenerateIfNecessary(SimpleKubernetesClient client) {
        Optional<Secret> caSecret = EntandoOperatorSpiConfig.getCertificateAuthoritySecretName()
                .flatMap(name -> Optional.ofNullable(client.loadControllerSecret(name)));
        LAST_CA_SECRET.getAndUpdate(lastSecret -> {
            if (caSecret.isPresent()) {
                Secret newValue = caSecret.get();
                if (lastSecret == null
                        || client.loadControllerSecret(TrustStoreHelper.DEFAULT_TRUSTSTORE_SECRET) == null
                        || !lastSecret.getMetadata().getUid().equals(newValue.getMetadata().getUid())
                        || !lastSecret.getMetadata().getResourceVersion().equals(newValue.getMetadata().getResourceVersion())
                ) {
                    client.overwriteControllerSecret(TrustStoreHelper.newTrustStoreSecret(newValue));
                    return newValue;
                } else {
                    return lastSecret;
                }
            } else {
                client.deleteControllerSecret(TrustStoreHelper.DEFAULT_TRUSTSTORE_SECRET);
                return null;
            }
        });
    }
}
