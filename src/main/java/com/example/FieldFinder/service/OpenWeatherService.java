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

    private static final String BASE_URL = "http://api.openweathermap.org/data/2.5/weather";

    private final OkHttpClient client = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public String getCurrentWeather(String city) throws IOException {
        String url = String.format("%s?q=%s&appid=%s&units=metric&lang=vi",
                BASE_URL, city, weatherApiKey);

        Request request = new Request.Builder().url(url).build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Không tìm thấy dữ liệu thời tiết cho thành phố này: " + city);
            }

            String json = response.body().string();
            JsonNode root = mapper.readTree(json);

            String description = root.at("/weather/0/description").asText();
            double temp = root.at("/main/temp").asDouble();

            return String.format("%s, nhiệt độ khoảng %.1f°C.",
                    description.substring(0, 1).toUpperCase() + description.substring(1), // Viết hoa chữ cái đầu
                    temp);
        }
    }
}