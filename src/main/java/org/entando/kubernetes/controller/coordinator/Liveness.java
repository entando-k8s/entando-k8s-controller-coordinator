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

import static java.lang.String.format;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Liveness {

    private static final Logger LOGGER = Logger.getLogger(Liveness.class.getName());

    private Liveness() {

    }

    public static void alive() {
        perform(() -> Files.write(aliveFile(), "yes".getBytes(StandardCharsets.UTF_8)), "create");
    }

    public static void dead() {
        perform(() -> Files.deleteIfExists(aliveFile()), "delete");
    }

    private static Path aliveFile() {
        return Paths.get("/tmp", EntandoControllerCoordinator.class.getSimpleName() + ".ready");
    }

    private interface FileOperation {

        void perform() throws IOException;
    }

    private static void perform(FileOperation io, String name) {
        try {
            io.perform();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE,
                    () -> format("Could not %s 'ready' file for %s", name, EntandoControllerCoordinator.class.getSimpleName()));
        }
    }

}
