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

import java.io.InputStream;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductImageCleanupMapperTest {

    private SqlSession session;
    private ProductMapper productMapper;

    @BeforeEach
    void setUp() throws Exception {
        // 直接加载真实 Mapper XML；独立内存库不会启动定时器或连接项目数据库。
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:image-cleanup-" + UUID.randomUUID() + ";MODE=MySQL");
        Configuration configuration = new Configuration(new Environment(
                "image-cleanup-test", new JdbcTransactionFactory(), dataSource
        ));
        String resource = "mapper/ProductMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource,
                    configuration.getSqlFragments()).parse();
        }
        session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        productMapper = session.getMapper(ProductMapper.class);
        try (Statement statement = session.getConnection().createStatement()) {
            statement.execute("""
                    CREATE TABLE product (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        image_url VARCHAR(255),
                        is_deleted INT NOT NULL
                    )
                    """);
        }
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    void shouldReturnOnlyCandidateUrlsAsStrings() throws SQLException {
        insertProduct("/uploads/products/first.png", 0);
        insertProduct("/uploads/products/second.png", 0);
        insertProduct("/uploads/products/outside-candidates.png", 0);

        List<String> referenced = productMapper.selectReferencedImageUrls(List.of(
                "/uploads/products/first.png", "/uploads/products/second.png", "/uploads/products/orphan.png"
        ));

        assertEquals(2, referenced.size());
        assertEquals(Set.of("/uploads/products/first.png", "/uploads/products/second.png"),
                Set.copyOf(referenced));
    }

    @Test
    void shouldIgnoreImagesReferencedOnlyByDeletedProducts() throws SQLException {
        insertProduct("/uploads/products/deleted.png", 1);
        insertProduct("/uploads/products/another-deleted.png", 2);

        List<String> referenced = productMapper.selectReferencedImageUrls(List.of(
                "/uploads/products/deleted.png", "/uploads/products/another-deleted.png"
        ));

        assertTrue(referenced.isEmpty());
    }

    @Test
    void shouldKeepReferenceWhenAnotherProductUsingSameUrlIsDeleted() throws SQLException {
        insertProduct("/uploads/products/shared.png", 0);
        insertProduct("/uploads/products/shared.png", 1);

        List<String> referenced = productMapper.selectReferencedImageUrls(
                List.of("/uploads/products/shared.png")
        );

        assertEquals(List.of("/uploads/products/shared.png"), referenced);
    }

    @Test
    void shouldReturnSharedUrlReferencedByMultipleActiveProducts() throws SQLException {
        insertProduct("/uploads/products/shared.png", 0);
        insertProduct("/uploads/products/shared.png", 0);

        List<String> referenced = productMapper.selectReferencedImageUrls(
                List.of("/uploads/products/shared.png")
        );

        assertEquals(Set.of("/uploads/products/shared.png"), Set.copyOf(referenced));
    }

    @Test
    void shouldReturnEmptyListWhenNoProductsMatchCandidates() throws SQLException {
        insertProduct("/uploads/products/another.png", 0);

        List<String> referenced = productMapper.selectReferencedImageUrls(
                List.of("/uploads/products/orphan.png")
        );

        assertTrue(referenced.isEmpty());
    }

    private void insertProduct(String imageUrl, int isDeleted) throws SQLException {
        try (PreparedStatement statement = session.getConnection().prepareStatement(
                "INSERT INTO product (image_url, is_deleted) VALUES (?, ?)")) {
            statement.setString(1, imageUrl);
            statement.setInt(2, isDeleted);
            statement.executeUpdate();
        }
    }
}
