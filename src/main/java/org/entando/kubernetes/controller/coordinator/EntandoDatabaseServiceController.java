package org.entando.kubernetes.controller.coordinator;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher.Action;
import org.entando.kubernetes.client.DefaultSimpleK8SClient;
import org.entando.kubernetes.controller.AbstractDbAwareController;
import org.entando.kubernetes.controller.KubeUtils;
import org.entando.kubernetes.controller.common.CreateExternalServiceCommand;
import org.entando.kubernetes.model.externaldatabase.EntandoDatabaseService;

public class EntandoDatabaseServiceController extends AbstractDbAwareController<EntandoDatabaseService> {

    private final KubernetesClient client;

    public EntandoDatabaseServiceController(KubernetesClient client) {
        super(client, false);
        this.client = client;
    }

    public void processEvent(Action action, EntandoDatabaseService db) {
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_ACTION, action.name());
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_NAMESPACE, db.getMetadata().getNamespace());
        System.setProperty(KubeUtils.ENTANDO_RESOURCE_NAME, db.getMetadata().getName());
        super.processCommand();
    }

    @Override
    protected void processAddition(EntandoDatabaseService entandoDatabaseService) {
        new CreateExternalServiceCommand(entandoDatabaseService).execute(new DefaultSimpleK8SClient(client));
    }
}
