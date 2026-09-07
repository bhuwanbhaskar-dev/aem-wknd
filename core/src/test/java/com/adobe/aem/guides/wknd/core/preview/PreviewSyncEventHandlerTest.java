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
import org.apache.sling.discovery.DiscoveryService;
import org.apache.sling.discovery.InstanceDescription;
import org.apache.sling.discovery.TopologyView;
import org.apache.sling.distribution.event.DistributionEventTopics;
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

    @Mock
    private DiscoveryService discoveryService;

    @Mock
    private TopologyView topologyView;

    @Mock
    private InstanceDescription localInstance;

    @BeforeEach
    void setUp() throws LoginException {
        lenient().when(resourceResolverFactory.getServiceResourceResolver(any()))
                .thenReturn(resolver);
        // Default: local pod is leader and topology is current
        lenient().when(discoveryService.getTopology()).thenReturn(topologyView);
        lenient().when(topologyView.isCurrent()).thenReturn(true);
        lenient().when(topologyView.getLocalInstance()).thenReturn(localInstance);
        lenient().when(localInstance.isLeader()).thenReturn(true);
        lenient().when(resource.getPath()).thenReturn("/content/wknd/us/en");
    }

    @Test
    @DisplayName("Should ignore events from non-publish SCD agents (loop prevention)")
    void shouldIgnoreNonPublishAgentEvents() {
        Event event = createDistributionEvent(PreviewSyncConstants.PREVIEW_AGENT_ID,
                "ADD", "/content/wknd/us/en");

        handler.handleEvent(event);

        verifyNoInteractions(jobManager);
    }

    // ---- Leader Guard Tests -----------------------------------------------

    @Test
    @DisplayName("Should drop event when local pod is NOT the leader (non-leader Author pod)")
    void shouldDropEventWhenNotLeader() {
        when(localInstance.isLeader()).thenReturn(false);
        Event event = createDistributionEvent(PreviewSyncConstants.PUBLISH_AGENT_ID,
                "ADD", "/content/wknd/us/en");

        handler.handleEvent(event);

        verifyNoInteractions(jobManager);
    }

    @Test
    @DisplayName("Should drop event when topology is not current (leader election in progress)")
    void shouldDropEventWhenTopologyNotCurrent() {
        when(topologyView.isCurrent()).thenReturn(false);
        Event event = createDistributionEvent(PreviewSyncConstants.PUBLISH_AGENT_ID,
                "ADD", "/content/wknd/us/en");

        handler.handleEvent(event);

        verifyNoInteractions(jobManager);
    }

    @Test
    @DisplayName("Should drop event when topology view is null (Cloud Manager rolling restart)")
    void shouldDropEventWhenTopologyIsNull() {
        when(discoveryService.getTopology()).thenReturn(null);
        Event event = createDistributionEvent(PreviewSyncConstants.PUBLISH_AGENT_ID,
                "ADD", "/content/wknd/us/en");

        handler.handleEvent(event);

        verifyNoInteractions(jobManager);
    }

    @Test
    @DisplayName("Should drop event when local instance is null")
    void shouldDropEventWhenLocalInstanceIsNull() {
        when(topologyView.getLocalInstance()).thenReturn(null);
        Event event = createDistributionEvent(PreviewSyncConstants.PUBLISH_AGENT_ID,
                "ADD", "/content/wknd/us/en");

        handler.handleEvent(event);

        verifyNoInteractions(jobManager);
    }

    // ---- Agent / Type Filter Tests ----------------------------------------

    @Test
    @DisplayName("Should ignore unsupported SCD distribution types")
    void shouldIgnoreUnsupportedDistributionTypes() {
        Event event = createDistributionEvent(PreviewSyncConstants.PUBLISH_AGENT_ID,
                "PULL", "/content/wknd/us/en");

        handler.handleEvent(event);

        verifyNoInteractions(jobManager);
    }

    @Test
    @DisplayName("Should enqueue job when CAConfig enableAutoSync is true (ADD)")
    void shouldEnqueueJobWhenEnabled() {
        String path = "/content/wknd/us/en";
        Event event = createDistributionEvent(PreviewSyncConstants.PUBLISH_AGENT_ID, "ADD", path);

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
        Event event = createDistributionEvent(PreviewSyncConstants.PUBLISH_AGENT_ID, "ADD", path);

        when(resolver.getResource(path)).thenReturn(resource);
        when(resource.adaptTo(ConfigurationBuilder.class)).thenReturn(configurationBuilder);
        PreviewSyncConfig config = mock(PreviewSyncConfig.class);
        when(config.enableAutoSync()).thenReturn(false);
        when(configurationBuilder.as(PreviewSyncConfig.class)).thenReturn(config);

        handler.handleEvent(event);

        verifyNoInteractions(jobManager);
    }

    @Test
    @DisplayName("Should walk ancestors on DELETE when resource is already removed")
    void shouldWalkAncestorsOnDeleteForRemovedResource() {
        String deletedPath = "/content/wknd/us/en/article-1";
        String parentPath = "/content/wknd/us/en";
        Event event = createDistributionEvent(PreviewSyncConstants.PUBLISH_AGENT_ID, "DELETE", deletedPath);

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
    @DisplayName("Should NOT walk ancestors on ADD when resource is null")
    void shouldNotWalkAncestorsOnAddNullResource() {
        String path = "/content/wknd/us/en/missing";
        Event event = createDistributionEvent(PreviewSyncConstants.PUBLISH_AGENT_ID, "ADD", path);

        when(resolver.getResource(path)).thenReturn(null);

        handler.handleEvent(event);

        verifyNoInteractions(jobManager);
    }

    @Test
    @DisplayName("Should enqueue job for DEACTIVATE (DELETE) when opted-in")
    void shouldEnqueueJobOnDelete() {
        String path = "/content/wknd/us/en";
        Event event = createDistributionEvent(PreviewSyncConstants.PUBLISH_AGENT_ID, "DELETE", path);

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
        Event event = createDistributionEvent(PreviewSyncConstants.PUBLISH_AGENT_ID,
                "ADD", "/content/wknd/us/en");

        handler.handleEvent(event);

        verifyNoInteractions(jobManager);
    }

    /**
     * Helper to create a simulated SCD AGENT_PACKAGE_DISTRIBUTED OSGi event.
     */
    private Event createDistributionEvent(String agentName, String distType, String... paths) {
        Map<String, Object> props = new HashMap<>();
        props.put("distribution.component.name", agentName);
        props.put("distribution.type", distType);
        props.put("distribution.paths", paths);
        return new Event(DistributionEventTopics.AGENT_PACKAGE_DISTRIBUTED, props);
    }
}
