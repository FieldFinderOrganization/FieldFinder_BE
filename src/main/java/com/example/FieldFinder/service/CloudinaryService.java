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
        Map params = ObjectUtils.asMap(
                "folder", "field_finder_products",
                "resource_type", "image",
                // Transcode on upload → never store AVIF/WebP, which older Android
                // image decoders can't render (broken-image box in the app).
                "format", "jpg"
        );

        Map uploadResult = cloudinary.uploader().upload(file.getBytes(), params);

        return buildResponseMap(uploadResult);
    }

    public Map<String, Object> uploadChatImage(MultipartFile file, String senderId) throws IOException {
        Map params = ObjectUtils.asMap(
                "folder", "field_finder_chat/" + senderId,
                "resource_type", "image"
        );

        Map uploadResult = cloudinary.uploader().upload(file.getBytes(), params);

        return buildResponseMap(uploadResult);
    }

    public Map<String, Object> uploadChatVideo(MultipartFile file, String senderId) throws IOException {
        Map params = ObjectUtils.asMap(
                "folder", "field_finder_chat/" + senderId,
                "resource_type", "video"
        );

        Map uploadResult = cloudinary.uploader().upload(file.getBytes(), params);

        return buildResponseMap(uploadResult);
    }

    private Map<String, Object> buildResponseMap(Map uploadResult) {
        Map<String, Object> result = new HashMap<>();
        // secure_url = https (Android blocks cleartext http on API 28+); url = http.
        result.put("url", uploadResult.get("secure_url"));
        result.put("public_id", uploadResult.get("public_id"));
        return result;
    }
}