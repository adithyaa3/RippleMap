package com.ripplemap.controller;

import com.ripplemap.entity.Project;
import com.ripplemap.importing.LocalDirectoryPolicy;
import com.ripplemap.security.CsrfTokenService;
import com.ripplemap.service.SnapshotService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/** Serves the page shells; every page loads its data from the REST API. */
@Controller
public class PageController {

    private static final Map<String, String> PROJECT_PAGES = Map.of(
            "overview", "Project Overview",
            "explorer", "Dependency Explorer",
            "impact", "Impact Analysis",
            "cycles", "Circular Dependencies",
            "history", "Analysis History",
            "compare", "Snapshot Comparison",
            "reports", "Reports");

    private final CsrfTokenService csrf;
    private final SnapshotService snapshots;
    private final LocalDirectoryPolicy localPolicy;

    public PageController(CsrfTokenService csrf, SnapshotService snapshots, LocalDirectoryPolicy localPolicy) {
        this.csrf = csrf;
        this.snapshots = snapshots;
        this.localPolicy = localPolicy;
    }

    @ModelAttribute
    void common(Model model) {
        model.addAttribute("csrfToken", csrf.token());
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        return page(model, "dashboard", "Dashboard", "dashboard");
    }

    @GetMapping("/projects")
    public String projects(Model model) {
        return page(model, "projects", "Projects", "projects");
    }

    @GetMapping("/import")
    public String importPage(Model model) {
        model.addAttribute("localScanEnabled", localPolicy.enabled());
        return page(model, "import", "Import Project", "import");
    }

    @GetMapping("/settings")
    public String settings(Model model) {
        return page(model, "settings", "Settings & About", "settings");
    }

    @GetMapping("/projects/{projectId}")
    public String overview(@PathVariable long projectId, Model model) {
        return projectPage(projectId, "overview", model);
    }

    @GetMapping("/projects/{projectId}/{section:explorer|impact|cycles|history|compare|reports}")
    public String projectSection(@PathVariable long projectId, @PathVariable String section, Model model) {
        return projectPage(projectId, section, model);
    }

    private String projectPage(long projectId, String section, Model model) {
        Project project = snapshots.project(projectId);
        model.addAttribute("projectId", project.getId());
        model.addAttribute("projectName", project.getName());
        model.addAttribute("projectSection", section);
        return page(model, "project/" + section, PROJECT_PAGES.get(section) + " · " + project.getName(), "projects");
    }

    private static String page(Model model, String template, String title, String activeNav) {
        model.addAttribute("pageTitle", title);
        model.addAttribute("activeNav", activeNav);
        return template;
    }
}
