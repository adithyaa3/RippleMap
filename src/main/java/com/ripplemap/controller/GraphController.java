package com.ripplemap.controller;

import com.ripplemap.analysis.model.EntityKind;
import com.ripplemap.analysis.model.GraphLayer;
import com.ripplemap.analysis.model.RelationshipKind;
import com.ripplemap.dto.ComparisonDtos;
import com.ripplemap.dto.CycleDtos;
import com.ripplemap.dto.GraphDtos;
import com.ripplemap.dto.ImpactDtos;
import com.ripplemap.graph.traversal.Direction;
import com.ripplemap.service.ComparisonService;
import com.ripplemap.service.CycleService;
import com.ripplemap.service.GraphQueryService;
import com.ripplemap.service.ImpactService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * Graph queries. Node ids contain characters such as '#', '$', '(' and ',', so they are passed as query
 * parameters, never as path segments.
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
@Tag(name = "Graph and impact")
public class GraphController {

    private final GraphQueryService graphs;
    private final ImpactService impact;
    private final CycleService cycles;
    private final ComparisonService comparisons;

    public GraphController(GraphQueryService graphs, ImpactService impact, CycleService cycles,
                           ComparisonService comparisons) {
        this.graphs = graphs;
        this.impact = impact;
        this.cycles = cycles;
        this.comparisons = comparisons;
    }

    @GetMapping("/graph")
    @Operation(summary = "Nodes and edges of a snapshot graph (bounded; truncation is reported)")
    public GraphDtos.GraphResponse graph(@PathVariable long projectId,
                                         @RequestParam(required = false) Long analysisId,
                                         @RequestParam(required = false) GraphLayer layer,
                                         @RequestParam(required = false) GraphQueryService.Scope scope,
                                         @RequestParam(required = false) String focus,
                                         @RequestParam(required = false) Integer depth,
                                         @RequestParam(required = false) Integer component,
                                         @RequestParam(required = false) Integer limit,
                                         @RequestParam(required = false) Set<EntityKind> nodeKinds,
                                         @RequestParam(required = false) Set<RelationshipKind> relationshipKinds,
                                         @RequestParam(required = false) Boolean includeInferred) {
        return graphs.graph(projectId, new GraphQueryService.GraphQuery(analysisId, layer, scope, focus, depth,
                component, limit, nodeKinds, relationshipKinds, includeInferred));
    }

    @GetMapping("/nodes")
    @Operation(summary = "Search graph nodes by name")
    public GraphDtos.SearchResult search(@PathVariable long projectId,
                                         @RequestParam(required = false) Long analysisId,
                                         @RequestParam(required = false) GraphLayer layer,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(required = false) EntityKind kind,
                                         @RequestParam(defaultValue = "20") int limit) {
        return graphs.search(projectId, analysisId, layer, q, kind, limit);
    }

    @GetMapping("/node")
    @Operation(summary = "Details of one declaration: metrics, direct dependencies and dependents, members")
    public GraphDtos.NodeDetail node(@PathVariable long projectId, @RequestParam(required = false) Long analysisId,
                                     @RequestParam String nodeId) {
        return graphs.nodeDetail(projectId, analysisId, nodeId);
    }

    @GetMapping("/edge")
    @Operation(summary = "Relationship kinds and source references behind one dependency")
    public GraphDtos.EdgeDetail edge(@PathVariable long projectId, @RequestParam(required = false) Long analysisId,
                                     @RequestParam(required = false) GraphLayer layer, @RequestParam String source,
                                     @RequestParam String target) {
        return graphs.edgeDetail(projectId, analysisId, layer, source, target);
    }

    @GetMapping("/dependencies")
    @Operation(summary = "What a component depends on (outgoing edges)")
    public GraphDtos.ReachableList dependencies(@PathVariable long projectId,
                                                @RequestParam(required = false) Long analysisId,
                                                @RequestParam String nodeId,
                                                @RequestParam(defaultValue = "false") boolean transitive,
                                                @RequestParam(required = false) Boolean includeInferred) {
        return graphs.reachable(projectId, analysisId, nodeId, Direction.DEPENDENCIES, transitive, includeInferred);
    }

    @GetMapping("/dependents")
    @Operation(summary = "What depends on a component (incoming edges)")
    public GraphDtos.ReachableList dependents(@PathVariable long projectId,
                                              @RequestParam(required = false) Long analysisId,
                                              @RequestParam String nodeId,
                                              @RequestParam(defaultValue = "false") boolean transitive,
                                              @RequestParam(required = false) Boolean includeInferred) {
        return graphs.reachable(projectId, analysisId, nodeId, Direction.DEPENDENTS, transitive, includeInferred);
    }

    @GetMapping("/path")
    @Operation(summary = "Shortest dependency path from one component to another")
    public GraphDtos.PathResponse path(@PathVariable long projectId, @RequestParam(required = false) Long analysisId,
                                       @RequestParam String from, @RequestParam String to,
                                       @RequestParam(required = false) Boolean includeInferred) {
        return graphs.path(projectId, analysisId, from, to, includeInferred);
    }

    @PostMapping(path = "/impact-analysis", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Simulate a change: potentially affected components, explanation paths and score")
    public ImpactDtos.ImpactResult impact(@PathVariable long projectId,
                                          @Valid @RequestBody ImpactDtos.ImpactRequest request) {
        return impact.analyze(projectId, request);
    }

    @GetMapping("/cycles")
    @Operation(summary = "Circular dependencies (cyclic strongly connected components)")
    public CycleDtos.CycleList cycles(@PathVariable long projectId, @RequestParam(required = false) Long analysisId,
                                      @RequestParam(required = false) GraphLayer layer,
                                      @RequestParam(required = false) Boolean includeInferred) {
        return cycles.cycles(projectId, analysisId, layer, includeInferred);
    }

    @GetMapping("/compare")
    @Operation(summary = "Differences between two snapshots of the project")
    public ComparisonDtos.Comparison compare(@PathVariable long projectId, @RequestParam Long base,
                                             @RequestParam Long target,
                                             @RequestParam(required = false) GraphLayer layer) {
        return comparisons.compare(projectId, base, target, layer);
    }
}
