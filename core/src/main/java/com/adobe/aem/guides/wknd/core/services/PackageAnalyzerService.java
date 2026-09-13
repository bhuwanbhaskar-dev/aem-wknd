package com.adobe.aem.guides.wknd.core.services;

import com.google.gson.JsonObject;
import org.apache.sling.api.resource.ResourceResolver;

/** Author-only asynchronous preflight. An estimate is never permission to build. */
public interface PackageAnalyzerService {
    JsonObject submit(String requestJson, ResourceResolver caller);
    JsonObject status(String jobId, ResourceResolver caller);

    final class ApiException extends RuntimeException {
        private final int status;
        public ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
        public int getStatus() { return status; }
    }
}
