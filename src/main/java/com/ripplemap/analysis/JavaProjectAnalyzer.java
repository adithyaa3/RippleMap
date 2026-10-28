package com.ripplemap.analysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.ripplemap.analysis.diagnostics.DiagnosticCategory;
import com.ripplemap.analysis.diagnostics.DiagnosticCollector;
import com.ripplemap.analysis.extraction.DeclarationIndex;
import com.ripplemap.analysis.extraction.DeclarationIndexer;
import com.ripplemap.analysis.extraction.HierarchyResolver;
import com.ripplemap.analysis.extraction.ParsedUnit;
import com.ripplemap.analysis.extraction.RelationshipAggregator;
import com.ripplemap.analysis.extraction.RelationshipExtractor;
import com.ripplemap.analysis.model.AggregatedRelationship;
import com.ripplemap.analysis.model.AnalysisResult;
import com.ripplemap.analysis.model.FileCounts;
import com.ripplemap.analysis.model.ReferenceStatistics;
import com.ripplemap.analysis.model.SourceFileInfo;
import com.ripplemap.analysis.parser.DiscoveredFile;
import com.ripplemap.analysis.parser.JavaSourceReader;
import com.ripplemap.analysis.parser.SourceDiscovery;
import com.ripplemap.analysis.parser.SourceScope;
import com.ripplemap.analysis.resolution.NameLookup;
import com.ripplemap.analysis.resolution.ProjectTypeSolver;
import com.ripplemap.analysis.resolution.ResolutionBudget;
import com.ripplemap.analysis.resolution.SourceIndexTypeSolver;
import com.ripplemap.analysis.resolution.SymbolSolverGate;
import com.ripplemap.analysis.resolution.TypeNameResolver;
import com.ripplemap.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Analyzes Java sources below a directory: discovery, parsing, declaration indexing, symbol resolution and
 * relationship extraction. Source files are only read; nothing from the analyzed project is ever executed.
 * This class has no Spring or persistence dependencies.
 */
public final class JavaProjectAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(JavaProjectAnalyzer.class);

    private static final int MAX_PROBLEMS_PER_FILE = 3;
    /** Packages of annotation processors that add members or classes at compile time (invisible to source analysis). */
    private static final List<String> CODE_GENERATORS =
            List.of("lombok", "com.google.auto.value", "org.immutables", "org.mapstruct");

    public AnalysisResult analyze(AnalysisRequest request) throws IOException, InterruptedException {
        Runnable checkpoint = () -> checkpoint(request);
        DiagnosticCollector diagnostics = new DiagnosticCollector(request.maxDiagnostics());

        request.progress().accept("Discovering source files");
        SourceDiscovery.Result discovery = SourceDiscovery.discover(request.root());
        List<DiscoveredFile> inScope = new ArrayList<>();
        int excludedTests = 0;
        int excludedGenerated = 0;
        for (DiscoveredFile file : discovery.files()) {
            if (file.scope() == SourceScope.BUILD_OUTPUT) {
                excludedGenerated++;
            } else if (file.scope() == SourceScope.TEST && !request.includeTests()) {
                excludedTests++;
            } else {
                inScope.add(file);
            }
        }
        if (inScope.size() > request.maxJavaFiles()) {
            throw new AnalysisLimitException("The project has " + inScope.size()
                    + " Java files in scope; the configured limit is " + request.maxJavaFiles());
        }
        if (excludedTests > 0) {
            diagnostics.add(DiagnosticCategory.EXCLUDED_SOURCES, null, 0, null, null,
                    excludedTests + " test source file(s) were not analyzed because tests are excluded from the scan scope");
        }
        if (excludedGenerated > 0) {
            diagnostics.add(DiagnosticCategory.EXCLUDED_SOURCES, null, 0, null, null,
                    excludedGenerated + " generated build-output file(s) (for example target/generated-sources) were not analyzed");
        }
        for (String link : discovery.skippedLinks()) {
            diagnostics.add(DiagnosticCategory.SKIPPED_LINK, link, 0, null, null,
                    "Symbolic link or unreadable entry skipped");
        }

        SourceIndexTypeSolver sourceSolver = new SourceIndexTypeSolver();
        ProjectTypeSolver typeSolver = new ProjectTypeSolver(sourceSolver);
        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(request.languageLevel())
                .setAttributeComments(false)
                .setSymbolResolver(new JavaSymbolSolver(typeSolver));
        JavaParser parser = new JavaParser(configuration);

        request.progress().accept("Parsing " + inScope.size() + " source files");
        List<ParsedUnit> units = new ArrayList<>();
        List<SourceFileInfo> files = new ArrayList<>(inScope.size());
        List<String> fingerprintLines = new ArrayList<>();
        Set<String> unparsedTypeNames = new HashSet<>();
        int failed = 0;
        int tooLarge = 0;
        int unreadable = 0;
        for (DiscoveredFile file : inScope) {
            checkpoint.run();
            boolean test = file.scope() == SourceScope.TEST;
            JavaSourceReader.Content content;
            try {
                content = JavaSourceReader.read(file.absolutePath(), request.maxFileBytes());
            } catch (JavaSourceReader.FileTooLargeException e) {
                failed++;
                tooLarge++;
                diagnostics.add(DiagnosticCategory.FILE_TOO_LARGE, file.relativePath(), 0, null, null,
                        "File exceeds the " + request.maxFileBytes() + " byte limit and was not analyzed");
                files.add(new SourceFileInfo(file.relativePath(), null, null, 0, 0,
                        SourceFileInfo.FileStatus.FAILED, test, 0));
                continue;
            } catch (IOException e) {
                failed++;
                unreadable++;
                diagnostics.add(DiagnosticCategory.FILE_UNREADABLE, file.relativePath(), 0, null, null,
                        "File could not be read");
                files.add(new SourceFileInfo(file.relativePath(), null, null, 0, 0,
                        SourceFileInfo.FileStatus.FAILED, test, 0));
                continue;
            }
            fingerprintLines.add(file.relativePath() + "\u0000" + content.sha256());
            if (content.encodingReplaced()) {
                diagnostics.add(DiagnosticCategory.ENCODING_REPLACED, file.relativePath(), 0, null, null, null);
            }
            CompilationUnit unit = parse(parser, content.text(), file.relativePath(), diagnostics);
            if (unit == null) {
                failed++;
                unparsedTypeNames.add(baseName(file.relativePath()));
                files.add(new SourceFileInfo(file.relativePath(), null, content.sha256(), content.sizeBytes(),
                        content.lineCount(), SourceFileInfo.FileStatus.FAILED, test, 0));
                continue;
            }
            ParsedUnit parsed = new ParsedUnit(file.relativePath(), unit, test);
            units.add(parsed);
            files.add(new SourceFileInfo(file.relativePath(), parsed.packageName(), content.sha256(),
                    content.sizeBytes(), content.lineCount(), SourceFileInfo.FileStatus.PARSED, test,
                    countTypes(unit)));
        }

        reportCodeGenerators(units, diagnostics);

        request.progress().accept("Indexing declarations");
        DeclarationIndex index = DeclarationIndexer.build(units, unparsedTypeNames, diagnostics);
        ReferenceStatistics references = new ReferenceStatistics();
        RelationshipAggregator aggregator = new RelationshipAggregator(Math.max(1, request.maxReferenceSamples()));

        request.progress().accept("Resolving symbols and extracting relationships");
        SymbolSolverGate.runExclusively(() -> {
            sourceSolver.register(index);
            ResolutionBudget budget = new ResolutionBudget(request.referenceBudget().toNanos(), checkpoint);
            typeSolver.useBudget(budget);
            NameLookup lookup = new NameLookup(index, typeSolver::isJdkType);
            TypeNameResolver resolver = new TypeNameResolver(index, typeSolver, lookup, budget);
            HierarchyResolver.resolve(index, resolver, checkpoint);
            lookup.enableInheritedMemberTypes();
            new RelationshipExtractor(index, resolver, JavaParserFacade.get(typeSolver), aggregator, diagnostics,
                    references, checkpoint, budget, request.progress()).extract();
            references.solverFailures += resolver.solverFailures();
            log.info("Symbol resolution: {} ms in the solver, slowest reference {} ms, {} reference(s) over budget",
                    budget.totalMillis(), budget.maxMillis(), budget.exceededCount());
            return null;
        });
        List<AggregatedRelationship> relationships = aggregator.build();

        List<String> partialReasons = new ArrayList<>();
        int parseFailures = failed - tooLarge - unreadable;
        if (parseFailures > 0) {
            partialReasons.add(parseFailures + " file(s) could not be parsed");
        }
        if (tooLarge > 0) {
            partialReasons.add(tooLarge + " file(s) exceeded the size limit");
        }
        if (unreadable > 0) {
            partialReasons.add(unreadable + " file(s) could not be read");
        }
        fingerprintLines.sort(String::compareTo);
        String fingerprint = Hashing.sha256Hex(String.join("\n", fingerprintLines));
        FileCounts counts = new FileCounts(discovery.files().size(), inScope.size(), excludedTests, excludedGenerated,
                units.size(), failed, discovery.skippedLinks().size());
        return new AnalysisResult(!partialReasons.isEmpty(), List.copyOf(partialReasons), counts, List.copyOf(files),
                index.entities(), relationships, diagnostics.stored(), diagnostics.categoryCounts(),
                diagnostics.truncated(), references, fingerprint, request.languageLevel().name());
    }

    private static CompilationUnit parse(JavaParser parser, String text, String path, DiagnosticCollector diagnostics) {
        ParseResult<CompilationUnit> result;
        try {
            result = parser.parse(text);
        } catch (StackOverflowError | RuntimeException e) {
            diagnostics.add(DiagnosticCategory.PARSE_ERROR, path, 0, null, null,
                    "The parser failed on this file (" + e.getClass().getSimpleName() + ")");
            return null;
        }
        if (result.isSuccessful() && result.getResult().isPresent()) {
            return result.getResult().get();
        }
        List<Problem> problems = result.getProblems();
        if (problems.isEmpty()) {
            diagnostics.add(DiagnosticCategory.PARSE_ERROR, path, 0, null, null, "The file could not be parsed");
        }
        for (int i = 0; i < problems.size() && i < MAX_PROBLEMS_PER_FILE; i++) {
            Problem problem = problems.get(i);
            int line = problem.getLocation().flatMap(l -> l.getBegin().getRange()).map(r -> r.begin.line).orElse(0);
            diagnostics.add(DiagnosticCategory.PARSE_ERROR, path, line, null, null, problem.getMessage());
        }
        if (problems.size() > MAX_PROBLEMS_PER_FILE) {
            diagnostics.add(DiagnosticCategory.PARSE_ERROR, path, 0, null, null,
                    (problems.size() - MAX_PROBLEMS_PER_FILE) + " further parse problem(s) in this file");
        }
        return null;
    }

    /** Reports each file that imports a known code generator, once, at its first such import. */
    private static void reportCodeGenerators(List<ParsedUnit> units, DiagnosticCollector diagnostics) {
        for (ParsedUnit unit : units) {
            for (ImportDeclaration imp : unit.compilationUnit().getImports()) {
                String name = imp.getNameAsString();
                String generator = CODE_GENERATORS.stream()
                        .filter(g -> name.equals(g) || name.startsWith(g + "."))
                        .findFirst().orElse(null);
                if (generator != null) {
                    diagnostics.add(DiagnosticCategory.GENERATED_CODE, unit.relativePath(),
                            imp.getBegin().map(p -> p.line).orElse(0), null, name,
                            "Uses " + generator + ": members and classes it generates at compile time are not visible;"
                                    + " references to them count as external and add no edges");
                    break;
                }
            }
        }
    }

    private static String baseName(String relativePath) {
        String name = relativePath.substring(relativePath.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static int countTypes(CompilationUnit unit) {
        int[] count = {0};
        for (TypeDeclaration<?> type : unit.getTypes()) {
            countMemberTypes(type, count);
        }
        return count[0];
    }

    private static void countMemberTypes(TypeDeclaration<?> type, int[] count) {
        count[0]++;
        type.getMembers().forEach(member -> {
            if (member instanceof TypeDeclaration<?> nested) {
                countMemberTypes(nested, count);
            }
        });
    }

    private static void checkpoint(AnalysisRequest request) {
        if (request.cancelled().getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new AnalysisInterruptedException(AnalysisInterruptedException.Reason.CANCELLED, "Analysis was cancelled");
        }
        if (request.deadline() != null && Instant.now().isAfter(request.deadline())) {
            throw new AnalysisInterruptedException(AnalysisInterruptedException.Reason.DEADLINE_EXCEEDED,
                    "Analysis exceeded the configured time limit");
        }
    }
}
