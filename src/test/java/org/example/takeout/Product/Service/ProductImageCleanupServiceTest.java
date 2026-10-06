package org.example.takeout.Product.Service;

import org.example.takeout.Product.Mapper.ProductMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDirFactory;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductImageCleanupServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-30T08:00:00Z");
    private static final String IMAGE_URL_PREFIX = "/uploads/products/";

    @TempDir(factory = BuildDirectoryTempDirFactory.class)
    private Path tempDir;

    @Mock
    private ProductMapper productMapper;

    private Path uploadDir;
    private ProductImageCleanupService cleanupService;

    @BeforeEach
    void setUp() throws IOException {
        uploadDir = Files.createDirectory(tempDir.resolve("products"));
        cleanupService = new ProductImageCleanupService(productMapper);
    }

    @Test
    void shouldDeleteOnlyExpiredUnreferencedImages() throws IOException {
        Path orphan = image("orphan.png", Duration.ofHours(25));
        Path referenced = image("referenced.png", Duration.ofHours(48));
        Path recent = image("recent.png", Duration.ofHours(23));
        Path directory = Files.createDirectory(uploadDir.resolve("nested"));
        Path nestedImage = Files.writeString(directory.resolve("nested.png"), "nested image");
        Files.setLastModifiedTime(nestedImage, FileTime.from(NOW.minus(Duration.ofDays(2))));
        Files.setLastModifiedTime(directory, FileTime.from(NOW.minus(Duration.ofDays(2))));

        when(productMapper.selectReferencedImageUrls(anyCollection())).thenAnswer(invocation -> {
            // candidates 会在查询后被修改，因此在调用时检查其内容。
            assertEquals(Set.of(url(orphan), url(referenced)), Set.copyOf(invocation.getArgument(0)));
            return List.of(url(referenced), url(referenced));
        });

        runCleanup();

        assertFalse(Files.exists(orphan));
        assertTrue(Files.exists(referenced));
        assertTrue(Files.exists(recent));
        assertTrue(Files.isDirectory(directory));
        assertTrue(Files.exists(nestedImage));
        verify(productMapper).selectReferencedImageUrls(anyCollection());
    }

    @Test
    void shouldKeepImageExactly24HoursOld() throws IOException {
        Path boundary = image("boundary.png", Duration.ofHours(24));

        runCleanup();

        assertTrue(Files.exists(boundary));
        verifyNoInteractions(productMapper);
    }

    @Test
    void shouldSkipDatabaseWhenDirectoryIsEmpty() {
        runCleanup();

        verifyNoInteractions(productMapper);
    }

    @Test
    void shouldSkipDatabaseWhenNoFilesAreExpired() throws IOException {
        Path recent = image("recent.png", Duration.ofHours(1));
        Path directory = Files.createDirectory(uploadDir.resolve("nested"));
        Files.setLastModifiedTime(directory, FileTime.from(NOW.minus(Duration.ofDays(2))));

        runCleanup();

        assertTrue(Files.exists(recent));
        assertTrue(Files.isDirectory(directory));
        verifyNoInteractions(productMapper);
    }

    @Test
    void shouldHandleMissingUploadDirectoryWithoutQueryingDatabase() {
        uploadDir = tempDir.resolve("missing");

        assertDoesNotThrow(this::runCleanup);

        verifyNoInteractions(productMapper);
    }

    @Test
    void shouldKeepAllFilesWhenDatabaseQueryFails() throws IOException {
        Path first = image("first.png", Duration.ofDays(2));
        Path second = image("second.png", Duration.ofDays(2));
        IllegalStateException failure = new IllegalStateException("database unavailable");
        when(productMapper.selectReferencedImageUrls(anyCollection())).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, this::runCleanup));

        assertTrue(Files.exists(first));
        assertTrue(Files.exists(second));
    }

    @Test
    void shouldContinueDeletingOtherImagesWhenOneDeletionFails() throws IOException {
        Path first = image("first.png", Duration.ofDays(2));
        Path second = image("second.png", Duration.ofDays(2));
        AtomicReference<Path> failedDeletion = new AtomicReference<>();
        when(productMapper.selectReferencedImageUrls(anyCollection())).thenReturn(List.of());

        // 不依赖 HashSet 顺序：让第一次删除失败，确认后续删除仍然执行。
        try (MockedStatic<Files> files = mockStatic(Files.class, invocation -> {
            if (invocation.getMethod().getName().equals("deleteIfExists")) {
                Path path = invocation.getArgument(0);
                if ((path.equals(first) || path.equals(second))
                        && failedDeletion.compareAndSet(null, path)) {
                    throw new IOException("file is locked");
                }
            }
            return invocation.callRealMethod();
        })) {
            assertDoesNotThrow(this::runCleanup);

            files.verify(() -> Files.deleteIfExists(first));
            files.verify(() -> Files.deleteIfExists(second));
        }

        assertNotNull(failedDeletion.get());
        assertTrue(Files.exists(failedDeletion.get()));
        Path deleted = failedDeletion.get().equals(first) ? second : first;
        assertFalse(Files.exists(deleted));
    }

    @Test
    void shouldKeepFilesWhenOpeningDirectoryFails() throws IOException {
        Path orphan = image("orphan.png", Duration.ofDays(2));

        try (MockedStatic<Files> files = failFileOperation(
                "newDirectoryStream", uploadDir, "directory cannot be read")) {
            assertDoesNotThrow(this::runCleanup);
        }

        assertTrue(Files.exists(orphan));
        verifyNoInteractions(productMapper);
    }

    @Test
    void shouldHandleFailureToReadImageModificationTime() throws IOException {
        Path orphan = image("orphan.png", Duration.ofDays(2));

        try (MockedStatic<Files> files = failFileOperation(
                "getLastModifiedTime", orphan, "modification time cannot be read")) {
            assertDoesNotThrow(this::runCleanup);
        }

        assertTrue(Files.exists(orphan));
        verifyNoInteractions(productMapper);
    }

    @Test
    void shouldHandleImageDisappearingAfterScan() throws IOException {
        Path disappearing = image("disappearing.png", Duration.ofDays(2));
        Path other = image("other.png", Duration.ofDays(2));
        when(productMapper.selectReferencedImageUrls(anyCollection())).thenAnswer(invocation -> {
            Files.delete(disappearing);
            return List.of();
        });

        assertDoesNotThrow(this::runCleanup);

        assertFalse(Files.exists(disappearing));
        assertFalse(Files.exists(other));
    }

    @Test
    void shouldBeSafeToRunAgainAfterOrphansAreDeleted() throws IOException {
        Path orphan = image("orphan.png", Duration.ofDays(2));
        when(productMapper.selectReferencedImageUrls(anyCollection())).thenReturn(List.of());

        runCleanup();
        assertDoesNotThrow(this::runCleanup);

        assertFalse(Files.exists(orphan));
        verify(productMapper, times(1)).selectReferencedImageUrls(anyCollection());
    }

    private Path image(String filename, Duration age) throws IOException {
        Path path = Files.writeString(uploadDir.resolve(filename), "test image");
        Files.setLastModifiedTime(path, FileTime.from(NOW.minus(age)));
        return path;
    }

    private String url(Path path) {
        return IMAGE_URL_PREFIX + path.getFileName();
    }

    private MockedStatic<Files> failFileOperation(String method, Path path, String message) {
        // 仅指定操作失败，其他 NIO 操作仍真实执行；配置 mock 时不提前执行文件操作。
        return mockStatic(Files.class, invocation -> {
            if (invocation.getMethod().getName().equals(method)
                    && invocation.getArguments().length > 0
                    && path.equals(invocation.getArgument(0))) {
                throw new IOException(message);
            }
            return invocation.callRealMethod();
        });
    }

    private void runCleanup() {
        // 只重定向上传目录和当前时间；扫描与删除使用真实的临时文件。
        try (MockedStatic<Paths> paths = mockStatic(Paths.class, CALLS_REAL_METHODS);
             MockedStatic<Instant> clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            paths.when(() -> Paths.get("./uploads/products")).thenReturn(uploadDir);
            clock.when(Instant::now).thenReturn(NOW);
            cleanupService.cleanupOrphanImages();
        }
    }

    static class BuildDirectoryTempDirFactory implements TempDirFactory {
        @Override
        public Path createTempDirectory(AnnotatedElementContext elementContext,
                                        ExtensionContext extensionContext) throws IOException {
            // JUnit normalizes paths during cleanup. Restricted Windows sessions
            // allow this inside the workspace but deny it in the system Temp dir.
            Path root = Files.createDirectories(Path.of("target", "product-image-cleanup-tests"));
            return Files.createTempDirectory(root, "junit-");
        }
    }
}
