package com.hopepms.domain.products;

import com.hopepms.security.HopePrincipal;
import com.hopepms.util.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/products/{code}/price-history")
public class PriceHistController {

    private static final Pattern CODE = Pattern.compile("^[A-Z]{2}\\d{4}$");
    private final JdbcClient jdbc;

    public PriceHistController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<Map<String, Object>> history(
            @AuthenticationPrincipal HopePrincipal me,
            @PathVariable String code
    ) {
        if (!CODE.matcher(code).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "Invalid prodCode");
        }
        return jdbc.sql("""
                SELECT "effDate", "prodCode", "unitPrice", stamp
                  FROM hopedb."priceHist"
                 WHERE "prodCode" = :c
                 ORDER BY "effDate" DESC
                """)
                .param("c", code)
                .query((rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    LocalDate d = rs.getDate("effDate").toLocalDate();
                    row.put("effDate", d);
                    row.put("prodCode", rs.getString("prodCode"));
                    BigDecimal up = rs.getBigDecimal("unitPrice");
                    row.put("unitPrice", up);
                    if (me.isAdmin()) row.put("stamp", rs.getString("stamp"));
                    return row;
                })
                .list();
    }
}
