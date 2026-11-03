/*
 * Copyright 2024 Adobe Systems Incorporated
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.adobe.aem.guides.wknd.core.services.impl;

import com.adobe.aem.guides.wknd.core.services.ContentIndexService;
import com.adobe.aem.guides.wknd.core.services.ContentIndexService.ContentIndexException;
import com.adobe.aem.guides.wknd.core.services.ContentIndexService.IndexEntry;
import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ContentIndexServiceImpl}.
 *
 * <h2>Testing strategy for ResourceResolverFactory</h2>
 * <p>
 * The AEM Mock context ({@link AemContext}) provides an in-memory
 * resource resolver backed by an Oak in-memory repository. We use this
 * to test the actual JCR read/write logic without a running AEM instance.
 * </p>
 *
 * <p>
 * For testing {@link LoginException} scenarios, we use Mockito to mock
 * the {@link ResourceResolverFactory} and make it throw the exception,
 * simulating a missing service user mapping.
 * </p>
 */
@ExtendWith({AemContextExtension.class, MockitoExtension.class})
class ContentIndexServiceImplTest {

    /*
     * AemContext sets up an in-memory Sling/AEM environment.
     *
     * ResourceResolverType.RESOURCERESOLVER_MOCK is a pure in-memory mock
     * that does not require a real Oak dependency. It supports basic CRUD
     * via ctx.build() and resolver.create(), which is all we need here.
     *
     * ResourceResolverType.JCR_OAK would require the additional Maven
     * dependency org.apache.sling:org.apache.sling.testing.sling-mock-oak.
     * Use RESOURCERESOLVER_MOCK unless you specifically need real Oak
     * JCR semantics (node type validation, versioning, etc.).
     */
    private final AemContext ctx = new AemContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @Mock
    private ResourceResolverFactory mockResolverFactory;

    // ── Test 1: Happy path — buildIndex reads content and writes index ─────

    @Test
    @DisplayName("buildIndex() scans content tree and writes index node")
    void testBuildIndex_happyPath() throws Exception {
        // Arrange — set up test content in the in-memory JCR
        ctx.build()
            .resource("/content/wknd/us/en", "jcr:primaryType", "cq:Page")
            .resource("/content/wknd/us/en/jcr:content",
                "jcr:primaryType", "cq:PageContent",
                "jcr:title", "WKND Home",
                "jcr:lastModified", "2024-01-01T00:00:00Z")
            .resource("/content/wknd/us/en/adventures", "jcr:primaryType", "cq:Page")
            .resource("/content/wknd/us/en/adventures/jcr:content",
                "jcr:primaryType", "cq:PageContent",
                "jcr:title", "Adventures")
            .commit();

        // Register the service with the AEM context's ResourceResolverFactory
        // AemContext provides a ResourceResolverFactory that bypasses real auth —
        // this is correct for unit testing; we test the logic, not the auth layer.
        ContentIndexServiceImpl service = new ContentIndexServiceImpl();
        ctx.registerInjectActivateService(service);

        // Act
        List<IndexEntry> entries = service.buildIndex();

        // Assert — pages were found
        assertFalse(entries.isEmpty(), "Should have found at least one page");
        assertTrue(entries.stream().anyMatch(e -> e.getPath().contains("/adventures")),
            "Should have indexed /adventures page");

        // Assert — index was written to JCR
        ResourceResolver resolver = ctx.resourceResolver();
        Resource indexRoot = resolver.getResource(ContentIndexService.INDEX_ROOT);
        assertNotNull(indexRoot, "Index node should have been created at " + ContentIndexService.INDEX_ROOT);

        Resource entriesNode = indexRoot.getChild("entries");
        assertNotNull(entriesNode, "entries child node should exist");

        // Verify entryCount property
        int storedCount = indexRoot.getValueMap().get("entryCount", 0);
        assertEquals(entries.size(), storedCount, "entryCount property should match actual count");
    }

    // ── Test 2: readIndex() after buildIndex() returns persisted data ──────

    @Test
    @DisplayName("readIndex() returns entries persisted by buildIndex()")
    void testReadIndex_afterBuildIndex() throws Exception {
        // Arrange
        ctx.build()
            .resource("/content/wknd/us/en", "jcr:primaryType", "cq:Page")
            .resource("/content/wknd/us/en/jcr:content",
                "jcr:primaryType", "cq:PageContent",
                "jcr:title", "WKND Home")
            .commit();

        ContentIndexServiceImpl service = new ContentIndexServiceImpl();
        ctx.registerInjectActivateService(service);

        // Build first
        service.buildIndex();

        // Act — read back
        List<IndexEntry> readEntries = service.readIndex();

        // Assert
        assertFalse(readEntries.isEmpty(), "readIndex() should return persisted entries");
    }

    // ── Test 3: readIndex() when no index exists returns empty list ────────

    @Test
    @DisplayName("readIndex() returns empty list when no index exists")
    void testReadIndex_noIndexExists() throws Exception {
        ContentIndexServiceImpl service = new ContentIndexServiceImpl();
        ctx.registerInjectActivateService(service);

        List<IndexEntry> entries = service.readIndex();

        assertNotNull(entries, "Should return a non-null list");
        assertTrue(entries.isEmpty(), "Should return empty list when index doesn't exist");
    }

    // ── Test 4: LoginException is wrapped in ContentIndexException ─────────

    @Test
    @DisplayName("buildIndex() wraps LoginException in ContentIndexException")
    void testBuildIndex_loginExceptionThrown() throws Exception {
        /*
         * This test simulates what happens when:
         *   - The ServiceUserMapper config is missing
         *   - The system user doesn't exist
         *   - The subservice name is wrong
         *
         * We create the service manually and inject a mock factory via
         * reflection, bypassing OSGi DS injection. This tests pure logic.
         */
        when(mockResolverFactory.getServiceResourceResolver(anyMap()))
            .thenThrow(new LoginException("Service user mapping not found"));

        // Create service and inject mock via ctx (register mock first)
        ctx.registerService(ResourceResolverFactory.class, mockResolverFactory);
        ContentIndexServiceImpl service = new ContentIndexServiceImpl();
        ctx.registerInjectActivateService(service);

        // Act + Assert — the service's own resourceResolverFactory reference
        // was already provided by the AEM context's internal factory from
        // registerInjectActivateService. We need to inject via the field directly.
        // Use reflection to inject the mock into the already-registered service.
        java.lang.reflect.Field field = ContentIndexServiceImpl.class
            .getDeclaredField("resourceResolverFactory");
        field.setAccessible(true);
        field.set(service, mockResolverFactory);

        ContentIndexException ex = assertThrows(ContentIndexException.class,
            () -> service.buildIndex(),
            "buildIndex() must throw ContentIndexException when LoginException occurs");

        assertTrue(ex.getMessage().contains("service ResourceResolver"),
            "Exception message should mention the service resolver problem");
        assertInstanceOf(LoginException.class, ex.getCause(),
            "Cause should be the original LoginException");
    }

    // ── Test 5: IndexEntry value object is correct ─────────────────────────

    @Test
    @DisplayName("IndexEntry correctly stores and returns values")
    void testIndexEntry_valueObject() {
        IndexEntry entry = new IndexEntry("/content/wknd/us/en", "WKND Home", "2024-01-01T00:00:00Z");

        assertEquals("/content/wknd/us/en", entry.getPath());
        assertEquals("WKND Home", entry.getTitle());
        assertEquals("2024-01-01T00:00:00Z", entry.getLastModified());
        assertTrue(entry.toString().contains("WKND Home"));
    }

    // ── Test 6: Constants are correct ─────────────────────────────────────

    @Test
    @DisplayName("Service constants have expected values")
    void testServiceConstants() {
        assertEquals("content-indexer", ContentIndexService.SUBSERVICE_NAME,
            "Subservice name must match ServiceUserMapper config exactly");
        assertEquals("/content/wknd/us/en", ContentIndexService.CONTENT_ROOT);
        assertEquals("/var/wknd/content-index", ContentIndexService.INDEX_ROOT);
    }
}
