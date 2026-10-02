package example;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests used as the AOT cache training workload. Running these through the
 * {@code aot-cache-training-maven-plugin} {@code record} goal is what produces
 * {@code target/aot-cache/application.aot}.
 */
@SpringBootTest
class ExampleApplicationTests {

	@Autowired
	private ExampleApplication application;

	@Test
	void contextLoads() {
		assertThat(this.application).isNotNull();
	}

	@Test
	void greets() {
		assertThat(this.application.hello()).isEqualTo("Hello!");
	}

}