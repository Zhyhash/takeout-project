package org.example.takeout.Product.Mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductCategoryMigrationMapperTest {

    private static final long MERCHANT_ID = 1L;
    private static final long SOURCE_CATEGORY_ID = 10L;
    private static final long TARGET_CATEGORY_ID = 20L;
    private static final long OTHER_CATEGORY_ID = 30L;

    private SqlSession session;
    private ProductMapper productMapper;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:product-category-migration-" + UUID.randomUUID() + ";MODE=MySQL");
        Configuration configuration = new Configuration(new Environment(
                "product-category-migration-test", new JdbcTransactionFactory(), dataSource));
        String resource = "mapper/ProductMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource,
                    configuration.getSqlFragments()).parse();
        }
        session = new SqlSessionFactoryBuilder().build(configuration).openSession(false);
        productMapper = session.getMapper(ProductMapper.class);
        // Fixture queries share the mapper transaction, including its FOR UPDATE locks.
        jdbcTemplate = new JdbcTemplate(new SingleConnectionDataSource(session.getConnection(), true));
        jdbcTemplate.execute("CREATE TABLE category (id BIGINT PRIMARY KEY)");
        jdbcTemplate.execute("""
                CREATE TABLE product (
                    id BIGINT PRIMARY KEY,
                    merchant_id BIGINT NOT NULL,
                    category_id BIGINT NOT NULL,
                    is_deleted INT NOT NULL,
                    status INT NOT NULL,
                    stock INT NOT NULL,
                    version INT NOT NULL,
                    CONSTRAINT fk_product_category FOREIGN KEY (category_id)
                        REFERENCES category (id) ON DELETE RESTRICT ON UPDATE RESTRICT
                )
                """);
        jdbcTemplate.update("INSERT INTO category (id) VALUES (?), (?), (?)",
                SOURCE_CATEGORY_ID, TARGET_CATEGORY_ID, OTHER_CATEGORY_ID);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    void migratesActiveAndDeletedProductsWithoutChangingOtherMerchantOrCategory() {
        insertProduct(101L, MERCHANT_ID, SOURCE_CATEGORY_ID, 0, 0, 8, 4);
        insertProduct(102L, MERCHANT_ID, SOURCE_CATEGORY_ID, 1, 1, 9, 7);
        insertProduct(103L, 2L, SOURCE_CATEGORY_ID, 0, 2, 0, 11);
        insertProduct(104L, MERCHANT_ID, OTHER_CATEGORY_ID, 0, 1, 4, 12);

        assertEquals(Set.of(101L, 102L), Set.copyOf(
                productMapper.selectIdsByMerchantIdAndCategoryId(MERCHANT_ID, SOURCE_CATEGORY_ID)));
        assertEquals(2, productMapper.updateCategory(MERCHANT_ID, SOURCE_CATEGORY_ID, TARGET_CATEGORY_ID));

        assertEquals(new ProductState(TARGET_CATEGORY_ID, 0, 0, 8, 5), productState(101L));
        assertEquals(new ProductState(TARGET_CATEGORY_ID, 1, 1, 9, 8), productState(102L));
        assertEquals(new ProductState(SOURCE_CATEGORY_ID, 0, 2, 0, 11), productState(103L));
        assertEquals(new ProductState(OTHER_CATEGORY_ID, 0, 1, 4, 12), productState(104L));
    }

    @Test
    void migrationAllowsDeletingCategoryReferencedByActiveAndDeletedProducts() {
        insertProduct(101L, MERCHANT_ID, SOURCE_CATEGORY_ID, 0, 0, 8, 4);
        insertProduct(102L, MERCHANT_ID, SOURCE_CATEGORY_ID, 1, 1, 9, 7);

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("DELETE FROM category WHERE id = ?", SOURCE_CATEGORY_ID));
        assertEquals(Set.of(101L, 102L), Set.copyOf(
                productMapper.selectIdsByMerchantIdAndCategoryId(MERCHANT_ID, SOURCE_CATEGORY_ID)));
        assertEquals(2, productMapper.updateCategory(MERCHANT_ID, SOURCE_CATEGORY_ID, TARGET_CATEGORY_ID));
        assertEquals(1, jdbcTemplate.update("DELETE FROM category WHERE id = ?", SOURCE_CATEGORY_ID));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM category WHERE id = ?", Integer.class, SOURCE_CATEGORY_ID));
        assertEquals(new ProductState(TARGET_CATEGORY_ID, 0, 0, 8, 5), productState(101L));
        assertEquals(new ProductState(TARGET_CATEGORY_ID, 1, 1, 9, 8), productState(102L));
    }

    private void insertProduct(long id, long merchantId, long categoryId,
                               int isDeleted, int status, int stock, int version) {
        jdbcTemplate.update("""
                INSERT INTO product (id, merchant_id, category_id, is_deleted, status, stock, version)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, merchantId, categoryId, isDeleted, status, stock, version);
    }

    private ProductState productState(long id) {
        return jdbcTemplate.queryForObject("""
                SELECT category_id, is_deleted, status, stock, version FROM product WHERE id = ?
                """, (result, row) -> new ProductState(result.getLong("category_id"),
                result.getInt("is_deleted"), result.getInt("status"),
                result.getInt("stock"), result.getInt("version")), id);
    }

    private record ProductState(long categoryId, int isDeleted, int status, int stock, int version) {
    }
}
