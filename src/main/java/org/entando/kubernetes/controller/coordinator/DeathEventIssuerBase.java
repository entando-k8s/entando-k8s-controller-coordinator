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

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfig;

public class DeathEventIssuerBase implements DeathEventIssuer {

    protected final KubernetesClient client;

    public DeathEventIssuerBase(KubernetesClient client) {
        this.client = client;
    }

    @Override
    public void issueOperatorDeathEvent(Event event) {
        event.getMetadata().setNamespace(client.getNamespace());
        Pod pod = client.pods().inNamespace(client.getNamespace()).withName(EntandoOperatorSpiConfig.getControllerPodName()).get();
        event.setInvolvedObject(new ObjectReferenceBuilder()
                .withName(pod.getMetadata().getName())
                .withNamespace(pod.getMetadata().getNamespace())
                .withKind(pod.getKind())
                .withApiVersion(pod.getApiVersion())
                .withUid(pod.getMetadata().getUid())
                .withResourceVersion(pod.getMetadata().getResourceVersion())
                .build());
        event.getInvolvedObject().setNamespace(client.getNamespace());
        client.v1().events().inNamespace(client.getNamespace()).createOrReplace(event);
    }
}
