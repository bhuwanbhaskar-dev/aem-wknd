# WKND AEM as a Cloud Service — Copilot Workspace Context

## Repository Synchronization

- **Dual Remote Sync**: Always ensure changes are synchronized to both git repositories:
  - **GitHub (`origin`)**: `https://github.com/bhuwanbhaskar-dev/aem-wknd.git`
  - **Adobe Cloud Manager (`adobe`)**: `https://git.cloudmanager.adobe.com/vlab22-us/wknd-test/`
- Whenever pushing commits or branches, push to both remotes:
  ```sh
  git push origin <branch-name>
  git push adobe <branch-name>
  ```
- Keep branches and commits in sync across both `origin` and `adobe` before considering tasks complete.

---

## Project Identity

| Property | Value |
|---|---|
| **Maven groupId** | `com.adobe.aem.guides` |
| **Maven artifactId** | `aem-guides-wknd` |
| **Current version** | `4.0.4` |
| **Java package root** | `com.adobe.aem.guides.wknd` |
| **AEM target** | AEM as a Cloud Service (`aemVersion=cloud`) |
| **App ID / JCR prefix** | `wknd` |
| **Java version required** | Java 21 (exactly `[21,22)`) |
| **Maven version required** | 3.9.4+ |
| **Node / npm** | Node v16.17.0 / npm 8.15.0 (managed by frontend-maven-plugin) |
| **Core WCM Components** | 2.29.0 |
| **ACS AEM Commons** | 6.17.2 |
| **wknd-shared** | 3.1.0 (cloud) / 2.2.2 (6.5 compat) |

---

## Multi-Module Maven Structure

Every Maven module maps to a JCR package type. Do not mix concerns across modules.

| Module | Artifact | Purpose |
|---|---|---|
| `core/` | OSGi bundle JAR | Sling Models, OSGi services, servlets, event handlers, Sling Rewriter |
| `ui.apps/` | content-package | HTL scripts, component definitions, clientlibs, i18n, Sling rewriter config |
| `ui.apps.structure/` | content-package | FileVault repository structure definition (declares `/apps/wknd` root) |
| `ui.config/` | content-package | OSGi `.cfg.json` configurations by runmode |
| `ui.content/` | content-package (mutable) | Site content, CAConfig, template policies, WCM segments |
| `ui.content.sample/` | content-package (mutable) | Sample/demo content |
| `ui.frontend/` | Webpack build → clientlibs | TypeScript/SCSS frontend; outputs into `ui.apps` clientlibs |
| `all/` | container package | Embeds all sub-packages for single Cloud Manager deployment |
| `dispatcher/` | dispatcher module | Apache/Dispatcher config for AEMaaCS |
| `it.tests/` | integration tests JAR | Author-side integration tests run by Cloud Manager |
| `ui.tests/` | UI test Docker image | Selenium/Playwright UI tests run by Cloud Manager |

**Build command**: `mvn clean install` (full build including frontend)  
**Backend only**: `mvn clean install -PautoInstallSinglePackage`  
**Skip frontend**: add `-DskipFrontend`

---

## Core Bundle — Java Code (`core/src/main/java/com/adobe/aem/guides/wknd/core/`)

### Sling Models (`models/`)

All models use `@Model` with `defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL`.

#### `Byline` / `BylineImpl`
- **Interface**: `models/Byline.java` — methods: `getName()`, `getOccupations()` (alphabetically sorted), `isEmpty()`
- **Implementation**: `models/impl/BylineImpl.java`
  - `@Model(adaptables = SlingHttpServletRequest.class, adapters = Byline.class, resourceType = "wknd/components/byline")`
  - Injects: `@Self SlingHttpServletRequest`, `@OSGiService ModelFactory`, `@ScriptVariable Page currentPage`, `@ScriptVariable ComponentContext`
  - `@ValueMapValue String name`, `@ValueMapValue List<String> occupations`
  - `@PostConstruct init()` — wraps the resource request to get a Core WCM `Image` model via `modelFactory.getModelFromWrappedRequest()`
  - `isEmpty()` returns true if name is blank, occupations is empty, or image has no src
  - HTL: `ui.apps/.../components/byline/byline.html` — uses `data-sly-use.byline="com.adobe.aem.guides.wknd.core.models.Byline"`, renders image via `resourceType='core/wcm/components/image/v2/image'`

#### `HelloWorldModel`
- `@Model(adaptables = Resource.class)` — no adapters declared; used directly in HTL
- Injects `@ValueMapValue String resourceType` (fallback "No resourceType"), `@SlingObject Resource`, `@SlingObject ResourceResolver`
- `@PostConstruct init()` builds a debug `message` string with resourceType and current page path
- HTL: `helloworld/helloworld.html` — uses `data-sly-use.model="...HelloWorldModel"`, shows `${model.message}`

#### `ImageList` / `ImageListImpl`
- Interface: `models/ImageList.java` — `Collection<ImageList.ListItem> getListItems()`; `ListItem` inner interface exposes `getPath()`, `getImage()`, `getId()`, `getData()` (`ComponentData`)
- `@JsonProperty` annotations on interface methods for AEM Data Layer JSON serialization
- Implementation in `models/impl/ImageListImpl.java`
- HTL: `components/image-list/image-list.html` and `item.html`
- Dialog: `components/image-list/_cq_dialog.xml`

### OSGi Services (`services/`)

#### `PackageAnalyzerService` / `PackageAnalyzerServiceImpl`
- Topic: `com/adobe/aem/guides/wknd/package_preflight`
- `@Component(service = {PackageAnalyzerService.class, JobExecutor.class}, configurationPolicy = REQUIRE)`
- `@Designate(ocd = Configuration.class)` — configurable: `operatorGroup`, `allowedRoots[]`, `maxNodes`, `maxProperties`, `maxValues`, `maxScanMillis`, `maxOutstandingJobs`, size allowances, `transferTargetSeconds`, `headroomFactor`
- Authorization: caller must be in `operatorGroup` (default `wknd-package-analyzer-users`) or be an administrator
- Backed by `PackageContentScanner` — iterative, cooperative metadata scan respecting budget limits
- Strict `@Activate` validation rejects invalid configuration values
- Service principal: `wknd-package-preflight-reader-service` (reads `/content/wknd`, `/content/dam/wknd`, `/content/experience-fragments/wknd`, `/conf/wknd` — read-only, no write/replication)
- OSGi config: `ui.config/.../osgiconfig/config.author/com.adobe.aem.guides.wknd.core.services.impl.PackageAnalyzerServiceImpl.cfg.json`

### Servlets (`servlets/`)

#### `PackageAnalyzerServlet`
- `@Component(sling.servlet.paths="/bin/api/analyze-package", methods=GET+POST, configurationPolicy=REQUIRE)`
- POST: submit filter XML + measuredMbps + Granite CSRF token → returns 202 + jobId + Location header
- GET: poll by jobId — ownership-scoped (only the submitting user can read their own job result)
- Requires `enabled=true` in OSGi config (Author-only, `config.author`)
- Sets `Cache-Control: private, no-store`, `X-Content-Type-Options: nosniff`

### Event Handlers / Preview (`preview/`)

#### `PreviewSyncEventHandler`
- `@Component(service = EventHandler.class, configurationPolicy = REQUIRE, property = EVENT_TOPIC = ReplicationAction.EVENT_TOPIC)`
- Listens for Publish replication events (ACTIVATE / DEACTIVATE)
- **Loop prevention Layer 1**: drops events from `preview-sync-service` principal (best-effort)
- Resolves `wknd.previewSyncConfig` CAConfig for the path using service resolver (`preview-sync` subservice)
- For DEACTIVATE of deleted nodes: walks ancestor hierarchy to find nearest existing resource for CAConfig resolution
- Enqueues to Sling Job topic `com/adobe/aem/guides/wknd/preview-sync`
- Active **Author-only** (OSGi config in `config.author`)

#### `PreviewSyncJobConsumer`
- **Loop prevention Layer 2** (primary): compares Publish vs Preview `ReplicationStatus` timestamps before executing
- Uses Replication API with `AgentFilter` targeting only the `preview` agent
- One path per job (retryable, bounded)
- Not a bulk-tree mechanism; large trees must use Adobe's Tree Activation workflow step

#### `PreviewSyncConfig`
- `@Configuration(name = "wknd.previewSyncConfig")` — Sling Context-Aware Configuration
- Single boolean `enableAutoSync()` (default `false`)
- Placed under `/conf/wknd/_sling_configs/wknd.previewSyncConfig`

#### `PreviewSyncConstants`
- `SERVICE_NAME = "preview-sync"`, `PREVIEW_SYNC_PRINCIPAL = "preview-sync-service"`
- `JOB_TOPIC = "com/adobe/aem/guides/wknd/preview-sync"`
- `PREVIEW_AGENT_ID = "preview"`, `PUBLISH_AGENT_ID = "publish"`

### Sling Rewriter (`rewriter/`)

#### `ExternalLinkTransformer` / `ExternalLinkTransformerFactory`
- `ExternalLinkTransformerFactory`: `@Component(service = TransformerFactory.class, property = "pipeline.type=external-link-transformer")`
- `ExternalLinkTransformer` extends `DefaultTransformer` — SAX transformer in the Sling Rewriter pipeline
- On `startElement`: intercepts `<a>` tags with `href` starting with `http://` or `https://`
  - Adds `target="_blank"` if not present
  - Adds `rel="noopener noreferrer"` for security if not present

### MCP (`mcp/`)

#### `CrossBoundaryReporter`
- Extends ACS AEM Commons `ProcessDefinition` — an MCP (Manage Controlled Processes) background process
- `@FormField` inputs: `inputPaths` (comma-separated JCR paths) and `globalAllowList`
- Walks content trees and resolves `Reference` objects via injected `ReferenceProvider` list
- Also inspects Content Fragments (`ContentElement`, `ContentVariation`, `FragmentData`) and DAM assets via `AssetReferenceSearch`
- Detects cross-boundary references (references from one tenant root into another) and collects `Violation` objects
- Loop prevention: `visitedPaths` set (thread-safe)
- Exclusion dictionary: `content`, `dam`, `experience-fragments`, `conf`, `cq:tags`

### Tests (`core/src/test/`)
- Framework: JUnit 5 + Mockito + io.wcm AEM Mock (`io.wcm.testing.aem-mock.junit5`)
- Test resource JSON: `resources/.../BylineImplTest.json`, `ImageListImplTest.json`
- Test classes mirror implementation packages exactly

---

## UI Apps — JCR Components (`ui.apps/src/main/content/jcr_root/apps/wknd/`)

### Component List (all extend Core WCM Components unless noted)
`accordion`, `breadcrumb`, `button`, **`byline`** (custom), `carousel`, `container`, `contentfragment`, `contentfragmentlist`, `download`, `embed`, `experiencefragment`, `form/` (button, container, hidden, options, **sign-in-buttons**, **sign-in-form**), **`helloworld`** (custom), `image`, **`image-list`** (custom), `languagenavigation` (custom itemContent.html), `list`, `navigation`, `page` (custom header/footer libs), `pdfviewer`, `progressbar`, `remotepage`, `remotepagenext`, `search`, `separator`, `sharing`, `spa`, `tabs`, `teaser`, `text`, `title`, `xfpage`

Components backed by a custom Sling Model: `byline`, `helloworld`, `image-list`.  
Custom HTL beyond the super component: `byline/byline.html`, `helloworld/helloworld.html`, `image-list/image-list.html`, `image-list/item.html`, `languagenavigation/itemContent.html`, `form/sign-in-buttons/sign-in-buttons.html`, `form/sign-in-form/sign-in-form.html`, `page/customheaderlibs.html`, `page/customfooterlibs.html`, `xfpage/content.html`

### Clientlibs (`apps/wknd/clientlibs/`)
- `clientlib-base` — base CSS/JS (Bootstrap grid, base styles)
- `clientlib-dependencies` — third-party vendor scripts
- `clientlib-grid` — responsive grid (LESS source: `less/grid.less`)
- `clientlib-site` — compiled frontend output (CSS + JS bundles from `ui.frontend` Webpack build; fonts; favicons; country flag SVGs; `manifest.json`)

### MSM (`apps/msm/wknd_blueprint/`)
- Blueprint configuration for Multi-Site Manager Live Copy roll-out

### Sling Rewriter Config (`apps/wknd/config/`)
- Pipeline configuration wiring `ExternalLinkTransformerFactory` into the default rewriter pipeline

---

## UI Config — OSGi Configurations (`ui.config/src/main/content/jcr_root/apps/wknd/osgiconfig/`)

Run-mode folders and their key configs:

| Folder | Key Configs |
|---|---|
| `config/` | Sling Logger (`org.apache.sling.commons.log.LogManager.factory.config~wknd.cfg.json`) |
| `config.author/` | PreviewSyncEventHandler, PreviewSyncJobConsumer, PackageAnalyzerServiceImpl, PackageAnalyzerServlet, CORS (`~wknd-graphql`), CSRF filter, Mobile emulator, Sling Job queues (`~package-preflight`, `~preview-sync`), RepoInit scripts (`~packageanalyzer`, `~preview-sync-service`), ServiceUserMapper (`~packageanalyzer`, `~preview-sync-service`) |
| `config.prod/` | Sling Logger (production level) |
| `config.publish/` | CORS (`~wknd-graphql`), CSRF filter, LoginSelectorHandler, RootMappingServlet, JcrResourceResolverFactory, ReferrerFilter |
| `config.publish.dev/stage/prod/` | Per-environment JcrResourceResolverFactory mapping overrides |
| `config.stage/` | Sling Logger (stage level) |

**Service principals** (created by RepoInit):
- `preview-sync-service` — reads WKND content/DAM/XF/conf roots; no write/replication
- `wknd-package-preflight-reader-service` — reads same roots; no write; no replication

---

## UI Content — Mutable Content (`ui.content/src/main/content/jcr_root/`)

### CAConfig (`/conf/wknd/`)
- `_sling_configs/wknd.previewSyncConfig` — Preview sync opt-in
- `settings/wcm/policies/` — Component policies
- `settings/wcm/segments/` — ContextHub segments: `summer`, `winter`
- `settings/wcm/template-types/` — `content-page`, `empty-experience-fragment`, `empty-page`
- `settings/wcm/templates/` — Site templates (e.g. `adventure-page-template`)
- `settings/cloudconfigs/` — Cloud service configurations

### Content (`/content/wknd/`)
- Language masters: `/content/wknd/language-masters/en`
- US site: `/content/wknd/us/en`
- Both are language-copy roots with minimal structure content

---

## Frontend (`ui.frontend/`)

### Toolchain
- **Webpack 5** (dev/prod configs: `webpack.dev.js`, `webpack.prod.js`, `webpack.common.js`)
- **TypeScript 4.8** (`tsconfig.json`)
- **SCSS/Sass** with PostCSS + autoprefixer
- **aem-clientlib-generator** (`clientlib.config.js`) — copies Webpack output into `ui.apps/.../clientlib-site`
- **aemsync** — live sync to AEM for development
- **Storybook 6** (`stories/`) for component development in isolation
- jQuery 3.4.x + normalize-scss as runtime dependencies

### Source Structure (`src/main/webpack/`)
- `base/sass/` — global: `_grid.scss`, `_mixins.scss`, `_shared.scss`, `_variables.scss`, `_wkndicons.scss`
- `components/<name>/scss/` — per-component SCSS with `styles/_default.scss` and variant partials
- `site/` — entry points: `main.ts`, `main.scss`, `main.js`, `util.js`, `vendors.js`, `elements.scss`, `scroll-indicator.js`

### Scripts
- `npm run dev` — Webpack dev build + clientlib copy
- `npm run prod` — Webpack production build + clientlib copy
- `npm run start` — webpack-dev-server (proxy to local AEM)
- `npm run watch` — dev-server + aemsync + clientlib watch in parallel
- `npm run storybook` — Storybook on port 6006

---

## Dispatcher (`dispatcher/src/`)

### Apache vhosts (`conf.d/`)
- `available_vhosts/wknd.vhost` — WKND virtual host definition
- `enabled_vhosts/wknd.vhost` — symlink enabling the vhost
- `rewrites/rewrite.rules` — site-specific URL rewrites
- `variables/custom.vars` — custom Apache variables

### Dispatcher farms (`conf.dispatcher.d/`)
- `available_farms/wknd.farm` — WKND farm (caching rules, filters, renders, virtualhosts)
- `enabled_farms/wknd.farm` — symlink enabling the farm
- `cache/rules.any` — cache rules (extends `default_rules.any`)
- `cache/marketing_query_parameters.any` — query params excluded from cache key
- `filters/filters.any` — allow/deny filter rules
- `opt-in/USE_SOURCES_DIRECTLY` — AEMaaCS immutable Dispatcher SDK opt-in

---

## Docs (`docs/`)

- `preview-sync.md` — Design doc: enablement, runtime flow, deployment validation, loop prevention, limitations
- `package-analyzer-architecture-review.md` — Architecture doc: preflight purpose, runtime sequence diagram (Mermaid), access model, service principal design
- `sling-rewriter-external-links.md` — Rewriter pipeline design and usage

---

## Testing

- **Unit tests**: JUnit 5 + Mockito + io.wcm AEM Mock, in `core/src/test/`
- **Integration tests**: `it.tests/` — Author-side, run by Cloud Manager pipeline
- **UI tests**: `ui.tests/` — Dockerized Selenium/WebDriver tests

---

## Key AEM Patterns Used in This Repo

1. **Sling Model** — `@Model` on impl class, interface as adapter, `@PostConstruct` for initialization, `DefaultInjectionStrategy.OPTIONAL`
2. **HTL (`data-sly-*`)** — `data-sly-use` to bind a Sling Model, `data-sly-resource` to delegate to a sub-resource/component, `data-sly-test` for conditional rendering, `data-sly-call` for template invocation
3. **OSGi DS** — `@Component`, `@Reference`, `@Activate`, `@Designate` / `@ObjectClassDefinition`, `configurationPolicy = REQUIRE` for sensitive services
4. **Sling Jobs** — durable async processing via `JobManager.addJob()` + `JobExecutor`; one path per job for retryability
5. **CAConfig** — `@Configuration` annotation, resolved via `resource.adaptTo(ConfigurationBuilder.class).as()`
6. **Sling Rewriter** — `TransformerFactory` + `Transformer` (extends `DefaultTransformer`) wired by `pipeline.type`
7. **RepoInit** — service users and ACLs declared in `.config` / `.cfg.json` under `config.author`
8. **Service User Mapping** — `ServiceUserMapperImpl.amended~<name>.cfg.json` maps bundle+subservice to a JCR principal
9. **ACS AEM Commons MCP** — `ProcessDefinition` for background admin processes with `@FormField` inputs
10. **Core WCM Components** — extended via `sling:resourceSuperType`; policies configured in CAConfig templates

---

## Conventions to Follow

- **Java package**: always `com.adobe.aem.guides.wknd.core.<layer>` where layer is `models`, `models.impl`, `services`, `services.impl`, `servlets`, `rewriter`, `mcp`, `preview`
- **Resource types**: always `wknd/components/<name>` (no leading `/apps/`)
- **OSGi configs**: go in `ui.config/` under the correct runmode folder; never in `ui.apps/` or `core/`
- **Mutable content**: goes in `ui.content/` or `ui.content.sample/`; never in `ui.apps/`
- **HTL model binding**: prefer interface adapter (`data-sly-use.model="com.adobe.aem.guides.wknd.core.models.Byline"`) not impl class
- **Tests**: mirror source package structure exactly; use AEM Mock context (`AemContext`) with JSON test fixtures
- **Frontend**: all new component styles go in `ui.frontend/src/main/webpack/components/<name>/scss/`; never hand-edit files in `clientlib-site`
- **Dual push**: every `git push` must go to both `origin` and `adobe` remotes
