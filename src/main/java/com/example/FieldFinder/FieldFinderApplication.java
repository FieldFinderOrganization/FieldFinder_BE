package com.example.FieldFinder;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FieldFinderApplication {

	public static void main(String[] args) {
		Dotenv dotenv = Dotenv.load();
		System.setProperty("YOUR_CLIENT_ID",dotenv.get("YOUR_CLIENT_ID"));
		System.setProperty("YOUR_API_KEY",dotenv.get("YOUR_API_KEY"));
		System.setProperty("YOUR_CHECKSUM_KEY",dotenv.get("YOUR_CHECKSUM_KEY"));
		System.setProperty("DB_URL", dotenv.get("DB_URL"));
		System.setProperty("DB_USERNAME", dotenv.get("DB_USERNAME"));
		System.setProperty("DB_PASSWORD", dotenv.get("DB_PASSWORD"));
		SpringApplication.run(FieldFinderApplication.class, args);
	}

}
