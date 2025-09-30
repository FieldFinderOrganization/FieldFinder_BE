package com.example.FieldFinder.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.IOException;

@Configuration
public class FirebaseConfig {
    @Bean
    public FirebaseApp firebaseApp() throws IOException {
        FileInputStream serviceAccount = new FileInputStream("src/main/resources/firebase/serviceAccountKey.json");

        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                .setDatabaseUrl("https://fieldfinder-c3a4b.firebaseio.com")
                .build();

        return FirebaseApp.initializeApp(options);
    }
}
