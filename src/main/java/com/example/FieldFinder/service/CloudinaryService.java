package com.example.FieldFinder.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CloudinaryService {

    private final Cloudinary cloudinary;

    public Map<String, Object> uploadProductImage(MultipartFile file, List<String> categories) throws IOException {
        // Let the SDK handle the list joining.
        // Do NOT manually join with "," as it might cause signature mismatches with spaces.
        Map params = ObjectUtils.asMap(
                "folder", "field_finder_products",
                "categorization", "google_tagging",
                "auto_tagging", 0.4,
                "tags", categories
        );

        // Upload to Cloudinary
        // The SDK will sort parameters and sign them automatically using your API_SECRET
        Map uploadResult = cloudinary.uploader().upload(file.getBytes(), params);

        return buildResponseMap(uploadResult);
    }

    private Map<String, Object> buildResponseMap(Map uploadResult) {
        Map<String, Object> result = new HashMap<>();
        result.put("url", uploadResult.get("url"));
        result.put("public_id", uploadResult.get("public_id"));

        if (uploadResult.containsKey("tags")) {
            result.put("tags", uploadResult.get("tags"));
        } else {
            result.put("tags", List.of());
        }

        if (uploadResult.containsKey("info")) {
            result.put("analysis", uploadResult.get("info"));
        }

        return result;
    }
}