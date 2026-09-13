package com.adobe.aem.guides.wknd.core.services.impl;

import com.adobe.aem.guides.wknd.core.services.PackageAnalysisRequest;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.jackrabbit.vault.fs.api.PathFilterSet;

/**
 * Metadata-only repository scan. No QueryBuilder, binary streams or ZIP creation.
 * Native filter membership is used; FileVault aggregate serialization is estimated,
 * not reproduced. Counts are service-visible content observed during a live scan.
 */
final class PackageContentScanner {
    record Limits(long maxNodes, long maxProperties, long maxValues, long maxMillis,
                  long nodeAllowance, long propertyAllowance, long archiveAllowance,
                  double transferSeconds, double headroom) { }

    private final LongSupplier nanoTime;
    PackageContentScanner() { this(System::nanoTime); }
    PackageContentScanner(LongSupplier nanoTime) { this.nanoTime = nanoTime; }

    JsonObject scan(PackageAnalysisRequest request, Session session, Limits limits, BooleanSupplier stopped) {
        Totals totals = new Totals();
        JsonArray roots = new JsonArray();
        long started = nanoTime.getAsLong();
        String status = "COMPLETE";
        String reason = "";
        String startedAt = Instant.now().toString();
        try {
            if (session == null) throw new RepositoryException("No session");
            for (PathFilterSet set : request.filter.getFilterSets()) {
                checkBudget(totals, limits, started, stopped);
                if (!session.nodeExists(set.getRoot())) throw new ScanStopped("MISSING_OR_UNREADABLE_ROOT");
                Node root = session.getNode(set.getRoot());
                long beforeBytes = totals.binaryBytes;
                long beforeNodes = totals.includedNodes;
                scanRoot(root, set, request, totals, limits, started, stopped);
                JsonObject rootTotal = new JsonObject();
                rootTotal.addProperty("root", set.getRoot());
                rootTotal.addProperty("binaryBytes", totals.binaryBytes - beforeBytes);
                rootTotal.addProperty("includedNodes", totals.includedNodes - beforeNodes);
                roots.add(rootTotal);
            }
        } catch (ScanStopped e) {
            status = "INCOMPLETE";
            reason = e.getMessage();
        } catch (RepositoryException | RuntimeException e) {
            status = "INCOMPLETE";
            reason = e instanceof ArithmeticException ? "SIZE_OVERFLOW" : "REPOSITORY_ERROR";
        }

        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", 2);
        result.addProperty("status", status);
        result.addProperty("scanComplete", "COMPLETE".equals(status));
        result.addProperty("reason", reason);
        result.addProperty("startedAt", startedAt);
        result.addProperty("finishedAt", Instant.now().toString());
        result.addProperty("elapsedMillis", Math.max(0, (nanoTime.getAsLong() - started) / 1_000_000));
        result.addProperty("visibility", "CONFIGURED_SERVICE_USER_SCOPE");
        result.addProperty("estimateType", "UNCOMPRESSED_CONTENT_PLUS_CONFIGURED_ALLOWANCES");
        result.addProperty("exactPackageSize", false);
        result.addProperty("timeoutGuaranteed", false);
        result.addProperty("visitedNodes", totals.visitedNodes);
        result.addProperty("includedNodes", totals.includedNodes);
        result.addProperty("includedProperties", totals.properties);
        result.addProperty("binaryValues", totals.binaryValues);
        result.addProperty("measuredBinaryBytes", totals.binaryBytes);
        result.addProperty("nonBinaryCharacters", totals.characters);
        result.add("completedRoots", roots);
        long budget = BigDecimal.valueOf(request.measuredMbps).multiply(BigDecimal.valueOf(125000))
                .multiply(BigDecimal.valueOf(limits.transferSeconds)).multiply(BigDecimal.valueOf(limits.headroom))
                .setScale(0, RoundingMode.DOWN).longValueExact();
        result.addProperty("measuredMbps", request.measuredMbps);
        result.addProperty("transferTargetSeconds", limits.transferSeconds);
        result.addProperty("headroomFactor", limits.headroom);
        result.addProperty("transferBudgetBytes", budget);
        result.addProperty("archiveAllowanceBytes", limits.archiveAllowance);
        result.addProperty("nodeAllowanceBytes", limits.nodeAllowance);
        result.addProperty("propertyAllowanceBytes", limits.propertyAllowance);
        // Six bytes per UTF-16 code unit is an XML escaping planning allowance,
        // not a bound on every FileVault serialization or compression outcome.
        result.addProperty("bytesPerNonBinaryCharacterAllowance", 6);
        result.add("estimatedArchiveBytes", JsonNull.INSTANCE);
        result.add("estimatedTransferSeconds", JsonNull.INSTANCE);
        String assessment = "UNKNOWN";
        if ("COMPLETE".equals(status)) {
            try {
                long allowance = Math.addExact(limits.archiveAllowance,
                        Math.addExact(Math.multiplyExact(totals.includedNodes, limits.nodeAllowance),
                                Math.addExact(Math.multiplyExact(totals.properties, limits.propertyAllowance),
                                        Math.multiplyExact(totals.characters, 6))));
                long estimate = Math.addExact(totals.binaryBytes, allowance);
                result.addProperty("estimatedArchiveBytes", estimate);
                result.addProperty("estimatedTransferSeconds", estimate / (request.measuredMbps * 1_000_000 / 8));
                assessment = estimate >= budget ? "EXCEEDS_TRANSFER_BUDGET" : "WITHIN_ESTIMATED_TRANSFER_BUDGET";
            } catch (ArithmeticException e) {
                result.addProperty("status", "INCOMPLETE");
                result.addProperty("scanComplete", false);
                result.addProperty("reason", "SIZE_OVERFLOW");
            }
        }
        result.addProperty("assessment", assessment);
        result.addProperty("recommendation", "UNKNOWN".equals(assessment)
                ? "Do not decide from this partial result. Correct the reported issue or split the filter roots and rerun."
                : "EXCEEDS_TRANSFER_BUDGET".equals(assessment)
                ? "Split the package filters into smaller scopes, or evaluate Content Copy for supported environment transfers."
                : "The estimate fits the transfer budget. Validate the completed ZIP size before transfer; build and install duration remain unpredicted.");
        JsonArray limitations = new JsonArray();
        limitations.add("Estimate only: ZIP compression, FileVault aggregation, ancestors, package metadata and serialization can change the result.");
        limitations.add("Content and permissions can change during or after this live scan; it is not a repository snapshot.");
        limitations.add("References outside the supplied filters are not followed or included automatically.");
        limitations.add("Service-user visibility must cover every intended node/property; restrictive ACLs can hide content.");
        limitations.add("Byte size cannot guarantee package build, installation, or network completion time.");
        result.add("limitations", limitations);
        return result;
    }

    private void scanRoot(Node root, PathFilterSet set, PackageAnalysisRequest request, Totals totals,
                          Limits limits, long started, BooleanSupplier stopped) throws RepositoryException, ScanStopped {
        visit(root, request, totals, limits, started, stopped);
        Deque<NodeIterator> stack = new ArrayDeque<>();
        stack.push(root.getNodes());
        while (!stack.isEmpty()) {
            checkBudget(totals, limits, started, stopped);
            NodeIterator iterator = stack.peek();
            if (!iterator.hasNext()) { stack.pop(); continue; }
            Node child = iterator.nextNode();
            // FileVault export traverses selected nodes, except all-relative rule sets.
            if (!set.hasOnlyRelativePatterns() && !set.contains(child.getPath())) {
                if (++totals.visitedNodes > limits.maxNodes) throw new ScanStopped("NODE_LIMIT");
                continue;
            }
            visit(child, request, totals, limits, started, stopped);
            if (stack.size() >= 512) throw new ScanStopped("DEPTH_LIMIT");
            stack.push(child.getNodes());
        }
    }

    private void visit(Node node, PackageAnalysisRequest request, Totals totals, Limits limits,
                       long started, BooleanSupplier stopped) throws RepositoryException, ScanStopped {
        checkBudget(totals, limits, started, stopped);
        if (++totals.visitedNodes > limits.maxNodes) throw new ScanStopped("NODE_LIMIT");
        if (!request.filter.contains(node.getPath())) return;
        totals.includedNodes++;
        PropertyIterator properties = node.getProperties();
        while (properties.hasNext()) {
            checkBudget(totals, limits, started, stopped);
            Property property = properties.nextProperty();
            if (++totals.examinedProperties > limits.maxProperties) throw new ScanStopped("PROPERTY_LIMIT");
            if (!request.filter.includesProperty(property.getPath())) continue;
            totals.properties++;
            long[] lengths = property.isMultiple() ? property.getLengths() : new long[]{property.getLength()};
            for (long length : lengths) {
                checkBudget(totals, limits, started, stopped);
                if (++totals.values > limits.maxValues) throw new ScanStopped("VALUE_LIMIT");
                if (length < 0) throw new ScanStopped("UNKNOWN_PROPERTY_LENGTH");
                if (property.getType() == PropertyType.BINARY) {
                    totals.binaryValues++;
                    totals.binaryBytes = Math.addExact(totals.binaryBytes, length);
                } else {
                    totals.characters = Math.addExact(totals.characters, length);
                }
            }
        }
    }

    private void checkBudget(Totals totals, Limits limits, long started, BooleanSupplier stopped) throws ScanStopped {
        if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new ScanStopped("CANCELLED");
        if ((nanoTime.getAsLong() - started) / 1_000_000 >= limits.maxMillis) throw new ScanStopped("TIME_LIMIT");
    }

    private static final class Totals {
        long visitedNodes, includedNodes, examinedProperties, properties, values, binaryValues, binaryBytes, characters;
    }
    private static final class ScanStopped extends Exception {
        ScanStopped(String reason) { super(reason); }
    }
}
