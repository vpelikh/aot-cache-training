package example;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Black-box integration tests used as the out-of-process AOT cache training workload. The
 * application under test runs in its own JVM; these tests drive it over HTTP. The base URL
 * is exposed by the training launcher through the {@code aot.training.url} system property.
 *
 * <p>These tests are skipped by the normal {@code test} phase, which has no application
 * running. They only run under the out-of-process training launcher, which sets the
 * property.
 */
class ExampleApplicationHttpTests {

    @Test
    void greetsOverHttp() throws Exception {
        String baseUrl = System.getProperty("aot.training.url");
        assumeTrue(baseUrl != null, "no application is running (this test runs under the record goal)");
        HttpClient client = HttpClient.newHttpClient();
        for (int i = 0; i < 3; i++) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("Hello");
        }
    }

}