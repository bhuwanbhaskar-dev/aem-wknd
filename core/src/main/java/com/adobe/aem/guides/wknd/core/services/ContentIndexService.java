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
package com.adobe.aem.guides.wknd.core.services;

import java.util.List;

/**
 * Service that reads WKND content pages and writes a summary index
 * to a dedicated /var location.
 *
 * <p>This service demonstrates the correct AEMaaCS pattern for
 * programmatic JCR access using ResourceResolverFactory with a
 * named subservice backed by a System User.</p>
 *
 * <p>The subservice name "content-indexer" maps to the system user
 * "wknd-content-indexer-service" via ServiceUserMapperImpl OSGi config.
 * That system user has:
 * <ul>
 *   <li>jcr:read on /content/wknd (to scan pages)</li>
 *   <li>jcr:write on /var/wknd/content-index (to persist the index)</li>
 * </ul>
 * </p>
 */
public interface ContentIndexService {

    /**
     * The subservice name. This string must match the key in
     * ServiceUserMapperImpl.amended~content-indexer.cfg.json exactly.
     * It is declared as a constant so Java code and tests can reference
     * it without hardcoding the string literal in multiple places.
     */
    String SUBSERVICE_NAME = "content-indexer";

    /**
     * The expected JCR principal name of the system user that backs this
     * subservice. Used for runtime identity verification — if the resolver
     * opens as a different principal, a security warning is logged.
     *
     * This value must match the RepoInit script:
     *   create service user wknd-content-indexer-service with path system/cq:services/wknd
     */
    String EXPECTED_SYSTEM_USER = "wknd-content-indexer-service";

    /**
     * The root path scanned for content pages.
     */
    String CONTENT_ROOT = "/content/wknd/us/en";

    /**
     * The path where the index node is written.
     * The system user must have jcr:write here.
     */
    String INDEX_ROOT = "/var/wknd/content-index";

    /**
     * Scans all pages under {@link #CONTENT_ROOT}, collects their
     * title and path, writes a summary node to {@link #INDEX_ROOT},
     * and returns the collected entries.
     *
     * @return list of index entries (never null; empty if nothing found)
     * @throws ContentIndexException if the service resolver cannot be
     *         obtained or the write operation fails
     */
    List<IndexEntry> buildIndex() throws ContentIndexException;

    /**
     * Reads the previously persisted index from {@link #INDEX_ROOT}
     * without performing a new scan.
     *
     * @return list of entries stored in the index node, or empty list
     * @throws ContentIndexException if the service resolver cannot be obtained
     */
    List<IndexEntry> readIndex() throws ContentIndexException;

    // ── Inner types ────────────────────────────────────────────────────────

    /**
     * An immutable value object representing a single indexed page.
     */
    final class IndexEntry {
        private final String path;
        private final String title;
        private final String lastModified;

        public IndexEntry(String path, String title, String lastModified) {
            this.path = path;
            this.title = title;
            this.lastModified = lastModified;
        }

        public String getPath()         { return path; }
        public String getTitle()        { return title; }
        public String getLastModified() { return lastModified; }

        @Override
        public String toString() {
            return "IndexEntry{path='" + path + "', title='" + title + "'}";
        }
    }

    /**
     * Checked exception thrown when the content index operation fails.
     */
    final class ContentIndexException extends Exception {
        public ContentIndexException(String message) {
            super(message);
        }
        public ContentIndexException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
