package com.roadguard.web;

import com.roadguard.security.AuthUser;
import com.roadguard.service.ShopService;
import com.roadguard.web.dto.ShopResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/shops")
@RequiredArgsConstructor
public class ShopController {

    private final ShopService shops;

    @GetMapping
    public ResponseEntity<List<ShopResponse>> all(@AuthenticationPrincipal AuthUser caller) {
        return ResponseEntity.ok(shops.allShops());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ShopResponse> byId(@PathVariable Long id) {
        return ResponseEntity.ok(shops.shopById(id));
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<Resource> image(@PathVariable Long id) {
        ShopService.StoredImage stored = shops.imageFor(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(stored.contentType()))
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES))
                .body(new FileSystemResource(stored.path()));
    }
}
