package com.hopepms.domain.products;

import com.hopepms.security.HopePrincipal;
import com.hopepms.security.RequiresRight;
import com.hopepms.util.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private static final Pattern PROD_CODE = Pattern.compile("^[A-Z]{2}\\d{4}$");

    private final ProductRepository repo;

    public ProductController(ProductRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @AuthenticationPrincipal HopePrincipal me,
            @RequestParam(value = "include", required = false) String include
    ) {
        boolean wantInactive = "inactive".equalsIgnoreCase(include);
        List<ProductDto> rows;
        if (!me.isAdmin()) {
            rows = repo.listActive();
        } else if (wantInactive) {
            rows = repo.listInactive();
        } else {
            rows = repo.listAll();
        }
        return rows.stream().map(p -> sanitize(p, me)).toList();
    }

    @GetMapping("/{code}")
    public Map<String, Object> getOne(@AuthenticationPrincipal HopePrincipal me, @PathVariable String code) {
        validateCode(code);
        ProductDto p = repo.findOne(code)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found", "No product " + code));
        if (!me.isAdmin() && !"ACTIVE".equals(p.recordStatus())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "No product " + code);
        }
        return sanitize(p, me);
    }

    @PostMapping
    @RequiresRight("PRD_ADD")
    public ResponseEntity<Map<String, Object>> create(
            @AuthenticationPrincipal HopePrincipal me,
            @Valid @RequestBody CreateRequest req
    ) {
        validateCode(req.prodCode);
        repo.insert(req.prodCode, req.description, req.unit, req.unitPrice, me.userId());
        ProductDto p = repo.findOne(req.prodCode)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "server_error", "Insert lost"));
        return ResponseEntity.status(HttpStatus.CREATED).body(sanitize(p, me));
    }

    @PatchMapping("/{code}")
    public Map<String, Object> patch(
            @AuthenticationPrincipal HopePrincipal me,
            @PathVariable String code,
            @RequestBody PatchRequest req
    ) {
        validateCode(code);
        if (req.recordStatus != null) {
            return changeStatus(me, code, req.recordStatus);
        }
        if (!me.hasRight("PRD_EDIT")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Missing right: PRD_EDIT");
        }
        repo.update(code, req.description, req.unit, req.unitPrice, me.userId());
        return Map.of("ok", true);
    }

    private Map<String, Object> changeStatus(HopePrincipal me, String code, String next) {
        if (!"ACTIVE".equals(next) && !"INACTIVE".equals(next)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "record_status must be ACTIVE or INACTIVE");
        }
        if ("INACTIVE".equals(next) && !me.hasRight("PRD_DEL")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Missing right: PRD_DEL");
        }
        if ("ACTIVE".equals(next) && !me.isAdmin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Only ADMIN/SUPERADMIN can recover");
        }
        repo.setRecordStatus(code, next, me.userId());
        return Map.of("ok", true);
    }

    private void validateCode(String code) {
        if (code == null || !PROD_CODE.matcher(code).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "Invalid prodCode");
        }
    }

    private Map<String, Object> sanitize(ProductDto p, HopePrincipal me) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prodCode", p.prodCode());
        out.put("description", p.description());
        out.put("unit", p.unit());
        out.put("record_status", p.recordStatus());
        out.put("currentPrice", p.currentPrice());
        out.put("effDate", p.effDate());
        if (me.isAdmin()) out.put("stamp", p.stamp());
        return out;
    }

    public record CreateRequest(
            @NotBlank @Pattern(regexp = "^[A-Z]{2}\\d{4}$") String prodCode,
            @NotBlank @Size(max = 30) String description,
            @NotBlank @Pattern(regexp = "^(pc|ea|mtr|pkg|ltr)$") String unit,
            @DecimalMin(value = "0.01") BigDecimal unitPrice
    ) {}

    public record PatchRequest(
            @Size(max = 30) String description,
            @Pattern(regexp = "^(pc|ea|mtr|pkg|ltr)$") String unit,
            @DecimalMin(value = "0.01") BigDecimal unitPrice,
            @Pattern(regexp = "^(ACTIVE|INACTIVE)$") String recordStatus
    ) {}
}
