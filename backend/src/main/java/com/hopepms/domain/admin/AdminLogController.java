package com.hopepms.domain.admin;

import com.hopepms.security.HopePrincipal;
import com.hopepms.security.RequiresRight;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Read-only administrative activity log. Gated by ADM_USER so it is reachable
 * only from the Admin section of the app, matching where it is surfaced.
 */
@RestController
@RequestMapping("/api/admin/logs")
public class AdminLogController {

    private final AdminLogService logs;

    public AdminLogController(AdminLogService logs) {
        this.logs = logs;
    }

    @GetMapping
    @RequiresRight("ADM_USER")
    public List<Map<String, Object>> list(
            @AuthenticationPrincipal HopePrincipal me,
            @RequestParam(value = "limit", required = false, defaultValue = "200") int limit
    ) {
        return logs.recent(limit);
    }
}
