package example;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests used as the AOT cache training workload. Running these is what the
 * {@code aotCacheTraining} task does, on a JAR-only class path.
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