package com.ripplemap.analysis;

import com.ripplemap.analysis.diagnostics.Diagnostic;
import com.ripplemap.analysis.diagnostics.DiagnosticCategory;
import com.ripplemap.analysis.model.AggregatedRelationship;
import com.ripplemap.analysis.model.AnalysisResult;
import com.ripplemap.analysis.model.EntityKind;
import com.ripplemap.analysis.model.GraphLayer;
import com.ripplemap.analysis.model.RelationshipKind;
import com.ripplemap.analysis.model.Resolution;
import com.ripplemap.analysis.model.SourceFileInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.ripplemap.testsupport.Fixtures.analyze;
import static com.ripplemap.testsupport.Fixtures.entity;
import static com.ripplemap.testsupport.Fixtures.entityIds;
import static com.ripplemap.testsupport.Fixtures.pairs;
import static com.ripplemap.testsupport.Fixtures.relationship;
import static com.ripplemap.testsupport.Fixtures.relationships;
import static com.ripplemap.testsupport.Fixtures.typeIds;
import static com.ripplemap.testsupport.Fixtures.typePairs;
import static org.assertj.core.api.Assertions.assertThat;

/** Golden fixtures A-F: expected facts are written by hand from Java semantics. */
class JavaProjectAnalyzerGoldenTest {

    private static final String CHAIN = "com.acme.chain.";

    @Test
    void chainFixtureHasExactTypeEdgesInDependencyDirection() {
        AnalysisResult result = analyze("chain");

        assertThat(result.partial()).isFalse();
        assertThat(result.fileCounts().parsed()).isEqualTo(3);
        assertThat(typeIds(result)).containsExactlyInAnyOrder(
                CHAIN + "Controller", CHAIN + "Service", CHAIN + "Repository");
        assertThat(typePairs(result)).containsExactly(
                CHAIN + "Controller->" + CHAIN + "Service",
                CHAIN + "Service->" + CHAIN + "Repository");
        assertThat(relationships(result, GraphLayer.TYPE)).containsExactlyInAnyOrder(
                CHAIN + "Controller->" + CHAIN + "Service:FIELD_TYPE",
                CHAIN + "Controller->" + CHAIN + "Service:CONSTRUCTOR_PARAMETER_TYPE",
                CHAIN + "Controller->" + CHAIN + "Service:METHOD_CALL",
                CHAIN + "Service->" + CHAIN + "Repository:FIELD_TYPE",
                CHAIN + "Service->" + CHAIN + "Repository:OBJECT_CREATION",
                CHAIN + "Service->" + CHAIN + "Repository:METHOD_CALL");
        assertThat(result.relationships()).allMatch(r -> r.resolution() == Resolution.RESOLVED);
        assertThat(result.references().unresolved()).isZero();
    }

    @Test
    void chainFixtureEdgesAreNeverReversed() {
        AnalysisResult result = analyze("chain");
        Set<String> pairs = typePairs(result);
        assertThat(pairs).doesNotContain(
                CHAIN + "Service->" + CHAIN + "Controller",
                CHAIN + "Repository->" + CHAIN + "Service",
                CHAIN + "Repository->" + CHAIN + "Controller",
                CHAIN + "Controller->" + CHAIN + "Repository");
    }

    @Test
    void chainFixtureMethodLevelCallsResolveToExactOverloads() {
        AnalysisResult result = analyze("chain");
        assertThat(relationships(result, GraphLayer.METHOD)).containsExactlyInAnyOrder(
                CHAIN + "Controller#get(long)->" + CHAIN + "Service#describe(long):METHOD_CALL",
                CHAIN + "Service#describe(long)->" + CHAIN + "Repository#find(long):METHOD_CALL");
        assertThat(entityIds(result, EntityKind.METHOD, EntityKind.CONSTRUCTOR)).containsExactlyInAnyOrder(
                CHAIN + "Controller#<init>(Service)",
                CHAIN + "Controller#get(long)",
                CHAIN + "Service#describe(long)",
                CHAIN + "Repository#find(long)");
    }

    @Test
    void relationshipEvidenceRecordsFileAndLine() {
        AnalysisResult result = analyze("chain");
        AggregatedRelationship call = relationship(result, GraphLayer.TYPE, CHAIN + "Controller", CHAIN + "Service",
                RelationshipKind.METHOD_CALL);
        assertThat(call.referenceCount()).isEqualTo(1);
        assertThat(call.samples()).singleElement().satisfies(sample -> {
            assertThat(sample.filePath()).isEqualTo("src/main/java/com/acme/chain/Controller.java");
            assertThat(sample.line()).isEqualTo(11);
            assertThat(sample.detail()).isEqualTo("call describe(1 arg)");
        });
        AggregatedRelationship field = relationship(result, GraphLayer.TYPE, CHAIN + "Controller", CHAIN + "Service",
                RelationshipKind.FIELD_TYPE);
        assertThat(field.samples()).singleElement().satisfies(sample -> {
            assertThat(sample.line()).isEqualTo(4);
            assertThat(sample.detail()).isEqualTo("field service");
        });
    }

    @Test
    void diamondFixtureHasFourEdges() {
        AnalysisResult result = analyze("diamond");
        String p = "com.acme.diamond.";
        assertThat(typePairs(result)).containsExactly(
                p + "A->" + p + "B", p + "A->" + p + "C", p + "B->" + p + "D", p + "C->" + p + "D");
        // C reaches D only through a static call
        assertThat(relationships(result, GraphLayer.TYPE)).contains(p + "C->" + p + "D:METHOD_CALL")
                .doesNotContain(p + "C->" + p + "D:FIELD_TYPE");
    }

    @Test
    void cycleFixtureHasThreeEdgeCycle() {
        AnalysisResult result = analyze("cycle");
        String p = "com.acme.cycle.";
        assertThat(typePairs(result)).containsExactly(p + "A->" + p + "B", p + "B->" + p + "C", p + "C->" + p + "A");
    }

    @Test
    void isolatedFixtureHasNoEdgesForIsolatedClass() {
        AnalysisResult result = analyze("isolated");
        String p = "com.acme.iso.";
        assertThat(typePairs(result)).containsExactly(p + "User->" + p + "Helper");
        // java.util.List / ArrayList are external, not project nodes
        assertThat(typeIds(result)).containsExactlyInAnyOrder(p + "Isolated", p + "User", p + "Helper");
        assertThat(result.references().external()).isPositive();
    }

    @Test
    void syntaxFixtureExtractsDeclarationsWithStableIds() {
        AnalysisResult result = analyze("syntax");
        assertThat(typeIds(result)).containsExactlyInAnyOrder(
                "com.acme.model.Shape", "com.acme.model.AbstractShape", "com.acme.model.Circle",
                "com.acme.model.Square", "com.acme.util.Formatter", "com.acme.util.Formatter$Options",
                "com.acme.service.ShapeService");
        assertThat(entity(result, "com.acme.model.Shape").kind()).isEqualTo(EntityKind.INTERFACE);
        assertThat(entity(result, "com.acme.util.Formatter$Options").declaringTypeId())
                .isEqualTo("com.acme.util.Formatter");
        assertThat(entity(result, "com.acme.util.Formatter$Options").name()).isEqualTo("Formatter.Options");
        assertThat(entityIds(result, EntityKind.METHOD)).contains(
                "com.acme.util.Formatter#format(double)",
                "com.acme.util.Formatter#format(double,int)",
                "com.acme.util.Formatter#format(String)");
        assertThat(entityIds(result, EntityKind.CONSTRUCTOR)).contains(
                "com.acme.model.Square#<init>(double)",
                "com.acme.model.Square#<init>()",
                "com.acme.model.AbstractShape#<init>(String)",
                "com.acme.model.Circle#<init>(double)",
                "com.acme.service.ShapeService#<init>(Audit)");
        assertThat(entity(result, "com.acme.model.Square#<init>()").signature()).isEqualTo("Square()");
        assertThat(entity(result, "com.acme.model.Circle").line()).isEqualTo(3);
    }

    @Test
    void syntaxFixtureTypeLevelRelationshipsAreExact() {
        AnalysisResult result = analyze("syntax");
        String svc = "com.acme.service.ShapeService";
        assertThat(typePairs(result)).containsExactlyInAnyOrder(
                "com.acme.model.Circle->com.acme.model.Shape",
                "com.acme.model.AbstractShape->com.acme.model.Shape",
                "com.acme.model.Square->com.acme.model.AbstractShape",
                svc + "->com.acme.model.Shape",
                svc + "->com.acme.model.Circle",
                svc + "->com.acme.util.Formatter",
                svc + "->com.acme.util.Formatter$Options");
        assertThat(relationships(result, GraphLayer.TYPE)).containsExactlyInAnyOrder(
                "com.acme.model.Circle->com.acme.model.Shape:IMPLEMENTS",
                "com.acme.model.AbstractShape->com.acme.model.Shape:IMPLEMENTS",
                "com.acme.model.Square->com.acme.model.AbstractShape:EXTENDS",
                "com.acme.model.Square->com.acme.model.AbstractShape:CONSTRUCTOR_CALL",
                svc + "->com.acme.model.Shape:TYPE_REFERENCE",
                svc + "->com.acme.model.Shape:FIELD_TYPE",
                svc + "->com.acme.model.Shape:METHOD_PARAMETER_TYPE",
                svc + "->com.acme.model.Shape:METHOD_CALL",
                svc + "->com.acme.model.Circle:METHOD_RETURN_TYPE",
                svc + "->com.acme.model.Circle:TYPE_REFERENCE",
                svc + "->com.acme.model.Circle:OBJECT_CREATION",
                svc + "->com.acme.util.Formatter:METHOD_CALL",
                svc + "->com.acme.util.Formatter$Options:METHOD_RETURN_TYPE",
                svc + "->com.acme.util.Formatter$Options:OBJECT_CREATION");
        // Square does not reference Shape directly
        assertThat(typePairs(result)).doesNotContain("com.acme.model.Square->com.acme.model.Shape");
    }

    @Test
    void syntaxFixtureMethodLevelRelationshipsAreExact() {
        AnalysisResult result = analyze("syntax");
        String svc = "com.acme.service.ShapeService#";
        assertThat(relationships(result, GraphLayer.METHOD)).containsExactlyInAnyOrder(
                svc + "createCircle(double)->com.acme.model.Circle#<init>(double):OBJECT_CREATION",
                svc + "createCircle(double)->" + svc + "add(Shape):METHOD_CALL",
                svc + "report()->com.acme.model.Shape#area():METHOD_CALL",
                svc + "report()->com.acme.util.Formatter#format(double):METHOD_CALL",
                svc + "report()->com.acme.util.Formatter#format(double,int):METHOD_CALL",
                svc + "formatExternal()->com.acme.util.Formatter#format(double,int):METHOD_CALL",
                "com.acme.model.Square#<init>(double)->com.acme.model.AbstractShape#<init>(String):CONSTRUCTOR_CALL",
                "com.acme.model.Square#<init>()->com.acme.model.Square#<init>(double):CONSTRUCTOR_CALL",
                "com.acme.model.Shape#describe()->com.acme.model.Shape#area():METHOD_CALL");
    }

    @Test
    void syntaxFixtureSeparatesResolvedInferredAndUnresolved() {
        AnalysisResult result = analyze("syntax");
        String svc = "com.acme.service.ShapeService#";
        AggregatedRelationship inferred = relationship(result, GraphLayer.METHOD, svc + "formatExternal()",
                "com.acme.util.Formatter#format(double,int)", RelationshipKind.METHOD_CALL);
        assertThat(inferred.resolution()).isEqualTo(Resolution.INFERRED);
        assertThat(inferred.samples().get(0).rule()).isEqualTo("RECEIVER_TYPE_UNIQUE_ARITY");

        AggregatedRelationship resolved = relationship(result, GraphLayer.METHOD, svc + "report()",
                "com.acme.util.Formatter#format(double)", RelationshipKind.METHOD_CALL);
        assertThat(resolved.resolution()).isEqualTo(Resolution.RESOLVED);
        assertThat(resolved.samples().get(0).rule()).isNull();

        // four calls to Formatter.format aggregate into one type-level relationship
        AggregatedRelationship toFormatter = relationship(result, GraphLayer.TYPE, "com.acme.service.ShapeService",
                "com.acme.util.Formatter", RelationshipKind.METHOD_CALL);
        assertThat(toFormatter.referenceCount()).isEqualTo(4);
        assertThat(toFormatter.resolvedCount()).isEqualTo(2);
        assertThat(toFormatter.inferredCount()).isEqualTo(2);
        assertThat(toFormatter.resolution()).isEqualTo(Resolution.RESOLVED);

        // ambiguous overload: type-level edge only, plus a diagnostic; no method-level edge
        assertThat(pairs(result, GraphLayer.METHOD)).noneMatch(p -> p.startsWith(svc + "formatAmbiguous()"));
        assertThat(diagnostics(result, DiagnosticCategory.AMBIGUOUS_CALL)).singleElement().satisfies(d -> {
            assertThat(d.entityId()).isEqualTo(svc + "formatAmbiguous()");
            assertThat(d.symbol()).isEqualTo("format");
        });
        // receiver of item.area() is an untyped lambda parameter over a library type
        assertThat(diagnostics(result, DiagnosticCategory.UNRESOLVED_CALL)).singleElement().satisfies(d -> {
            assertThat(d.entityId()).isEqualTo(svc + "unknownReceiver()");
            assertThat(d.symbol()).isEqualTo("area");
        });
        assertThat(result.references().unresolved()).isEqualTo(1);
        // the library call audit.record(..) never becomes an edge or an unresolved project reference
        assertThat(result.relationships()).noneMatch(r -> r.targetId().contains("Audit"));
    }

    @Test
    void modernFixtureHandlesEnumsRecordsSealedTypesAnnotationsAndStaticImports() {
        AnalysisResult result = analyze("modern");
        String p = "com.acme.modern.";
        assertThat(entity(result, p + "Status").kind()).isEqualTo(EntityKind.ENUM);
        assertThat(entity(result, p + "Point").kind()).isEqualTo(EntityKind.RECORD);
        assertThat(entity(result, p + "Audited").kind()).isEqualTo(EntityKind.ANNOTATION);
        assertThat(entity(result, p + "Event").kind()).isEqualTo(EntityKind.INTERFACE);
        assertThat(entityIds(result, EntityKind.CONSTRUCTOR)).containsExactlyInAnyOrder(
                p + "Point#<init>(int,int)", p + "Constants#<init>()");
        assertThat(entityIds(result, EntityKind.FIELD)).contains(
                p + "Status#ACTIVE", p + "Status#INACTIVE", p + "Point#x", p + "Point#y", p + "Constants#LIMIT");

        assertThat(relationships(result, GraphLayer.TYPE)).containsExactlyInAnyOrder(
                p + "Created->" + p + "Event:IMPLEMENTS",
                p + "Deleted->" + p + "Event:IMPLEMENTS",
                p + "Event->" + p + "Created:TYPE_REFERENCE",
                p + "Event->" + p + "Deleted:TYPE_REFERENCE",
                p + "Processor->" + p + "Audited:ANNOTATION_USAGE",
                p + "Processor->" + p + "Event:METHOD_PARAMETER_TYPE",
                p + "Processor->" + p + "Created:TYPE_REFERENCE",
                p + "Processor->" + p + "Created:METHOD_CALL",
                p + "Processor->" + p + "Deleted:TYPE_REFERENCE",
                p + "Processor->" + p + "Deleted:METHOD_CALL",
                p + "Processor->" + p + "Constants:FIELD_ACCESS",
                p + "Processor->" + p + "Status:METHOD_PARAMETER_TYPE",
                p + "Processor->" + p + "Status:FIELD_ACCESS",
                p + "Processor->" + p + "Status:METHOD_CALL",
                p + "Processor->" + p + "Status:METHOD_RETURN_TYPE",
                p + "Processor->" + p + "Point:METHOD_PARAMETER_TYPE",
                p + "Processor->" + p + "Point:METHOD_CALL",
                p + "StaticUser->" + p + "Constants:FIELD_ACCESS");
        assertThat(relationships(result, GraphLayer.METHOD)).containsExactly(
                p + "Processor#check(Status)->" + p + "Status#isActive():METHOD_CALL");
        assertThat(result.references().unresolved()).isZero();
    }

    @Test
    void malformedFileIsReportedAndDoesNotProduceRelationships() {
        AnalysisResult result = analyze("malformed");
        String p = "com.acme.bad.";
        assertThat(result.partial()).isTrue();
        assertThat(result.partialReasons()).containsExactly("1 file(s) could not be parsed");
        assertThat(result.fileCounts().parsed()).isEqualTo(2);
        assertThat(result.fileCounts().failed()).isEqualTo(1);
        assertThat(result.files()).filteredOn(f -> f.status() == SourceFileInfo.FileStatus.FAILED)
                .extracting(SourceFileInfo::relativePath)
                .containsExactly("src/main/java/com/acme/bad/Broken.java");
        assertThat(typeIds(result)).containsExactlyInAnyOrder(p + "Good", p + "Helper");
        assertThat(typePairs(result)).containsExactly(p + "Good->" + p + "Helper");
        assertThat(result.relationships()).noneMatch(r -> r.sourceId().contains("Broken") || r.targetId().contains("Broken"));
        assertThat(diagnostics(result, DiagnosticCategory.PARSE_ERROR)).isNotEmpty()
                .allMatch(d -> d.filePath().equals("src/main/java/com/acme/bad/Broken.java"));
        // the reference to the unparsed type is unresolved, not silently external
        assertThat(diagnostics(result, DiagnosticCategory.UNRESOLVED_TYPE)).singleElement()
                .satisfies(d -> assertThat(d.symbol()).isEqualTo("Broken"));
    }

    @Test
    void sameSimpleNameInDifferentPackagesStaysDistinct() {
        AnalysisResult result = analyze("samename");
        assertThat(typeIds(result)).containsExactlyInAnyOrder(
                "com.acme.alpha.Item", "com.acme.alpha.Item$Inner", "com.acme.beta.Item",
                "com.acme.beta.Item$Inner", "com.acme.beta.Consumer");
        assertThat(relationships(result, GraphLayer.TYPE)).containsExactlyInAnyOrder(
                "com.acme.beta.Consumer->com.acme.alpha.Item:FIELD_TYPE",
                "com.acme.beta.Consumer->com.acme.beta.Item:FIELD_TYPE",
                "com.acme.beta.Consumer->com.acme.alpha.Item$Inner:FIELD_TYPE",
                "com.acme.alpha.Item->com.acme.beta.Item:METHOD_PARAMETER_TYPE");
        assertThat(entityIds(result, EntityKind.METHOD)).containsExactlyInAnyOrder(
                "com.acme.alpha.Item#process(int)",
                "com.acme.alpha.Item#process(String)",
                "com.acme.alpha.Item#process(com.acme.beta.Item)");
    }

    @Test
    void testSourcesAreExcludedByDefaultAndIncludedOnRequest() {
        AnalysisResult production = analyze("samename");
        assertThat(production.fileCounts().excludedTests()).isEqualTo(1);
        assertThat(typeIds(production)).doesNotContain("com.acme.alpha.ItemTest");
        assertThat(diagnostics(production, DiagnosticCategory.EXCLUDED_SOURCES)).hasSize(1);

        AnalysisResult withTests = com.ripplemap.testsupport.Fixtures.analyze(
                com.ripplemap.testsupport.Fixtures.path("samename"), true);
        assertThat(withTests.fileCounts().excludedTests()).isZero();
        assertThat(entity(withTests, "com.acme.alpha.ItemTest").testSource()).isTrue();
        assertThat(relationships(withTests, GraphLayer.TYPE)).contains(
                "com.acme.alpha.ItemTest->com.acme.alpha.Item:FIELD_TYPE",
                "com.acme.alpha.ItemTest->com.acme.alpha.Item:OBJECT_CREATION");
    }

    @Test
    void identicalInputProducesIdenticalOutput() {
        AnalysisResult first = analyze("syntax");
        AnalysisResult second = analyze("syntax");
        assertThat(second.entities()).isEqualTo(first.entities());
        assertThat(second.relationships()).isEqualTo(first.relationships());
        assertThat(second.diagnostics()).isEqualTo(first.diagnostics());
        assertThat(second.sourceFingerprint()).isEqualTo(first.sourceFingerprint());
    }

    private static List<Diagnostic> diagnostics(AnalysisResult result, DiagnosticCategory category) {
        return result.diagnostics().stream().filter(d -> d.category() == category).toList();
    }
}
