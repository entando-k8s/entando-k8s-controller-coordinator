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

import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.entando.kubernetes.controller.coordinator.EntandoOperatorMatcher.EntandoOperatorMatcherProperty;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorConfigBase;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfig;
import org.entando.kubernetes.controller.support.common.KubeUtils;
import org.entando.kubernetes.controller.support.common.OperatorProcessingInstruction;
import org.entando.kubernetes.model.EntandoCustomResource;
import org.entando.kubernetes.model.EntandoDeploymentPhase;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeApp;

public class EntandoResourceObserver<R extends EntandoCustomResource> implements Watcher<R> {

    private static final Logger LOGGER = Logger.getLogger(EntandoResourceObserver.class.getName());
    private final Map<String, R> cache = new ConcurrentHashMap<>();
    private final BiConsumer<Action, R> callback;
    private final SimpleEntandoOperations<R> operations;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public EntandoResourceObserver(SimpleEntandoOperations<R> operations, BiConsumer<Action, R> callback) {
        this.callback = callback;
        this.operations = operations;
        processExistingRequestedEntandoResources();
        this.operations.watch(this);
    }

    private boolean requiresUpgrade(R resource) {
        final boolean requiresUpgrade = EntandoOperatorConfigBase
                .lookupProperty(EntandoOperatorMatcherProperty.ENTANDO_K8S_OPERATOR_VERSION_TO_REPLACE)
                .flatMap(versionToReplace ->
                        KubeUtils.resolveAnnotation(resource, EntandoOperatorMatcher.ENTANDO_K8S_PROCESSED_BY_OPERATOR_VERSION)
                                .map(versionToReplace::equals))
                .orElse(false);
        if (requiresUpgrade) {
            logResource(Level.WARNING, "%s %s/%s needs to be processed as part of the upgrade to the version " + EntandoOperatorConfigBase
                    .lookupProperty(EntandoOperatorMatcherProperty.ENTANDO_K8S_OPERATOR_VERSION).orElse("latest"), resource);
        }
        return requiresUpgrade;
    }

    private void processExistingRequestedEntandoResources() {
        List<R> items = this.operations.list();
        for (R resource : items) {
            eventReceived(Action.MODIFIED, resource);
            cache.put(resource.getMetadata().getUid(), resource);
        }
    }

    @Override
    public void eventReceived(Action action, R resource) {
        try {
            if (performCriteriaProcessing(resource)) {
                performCallback(action, resource);
            } else if (needsToRemoveSuccessfullyCompletedPods(resource)) {
                removeSuccessfullyCompletedPods(resource);
            }
        } catch (Exception e) {
            logFailure(resource, e);
        }
    }

    private void removeSuccessfullyCompletedPods(R resource) {
        operations.removeSuccessfullyCompletedPods(resource);
    }

    private boolean needsToRemoveSuccessfullyCompletedPods(R resource) {
        return EntandoOperatorConfig.garbageCollectSuccessfullyCompletedPods()
                && Optional.ofNullable(resource.getMetadata().getGeneration())
                .map(aLong -> aLong.equals(resource.getMetadata().getGeneration()))
                .orElse(false)
                && resource.getStatus().getEntandoDeploymentPhase() == EntandoDeploymentPhase.SUCCESSFUL;
    }

    private void logFailure(R resource, Exception e) {
        LOGGER.log(Level.SEVERE, e, () -> format("Could not process the %s %s/%s", resource.getKind(),
                resource.getMetadata().getNamespace(), resource.getMetadata().getName()));
    }

    private boolean performCriteriaProcessing(R resource) {
        return requiresUpgrade(resource)
                || (hasNewResourceVersion(resource)
                && isNotOwnedByCompositeApp(resource)
                && EntandoOperatorMatcher.matchesThisOperator(resource)
                && processGenerationIncrement(resource));
    }

    private boolean isNotOwnedByCompositeApp(R resource) {
        final boolean topLevel = resource.getMetadata().getOwnerReferences().stream()
                .noneMatch(ownerReference -> ownerReference.getKind().equals(
                        EntandoCompositeApp.class.getSimpleName()));
        if (topLevel) {
            logResource(Level.WARNING, "%s %s/%s is a top level resource", resource);

        }
        return topLevel;
    }

    @Override
    public void onClose(WatcherException cause) {
        if (cause.getMessage().contains("resourceVersion") && cause.getMessage().contains("too old")) {
            LOGGER.log(Level.WARNING, () -> "EntandoResourceObserver closed due to out of date resourceVersion. Reconnecting ... ");
            operations.watch(this);
        } else {
            LOGGER.log(Level.SEVERE, cause, () -> "EntandoResourceObserver closed. Can't reconnect. The container should restart now.");
            Liveness.dead();
        }
    }

    protected void performCallback(Action action, R resource) {
        logResource(Level.INFO, "Received " + action.name() + " for the %s %s/%s", resource);
        if (action == Action.ADDED || action == Action.MODIFIED) {
            cache.put(resource.getMetadata().getUid(), resource);
            EntandoOperatorConfigBase
                    .lookupProperty(EntandoOperatorMatcherProperty.ENTANDO_K8S_OPERATOR_VERSION)
                    .ifPresent(
                            s -> operations.putAnnotation(resource, EntandoOperatorMatcher.ENTANDO_K8S_PROCESSED_BY_OPERATOR_VERSION, s)
                    );
            executor.execute(() -> callback.accept(action, resource));
        } else if (action == Action.DELETED) {
            cache.remove(resource.getMetadata().getUid());
        } else {
            logResource(Level.WARNING, "EntandoResourceObserver could not process the action " + action.name() + " on the %s %s/%s",
                    resource);
        }
    }

    private void logResource(Level info, String format, R resource) {
        LOGGER.log(info,
                () -> format(format, resource.getKind(), resource.getMetadata().getNamespace(),
                        resource.getMetadata().getName()));
    }

    private boolean processGenerationIncrement(R newResource) {
        OperatorProcessingInstruction instruction = KubeUtils.resolveProcessingInstruction(newResource);
        if (instruction == OperatorProcessingInstruction.FORCE) {
            //Remove to avoid recursive updates
            final R latestResource = operations.removeAnnotation(newResource, KubeUtils.PROCESSING_INSTRUCTION_ANNOTATION_NAME);
            cache.put(latestResource.getMetadata().getUid(), latestResource);
            logResource(Level.WARNING, "Processing of %s %s/%s has been forced using entando.org/processing-instruction.", newResource);
            return true;
        } else if (instruction == OperatorProcessingInstruction.DEFER || instruction == OperatorProcessingInstruction.IGNORE) {
            logResource(Level.WARNING, "Processing of %s %s/%s has been deferred or ignored using entando.org/processing-instruction.",
                    newResource);
            return false;
        } else {
            final boolean needsObservation = newResource.getStatus().getObservedGeneration() == null
                    || newResource.getMetadata().getGeneration() == null
                    || newResource.getStatus().getObservedGeneration() < newResource.getMetadata().getGeneration();
            if (needsObservation) {
                logResource(Level.WARNING, "%s %s/%s is processed after a metadata.generation increment.", newResource);
            }
            return needsObservation;
        }
    }

    private boolean hasNewResourceVersion(R newResource) {
        if (cache.containsKey(newResource.getMetadata().getUid())) {
            R oldResource = cache.get(newResource.getMetadata().getUid());
            int knownResourceVersion = Integer.parseInt(oldResource.getMetadata().getResourceVersion());
            int receivedVersion = Integer.parseInt(newResource.getMetadata().getResourceVersion());
            if (knownResourceVersion >= receivedVersion) {
                logResource(Level.WARNING, "Duplicate event for %s %s/%s. ResourceVersion=" + receivedVersion, newResource);
                return false;
            }
        }
        logResource(Level.WARNING, "%s %s/%s has a new resource version: " + newResource.getMetadata().getResourceVersion(), newResource);
        return true;
    }

    public void shutDownAndWait(long i, TimeUnit timeUnit) throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(i, timeUnit)) {
            LOGGER.log(Level.WARNING, "Could not sure EntandoResourceObserver down.");
        }
    }
}
