# Package preflight for AEM as a Cloud Service

## Purpose and architectural decision

Before building a content package, submit its intended FileVault filter definition to an Author-only background analysis. The report estimates archive size and transfer time, counts all selected binary property values (including renditions), and flags incomplete scans. It never creates, installs, downloads, or modifies a package.

Exact ZIP size is available only after serialization and compression. A metadata-only preflight cannot guarantee it. Byte size also cannot predict repository traversal, package build, or installation duration. Use this tool to choose smaller scopes before expensive work starts.

Adobe recommends a transfer budget based on measured throughput and a 60-second target. Its 750 MB example assumes 100 Mbps; it is not a universal package limit. Adobe separately documents an installation UI timeout while installation can continue in the background. [Adobe Package Manager](https://experienceleague.adobe.com/en/docs/experience-manager-cloud-service/content/implementing/developer-tools/package-manager).

## Runtime architecture

~~~mermaid
sequenceDiagram
    participant Operator
    participant API as Author servlet
    participant Jobs as Sling Jobs
    participant Scanner as Read-only scanner
    participant JCR
    Operator->>API: POST filterXml + measuredMbps + CSRF token
    API->>API: Verify membership, scope and request bounds
    API->>Jobs: Persist owner and filter snapshot
    API-->>Operator: 202 + jobId + Location
    Jobs->>Scanner: Execute in ordered queue
    Scanner->>JCR: Read selected node/property lengths
    Scanner-->>Jobs: Estimate, counts, assumptions or incomplete reason
    Operator->>API: GET jobId
    API->>API: Recheck membership and job ownership
    API->>Jobs: Read current job snapshot
    API-->>Operator: Job state and private report
~~~

Sling owns persistence, scheduling, retries and result history. HTTP requests do not scan content. No custom executor keeps work only in memory. Re-execution after interruption recomputes against live content without modifying it. Results use the native job result message and follow the environment's Sling job-history retention. Download reports promptly; missing/expired jobs return 404. [Sling job handling](https://sling.apache.org/documentation/bundles/apache-sling-eventing-and-job-handling.html), [JobManager API](https://sling.apache.org/apidocs/sling14/org/apache/sling/event/jobs/JobManager.html).

Implementation:
- PackageAnalysisRequest: secure XML parsing, scope validation and native FileVault filters.
- PackageContentScanner: iterative metadata scan with cooperative resource budgets.
- PackageAnalyzerServiceImpl: authorization, admission, worker execution and owner-scoped status.
- PackageAnalyzerServlet: POST submission and GET polling.
- ui.config Author configurations: enablement, ordered queue, principal mapping and RepoInit.

## Access and deployment

Deploy code/configuration through Cloud Manager. The servlet and executor require OSGi configuration; enablement ships only in config.author.

The dedicated wknd-package-preflight-reader-service principal can read /content/wknd, /content/dam/wknd, /content/experience-fragments/wknd and /conf/wknd. It has no content write or replication privileges. Principal mapping replaces the old broad DAM reader. The new principal is separate from the old identity, whose historical permissions cannot broaden the new worker.

Removing an old OSGi configuration does not delete an existing service user or its ACLs. Administrators can retire the unused package-analyzer-service identity separately after migration.

Administrators can use the endpoint. Other callers must belong to **wknd-package-analyzer-users**, which RepoInit creates empty. **Membership authorizes aggregate analysis of every configured root, even where the caller's own content ACLs are narrower.** This is an operator tool, not a general author endpoint.

The API returns a report only to its submitter, with membership rechecked on polling. For a different tenant/security boundary, provision a separate scoped principal and operator boundary. Keep allowedRoots aligned with effective permissions; ordinary JCR readers cannot detect nodes/properties hidden by ACLs. Reports explicitly label their visibility CONFIGURED_SERVICE_USER_SCOPE.

These choices follow Adobe's guidance on explicit principals and narrow operations. [Service-user mapping and definition](https://experienceleague.adobe.com/en/docs/experience-manager-cloud-service/content/security/best-practices-for-sling-service-user-mapping-and-service-user-definition).

POST requires the normal Granite CSRF token. The endpoint has no CSRF exemption or public Dispatcher allow rule. All responses are private and non-cacheable. [Adobe CSRF protection](https://experienceleague.adobe.com/en/docs/experience-manager-learn/cloud-service/developing/advanced/csrf-protection).

## Request contract

POST /bin/api/analyze-package with Content-Type: application/json:

~~~json
{
  "filterXml": "<workspaceFilter version=\"1.0\"><filter root=\"/content/dam/wknd/adventures\"/></workspaceFilter>",
  "measuredMbps": 100
}
~~~

Use the lower measured upload/download throughput if both transfers are planned. Replace the example speed with a measurement for the connection that will actually transfer the package.

Supply the same filter XML intended for Package Manager, or a proposed definition before creating a package. The API does not load an existing package definition automatically.

Supported:
- Up to 32 disjoint roots under configured content/configuration paths.
- Ordered include/exclude node rules and property rules with matchProperties.
- FileVault import modes; these do not affect the source byte estimate.
- Single assets, pages, DAM, Experience Fragments and configuration content.
- Absolute and relative patterns using native membership and modeled export traversal.

Rejected:
- Empty, duplicate or overlapping roots. Normalize those definitions explicitly.
- Immutable/system paths, tenant lookalikes and path traversal.
- DTDs, entities, unsupported XML elements/attributes and cleanup filters.
- Requests above 65,536 characters, more than 128 rules, or patterns above 1,024 characters.

FileVault applies rules in order; the last matching rule controls inclusion. Export traversal can stop at excluded ancestors, while all-relative patterns have different traversal behavior. The scanner models that distinction but does not reproduce FileVault aggregate serialization. Referenced resources outside the supplied filters are not automatically counted. [FileVault workspace filters](https://jackrabbit.apache.org/filevault/filter.html).

Only trusted operators should submit patterns. Native Java regular expressions and individual repository calls cannot be forcibly interrupted by the cooperative deadline. Review complex expressions before use.

## Calculation and results

The scanner includes the root itself, visits selected children iteratively, and reads JCR property lengths. All selected binary values are counted, including multi-valued properties, original/derived renditions and binaries outside dam:Asset nodes. Distinct selected properties referencing shared storage are counted separately. No binary stream is opened.

JCR binary lengths are bytes; other property lengths represent string lengths. Unknown lengths cause an incomplete result. [JCR Property API](https://developer.adobe.com/experience-manager/reference-materials/spec/javax.jcr/javadocs/jcr-2.0/javax/jcr/Property.html).

~~~text
estimatedArchiveBytes =
    measuredBinaryBytes
  + nonBinaryCharacters * 6
  + includedNodes * nodeAllowanceBytes
  + includedProperties * propertyAllowanceBytes
  + archiveAllowanceBytes

transferBudgetBytes =
    floor(measuredMbps * 1,000,000 / 8
          * transferTargetSeconds * headroomFactor)

estimatedTransferSeconds =
    estimatedArchiveBytes / (measuredMbps * 1,000,000 / 8)
~~~

Defaults: 4,096 bytes/node, 512 bytes/property and 65,536 bytes/archive. These are **application planning assumptions**, not Adobe limits or guaranteed ZIP overhead bounds. The character multiplier allows for XML escaping; no compression savings are assumed. Compression, FileVault aggregation, path/ancestor serialization and package metadata can change actual size in either direction.

Default headroom is 0.7. At 100 Mbps and 60 seconds, the budget is 525,000,000 decimal bytes. Comparisons use consistent byte units.

| Assessment | Meaning |
|---|---|
| WITHIN_ESTIMATED_TRANSFER_BUDGET | Estimate fits; build/install duration is still unpredicted. |
| EXCEEDS_TRANSFER_BUDGET | Split filters or investigate a supported content-transfer mechanism. |
| UNKNOWN | Incomplete scan; estimatedArchiveBytes is null, not zero. |

scanComplete means this service-visible traversal finished, not that an exact ZIP was predicted. Even empty scopes carry metadata allowance and never return LOW_RISK. Reports include per-completed-root binary/node totals, counts, timestamps, filter XML and planning assumptions.

The scan is not a repository snapshot. Re-run close to creation, avoid concurrent edits, and check the completed ZIP's actual byte length against the budget before transfer.

## Example in an authenticated Author browser session

After deployment and operator assignment, run this in an Author-origin development console. Replace the filter XML and measured speed. Same-origin requests use the current session without embedded credentials.

~~~javascript
const endpoint = "/bin/api/analyze-package";
const csrf = await fetch("/libs/granite/csrf/token.json", {
  credentials: "same-origin", cache: "no-store"
}).then(r => r.json());

const submission = await fetch(endpoint, {
  method: "POST",
  credentials: "same-origin",
  headers: { "Content-Type": "application/json", "CSRF-Token": csrf.token },
  body: JSON.stringify({
    filterXml: '<workspaceFilter version="1.0"><filter root="/content/dam/wknd/adventures"/></workspaceFilter>',
    measuredMbps: 100 // Replace with measured throughput.
  })
});
const submitted = await submission.json();
if (!submission.ok) throw new Error(submitted.error);
console.log("Analysis submitted", submitted.jobId);

// Save jobId. Repeat this GET after a few seconds until the job is terminal.
const polling = await fetch(endpoint + "?jobId=" + encodeURIComponent(submitted.jobId), {
  credentials: "same-origin", cache: "no-store"
});
const result = await polling.json();
if (!polling.ok) throw new Error(result.error);
console.log(result);
~~~

POST returns 202, jobId, a Location polling URL and Retry-After: 5. GET returns jobState and a report when Sling has a result message. Check both: transient failures may be retried, and an intermediate failure message can exist while the job is unfinished.

SUCCEEDED means execution finished, not that a package is safe; inspect report.scanComplete and report.assessment. Terminal errors/give-up states are not successful preflight.

Other responses: 400 invalid input; 401 unauthenticated; 403 non-operator; 404 disabled/not-owned/expired; 413 oversized input; 415 wrong content type; 429 admission limit; 503 unavailable.

**Breaking change:** synchronous GET ?path=... and Java analyzePath methods are removed. Submit with POST, then poll the returned job ID.

## Operational limits

Defaults:
- Ordered queue, one worker; two retries with five-second delay for infrastructure failures.
- At most 20 outstanding jobs observed at admission; one observed outstanding job per owner.
- 100,000 examined nodes, 1,000,000 examined properties, 2,000,000 selected values.
- A 512-level iterator stack and a 120-second cooperative scan deadline.

Admission is best-effort across Author instances, not an atomic global quota. Bursts can exceed the observed cap; the ordered queue controls processing concurrency. Restrict membership and monitor the queue.

Stopped or budget-exhausted scans return INCOMPLETE/UNKNOWN. Individual slow calls may exceed the cooperative deadline. Asynchronous execution removes the HTTP dependency but does not eliminate repository I/O.

QueryBuilder is removed, so no query index is required. Large tree traversal still has a cost. Split scopes before repeatedly rerunning a limited scan. Sling job history has its own operational ACLs and retention.

For environment-to-environment movement, evaluate Content Copy only for supported source/destination, permissions and scope. It is not a replacement for every package task. [Adobe Content Copy](https://experienceleague.adobe.com/en/docs/experience-manager-cloud-service/content/implementing/developer-tools/content-copy).

## Validation and calibration

Automated tests cover root inclusion, renditions, non-asset/multi-valued binaries, node/property exclusions, relative/absolute traversal, disjoint roots, unknown/missing data, overflow, cancellation, scan limits, bandwidth units, XML hardening, owner/operator controls and HTTP behavior.

Tests pin FileVault 3.8.4 because legacy AEM mocks pull 3.1.x. Production uses AEM's FileVault API. XML parsing explicitly uses the Java 21 default parser with external access disabled.

Before production use:
1. Verify DS activation only on Author, service mapping and effective read permissions.
2. Test real group membership, hidden child/property ACLs, CSRF and owner-isolated polling.
3. Restart Author during a job; verify safe re-execution and result retrieval/history expiry.
4. Compare actual built ZIPs against estimates for pages, videos/images, many renditions, Content Fragments and property/multi-root filters.
5. Calibrate allowances and scan budgets from those results; record underestimates.
6. Verify queue behavior under bursts and the maximum expected tree size.
7. Validate package build and installation separately.

No live Cloud deployment or package creation is performed by the unit tests.
