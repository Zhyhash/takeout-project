package org.example.takeout.Category.Service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.example.takeout.Category.Entity.Category;
import org.example.takeout.Category.Mapper.CategoryConverter;
import org.example.takeout.Category.Mapper.CategoryMapper;
import org.example.takeout.Category.StatusEnum.CategoryDefaultEnum;
import org.example.takeout.Category.StatusEnum.CategoryStatusEnum;
import org.example.takeout.Common.Exception.BusinessException;
import org.example.takeout.Common.Utils.Context.MerchantContextHolder;
import org.example.takeout.Product.Service.ProductCommandService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    private static final long MERCHANT_ID = 201L;
    private static final long SOURCE_CATEGORY_ID = 301L;
    private static final long DEFAULT_CATEGORY_ID = 302L;

    @Mock
    private CategoryMapper categoryMapper;
    @Mock
    private CategoryConverter categoryConverter;
    @Mock
    private ProductCommandService productCommandService;
    @InjectMocks
    private CategoryService categoryService;

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Category.class);
    }

    @BeforeEach
    void setUp() {
        MerchantContextHolder.setMerchantId(MERCHANT_ID);
    }

    @AfterEach
    void tearDown() {
        MerchantContextHolder.clear();
    }

    @Test
    void deleteMigratesProductsThroughCommandServiceBeforeDeletingCategory() {
        when(categoryMapper.selectOne(any())).thenReturn(
                category(SOURCE_CATEGORY_ID, CategoryDefaultEnum.CLASSIFICATION.getCode()),
                category(DEFAULT_CATEGORY_ID, CategoryDefaultEnum.DEFAULT.getCode()));

        categoryService.deleteById(SOURCE_CATEGORY_ID);

        var order = inOrder(productCommandService, categoryMapper);
        order.verify(productCommandService).migrateProductsToCategory(
                MERCHANT_ID, SOURCE_CATEGORY_ID, DEFAULT_CATEGORY_ID);
        order.verify(categoryMapper).deleteById(SOURCE_CATEGORY_ID);
    }

    @Test
    void failedProductMigrationPreventsCategoryDeletion() {
        when(categoryMapper.selectOne(any())).thenReturn(
                category(SOURCE_CATEGORY_ID, CategoryDefaultEnum.CLASSIFICATION.getCode()),
                category(DEFAULT_CATEGORY_ID, CategoryDefaultEnum.DEFAULT.getCode()));
        var migrationFailure = new IllegalStateException("migration failed");
        doThrow(migrationFailure).when(productCommandService).migrateProductsToCategory(
                MERCHANT_ID, SOURCE_CATEGORY_ID, DEFAULT_CATEGORY_ID);

        assertSame(migrationFailure, assertThrows(IllegalStateException.class,
                () -> categoryService.deleteById(SOURCE_CATEGORY_ID)));

        verify(categoryMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void defaultCategoryCannotBeMigratedOrDeleted() {
        when(categoryMapper.selectOne(any())).thenReturn(
                category(DEFAULT_CATEGORY_ID, CategoryDefaultEnum.DEFAULT.getCode()));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> categoryService.deleteById(DEFAULT_CATEGORY_ID));

        assertEquals("默认分类不能删除", exception.getMessage());
        verifyNoInteractions(productCommandService);
        verify(categoryMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void missingDefaultCategoryCannotDeleteSourceCategory() {
        when(categoryMapper.selectOne(any())).thenReturn(
                category(SOURCE_CATEGORY_ID, CategoryDefaultEnum.CLASSIFICATION.getCode()), null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> categoryService.deleteById(SOURCE_CATEGORY_ID));

        assertEquals("默认分类不存在", exception.getMessage());
        verifyNoInteractions(productCommandService);
        verify(categoryMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void categoryLockIsScopedToActiveCategoryAndOwningMerchant() {
        Category expected = category(SOURCE_CATEGORY_ID, CategoryDefaultEnum.CLASSIFICATION.getCode());
        when(categoryMapper.selectOne(any())).thenReturn(expected);

        assertSame(expected, categoryService.lockActiveCategory(SOURCE_CATEGORY_ID, MERCHANT_ID));

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Wrapper<Category>> captor = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(categoryMapper).selectOne(captor.capture());
        AbstractWrapper<?, ?, ?> query = (AbstractWrapper<?, ?, ?>) captor.getValue();
        String sql = query.getSqlSegment().toUpperCase();
        assertTrue(sql.contains("FOR UPDATE"));
        assertTrue(sql.contains("MERCHANT_ID"));
        assertTrue(sql.contains("STATUS"));
        assertTrue(query.getParamNameValuePairs().containsValue(MERCHANT_ID));
        assertTrue(query.getParamNameValuePairs().containsValue(SOURCE_CATEGORY_ID));
        assertTrue(query.getParamNameValuePairs().containsValue(CategoryStatusEnum.ACTIVE.getCode()));
    }

    private Category category(long id, Integer isDefault) {
        Category category = new Category();
        category.setId(id);
        category.setMerchantId(MERCHANT_ID);
        category.setIsDefault(isDefault);
        category.setStatus(CategoryStatusEnum.ACTIVE.getCode());
        return category;
    }
}
