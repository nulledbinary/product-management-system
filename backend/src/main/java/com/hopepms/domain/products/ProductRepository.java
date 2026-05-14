package com.hopepms.domain.products;

import com.hopepms.util.StampHelper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class ProductRepository {

    private final JdbcClient jdbc;

    public ProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT_WITH_PRICE = """
            SELECT p."prodCode", p.description, p.unit, p.record_status, p.stamp,
                   ph."unitPrice", ph."effDate"
              FROM hopedb.product p
              LEFT JOIN LATERAL (
                  SELECT "unitPrice", "effDate"
                    FROM hopedb."priceHist"
                   WHERE "prodCode" = p."prodCode"
                   ORDER BY "effDate" DESC
                   LIMIT 1
              ) ph ON true
            """;

    public List<ProductDto> listActive() {
        return jdbc.sql(SELECT_WITH_PRICE + " WHERE p.record_status = 'ACTIVE' ORDER BY p.\"prodCode\"")
                .query(ProductRepository::map)
                .list();
    }

    public List<ProductDto> listAll() {
        return jdbc.sql(SELECT_WITH_PRICE + " ORDER BY p.\"prodCode\"")
                .query(ProductRepository::map)
                .list();
    }

    public List<ProductDto> listInactive() {
        return jdbc.sql(SELECT_WITH_PRICE + " WHERE p.record_status = 'INACTIVE' ORDER BY p.\"prodCode\"")
                .query(ProductRepository::map)
                .list();
    }

    public Optional<ProductDto> findOne(String prodCode) {
        return jdbc.sql(SELECT_WITH_PRICE + " WHERE p.\"prodCode\" = :code")
                .param("code", prodCode)
                .query(ProductRepository::map)
                .optional();
    }

    @Transactional
    public void insert(String prodCode, String description, String unit, BigDecimal unitPrice, String callerId) {
        setCallerId(callerId);
        String stamp = StampHelper.make("ADDED", callerId);
        jdbc.sql("""
                INSERT INTO hopedb.product ("prodCode", description, unit, record_status, stamp)
                VALUES (:c, :d, :u, 'ACTIVE', :s)
                """)
                .param("c", prodCode).param("d", description).param("u", unit).param("s", stamp)
                .update();
        upsertPrice(prodCode, unitPrice, callerId);
    }

    @Transactional
    public void update(String prodCode, String description, String unit, BigDecimal unitPrice, String callerId) {
        setCallerId(callerId);
        String stamp = StampHelper.make("EDITED", callerId);
        if (description != null || unit != null) {
            jdbc.sql("""
                    UPDATE hopedb.product
                       SET description = COALESCE(:d, description),
                           unit        = COALESCE(:u, unit),
                           stamp       = :s
                     WHERE "prodCode"  = :c
                    """)
                    .param("c", prodCode).param("d", description).param("u", unit).param("s", stamp)
                    .update();
        }
        if (unitPrice != null) {
            upsertPrice(prodCode, unitPrice, callerId);
        }
    }

    @Transactional
    public void setRecordStatus(String prodCode, String nextStatus, String callerId) {
        setCallerId(callerId);
        String action = "INACTIVE".equals(nextStatus) ? "DEACTIVATED" : "REACTIVATED";
        jdbc.sql("""
                UPDATE hopedb.product
                   SET record_status = :st, stamp = :s
                 WHERE "prodCode" = :c
                """)
                .param("c", prodCode).param("st", nextStatus)
                .param("s", StampHelper.make(action, callerId))
                .update();
    }

    private void upsertPrice(String prodCode, BigDecimal unitPrice, String callerId) {
        jdbc.sql("""
                INSERT INTO hopedb."priceHist" ("effDate", "prodCode", "unitPrice", stamp)
                VALUES (CURRENT_DATE, :c, :p, :s)
                ON CONFLICT ("effDate", "prodCode")
                DO UPDATE SET "unitPrice" = EXCLUDED."unitPrice", stamp = EXCLUDED.stamp
                """)
                .param("c", prodCode).param("p", unitPrice)
                .param("s", StampHelper.make("PRICE_SET", callerId))
                .update();
    }

    private void setCallerId(String callerId) {
        jdbc.sql("SELECT set_config('hopepms.caller_userid', :uid, true)")
                .param("uid", callerId)
                .query(String.class)
                .optional();
    }

    private static ProductDto map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Date d = rs.getDate("effDate");
        BigDecimal price = rs.getBigDecimal("unitPrice");
        LocalDate effDate = d == null ? null : d.toLocalDate();
        return new ProductDto(
                rs.getString("prodCode"),
                rs.getString("description"),
                rs.getString("unit"),
                rs.getString("record_status"),
                rs.getString("stamp"),
                price,
                effDate
        );
    }
}
