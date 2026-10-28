/*
 * Cytoscape helpers shared by the explorer, impact and cycle pages.
 * Edge direction: source -> target means "source depends on target"; arrows point at the dependency.
 */
(function () {
  'use strict';

  const SHAPES = {
    CLASS: 'round-rectangle',
    INTERFACE: 'ellipse',
    ENUM: 'hexagon',
    RECORD: 'round-tag',
    ANNOTATION: 'diamond',
    METHOD: 'ellipse',
    CONSTRUCTOR: 'round-diamond'
  };

  function cssVar(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  }

  function palette() {
    return {
      text: cssVar('--text'),
      muted: cssVar('--text-muted'),
      bg: cssVar('--bg'),
      surface: cssVar('--surface'),
      accent: cssVar('--accent'),
      edge: cssVar('--edge'),
      dependency: cssVar('--edge-dependency'),
      dependent: cssVar('--edge-dependent'),
      inferred: cssVar('--edge-inferred'),
      danger: cssVar('--danger'),
      warning: cssVar('--warning'),
      kinds: {
        CLASS: cssVar('--kind-class'),
        INTERFACE: cssVar('--kind-interface'),
        ENUM: cssVar('--kind-enum'),
        RECORD: cssVar('--kind-record'),
        ANNOTATION: cssVar('--kind-annotation'),
        METHOD: cssVar('--kind-method'),
        CONSTRUCTOR: cssVar('--kind-constructor')
      }
    };
  }

  function stylesheet() {
    const p = palette();
    const styles = [
      {
        selector: 'node',
        style: {
          label: 'data(label)',
          'font-family': 'Inter, Segoe UI, system-ui, sans-serif',
          'font-size': 11,
          color: p.text,
          'text-valign': 'bottom',
          'text-halign': 'center',
          'text-margin-y': 5,
          'text-wrap': 'ellipsis',
          'text-max-width': 150,
          'text-background-color': p.bg,
          'text-background-opacity': 0.75,
          'text-background-padding': 2,
          'text-background-shape': 'roundrectangle',
          'min-zoomed-font-size': 7,
          width: 'mapData(weight, 0, 30, 22, 46)',
          height: 'mapData(weight, 0, 30, 22, 46)',
          'background-color': p.kinds.CLASS,
          'border-width': 2,
          'border-color': p.surface,
          'overlay-padding': 4
        }
      },
      {
        selector: 'edge',
        style: {
          width: 1.4,
          'line-color': p.edge,
          'target-arrow-color': p.edge,
          'target-arrow-shape': 'triangle',
          'arrow-scale': 0.85,
          'curve-style': 'bezier',
          opacity: 0.9
        }
      },
      { selector: 'edge[resolution = "INFERRED"]', style: { 'line-style': 'dashed', 'line-color': p.inferred, 'target-arrow-color': p.inferred } },
      { selector: 'edge[?selfLoop]', style: { 'curve-style': 'bezier', 'loop-direction': '-45deg', 'loop-sweep': '45deg' } },
      { selector: 'node.in-cycle', style: { 'border-color': p.danger, 'border-style': 'dashed' } },
      { selector: 'node.test-source', style: { 'background-opacity': 0.7 } },
      { selector: '.faded', style: { opacity: 0.13, 'text-opacity': 0.25 } },
      { selector: 'node.focus', style: { 'border-width': 4, 'border-color': p.accent, 'z-index': 20 } },
      { selector: 'node.dependency', style: { 'border-width': 3.5, 'border-color': p.dependency, 'z-index': 15 } },
      { selector: 'node.dependent', style: { 'border-width': 3.5, 'border-color': p.dependent, 'z-index': 15 } },
      { selector: 'edge.dependency', style: { width: 2.6, 'line-color': p.dependency, 'target-arrow-color': p.dependency, opacity: 1, 'z-index': 15 } },
      { selector: 'edge.dependent', style: { width: 2.6, 'line-color': p.dependent, 'target-arrow-color': p.dependent, opacity: 1, 'z-index': 15 } },
      { selector: 'node.affected', style: { 'border-width': 3, 'border-color': p.dependent } },
      { selector: 'node.selected-target', style: { 'border-width': 4.5, 'border-color': p.accent, 'z-index': 20 } },
      { selector: 'edge.path', style: { width: 2.8, 'line-color': p.dependent, 'target-arrow-color': p.dependent, opacity: 1 } },
      { selector: 'node:selected', style: { 'overlay-color': p.accent, 'overlay-opacity': 0.18 } },
      { selector: 'edge:selected', style: { width: 3.2, 'line-color': p.accent, 'target-arrow-color': p.accent } }
    ];
    for (const [kind, shape] of Object.entries(SHAPES)) {
      styles.splice(1, 0, { selector: 'node[kind = "' + kind + '"]', style: { shape, 'background-color': p.kinds[kind] } });
    }
    return styles;
  }

  function elementsFrom(response) {
    const elements = [];
    for (const n of response.nodes) {
      const classes = [];
      if (n.inCycle) classes.push('in-cycle');
      if (n.testSource) classes.push('test-source');
      elements.push({
        group: 'nodes',
        data: {
          id: n.id, label: n.label, name: n.name, kind: n.kind, filePath: n.filePath, line: n.line,
          weight: Math.min(30, (n.inDegree || 0) + (n.outDegree || 0)), inDegree: n.inDegree, outDegree: n.outDegree,
          distance: n.distance
        },
        classes: classes.join(' ')
      });
    }
    for (const e of response.edges) {
      elements.push({
        group: 'edges',
        data: { id: e.id, source: e.source, target: e.target, kinds: e.kinds, resolution: e.resolution, referenceCount: e.referenceCount, selfLoop: e.source === e.target }
      });
    }
    return elements;
  }

  function layoutOptions(name, cy, focusId) {
    const count = cy.nodes().length;
    switch (name) {
      case 'hierarchy':
        return { name: 'breadthfirst', directed: true, spacingFactor: count > 80 ? 0.9 : 1.25, padding: 30, animate: false,
          roots: focusId ? cy.getElementById(focusId) : undefined, avoidOverlap: true, nodeDimensionsIncludeLabels: true };
      case 'concentric':
        return { name: 'concentric', padding: 30, animate: false, minNodeSpacing: 24, avoidOverlap: true,
          concentric: (node) => node.degree(), levelWidth: () => 2 };
      case 'grid':
        return { name: 'grid', padding: 30, animate: false, avoidOverlap: true, nodeDimensionsIncludeLabels: true };
      case 'circle':
        return { name: 'circle', padding: 30, animate: false, avoidOverlap: true };
      default:
        return { name: 'cose', animate: false, padding: 30, randomize: false, nodeRepulsion: () => 9000,
          idealEdgeLength: () => 90, edgeElasticity: () => 80, gravity: 0.35, numIter: count > 400 ? 600 : 1200,
          nodeDimensionsIncludeLabels: true, componentSpacing: 70 };
    }
  }

  function create(container, response, options) {
    const opts = options || {};
    if (typeof window.cytoscape !== 'function') {
      throw new Error('The graph library could not be loaded.');
    }
    const cy = window.cytoscape({
      container,
      elements: elementsFrom(response),
      style: stylesheet(),
      layout: { name: 'preset' },
      minZoom: 0.05,
      maxZoom: 3.5,
      boxSelectionEnabled: false,
      selectionType: 'single',
      autoungrabify: false
    });
    runLayout(cy, opts.layout || 'force', opts.focus);
    document.addEventListener('rm:theme', () => cy.style(stylesheet()));
    return cy;
  }

  function runLayout(cy, name, focusId) {
    if (!cy.nodes().length) return;
    cy.layout(layoutOptions(name, cy, focusId)).run();
    cy.fit(undefined, 40);
    if (cy.zoom() > 1.6) {
      cy.zoom(1.6);
      cy.center();
    }
  }

  /** Highlights a node with its direct dependencies (outgoing) and dependents (incoming); fades the rest. */
  function highlightNeighborhood(cy, nodeId) {
    clearHighlight(cy);
    const node = cy.getElementById(nodeId);
    if (!node || node.empty()) return;
    cy.elements().addClass('faded');
    node.removeClass('faded').addClass('focus');
    node.outgoers('edge').forEach((edge) => {
      if (edge.target().id() === nodeId) return;
      edge.removeClass('faded').addClass('dependency');
      edge.target().removeClass('faded').addClass('dependency');
    });
    node.incomers('edge').forEach((edge) => {
      if (edge.source().id() === nodeId) return;
      edge.removeClass('faded').addClass('dependent');
      edge.source().removeClass('faded').addClass('dependent');
    });
  }

  /** Highlights a set of node ids (and the edges among them), fading everything else. */
  function highlightSet(cy, ids, focusId, cls) {
    clearHighlight(cy);
    const set = new Set(ids);
    if (focusId) set.add(focusId);
    cy.elements().addClass('faded');
    cy.nodes().forEach((n) => {
      if (set.has(n.id())) {
        n.removeClass('faded');
        if (n.id() === focusId) n.addClass('focus');
        else if (cls) n.addClass(cls);
      }
    });
    cy.edges().forEach((e) => {
      if (set.has(e.source().id()) && set.has(e.target().id())) {
        e.removeClass('faded');
        if (cls) e.addClass(cls);
      }
    });
  }

  function clearHighlight(cy) {
    cy.elements().removeClass('faded focus dependency dependent affected path selected-target');
  }

  window.RM = window.RM || {};
  window.RM.graph = { create, runLayout, highlightNeighborhood, highlightSet, clearHighlight, elementsFrom, stylesheet };
})();
