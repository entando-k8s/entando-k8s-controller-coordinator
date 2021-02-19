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

import java.util.List;
import org.entando.kubernetes.model.DoneableEntandoCustomResource;
import org.entando.kubernetes.model.EntandoCustomResource;

public interface SimpleEntandoOperations<
        R extends EntandoCustomResource,
        D extends DoneableEntandoCustomResource<R, D>> {

    SimpleEntandoOperations<R, D> inNamespace(String namespace);

    SimpleEntandoOperations<R, D> inAnyNamespace();

    void watch(EntandoResourceObserver<R, D> rldEntandoResourceObserver);

    List<R> list();

    default R removeAnnotation(R r, String name) {
        return edit(r)
                .editMetadata()
                .removeFromAnnotations(name)
                .endMetadata()
                .done();

    }

    D edit(R r);

    default R putAnnotation(R r, String name, String value) {
        return edit(r)
                .editMetadata()
                .removeFromAnnotations(name)
                .addToAnnotations(name, value)
                .endMetadata()
                .done();

    }

    void removeSuccessfullyCompletedPods(R resource);
}
