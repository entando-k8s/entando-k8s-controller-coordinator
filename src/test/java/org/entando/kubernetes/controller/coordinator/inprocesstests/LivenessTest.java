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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Paths;
import org.entando.kubernetes.controller.coordinator.Liveness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

@Tags({@Tag("unit"), @Tag("pre-deployment")})
class LivenessTest {

    @Test
    void shouldDeleteLivenessFileWithoutFailing() {
        final File file = Paths.get("/tmp/EntandoControllerCoordinator.ready").toFile();
        Liveness.alive();
        assertTrue(file.exists());
        Liveness.dead();
        assertFalse(file.exists());
        Liveness.dead();
        assertFalse(file.exists());
    }
}
