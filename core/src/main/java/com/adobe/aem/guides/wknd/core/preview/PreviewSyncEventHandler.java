package com.adobe.aem.guides.wknd.core.preview;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.caconfig.ConfigurationBuilder;
import org.apache.sling.event.jobs.JobManager;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.replication.ReplicationAction;
import com.day.cq.replication.ReplicationActionType;

/** Converts only Publish replication events for opted-in content into durable jobs. */
@Component(
        service = EventHandler.class,
        property = EventConstants.EVENT_TOPIC + "=" + ReplicationAction.EVENT_TOPIC
)
public final class PreviewSyncEventHandler implements EventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PreviewSyncEventHandler.class);

    @Reference
    private ResourceResolverFactory resourceResolverFactory;

    @Reference
    private JobManager jobManager;

    @Override
    public void handleEvent(Event event) {
        ReplicationAction action = ReplicationAction.fromEvent(event);
        if (action == null || !isSupportedAction(action) || !isPublishEvent(action)) {
            return;
        }

        String[] paths = action.getPaths();
        if (paths == null || paths.length == 0) {
            return;
        }

        try (ResourceResolver resolver = resourceResolverFactory.getServiceResourceResolver(
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, PreviewSyncConstants.SERVICE_NAME))) {
            for (String path : paths) {
                if (isEnabled(resolver, path)) {
                    enqueue(path, action.getType());
                }
            }
        } catch (LoginException e) {
            LOGGER.error("Unable to obtain the preview-sync service resolver", e);
        }
    }

    private boolean isSupportedAction(ReplicationAction action) {
        return action.getType() == ReplicationActionType.ACTIVATE
                || action.getType() == ReplicationActionType.DEACTIVATE;
    }

    /**
     * Preview-originated replication must never feed this handler again. Requiring a
     * known Publish agent is fail-closed and avoids an event loop if event metadata is incomplete.
     */
    private boolean isPublishEvent(ReplicationAction action) {
        return action.getConfig() != null
                && PreviewSyncConstants.PUBLISH_AGENT_ID.equals(action.getConfig().getAgentId());
    }

    private boolean isEnabled(ResourceResolver resolver, String path) {
        Resource resource = resolver.getResource(path);
        if (resource == null) {
            return false;
        }
        ConfigurationBuilder builder = resource.adaptTo(ConfigurationBuilder.class);
        PreviewSyncConfig config = builder == null ? null : builder.as(PreviewSyncConfig.class);
        return config != null && config.enableAutoSync();
    }

    private void enqueue(String path, ReplicationActionType actionType) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(PreviewSyncConstants.JOB_PATH, path);
        properties.put(PreviewSyncConstants.JOB_ACTION, actionType.name());
        if (jobManager.addJob(PreviewSyncConstants.JOB_TOPIC, properties) == null) {
            LOGGER.warn("Preview synchronization job could not be queued for {}", path);
        }
    }
}
