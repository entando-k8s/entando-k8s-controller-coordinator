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
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorConfigBase;
import org.entando.kubernetes.model.capability.ProvidedCapability;
import org.entando.kubernetes.model.common.EntandoCustomResource;
import org.entando.kubernetes.model.common.EntandoDeploymentPhase;

public class EntandoResourceObserver implements Watcher<EntandoCustomResource> {

    private static final Logger LOGGER = Logger.getLogger(EntandoResourceObserver.class.getName());

    private final Map<String, Deque<String>> cache = new ConcurrentHashMap<>();
    private final Map<String, EntandoCustomResource> resourcesBeingUpgraded = new ConcurrentHashMap<>();
    private final BiConsumer<Action, EntandoCustomResource> callback;
    private final SimpleEntandoOperations operations;
    //TODO make the poolsize configurable
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(5);
    private final CrdNameMapSync crdNameMapSync;
    private final Long generation;

    public EntandoResourceObserver(SimpleEntandoOperations operations,
            BiConsumer<Action, EntandoCustomResource> callback,
            CrdNameMapSync crdNameMapSync,
            Long generation) {
        this.callback = callback;
        this.operations = operations;
        this.crdNameMapSync = crdNameMapSync;
        this.generation = generation;
        processOperationInScope(operations, simpleEntandoOperations -> simpleEntandoOperations.list()
                .forEach(entandoCustomResource -> eventReceived(Action.MODIFIED, entandoCustomResource)));
        processOperationInScope(operations, simpleEntandoOperations -> simpleEntandoOperations.watch(this));
    }

    private static void processOperationInScope(SimpleEntandoOperations operations, Consumer<SimpleEntandoOperations> consumer) {
        if (ControllerCoordinatorConfig.isClusterScopedDeployment()) {
            consumer.accept(operations.inAnyNamespace());
        } else {
            List<String> namespaces = ControllerCoordinatorConfig.getNamespacesToObserve();
            if (namespaces.isEmpty()) {
                namespaces.add(operations.getControllerNamespace());
            }
            for (String namespace : namespaces) {
                consumer.accept(operations.inNamespace(namespace));
            }
        }
    }

    private boolean requiresUpgrade(EntandoCustomResource resource) {
        if (!isBeingUpgraded(resource) && wasProcessedByVersionBeingReplaced(resource)) {
            resourcesBeingUpgraded.put(resource.getMetadata().getUid(), resource);
            logResource(Level.WARNING, "%s %s/%s needs to be processed as part of the upgrade to the version " + EntandoOperatorConfigBase
                    .lookupProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_VERSION).orElse("latest"), resource);
            return true;
        }
        return false;
    }

    private boolean wasProcessedByVersionBeingReplaced(EntandoCustomResource resource) {
        return EntandoOperatorConfigBase
                .lookupProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_VERSION_TO_REPLACE)
                .flatMap(versionToReplace ->
                        CoordinatorUtils.resolveAnnotation(resource, EntandoOperatorMatcher.ENTANDO_K8S_PROCESSED_BY_OPERATOR_VERSION)
                                .map(versionToReplace::equals))
                .orElse(false);
    }

    private boolean isBeingUpgraded(EntandoCustomResource resource) {
        return resourcesBeingUpgraded.containsKey(resource.getMetadata().getUid());
    }

    @Override
    public void eventReceived(Action action, EntandoCustomResource resource) {
        try {
            if (performCriteriaProcessing(resource)) {
                performCallback(action, resource);
            } else if (resource.getStatus().getPhase() == EntandoDeploymentPhase.SUCCESSFUL) {
                markAsUpgraded(resource);
                if (needsToRemoveSuccessfullyCompletedPods(resource)) {
                    removeSuccessfullyCompletedPods(resource);
                }
            }
        } catch (Exception e) {
            logFailure(resource, e);
        }
    }

    private void markAsUpgraded(EntandoCustomResource resource) {
        resourcesBeingUpgraded.remove(resource.getMetadata().getUid());
        EntandoOperatorConfigBase
                .lookupProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_VERSION)
                .ifPresent(
                        s -> operations.putAnnotation(resource, EntandoOperatorMatcher.ENTANDO_K8S_PROCESSED_BY_OPERATOR_VERSION, s)
                );
    }

    private void removeSuccessfullyCompletedPods(EntandoCustomResource resource) {
        executor.schedule(() -> operations.removeSuccessfullyCompletedPods(resource), (long) ControllerCoordinatorConfig.getRemovalDelay(),
                TimeUnit.SECONDS);
    }

    private boolean needsToRemoveSuccessfullyCompletedPods(EntandoCustomResource resource) {
        return ControllerCoordinatorConfig.garbageCollectSuccessfullyCompletedPods()
                && Optional.ofNullable(resource.getMetadata().getGeneration())
                .map(aLong -> aLong.equals(resource.getMetadata().getGeneration()))
                .orElse(false)
                && resource.getStatus().getPhase() == EntandoDeploymentPhase.SUCCESSFUL;
    }

    private void logFailure(EntandoCustomResource resource, Exception e) {
        LOGGER.log(Level.SEVERE, e, () -> format("Could not process the %s %s/%s", resource.getKind(),
                resource.getMetadata().getNamespace(), resource.getMetadata().getName()));
    }

    private boolean performCriteriaProcessing(EntandoCustomResource resource) {
        return requiresUpgrade(resource)
                || (hasNewResourceVersion(resource)
                && isNotOwnedByResourceOfInterest(resource)
                && EntandoOperatorMatcher.matchesThisOperator(resource)
                && processGenerationIncrement(resource));
    }

    private boolean isNotOwnedByResourceOfInterest(EntandoCustomResource resource) {
        final boolean topLevel = resource.getMetadata().getOwnerReferences().stream()
                .noneMatch(ownerReference -> ownerReference.getKind().equals(
                        ProvidedCapability.class.getSimpleName()) || crdNameMapSync.isOfInterest(ownerReference));
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

    protected void performCallback(Action action, EntandoCustomResource resource) {
        logResource(Level.INFO, "Received " + action.name() + " for the %s %s/%s", resource);
        if (action == Action.ADDED || action == Action.MODIFIED) {
            executor.execute(() -> callback.accept(action, resource));
        } else if (action == Action.DELETED) {
            cache.remove(resource.getMetadata().getUid());
        } else {
            logResource(Level.WARNING, "EntandoResourceObserver could not process the action " + action.name() + " on the %s %s/%s",
                    resource);
        }
    }

    private void logResource(Level info, String format, EntandoCustomResource resource) {
        LOGGER.log(info,
                () -> format(format, resource.getKind(), resource.getMetadata().getNamespace(),
                        resource.getMetadata().getName()));
    }

    private boolean processGenerationIncrement(EntandoCustomResource newResource) {
        OperatorProcessingInstruction instruction = CoordinatorUtils.resolveProcessingInstruction(newResource);
        if (instruction == OperatorProcessingInstruction.FORCE) {
            //Remove to avoid recursive updates
            final EntandoCustomResource latestResource = operations
                    .removeAnnotation(newResource, CoordinatorUtils.PROCESSING_INSTRUCTION_ANNOTATION_NAME);
            markResourceVersionProcessed(latestResource);
            logResource(Level.WARNING, "Processing of %s %s/%s has been forced using entando.org/processing-instruction.", latestResource);
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

    private boolean hasNewResourceVersion(EntandoCustomResource newResource) {
        Deque<String> queue = cache.computeIfAbsent(newResource.getMetadata().getUid(), key -> new ConcurrentLinkedDeque<>());
        if (queue.contains(newResource.getMetadata().getResourceVersion())) {
            //TODO observe logs to see if this actually still happens
            logResource(Level.WARNING, "Duplicate event for %s %s/%s. ResourceVersion=" + newResource.getMetadata().getResourceVersion(),
                    newResource);
            return false;
        }
        markResourceVersionProcessed(newResource);
        logResource(Level.WARNING, "%s %s/%s has a new resource version: " + newResource.getMetadata().getResourceVersion(), newResource);
        return true;
    }

    private void markResourceVersionProcessed(EntandoCustomResource newResource) {
        Deque<String> queue = cache.computeIfAbsent(newResource.getMetadata().getUid(), key -> new ConcurrentLinkedDeque<>());
        queue.offer(newResource.getMetadata().getResourceVersion());
        while (queue.size() > 10) {
            queue.poll();
        }
    }

    public void shutDownAndWait(int i, TimeUnit timeUnit) {
        try {
            executor.shutdown();
            if (!executor.awaitTermination(i, timeUnit)) {
                LOGGER.log(Level.WARNING, "Could not shut EntandoResourceObserver down.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public Long getCrdGeneration() {
        return generation;
    }
}
