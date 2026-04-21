package com.yizhaoqi.smartpai.service.creator;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CreatorServicePageableTest {

    @Test
    void defaultsWhenInvalid() {
        Pageable p = CreatorService.defaultPageable(-1, 0, null);
        assertEquals(0, p.getPageNumber());
        assertEquals(20, p.getPageSize());
        Sort.Order o = p.getSort().iterator().next();
        assertEquals("id", o.getProperty());
        assertEquals(Sort.Direction.DESC, o.getDirection());
    }

    @Test
    void capsPageSize() {
        Pageable p = CreatorService.defaultPageable(3, 9999, "followers:asc");
        assertEquals(3, p.getPageNumber());
        assertEquals(200, p.getPageSize());
        Sort.Order o = p.getSort().iterator().next();
        assertEquals("followers", o.getProperty());
        assertEquals(Sort.Direction.ASC, o.getDirection());
    }

    @Test
    void prefixSyntaxWorks() {
        Pageable p = CreatorService.defaultPageable(0, 10, "+updatedAt");
        Sort.Order o = p.getSort().iterator().next();
        assertEquals("updatedAt", o.getProperty());
        assertEquals(Sort.Direction.ASC, o.getDirection());
    }

    @Test
    void descSuffix() {
        Pageable p = CreatorService.defaultPageable(0, 10, "avgLikes:desc");
        Sort.Order o = p.getSort().iterator().next();
        assertEquals("avgLikes", o.getProperty());
        assertEquals(Sort.Direction.DESC, o.getDirection());
    }
}
