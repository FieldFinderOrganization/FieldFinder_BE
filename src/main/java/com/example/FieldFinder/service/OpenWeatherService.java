package com.example.FieldFinder.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class OpenWeatherService {

    @Value("${weather_api_key}")
    private String weatherApiKey;

    @Value("${OPEN_WEATHER_URL}")
    private String openWeatherUrl;

    private final OkHttpClient client = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private String cachedDefaultWeather = "Trời quang, nhiệt độ khoảng 28°C";
    private long lastFetchTime = 0;
    private static final long CACHE_DURATION_MS = 15 * 60 * 1000;

    public String getCurrentWeather(String city) throws IOException {
        String encodedCity = java.net.URLEncoder.encode(city, java.nio.charset.StandardCharsets.UTF_8);
        String url = String.format("%s?q=%s&appid=%s&units=metric&lang=vi",
                openWeatherUrl, encodedCity, weatherApiKey);

        Request request = new Request.Builder().url(url).build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Không tìm thấy dữ liệu thời tiết cho thành phố này: " + city);
            }

            assert response.body() != null;
            String json = response.body().string();
            JsonNode root = mapper.readTree(json);

            String description = root.at("/weather/0/description").asText();
            double temp = root.at("/main/temp").asDouble();

            return String.format("%s, nhiệt độ khoảng %.1f°C.",
                    description.substring(0, 1).toUpperCase() + description.substring(1), // Viết hoa chữ cái đầu
                    temp);
        }
    }

    // Hàm riêng cho AOP gọi (Sử dụng Cache)
    public String getCachedDefaultWeather() {
        long now = System.currentTimeMillis();
        if (now - lastFetchTime > CACHE_DURATION_MS) {
            try {
                // Tạm thời hardcode HCM, sau này có thể lấy từ Profile User
                cachedDefaultWeather = getCurrentWeather("Ho Chi Minh");
                lastFetchTime = now;
            } catch (Exception e) {
                System.err.println("Lỗi làm mới cache thời tiết: " + e.getMessage());
            }
        }
        return cachedDefaultWeather;
    }
}