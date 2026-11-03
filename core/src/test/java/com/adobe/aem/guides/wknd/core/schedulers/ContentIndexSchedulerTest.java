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
package com.adobe.aem.guides.wknd.core.schedulers;

import com.adobe.aem.guides.wknd.core.services.ContentIndexService;
import com.adobe.aem.guides.wknd.core.services.ContentIndexService.ContentIndexException;
import com.adobe.aem.guides.wknd.core.services.ContentIndexService.IndexEntry;
import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ContentIndexScheduler}.
 *
 * <p>We mock {@link ContentIndexService} completely — the scheduler test
 * should only verify scheduler-specific behavior (enabled flag, exception
 * swallowing, delegation) not re-test the service logic itself.</p>
 */
@ExtendWith({AemContextExtension.class, MockitoExtension.class})
class ContentIndexSchedulerTest {

    private final AemContext ctx = new AemContext();

    @Mock
    private ContentIndexService mockContentIndexService;

    private ContentIndexScheduler scheduler;

    @BeforeEach
    void setUp() {
        ctx.registerService(ContentIndexService.class, mockContentIndexService);
        scheduler = ctx.registerInjectActivateService(new ContentIndexScheduler(),
            "enabled", true,
            "scheduler.expression", "0 0/5 * * * ?");
    }

    // ── Test 1: run() delegates to buildIndex() when enabled ──────────────

    @Test
    @DisplayName("run() calls buildIndex() when enabled")
    void testRun_enabled_callsBuildIndex() throws Exception {
        when(mockContentIndexService.buildIndex()).thenReturn(List.of(
            new IndexEntry("/content/wknd/us/en", "WKND", "2024-01-01T00:00:00Z")
        ));

        scheduler.run();

        verify(mockContentIndexService, times(1)).buildIndex();
    }

    // ── Test 2: run() skips buildIndex() when disabled ────────────────────

    @Test
    @DisplayName("run() skips buildIndex() when disabled")
    void testRun_disabled_skipsBuildIndex() throws Exception {
        // Re-register with enabled=false
        ContentIndexScheduler disabledScheduler = ctx.registerInjectActivateService(
            new ContentIndexScheduler(),
            "enabled", false,
            "scheduler.expression", "0 0/5 * * * ?");

        disabledScheduler.run();

        verify(mockContentIndexService, never()).buildIndex();
    }

    // ── Test 3: run() swallows ContentIndexException (does NOT rethrow) ───

    @Test
    @DisplayName("run() swallows ContentIndexException — does not propagate it")
    void testRun_exceptionIsSwallowed() throws Exception {
        when(mockContentIndexService.buildIndex())
            .thenThrow(new ContentIndexException("Simulated failure"));

        // Must NOT throw — an uncaught exception from run() would break
        // the scheduler's future execution ticks
        scheduler.run();

        verify(mockContentIndexService, times(1)).buildIndex();
        // If we reach here without an exception being thrown, the test passes
    }

    // ── Test 4: run() with empty index result doesn't throw ───────────────

    @Test
    @DisplayName("run() handles empty index result gracefully")
    void testRun_emptyIndexResult() throws Exception {
        when(mockContentIndexService.buildIndex()).thenReturn(List.of());

        scheduler.run();

        verify(mockContentIndexService, times(1)).buildIndex();
    }
}
