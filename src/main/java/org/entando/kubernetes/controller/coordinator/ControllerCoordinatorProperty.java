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

import org.entando.kubernetes.controller.spi.common.ConfigProperty;

public enum ControllerCoordinatorProperty implements ConfigProperty {
    ENTANDO_K8S_OPERATOR_DEPLOYMENT_TYPE,
    ENTANDO_NAMESPACES_TO_OBSERVE,
    ENTANDO_K8S_OPERATOR_ID,
    ENTANDO_K8S_PROBE_FOLDER,
    ENTANDO_K8S_OPERATOR_VERSION,
    ENTANDO_K8S_OPERATOR_VERSION_TO_REPLACE,
    ENTANDO_K8S_CONTROLLER_REMOVAL_DELAY,
    ENTANDO_K8S_OPERATOR_GC_CONTROLLER_PODS,
    ENTANDO_DOCKER_IMAGE_INFO_CONFIGMAP, ENTANDO_K8S_OPERATOR_SERVICEACCOUNT

}
