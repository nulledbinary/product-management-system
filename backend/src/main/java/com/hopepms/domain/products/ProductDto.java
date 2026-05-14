package com.hopepms.domain.products;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ProductDto(
        String prodCode,
        String description,
        String unit,
        String recordStatus,
        String stamp,
        BigDecimal currentPrice,
        LocalDate effDate
) {}
