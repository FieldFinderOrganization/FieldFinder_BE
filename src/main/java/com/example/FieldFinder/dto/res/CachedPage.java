package com.example.FieldFinder.dto.res;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

/**
 * Jackson-friendly page wrapper for caching. Spring's PageImpl has no default
 * constructor, so GenericJackson2JsonRedisSerializer can serialize but cannot
 * deserialize back. This record solves that.
 */
public record CachedPage<T>(
        List<T> content,
        int number,
        int size,
        long totalElements,
        int totalPages,
        boolean last,
        boolean first,
        int numberOfElements,
        boolean empty
) {

    @JsonCreator
    public CachedPage(
            @JsonProperty("content") List<T> content,
            @JsonProperty("number") int number,
            @JsonProperty("size") int size,
            @JsonProperty("totalElements") long totalElements,
            @JsonProperty("totalPages") int totalPages,
            @JsonProperty("last") boolean last,
            @JsonProperty("first") boolean first,
            @JsonProperty("numberOfElements") int numberOfElements,
            @JsonProperty("empty") boolean empty) {
        this.content = content;
        this.number = number;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalPages;
        this.last = last;
        this.first = first;
        this.numberOfElements = numberOfElements;
        this.empty = empty;
    }

    public static <T> CachedPage<T> from(Page<T> page) {
        return new CachedPage<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast(),
                page.isFirst(),
                page.getNumberOfElements(),
                page.isEmpty()
        );
    }

    public Page<T> toPage() {
        Pageable pageable = size > 0 ? PageRequest.of(number, size, Sort.unsorted()) : Pageable.unpaged();
        return new PageImpl<>(content, pageable, totalElements);
    }

    public boolean hasNext() {
        return !last;
    }
}
