package com.adobe.aem.guides.wknd.core.preview;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.caconfig.ConfigurationBuilder;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.JobManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.service.event.Event;

import com.day.cq.replication.ReplicationAction;

@ExtendWith(MockitoExtension.class)
class PreviewSyncEventHandlerTest {

    @InjectMocks
    private PreviewSyncEventHandler handler;

    @Mock
    private ResourceResolverFactory resourceResolverFactory;

    @Mock
    private JobManager jobManager;

    @Mock
    private ResourceResolver resolver;

    @Mock
    private Resource resource;

    @Mock
    private ConfigurationBuilder configurationBuilder;

    @Mock
    private Job job;

    @BeforeEach
    void setUp() throws LoginException {
        lenient().when(resourceResolverFactory.getServiceResourceResolver(any()))
                .thenReturn(resolver);
    }

    @Test
    @DisplayName("Should ignore events triggered by own service user (loop prevention)")
    void shouldIgnoreSelfTriggeredEvents() {
        Event event = createReplicationEvent("/content/wknd/us/en", "ACTIVATE",
                PreviewSyncConstants.PREVIEW_SYNC_PRINCIPAL);

        handler.handleEvent(event);

        verifyNoInteractions(jobManager);
    }

    @Test
    @DisplayName("Should ignore unsupported action types like TEST")
    void shouldIgnoreUnsupportedActionTypes() {
        Event event = createReplicationEvent("/content/wknd/us/en", "TEST", "admin");

        handler.handleEvent(event);

        verifyNoInteractions(jobManager);
    }

    @Test
    @DisplayName("Should enqueue job when CAConfig enableAutoSync is true")
    void shouldEnqueueJobWhenEnabled() {
        String path = "/content/wknd/us/en";
        Event event = createReplicationEvent(path, "ACTIVATE", "admin");

        when(resolver.getResource(path)).thenReturn(resource);
        when(resource.adaptTo(ConfigurationBuilder.class)).thenReturn(configurationBuilder);
        PreviewSyncConfig config = mock(PreviewSyncConfig.class);
        when(config.enableAutoSync()).thenReturn(true);
        when(configurationBuilder.as(PreviewSyncConfig.class)).thenReturn(config);
        when(jobManager.addJob(eq(PreviewSyncConstants.JOB_TOPIC), any(Map.class))).thenReturn(job);

        handler.handleEvent(event);

        verify(jobManager).addJob(eq(PreviewSyncConstants.JOB_TOPIC), any(Map.class));
    }

    @Test
    @DisplayName("Should NOT enqueue job when CAConfig enableAutoSync is false")
    void shouldNotEnqueueJobWhenDisabled() {
        String path = "/content/wknd/us/en";
        Event event = createReplicationEvent(path, "ACTIVATE", "admin");

        when(resolver.getResource(path)).thenReturn(resource);
        when(resource.adaptTo(ConfigurationBuilder.class)).thenReturn(configurationBuilder);
        PreviewSyncConfig config = mock(PreviewSyncConfig.class);
        when(config.enableAutoSync()).thenReturn(false);
        when(configurationBuilder.as(PreviewSyncConfig.class)).thenReturn(config);

        handler.handleEvent(event);

        verifyNoInteractions(jobManager);
    }

    @Test
    @DisplayName("Should walk ancestors on DEACTIVATE when resource is deleted")
    void shouldWalkAncestorsOnDeactivateDeletedResource() {
        String deletedPath = "/content/wknd/us/en/article-1";
        String parentPath = "/content/wknd/us/en";
        Event event = createReplicationEvent(deletedPath, "DEACTIVATE", "admin");

        when(resolver.getResource(deletedPath)).thenReturn(null);
        when(resolver.getResource(parentPath)).thenReturn(resource);
        when(resource.adaptTo(ConfigurationBuilder.class)).thenReturn(configurationBuilder);
        PreviewSyncConfig config = mock(PreviewSyncConfig.class);
        when(config.enableAutoSync()).thenReturn(true);
        when(configurationBuilder.as(PreviewSyncConfig.class)).thenReturn(config);
        when(jobManager.addJob(eq(PreviewSyncConstants.JOB_TOPIC), any(Map.class))).thenReturn(job);

        handler.handleEvent(event);

        verify(jobManager).addJob(eq(PreviewSyncConstants.JOB_TOPIC), any(Map.class));
    }

    @Test
    @DisplayName("Should NOT walk ancestors on ACTIVATE when resource is null")
    void shouldNotWalkAncestorsOnActivateNullResource() {
        String path = "/content/wknd/us/en/missing";
        Event event = createReplicationEvent(path, "ACTIVATE", "admin");

        when(resolver.getResource(path)).thenReturn(null);

        handler.handleEvent(event);

        verifyNoInteractions(jobManager);
    }

    @Test
    @DisplayName("Should process events with empty userId (AEMaaCS SCD scenario)")
    void shouldProcessEventsWithEmptyUserId() {
        String path = "/content/wknd/us/en";
        Event event = createReplicationEvent(path, "ACTIVATE", "");

        when(resolver.getResource(path)).thenReturn(resource);
        when(resource.adaptTo(ConfigurationBuilder.class)).thenReturn(configurationBuilder);
        PreviewSyncConfig config = mock(PreviewSyncConfig.class);
        when(config.enableAutoSync()).thenReturn(true);
        when(configurationBuilder.as(PreviewSyncConfig.class)).thenReturn(config);
        when(jobManager.addJob(eq(PreviewSyncConstants.JOB_TOPIC), any(Map.class))).thenReturn(job);

        handler.handleEvent(event);

        verify(jobManager).addJob(eq(PreviewSyncConstants.JOB_TOPIC), any(Map.class));
    }

    @Test
    @DisplayName("Should handle LoginException gracefully")
    void shouldHandleLoginException() throws LoginException {
        when(resourceResolverFactory.getServiceResourceResolver(any()))
                .thenThrow(new LoginException("test"));
        Event event = createReplicationEvent("/content/wknd/us/en", "ACTIVATE", "admin");

        handler.handleEvent(event);

        verifyNoInteractions(jobManager);
    }

    /**
     * Helper to create a simulated ReplicationAction OSGi event.
     */
    private Event createReplicationEvent(String path, String actionType, String userId) {
        Map<String, Object> props = new HashMap<>();
        props.put("paths", new String[] { path });
        props.put("type", actionType);
        if (userId != null) {
            props.put("userId", userId);
        }
        props.put("modificationDate", System.currentTimeMillis());
        return new Event(ReplicationAction.EVENT_TOPIC, props);
    }
}
