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

import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.entando.kubernetes.controller.spi.client.SerializedEntandoResource;

public interface SimpleEntandoOperations {

    SimpleEntandoOperations inNamespace(String namespace);

    SimpleEntandoOperations inAnyNamespace();

    Watch watch(SerializedResourceWatcher rldEntandoResourceObserver);

    List<SerializedEntandoResource> list();

    SerializedEntandoResource removeAnnotation(SerializedEntandoResource r, String name);

    SerializedEntandoResource putAnnotation(SerializedEntandoResource r, String name, String value);

    void removeSuccessfullyCompletedPods(SerializedEntandoResource resource) throws TimeoutException;

    CustomResourceDefinitionContext getDefinitionContext();

    String getControllerNamespace();

}
