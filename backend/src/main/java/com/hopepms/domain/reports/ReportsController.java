package com.hopepms.domain.reports;

import com.hopepms.security.RequiresRight;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
public class ReportsController {

    private final JdbcClient jdbc;

    public ReportsController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** REP_001 — Product Listing with current price. */
    @GetMapping("/product-listing")
    @RequiresRight("REP_001")
    public List<Map<String, Object>> productListing() {
        return jdbc.sql("""
                SELECT "prodCode", description, unit, "unitPrice", "effDate"
                  FROM hopedb.v_product_current_price
                 WHERE record_status = 'ACTIVE'
                 ORDER BY "prodCode"
                """)
                .query((rs, n) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("prodCode",  rs.getString("prodCode"));
                    r.put("description", rs.getString("description"));
                    r.put("unit",      rs.getString("unit"));
                    r.put("unitPrice", rs.getBigDecimal("unitPrice"));
                    r.put("effDate",   rs.getDate("effDate") == null ? null : rs.getDate("effDate").toLocalDate());
                    return r;
                })
                .list();
    }

    /** REP_002 — Top-selling products. SUPERADMIN-only per the rights matrix. */
    @GetMapping("/top-selling")
    @RequiresRight("REP_002")
    public List<Map<String, Object>> topSelling(
            @RequestParam(value = "limit", required = false, defaultValue = "10") int limit
    ) {
        int capped = Math.max(1, Math.min(100, limit));
        return jdbc.sql("""
                SELECT "prodCode", description, "totalQty"
                  FROM hopedb.v_top_selling
                 LIMIT :n
                """)
                .param("n", capped)
                .query((rs, n) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("prodCode",   rs.getString("prodCode"));
                    r.put("description", rs.getString("description"));
                    r.put("totalQty",   rs.getBigDecimal("totalQty"));
                    return r;
                })
                .list();
    }
}
