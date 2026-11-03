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
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.propertytypes.ServiceDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Implementation of {@link ContentIndexService}.
 *
 * <h2>How ResourceResolverFactory is used here</h2>
 * <p>
 * This class never calls {@code getAdministrativeResourceResolver()} —
 * that API is banned in AEM as a Cloud Service. Instead, it calls
 * {@code getServiceResourceResolver()} with a {@code Map} containing
 * the key {@link ResourceResolverFactory#SUBSERVICE} set to the
 * subservice name {@value ContentIndexService#SUBSERVICE_NAME}.
 * </p>
 *
 * <h2>How the subservice resolves to a system user</h2>
 * <pre>
 *   Bundle symbolic name : aem-guides-wknd.core
 *   Subservice name      : content-indexer
 *   ─────────────────────────────────────────────
 *   OSGi config file:
 *     ServiceUserMapperImpl.amended~content-indexer.cfg.json
 *     user.mapping = ["aem-guides-wknd.core:content-indexer=[wknd-content-indexer-service]"]
 *   ─────────────────────────────────────────────
 *   Resolved system user : wknd-content-indexer-service
 * </pre>
 *
 * <h2>Resource management contract</h2>
 * <p>
 * Every code path that opens a ResourceResolver MUST close it.
 * This implementation uses try-with-resources which is the safest pattern.
 * If you cannot use try-with-resources (e.g., the resolver must outlive
 * the method), use a try/finally block and close in finally.
 * </p>
 *
 * <h2>Commit vs. revert</h2>
 * <p>
 * Changes via the ResourceResolver are buffered in memory until
 * {@code resolver.commit()} is called. If an exception occurs before
 * commit, the changes are automatically discarded when the resolver
 * is closed. Always call commit only after all writes succeed.
 * </p>
 */
@Component(
    service = ContentIndexService.class,
    immediate = true  // activate as soon as the bundle starts
)
@ServiceDescription("WKND Content Index Service — demonstrates service users and ResourceResolverFactory")
public class ContentIndexServiceImpl implements ContentIndexService {

    private static final Logger LOG = LoggerFactory.getLogger(ContentIndexServiceImpl.class);

    /*
     * ──────────────────────────────────────────────────────────────────────
     * JCR node structure we write under /var/wknd/content-index
     *
     *   /var/wknd/content-index           (nt:unstructured)
     *     indexedAt       = "2024-11-03T..."
     *     entryCount      = 42
     *     /entries        (nt:unstructured)
     *       /entry-0      (nt:unstructured)
     *         path        = "/content/wknd/us/en/adventures"
     *         title       = "Adventures"
     *         lastModified = "2024-10-01T..."
     *       /entry-1      ...
     * ──────────────────────────────────────────────────────────────────────
     */

    private static final String NT_UNSTRUCTURED = "nt:unstructured";
    private static final String PROP_JCR_TITLE   = "jcr:title";
    private static final String PROP_JCR_LAST_MODIFIED = "jcr:lastModified";
    private static final String PROP_CQ_PAGE_CONTENT = "jcr:content";

    // ── OSGi Service References ────────────────────────────────────────────

    /*
     * @Reference tells OSGi DS (Declarative Services) to inject the
     * ResourceResolverFactory OSGi service into this field.
     *
     * IMPORTANT: ResourceResolverFactory is injected — we do NOT construct
     * it ourselves. The OSGi runtime manages its lifecycle. This component
     * will not activate until ResourceResolverFactory is available.
     */
    @Reference
    private ResourceResolverFactory resourceResolverFactory;

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Activate
    protected void activate() {
        LOG.info("[ContentIndexService] Service activated. Subservice='{}', contentRoot='{}', indexRoot='{}'",
            SUBSERVICE_NAME, CONTENT_ROOT, INDEX_ROOT);
        /*
         * Note: we do NOT open a ResourceResolver here.
         * The @Activate method is part of the OSGi lifecycle.
         * Opening a resolver here and storing it as a field would
         * create a long-lived session that leaks memory and holds
         * JCR locks. Always open resolvers per-operation and close them.
         */
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Execution flow:
     * <ol>
     *   <li>Obtain a service ResourceResolver (authenticated as system user)</li>
     *   <li>Traverse pages under CONTENT_ROOT via the resolver</li>
     *   <li>Collect IndexEntry objects from page jcr:content nodes</li>
     *   <li>Create or update the index node at INDEX_ROOT</li>
     *   <li>Write each entry as a child node</li>
     *   <li>Commit the session</li>
     *   <li>Close the resolver (auto-close via try-with-resources)</li>
     * </ol>
     * </p>
     */
    @Override
    public List<IndexEntry> buildIndex() throws ContentIndexException {
        LOG.info("[ContentIndexService] buildIndex() called");

        /*
         * ── STEP 1: Open the service ResourceResolver ──────────────────────
         *
         * getServiceResourceResolver() requires:
         *   - ResourceResolverFactory.SUBSERVICE  →  "content-indexer"
         *
         * The OSGi runtime reads the calling bundle's symbolic name
         * automatically from the OSGi framework context, so we only
         * supply the subservice name.
         *
         * This call will throw LoginException if:
         *   (a) No service user mapping exists for this bundle+subservice
         *   (b) The mapped system user does not exist in the JCR
         *   (c) The RepoInit script failed to create the system user
         *
         * try-with-resources ensures the resolver is closed even if
         * an exception is thrown inside the block.
         */
        Map<String, Object> authInfo = new HashMap<>();
        authInfo.put(ResourceResolverFactory.SUBSERVICE, SUBSERVICE_NAME);

        try (ResourceResolver resolver = resourceResolverFactory.getServiceResourceResolver(authInfo)) {

            LOG.debug("[ContentIndexService] ResourceResolver opened as user='{}'",
                resolver.getUserID());
            // resolver.getUserID() returns "wknd-content-indexer-service"
            // This is the system user — confirms the mapping worked.

            // ── Exercise 4: Runtime Identity Verification ────────────────────
            verifyResolverIdentity(resolver);

            // ── STEP 2: Read phase (needs jcr:read on /content/wknd) ────────
            List<IndexEntry> entries = scanContentTree(resolver);
            LOG.info("[ContentIndexService] Scanned {} pages from '{}'", entries.size(), CONTENT_ROOT);

            // ── STEP 3: Write phase (needs jcr:write on /var/wknd/content-index)
            persistIndex(resolver, entries);

            // ── STEP 4: Commit ────────────────────────────────────────────────
            /*
             * commit() flushes the in-memory changes to the JCR.
             * If commit() is not called, all changes are lost when the
             * resolver is closed. This is intentional — changes are
             * transactional within the resolver's session.
             *
             * If commit() throws PersistenceException, the catch block
             * below converts it to ContentIndexException. The resolver
             * is still closed by try-with-resources — any uncommitted
             * changes are automatically discarded.
             */
            resolver.commit();
            LOG.info("[ContentIndexService] Index committed to '{}'", INDEX_ROOT);

            return entries;

        } catch (LoginException e) {
            /*
             * LoginException means authentication failed.
             * Root causes:
             *   1. Missing ServiceUserMapper OSGi config
             *   2. System user "wknd-content-indexer-service" not created by RepoInit
             *   3. Typo in subservice name or bundle symbolic name
             *
             * NEVER swallow this silently — it is a configuration problem
             * that must be fixed, not retried.
             */
            LOG.error("[ContentIndexService] LoginException — check ServiceUserMapper config. " +
                "Bundle: aem-guides-wknd.core, Subservice: {}", SUBSERVICE_NAME, e);
            throw new ContentIndexException(
                "Failed to obtain service ResourceResolver for subservice '" + SUBSERVICE_NAME +
                "'. Verify ServiceUserMapperImpl.amended~content-indexer.cfg.json exists and " +
                "the system user 'wknd-content-indexer-service' was created by RepoInit.", e);

        } catch (PersistenceException e) {
            /*
             * PersistenceException means the commit failed.
             * Root causes:
             *   1. System user lacks jcr:write on the target path
             *   2. A constraint violation in the JCR (invalid node type, etc.)
             *   3. Concurrent modification conflict
             */
            LOG.error("[ContentIndexService] PersistenceException — check ACL for system user " +
                "'wknd-content-indexer-service' on path '{}'", INDEX_ROOT, e);
            throw new ContentIndexException(
                "Failed to persist index at '" + INDEX_ROOT + "'. " +
                "Verify that 'wknd-content-indexer-service' has jcr:write permission.", e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads the existing index node without writing anything.
     * Still needs a service resolver (read requires authentication too).</p>
     */
    @Override
    public List<IndexEntry> readIndex() throws ContentIndexException {
        Map<String, Object> authInfo = new HashMap<>();
        authInfo.put(ResourceResolverFactory.SUBSERVICE, SUBSERVICE_NAME);

        try (ResourceResolver resolver = resourceResolverFactory.getServiceResourceResolver(authInfo)) {
            return loadPersistedEntries(resolver);
        } catch (LoginException e) {
            LOG.error("[ContentIndexService] LoginException on readIndex()", e);
            throw new ContentIndexException("Cannot open service resolver for readIndex()", e);
        }
    }

    // ── Private Helpers ────────────────────────────────────────────────────

    /**
     * Exercise 4 — Runtime Identity Verification.
     *
     * <p>Verifies that the ResourceResolver is authenticated as the expected
     * system user. In a correctly configured system this check always passes,
     * but it serves three production purposes:</p>
     *
     * <ol>
     *   <li><strong>Audit trail:</strong> Every index operation logs the exact
     *       JCR principal, making it trivial to correlate actions in Oak
     *       audit logs with the service that caused them.</li>
     *
     *   <li><strong>Misconfiguration detection:</strong> If someone accidentally
     *       maps the subservice to a different system user (e.g., an admin user
     *       during a copy-paste error in ServiceUserMapper config), this log
     *       will immediately expose the problem rather than silently running
     *       with elevated privileges.</li>
     *
     *   <li><strong>Security defence in depth:</strong> Fail fast with a clear
     *       warning rather than operating with unexpected permissions. In a
     *       zero-trust architecture, you never assume the resolver is who you
     *       think it is — you verify.</li>
     * </ol>
     *
     * <p>In AEM as a Cloud Service, {@link ResourceResolver#getUserID()} returns
     * the JCR principal name of the authenticated session. For a service resolver
     * this is the system user name exactly as created by RepoInit.</p>
     *
     * @param resolver the freshly opened service resolver
     */
    private void verifyResolverIdentity(ResourceResolver resolver) {
        String actualUser = resolver.getUserID();

        if (EXPECTED_SYSTEM_USER.equals(actualUser)) {
            // Expected case — everything is configured correctly
            LOG.info("[ContentIndexService] Identity verified: resolver authenticated as '{}'", actualUser);
        } else {
            /*
             * This should NEVER happen in production if configs are correct.
             * Possible causes:
             *   1. A different system user was accidentally mapped to this subservice
             *   2. The RepoInit script uses a different user name than expected
             *   3. The ServiceUserMapper was changed without updating this constant
             *
             * We log a SECURITY WARNING but do NOT throw — the operation may still
             * succeed (the other user might have the same permissions). However,
             * this warning must be investigated before the next deployment.
             *
             * In a stricter security posture, you could throw ContentIndexException
             * here to hard-fail any operation running under the wrong identity.
             */
            LOG.warn("[ContentIndexService] ⚠ SECURITY WARNING: Resolver authenticated as '{}' " +
                "but expected '{}'. Check ServiceUserMapperImpl.amended~content-indexer.cfg.json " +
                "and verify the RepoInit script created the correct system user. " +
                "Proceeding, but investigate this immediately.",
                actualUser, EXPECTED_SYSTEM_USER);
        }
    }

    /**
     * Traverses the content tree under CONTENT_ROOT and collects
     * IndexEntry objects from each cq:Page jcr:content node.
     *
     * <p>Note: resolver.getResource() returns null when the resource
     * does not exist OR when the system user has no jcr:read permission.
     * Always do a null check.</p>
     */
    private List<IndexEntry> scanContentTree(ResourceResolver resolver) {
        List<IndexEntry> entries = new ArrayList<>();

        Resource root = resolver.getResource(CONTENT_ROOT);
        if (root == null) {
            LOG.warn("[ContentIndexService] Cannot read '{}' — resource is null. " +
                "Check jcr:read permission for system user.", CONTENT_ROOT);
            return entries;
        }

        traversePages(root, entries, 0);
        return entries;
    }

    /**
     * Recursively walks child resources looking for cq:Page nodes
     * (identified by having a jcr:content child).
     *
     * @param resource the current resource being examined
     * @param entries  accumulator for found pages
     * @param depth    current recursion depth (for logging and safety)
     */
    private void traversePages(Resource resource, List<IndexEntry> entries, int depth) {
        if (depth > 10) {
            LOG.warn("[ContentIndexService] Max depth reached at '{}'", resource.getPath());
            return;
        }

        Resource content = resource.getChild(PROP_CQ_PAGE_CONTENT);
        if (content != null) {
            // This resource is a cq:Page — extract its properties
            ValueMap props = content.getValueMap();
            String title        = props.get(PROP_JCR_TITLE, resource.getName());
            Calendar lastMod    = props.get(PROP_JCR_LAST_MODIFIED, Calendar.class);
            String lastModStr   = lastMod != null ? lastMod.toInstant().toString() : "unknown";

            entries.add(new IndexEntry(resource.getPath(), title, lastModStr));
            LOG.debug("[ContentIndexService] Indexed page: path='{}', title='{}'",
                resource.getPath(), title);
        }

        // Recurse into children (whether or not this was a page)
        Iterator<Resource> children = resource.listChildren();
        while (children.hasNext()) {
            Resource child = children.next();
            // Skip jcr:content nodes — they are properties of the page, not sub-pages
            if (!PROP_CQ_PAGE_CONTENT.equals(child.getName())) {
                traversePages(child, entries, depth + 1);
            }
        }
    }

    /**
     * Creates or updates the index node at INDEX_ROOT and writes
     * each entry as a child node.
     *
     * <p>Uses {@link ResourceUtil#getOrCreateResource} which is the
     * Sling-idiomatic way to ensure a node exists before writing to it.</p>
     *
     * <p>This method requires the system user to have {@code jcr:write}
     * on {@code /var/wknd/content-index}. If the node doesn't exist,
     * {@code jcr:addChildNodes} is also needed on the parent.</p>
     *
     * @throws PersistenceException if the write fails (permission denied,
     *         constraint violation, etc.)
     */
    private void persistIndex(ResourceResolver resolver, List<IndexEntry> entries)
            throws PersistenceException {

        // Create the index root node if it doesn't exist
        // ResourceUtil.getOrCreateResource handles both create and get cases
        Map<String, Object> indexProps = new HashMap<>();
        indexProps.put("jcr:primaryType", NT_UNSTRUCTURED);
        indexProps.put("indexedAt", Calendar.getInstance().toInstant().toString());
        indexProps.put("entryCount", entries.size());
        indexProps.put("indexedBy", "wknd-content-indexer-service");

        Resource indexRoot = ResourceUtil.getOrCreateResource(
            resolver,
            INDEX_ROOT,
            indexProps,
            NT_UNSTRUCTURED,
            false  // don't auto-commit — we commit at the end
        );

        // Update the indexedAt and entryCount properties on the root node
        ModifiableValueMap indexMap = indexRoot.adaptTo(ModifiableValueMap.class);
        if (indexMap != null) {
            indexMap.put("indexedAt", Calendar.getInstance().toInstant().toString());
            indexMap.put("entryCount", entries.size());
        }

        // Remove old entries child node if it exists, then recreate it
        // This ensures a clean rebuild rather than incremental updates
        Resource oldEntries = indexRoot.getChild("entries");
        if (oldEntries != null) {
            resolver.delete(oldEntries);
        }

        // Create a fresh entries container
        Map<String, Object> entriesContainerProps = new HashMap<>();
        entriesContainerProps.put("jcr:primaryType", NT_UNSTRUCTURED);
        Resource entriesContainer = resolver.create(indexRoot, "entries", entriesContainerProps);

        // Write each entry as a child node named "entry-0", "entry-1", etc.
        for (int i = 0; i < entries.size(); i++) {
            IndexEntry entry = entries.get(i);
            Map<String, Object> entryProps = new HashMap<>();
            entryProps.put("jcr:primaryType", NT_UNSTRUCTURED);
            entryProps.put("path",  entry.getPath());
            entryProps.put("title", entry.getTitle());
            entryProps.put("lastModified", entry.getLastModified());

            resolver.create(entriesContainer, "entry-" + i, entryProps);
            LOG.debug("[ContentIndexService] Written entry-{}: '{}'", i, entry.getPath());
        }

        LOG.info("[ContentIndexService] Wrote {} entries to '{}'", entries.size(), INDEX_ROOT);
        // NOTE: we do NOT call resolver.commit() here.
        // Commit is called in the caller (buildIndex) after ALL writes succeed.
        // This keeps the operation atomic — either everything commits or nothing does.
    }

    /**
     * Reads the persisted index entries from INDEX_ROOT/entries.
     */
    private List<IndexEntry> loadPersistedEntries(ResourceResolver resolver) {
        Resource indexRoot = resolver.getResource(INDEX_ROOT);
        if (indexRoot == null) {
            LOG.info("[ContentIndexService] No index found at '{}'. Run buildIndex() first.", INDEX_ROOT);
            return Collections.emptyList();
        }

        Resource entriesContainer = indexRoot.getChild("entries");
        if (entriesContainer == null) {
            return Collections.emptyList();
        }

        List<IndexEntry> entries = new ArrayList<>();
        Iterator<Resource> children = entriesContainer.listChildren();
        while (children.hasNext()) {
            Resource entryResource = children.next();
            ValueMap props = entryResource.getValueMap();
            entries.add(new IndexEntry(
                props.get("path", ""),
                props.get("title", ""),
                props.get("lastModified", "")
            ));
        }
        LOG.debug("[ContentIndexService] Loaded {} entries from persisted index", entries.size());
        return entries;
    }
}
