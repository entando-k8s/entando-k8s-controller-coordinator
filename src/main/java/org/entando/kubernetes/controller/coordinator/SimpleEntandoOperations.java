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

import java.util.HashMap;
import java.util.List;
import java.util.function.UnaryOperator;
import org.entando.kubernetes.model.EntandoCustomResource;

public interface SimpleEntandoOperations<R extends EntandoCustomResource> {

    SimpleEntandoOperations<R> inNamespace(String namespace);

    SimpleEntandoOperations<R> inAnyNamespace();

    void watch(EntandoResourceObserver<R> rldEntandoResourceObserver);

    List<R> list();

    default R removeAnnotation(R r, String name) {
        return patch(r, toPatch -> {
            if (toPatch.getMetadata().getAnnotations() != null) {
                toPatch.getMetadata().getAnnotations().remove(name);
            }
            return toPatch;
        });
    }

    R patch(R r, UnaryOperator<R> builder);

    default R putAnnotation(R r, String name, String value) {
        return patch(r, toPatch -> {
            if (toPatch.getMetadata().getAnnotations() == null) {
                toPatch.getMetadata().setAnnotations(new HashMap<>());
            }
            toPatch.getMetadata().getAnnotations().remove(name);
            toPatch.getMetadata().getAnnotations().put(name, value);
            return toPatch;
        });

    }

    void removeSuccessfullyCompletedPods(R resource);

}
