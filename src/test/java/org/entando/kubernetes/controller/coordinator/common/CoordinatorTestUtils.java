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

package org.entando.kubernetes.controller.coordinator.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import java.io.IOException;
import java.util.Map;
import org.entando.kubernetes.controller.spi.client.SerializedEntandoResource;
import org.entando.kubernetes.model.common.EntandoCustomResource;

public class CoordinatorTestUtils {

    private CoordinatorTestUtils() {

    }

    public static boolean matchesLabels(Map<String, String> labels, Pod pod) {
        return labels.entrySet().stream()
                .allMatch(
                        entry -> (entry.getValue() == null && pod.getMetadata().getLabels().containsKey(entry.getKey()))
                                || entry.getValue().equals(pod.getMetadata().getLabels().get(entry.getKey())));
    }

    @SuppressWarnings("unchecked")
    public static SerializedEntandoResource toSerializedResource(EntandoCustomResource entandoCustomResource) {
        try {
            final ObjectMapper objectMapper = new ObjectMapper();
            final SerializedEntandoResource result = objectMapper
                    .readValue(objectMapper.writeValueAsString(entandoCustomResource), SerializedEntandoResource.class);
            result.setDefinition(CustomResourceDefinitionContext
                    .fromCustomResourceType((Class<? extends CustomResource<?, ?>>) entandoCustomResource.getClass()));
            return result;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
