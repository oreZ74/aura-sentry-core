package de.orez.aura_sentry_core.controller;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import de.orez.aura_sentry_core.advisor.dto.AnalysisReportDto;
import de.orez.aura_sentry_core.service.demo.DemoModeProvider;
import jakarta.servlet.http.HttpSession;

@Controller
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private static final java.util.concurrent.Executor VIRTUAL_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    private final DashboardService dashboardService;
    private final DemoModeProvider demoModeProvider;

    public DashboardController(DashboardService dashboardService,
                               DemoModeProvider demoModeProvider) {
        this.dashboardService = dashboardService;
        this.demoModeProvider = demoModeProvider;
    }

    @GetMapping("/")
    public String dashboard(
            @RequestHeader(value = "X-Demo-Mode", defaultValue = "false") boolean headerDemoMode,
            HttpSession session,
            Model model) {
        Boolean sessionDemo = (Boolean) session.getAttribute("demoModeActive");
        boolean demoMode = (sessionDemo != null && sessionDemo) || headerDemoMode;
        demoModeProvider.setDemoMode(demoMode);
        model.addAttribute("currentPage", "dashboard");

        try {
            DashboardService.DashboardData data = dashboardService.loadDashboard(demoMode);

            model.addAttribute("results", java.util.Collections.emptyList());
            model.addAttribute("totalResources", data.cached().size());
            model.addAttribute("warningCount", data.warningCount());
            model.addAttribute("totalMonthlyCost", data.totalMonthlyCost());
            model.addAttribute("totalMonthlySavings", data.totalMonthlySavings());
            model.addAttribute("totalAnnualSavings", data.totalAnnualSavings());
            model.addAttribute("currencySymbol", data.currencySymbol());
            model.addAttribute("scanDurationMs", 0);
            model.addAttribute("demoMode", demoMode);
            model.addAttribute("credentialsMissing", data.credentialsMissing());

            if (data.azureError()) {
                model.addAttribute("azureError", true);
                model.addAttribute("azureErrorMessage", data.azureErrorMessage());
            }

            return "index";
        } catch (Exception e) {
            log.warn("[Dashboard] Azure fetch failed ({}: {})",
                    e.getClass().getSimpleName(), e.getMessage());

            model.addAttribute("results", java.util.Collections.emptyList());
            model.addAttribute("totalResources", 0);
            model.addAttribute("warningCount", 0);
            model.addAttribute("totalMonthlyCost", "€0.00");
            model.addAttribute("totalMonthlySavings", "€0.00");
            model.addAttribute("totalAnnualSavings", "€0.00");
            model.addAttribute("currencySymbol", "€");
            model.addAttribute("scanDurationMs", 0);
            model.addAttribute("demoMode", false);
            model.addAttribute("azureError", true);
            model.addAttribute("azureErrorMessage",
                    e.getClass().getSimpleName() + ": " + e.getMessage());

            return "index";
        } finally {
            demoModeProvider.clear();
        }
    }

    @GetMapping(value = "/api/insight", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public CompletableFuture<ResponseEntity<AnalysisReportDto>> getInsight(
            @RequestHeader(value = "X-Demo-Mode", defaultValue = "false") boolean headerDemoMode,
            HttpSession session,
            @RequestParam String resourceId) {
        Boolean sessionDemo = (Boolean) session.getAttribute("demoModeActive");
        boolean demoMode = (sessionDemo != null && sessionDemo) || headerDemoMode;

        return CompletableFuture.supplyAsync(() -> {
            demoModeProvider.setDemoMode(demoMode);
            try {
                AnalysisReportDto report = dashboardService.getInsight(resourceId, demoMode);
                return ResponseEntity.ok(report);
            } catch (Exception e) {
                log.warn("[AI Insight] Failed for '{}': {}", resourceId, e.getMessage());
                return ResponseEntity.ok(AnalysisReportDto.fallback(
                        "Processing failed: " + e.getMessage()));
            } finally {
                demoModeProvider.clear();
            }
        }, VIRTUAL_EXECUTOR);
    }
}
