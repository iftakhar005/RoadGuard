package com.roadguard.service;

import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.enums.Role;
import com.roadguard.repository.MechanicProfileRepository;
import com.roadguard.security.AuthUser;
import com.roadguard.web.dto.ShopRequest;
import com.roadguard.web.dto.ShopResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShopService {

    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp");

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".webp");

    private final MechanicProfileRepository mechanics;

    @Value("${app.uploads.dir:uploads}")
    private String uploadsDir;

    @Transactional
    public ShopResponse saveShop(AuthUser caller, ShopRequest req) {
        MechanicProfile profile = profileOf(caller);

        profile.setShopName(req.shopName().trim());
        profile.setShopType(req.shopType());
        profile.setContactPhone(req.contactPhone().trim());
        profile.setShopAddress(req.shopAddress() == null ? null : req.shopAddress().trim());
        profile.setShopLat(req.shopLat());
        profile.setShopLng(req.shopLng());

        mechanics.save(profile);
        return ShopResponse.from(profile);
    }

    @Transactional(readOnly = true)
    public ShopResponse myShop(AuthUser caller) {
        return ShopResponse.from(profileOf(caller));
    }

    @Transactional(readOnly = true)
    public List<ShopResponse> allShops() {
        return mechanics.findByShopNameIsNotNullAndShopLatIsNotNull()
                .stream()
                .map(ShopResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ShopResponse shopById(Long profileId) {
        MechanicProfile profile = mechanics.findById(profileId)
                .orElseThrow(() -> new IllegalArgumentException("No shop with id " + profileId));
        if (!profile.hasShop()) {
            throw new IllegalArgumentException("That mechanic has not set up a shop yet");
        }
        return ShopResponse.from(profile);
    }

    @Transactional
    public ShopResponse saveImage(AuthUser caller, MultipartFile file) {
        MechanicProfile profile = profileOf(caller);

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Choose a picture to upload");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("That picture is larger than 5 MB");
        }

        String extension = extensionFor(file);
        Path target = uploadsPath().resolve("shop-" + profile.getId() + extension);

        try {
            Files.createDirectories(target.getParent());
            deleteExistingImage(profile);
            try (var in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not save that picture");
        }

        profile.setShopImagePath(target.getFileName().toString());
        mechanics.save(profile);
        return ShopResponse.from(profile);
    }

    @Transactional(readOnly = true)
    public StoredImage imageFor(Long profileId) {
        MechanicProfile profile = mechanics.findById(profileId)
                .orElseThrow(() -> new IllegalArgumentException("No shop with id " + profileId));

        String name = profile.getShopImagePath();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("That shop has no picture");
        }

        Path file = uploadsPath().resolve(name).normalize();
        if (!file.startsWith(uploadsPath()) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("That picture is missing");
        }
        return new StoredImage(file, contentTypeFor(name));
    }

    private void deleteExistingImage(MechanicProfile profile) {
        String existing = profile.getShopImagePath();
        if (existing == null || existing.isBlank()) {
            return;
        }
        try {
            Path old = uploadsPath().resolve(existing).normalize();
            if (old.startsWith(uploadsPath())) {
                Files.deleteIfExists(old);
            }
        } catch (IOException e) {
            log.debug("Could not remove the old shop picture: {}", e.toString());
        }
    }

    private String extensionFor(MultipartFile file) {
        String declared = file.getContentType();
        if (declared != null && ALLOWED_TYPES.containsKey(declared.toLowerCase(Locale.ROOT))) {
            return ALLOWED_TYPES.get(declared.toLowerCase(Locale.ROOT));
        }

        String original = file.getOriginalFilename();
        if (original != null) {
            int dot = original.lastIndexOf('.');
            if (dot >= 0) {
                String ext = original.substring(dot).toLowerCase(Locale.ROOT);
                if (ALLOWED_EXTENSIONS.contains(ext)) {
                    return ext.equals(".jpeg") ? ".jpg" : ext;
                }
            }
        }
        throw new IllegalArgumentException("Only JPG, PNG or WEBP pictures are allowed");
    }

    private String contentTypeFor(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }

    private Path uploadsPath() {
        return Paths.get(uploadsDir, "shops").toAbsolutePath().normalize();
    }

    private MechanicProfile profileOf(AuthUser caller) {
        if (caller.getRole() != Role.MECHANIC) {
            throw new AccessDeniedException("Only a mechanic has a shop");
        }
        return mechanics.findByUserId(caller.getId())
                .orElseThrow(() -> new IllegalStateException("No mechanic profile for this account"));
    }

    public record StoredImage(Path path, String contentType) {
    }
}
