package com.example.FieldFinder.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class OpenWeatherService {

    // Thay thế bằng khóa API OpenWeatherMap của bạn
    private static final String WEATHER_API_KEY = "7b371e851bb81069b5aa19ba5a85918e";
    private static final String BASE_URL = "http://api.openweathermap.org/data/2.5/weather";

    private final OkHttpClient client = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public String getCurrentWeather(String city) throws IOException {
        String url = String.format("%s?q=%s&appid=%s&units=metric&lang=vi",
                BASE_URL, city, WEATHER_API_KEY);

        Request request = new Request.Builder().url(url).build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                // Nếu API trả lỗi (ví dụ: 404 Not Found), ném IOException
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