package com.example.FieldFinder.service.impl;

import com.example.FieldFinder.Enum.CategoryType;
import com.example.FieldFinder.dto.req.CategoryRequestDTO;
import com.example.FieldFinder.dto.res.CategoryResponseDTO;
import com.example.FieldFinder.entity.Category;
import com.example.FieldFinder.repository.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CategoryServiceImplTest {

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private CategoryServiceImpl categoryService;

    private CategoryRequestDTO requestDTO;
    private Category parentCategory;
    private Category category;

    @BeforeEach
    void setUp() {
        requestDTO = new CategoryRequestDTO();
        requestDTO.setName("Thể thao");
        requestDTO.setDescription("Danh mục thể thao");
        requestDTO.setCategoryType(CategoryType.STANDARD);

        parentCategory = Category.builder()
                .name("Giải trí")
                .description("Danh mục giải trí")
                .categoryId(1L)
                .build();

        category = new Category();
        category.setCategoryId(2L);
        category.setName("Thể thao");
        category.setDescription("Danh mục thể thao");
        category.setParent(parentCategory);
    }

    @Nested
    class createCategory {
        @Test
        void success_WithoutParent() {
            requestDTO.setParentId(null);
            when(categoryRepository.existsByName(requestDTO.getName())).thenReturn(false);

            CategoryResponseDTO result = categoryService.createCategory(requestDTO);

            assertNotNull(result);
            assertEquals("Thể thao", result.getName());
            assertNull(result.getParentName());

            verify(categoryRepository, times(1)).save(any(Category.class));
        }

        @Test
        void success_WithParent() {
            requestDTO.setParentId(1L);
            when(categoryRepository.existsByName(requestDTO.getName())).thenReturn(false);
            when(categoryRepository.findById(requestDTO.getParentId())).thenReturn(Optional.of(parentCategory));

            CategoryResponseDTO result = categoryService.createCategory(requestDTO);

            assertNotNull(result);
            assertEquals("Thể thao", result.getName());
            assertEquals("Giải trí", result.getParentName());
            assertEquals("Danh mục thể thao", result.getDescription());
            assertEquals(CategoryType.STANDARD, result.getCategoryType());

            verify(categoryRepository, times(1)).save(any(Category.class));
        }

        @Test
        void nameAlreadyExists_ThrowsException() {
            when(categoryRepository.existsByName(requestDTO.getName())).thenReturn(true);

            RuntimeException exception = assertThrows(RuntimeException.class, () -> categoryService.createCategory(requestDTO));

            assertTrue(exception.getMessage().contains("Category already exists!"));

            verify(categoryRepository, never()).findById(requestDTO.getParentId());
            verify(categoryRepository, never()).save(any());
        }

        @Test
        void parentNotFound_ThrowsException() {
            requestDTO.setParentId(99L);
            when(categoryRepository.existsByName(requestDTO.getName())).thenReturn(false);
            when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

            RuntimeException exception = assertThrows(RuntimeException.class, () -> categoryService.createCategory(requestDTO));

            assertTrue(exception.getMessage().contains("Cannot find parent category!"));

            verify(categoryRepository, never()).save(any());
        }
    }

    @Nested
    class getAllCategories {
        @Test
        void hasData_ReturnsList() {
            when(categoryRepository.findAll()).thenReturn(List.of(parentCategory));

            List<CategoryResponseDTO> result = categoryService.getAllCategories();

            assertNotNull(result);
            assertEquals(1, result.size());

            assertEquals("Giải trí", result.getFirst().getName());
            assertEquals(1L, result.getFirst().getId());

            verify(categoryRepository, times(1)).findAll();
        }

        @Test
        void noData_ReturnsEmptyList() {
            when(categoryRepository.findAll()).thenReturn(List.of());

            List<CategoryResponseDTO> result = categoryService.getAllCategories();

            assertNotNull(result);
            assertTrue(result.isEmpty());

            verify(categoryRepository, times(1)).findAll();
        }

    }

    @Nested
    class getCategoryById {
        @Test
        void hasData_ReturnsResponseDTO() {
            when(categoryRepository.findById(category.getCategoryId())).thenReturn(Optional.of(category));

            CategoryResponseDTO result = categoryService.getCategoryById(category.getCategoryId());

            assertNotNull(result);
            assertEquals("Thể thao", result.getName());

            verify(categoryRepository, times(1)).findById(category.getCategoryId());
        }

        @Test
        void hasNoData_ThrowsException() {
            when(categoryRepository.findById(category.getCategoryId())).thenReturn(Optional.empty());

            RuntimeException exception = assertThrows(RuntimeException.class, () -> categoryService.getCategoryById(category.getCategoryId()));

            assertTrue(exception.getMessage().contains("Cannot find category!"));

            verify(categoryRepository,times(1)).findById(category.getCategoryId());
        }
    }

    @Nested
    class updateCategory {
        @Test
        void success_WithoutParent() {
            when(categoryRepository.findById(category.getCategoryId())).thenReturn(Optional.of(category));

            requestDTO.setName("Bóng đá");
            requestDTO.setDescription("Danh mục bóng đá");

            CategoryResponseDTO result = categoryService.updateCategory(category.getCategoryId(), requestDTO);

            assertNotNull(result);
            assertEquals("Bóng đá", result.getName());
            assertEquals("Danh mục bóng đá", result.getDescription());
            assertNull(result.getParentName());

            verify(categoryRepository, times(1)).save(any(Category.class));
        }

        @Test
        void success_WithParent() {
            requestDTO.setParentId(1L);
            when(categoryRepository.findById(category.getCategoryId())).thenReturn(Optional.of(category));
            when(categoryRepository.findById(requestDTO.getParentId())).thenReturn(Optional.of(parentCategory));

            requestDTO.setName("Bóng đá");
            requestDTO.setDescription("Danh mục bóng đá");

            CategoryResponseDTO result = categoryService.updateCategory(category.getCategoryId(), requestDTO);

            assertNotNull(result);
            assertEquals("Bóng đá", result.getName());
            assertEquals("Danh mục bóng đá", result.getDescription());
            assertEquals("Giải trí", result.getParentName());

            verify(categoryRepository, times(1)).save(any(Category.class));
        }

        @Test
        void categoryNotFound_ThrowsException() {
            when(categoryRepository.findById(category.getCategoryId())).thenReturn(Optional.empty());

            RuntimeException exception = assertThrows(RuntimeException.class, () -> categoryService.updateCategory(category.getCategoryId(), requestDTO));

            assertTrue(exception.getMessage().contains("Cannot find category"));

            verify(categoryRepository, never()).findById(requestDTO.getParentId());
            verify(categoryRepository, never()).save(any(Category.class));
        }

        @Test
        void parentNotFound() {
            when(categoryRepository.findById(category.getCategoryId())).thenReturn(Optional.of(category));
            requestDTO.setParentId(90L);
            when(categoryRepository.findById(90L)).thenReturn(Optional.empty());

            RuntimeException e = assertThrows(RuntimeException.class, () -> categoryService.updateCategory(category.getCategoryId(), requestDTO));

            assertTrue(e.getMessage().contains("Cannot find parent category!"));

            verify(categoryRepository, never()).save(any(Category.class));
        }
    }
}