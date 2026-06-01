package com.example.FieldFinder;

import com.example.FieldFinder.repository.PitchRepository;
import com.example.FieldFinder.repository.ReviewRepository;
import com.example.FieldFinder.repository.ProductRepository;
import com.example.FieldFinder.repository.ProductVariantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

@SpringBootTest
class FieldFinderApplicationTests {

	@Autowired
	private PitchRepository pitchRepository;

	@Autowired
	private ReviewRepository reviewRepository;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private ProductVariantRepository productVariantRepository;

	@Test
	void contextLoads() {
		System.out.println("====== DIAGNOSTICS START ======");
		System.out.println("Pitches count: " + pitchRepository.count());
		System.out.println("Reviews count: " + reviewRepository.count());
		System.out.println("Products count: " + productRepository.count());
		System.out.println("Product variants count: " + productVariantRepository.count());

		// Test findTopRatedPitches
		try {
			java.util.UUID dummyPitchId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000000");
			var topRated = pitchRepository.findTopRatedPitches(dummyPitchId, PageRequest.of(0, 10));
			System.out.println("Top rated pitches found: " + topRated.size());
			for (var p : topRated) {
				System.out.println("  - Pitch: " + p.getName() + " (ID: " + p.getPitchId() + ")");
			}
		} catch (Exception e) {
			System.out.println("Error testing findTopRatedPitches: " + e.getMessage());
			e.printStackTrace();
		}

		// Test findTopSellingProducts
		try {
			var topSelling = productRepository.findTopSellingProducts(PageRequest.of(0, 10));
			System.out.println("Top selling products found: " + topSelling.size());
			for (var p : topSelling) {
				System.out.println("  - Product: " + p.getName() + " (ID: " + p.getProductId() + ")");
			}
		} catch (Exception e) {
			System.out.println("Error testing findTopSellingProducts: " + e.getMessage());
			e.printStackTrace();
		}
		System.out.println("====== DIAGNOSTICS END ======");
	}

}

