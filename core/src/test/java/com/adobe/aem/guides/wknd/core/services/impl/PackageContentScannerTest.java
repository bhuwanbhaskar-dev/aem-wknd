package com.adobe.aem.guides.wknd.core.services.impl;

import com.adobe.aem.guides.wknd.core.services.PackageAnalysisRequest;
import com.adobe.aem.guides.wknd.core.services.PackageAnalyzerService.ApiException;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.Session;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PackageContentScannerTest {
    private static final String ROOT = "/content/dam/wknd";
    private final PackageContentScanner scanner = new PackageContentScanner();

    private PackageContentScanner.Limits limits(long nodes) {
        return new PackageContentScanner.Limits(nodes, 1000, 1000, 10000, 4096, 512, 65536, 60, 0.7);
    }

    private PackageAnalysisRequest request(String filters) {
        JsonObject body = new JsonObject();
        body.addProperty("filterXml", "<workspaceFilter version=\"1.0\">" + filters + "</workspaceFilter>");
        body.addProperty("measuredMbps", 100);
        return PackageAnalysisRequest.parse(body.toString(), List.of(ROOT, "/content/wknd", "/conf/wknd"));
    }

    private PackageAnalysisRequest all(String root) { return request("<filter root=\"" + root + "\"/>"); }

    private Property property(String path, int type, long... lengths) throws Exception {
        Property property = mock(Property.class);
        when(property.getPath()).thenReturn(path);
        lenient().when(property.getType()).thenReturn(type);
        lenient().when(property.isMultiple()).thenReturn(lengths.length != 1);
        if (lengths.length == 1) lenient().when(property.getLength()).thenReturn(lengths[0]);
        else lenient().when(property.getLengths()).thenReturn(lengths);
        return property;
    }

    private Node node(String path, Property[] props, Node... children) throws Exception {
        Node node = mock(Node.class);
        when(node.getPath()).thenReturn(path);
        lenient().when(node.getNodes()).thenAnswer(call -> {
            Iterator<Node> values = Arrays.asList(children).iterator();
            NodeIterator iterator = mock(NodeIterator.class);
            when(iterator.hasNext()).thenAnswer(i -> values.hasNext());
            when(iterator.nextNode()).thenAnswer(i -> values.next());
            return iterator;
        });
        lenient().when(node.getProperties()).thenAnswer(call -> {
            Iterator<Property> values = Arrays.asList(props).iterator();
            PropertyIterator iterator = mock(PropertyIterator.class);
            when(iterator.hasNext()).thenAnswer(i -> values.hasNext());
            when(iterator.nextProperty()).thenAnswer(i -> values.next());
            return iterator;
        });
        return node;
    }

    private Session session(Node... roots) throws Exception {
        Session session = mock(Session.class);
        for (Node root : roots) {
            when(session.nodeExists(root.getPath())).thenReturn(true);
            when(session.getNode(root.getPath())).thenReturn(root);
        }
        return session;
    }

    @Test void singleAssetRootIncludesOwnBinaryAndRenditions() throws Exception {
        String asset = ROOT + "/video.mp4";
        Property original = property(asset + "/jcr:content/renditions/original/jcr:content/jcr:data", PropertyType.BINARY, 500);
        Property thumbnail = property(asset + "/thumbnail/jcr:data", PropertyType.BINARY, 100);
        Node root = node(asset, new Property[]{original}, node(asset + "/thumbnail", new Property[]{thumbnail}));
        JsonObject report = scanner.scan(all(asset), session(root), limits(100), () -> false);
        assertEquals(600, report.get("measuredBinaryBytes").getAsLong());
        assertEquals(2, report.get("binaryValues").getAsLong());
        assertTrue(report.get("scanComplete").getAsBoolean());
        assertFalse(report.get("exactPackageSize").getAsBoolean());
        verify(original, never()).getBinary();
        verify(original, never()).getValue();
    }

    @Test void countsMultivalueAndNonAssetBinariesWithoutDeduplication() throws Exception {
        Node root = node(ROOT, new Property[]{property(ROOT + "/files", PropertyType.BINARY, 10, 10, 30)});
        JsonObject report = scanner.scan(all(ROOT), session(root), limits(100), () -> false);
        assertEquals(50, report.get("measuredBinaryBytes").getAsLong());
        assertEquals(3, report.get("binaryValues").getAsLong());
    }

    @Test void emptyContentNeverClaimsSafeOrExactAndHasArchiveOverhead() throws Exception {
        Node root = node(ROOT, new Property[0]);
        JsonObject report = scanner.scan(all(ROOT), session(root), limits(100), () -> false);
        assertEquals("WITHIN_ESTIMATED_TRANSFER_BUDGET", report.get("assessment").getAsString());
        assertTrue(report.get("estimatedArchiveBytes").getAsLong() >= 65536);
        assertFalse(report.get("timeoutGuaranteed").getAsBoolean());
    }

    @Test void excludesRenditionSubtreeAndSpecificProperty() throws Exception {
        Property ignored = property(ROOT + "/secret", PropertyType.BINARY, 9999);
        Property included = property(ROOT + "/binary", PropertyType.BINARY, 100);
        Node child = node(ROOT + "/renditions", new Property[]{property(ROOT + "/renditions/data", PropertyType.BINARY, 999)});
        Node root = node(ROOT, new Property[]{ignored, included}, child);
        PackageAnalysisRequest request = request("<filter root=\"" + ROOT + "\">"
                + "<exclude pattern=\"" + ROOT + "/renditions(/.*)?\"/>"
                + "<exclude pattern=\"" + ROOT + "/secret\" matchProperties=\"true\"/></filter>");
        JsonObject report = scanner.scan(request, session(root), limits(100), () -> false);
        assertEquals(100, report.get("measuredBinaryBytes").getAsLong());
        verify(ignored, never()).getLength();
    }

    @Test void relativeIncludeTraversesUnmatchedParents() throws Exception {
        Node selected = node(ROOT + "/folder/keep", new Property[]{property(ROOT + "/folder/keep/data", PropertyType.BINARY, 77)});
        Node middle = node(ROOT + "/folder", new Property[0], selected);
        Node root = node(ROOT, new Property[0], middle);
        PackageAnalysisRequest request = request("<filter root=\"" + ROOT + "\"><include pattern=\".*/keep\"/></filter>");
        JsonObject report = scanner.scan(request, session(root), limits(100), () -> false);
        assertEquals(77, report.get("measuredBinaryBytes").getAsLong());
    }

    @Test void absoluteIncludeDoesNotTraverseExcludedParent() throws Exception {
        Node selected = node(ROOT + "/folder/keep", new Property[]{property(ROOT + "/folder/keep/data", PropertyType.BINARY, 77)});
        Node middle = node(ROOT + "/folder", new Property[0], selected);
        Node root = node(ROOT, new Property[0], middle);
        PackageAnalysisRequest request = request("<filter root=\"" + ROOT + "\"><include pattern=\"" + ROOT + "/folder/keep\"/></filter>");
        JsonObject report = scanner.scan(request, session(root), limits(100), () -> false);
        assertEquals(0, report.get("measuredBinaryBytes").getAsLong());
    }

    @Test void disjointRootsAreSummedAndReportedSeparately() throws Exception {
        Node first = node(ROOT, new Property[]{property(ROOT + "/data", PropertyType.BINARY, 11)});
        Node second = node("/content/wknd", new Property[]{property("/content/wknd/data", PropertyType.BINARY, 22)});
        JsonObject report = scanner.scan(request("<filter root=\"" + ROOT + "\"/><filter root=\"/content/wknd\"/>"),
                session(first, second), limits(100), () -> false);
        assertEquals(33, report.get("measuredBinaryBytes").getAsLong());
        assertEquals(2, report.getAsJsonArray("completedRoots").size());
    }

    @Test void unknownLengthIsIncompleteWithNoArchiveEstimate() throws Exception {
        Node root = node(ROOT, new Property[]{property(ROOT + "/data", PropertyType.BINARY, -1)});
        JsonObject report = scanner.scan(all(ROOT), session(root), limits(100), () -> false);
        assertEquals("UNKNOWN_PROPERTY_LENGTH", report.get("reason").getAsString());
        assertEquals("UNKNOWN", report.get("assessment").getAsString());
        assertTrue(report.get("estimatedArchiveBytes").isJsonNull());
    }

    @Test void overflowIsIncomplete() throws Exception {
        Node root = node(ROOT, new Property[]{property(ROOT + "/data", PropertyType.BINARY, Long.MAX_VALUE, 1)});
        JsonObject report = scanner.scan(all(ROOT), session(root), limits(100), () -> false);
        assertEquals("SIZE_OVERFLOW", report.get("reason").getAsString());
        assertFalse(report.get("scanComplete").getAsBoolean());
    }

    @Test void missingRootIsNotZeroSizeSuccess() {
        JsonObject report = scanner.scan(all(ROOT), mock(Session.class), limits(100), () -> false);
        assertEquals("MISSING_OR_UNREADABLE_ROOT", report.get("reason").getAsString());
        assertTrue(report.get("estimatedArchiveBytes").isJsonNull());
    }

    @Test void nodeLimitAndCancellationFailClosed() throws Exception {
        Node root = node(ROOT, new Property[0], node(ROOT + "/child", new Property[0]));
        JsonObject limited = scanner.scan(all(ROOT), session(root), limits(1), () -> false);
        assertEquals("NODE_LIMIT", limited.get("reason").getAsString());
        JsonObject cancelled = scanner.scan(all(ROOT), session(root), limits(100), () -> true);
        assertEquals("CANCELLED", cancelled.get("reason").getAsString());
    }

    @Test void elapsedTimeIncludesRootLookup() throws Exception {
        AtomicLong clock = new AtomicLong();
        Session session = mock(Session.class);
        Node root = node(ROOT, new Property[0]);
        when(session.nodeExists(ROOT)).thenAnswer(i -> { clock.set(11_000_000_000L); return true; });
        when(session.getNode(ROOT)).thenReturn(root);
        JsonObject report = new PackageContentScanner(clock::get).scan(all(ROOT), session, limits(100), () -> false);
        assertEquals("TIME_LIMIT", report.get("reason").getAsString());
    }

    @Test void decimalBandwidthAndHeadroomAreConsistent() throws Exception {
        Node root = node(ROOT, new Property[]{property(ROOT + "/data", PropertyType.BINARY, 600_000_000)});
        JsonObject report = scanner.scan(all(ROOT), session(root), limits(100), () -> false);
        assertEquals(525_000_000, report.get("transferBudgetBytes").getAsLong());
        assertEquals("EXCEEDS_TRANSFER_BUDGET", report.get("assessment").getAsString());
        assertTrue(report.get("estimatedTransferSeconds").getAsDouble() > 48);
    }

    @Test void rejectsOverlapsUnsafeXmlAndOutsideScope() {
        assertThrows(ApiException.class, () -> request("<filter root=\"" + ROOT + "\"/><filter root=\"" + ROOT + "/a\"/>"));
        assertThrows(ApiException.class, () -> all("/content/dam/wknd-other"));
        assertThrows(ApiException.class, () -> all(ROOT + "/../other"));
        JsonObject body = new JsonObject();
        body.addProperty("measuredMbps", 100);
        body.addProperty("filterXml", "<!DOCTYPE foo [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><workspaceFilter version=\"1.0\">&x;</workspaceFilter>");
        assertThrows(ApiException.class, () -> PackageAnalysisRequest.parse(body.toString(), List.of(ROOT)));
        assertThrows(ApiException.class, () -> request("<filter root=\"" + ROOT + "\" type=\"cleanup\"/>"));
    }
}
