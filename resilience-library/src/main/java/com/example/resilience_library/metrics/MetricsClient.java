package com.example.resilience_library.metrics;

import com.example.resilience_library.config.ResilienceConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cliente para obtener métricas Prometheus de los servicios y calcular latencia "delta" y errorRate.
 */
@Component
public class MetricsClient {

    private static final Logger logger = LoggerFactory.getLogger(MetricsClient.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final ResilienceConfig resilienceConfig;
    private final RetryRegistry retryRegistry;

    /**
     * Almacena el "estado anterior" de sum y count para cada servicio.
     * Key = serviceName, Value = instancia con previousSum y previousCount
     */
    private final Map<String, LatencyState> latencyStates = new ConcurrentHashMap<>();

    public MetricsClient(ResilienceConfig resilienceConfig, RetryRegistry retryRegistry) {
        this.resilienceConfig = resilienceConfig;
        this.retryRegistry = retryRegistry;
    }

    /**
     * Devuelve la latencia "instantánea" (aproximada) del servicio, calculada como
     * delta de la sum de segundos / delta de count de requests.
     *
     * @param serviceName Nombre del servicio (debe coincidir con ServiceDefinition.name).
     * @return Latencia en milisegundos (aprox), o 0.0 si no hay suficientes datos.
     */
    public double getLatency(String serviceName) {
        // Obtenemos la suma (http_server_requests_seconds_sum) y el conteo (http_server_requests_seconds_count)
        double currentSum = fetchPrometheusMetric(serviceName, "latency", "http_server_requests_seconds_sum", null);
        double currentCount = fetchPrometheusMetric(serviceName, "latency", "http_server_requests_seconds_count", null);

        // Recuperamos el estado anterior
        LatencyState state = latencyStates.computeIfAbsent(serviceName, s -> new LatencyState());

        // Calculamos la diferencia
        double deltaSum = currentSum - state.previousSum;
        double deltaCount = currentCount - state.previousCount;

        double immediateLatencyMs = 0.0;
        if (deltaSum > 0 && deltaCount > 0) {
            // Promedio de latencia en segundos
            double avgLatencySeconds = deltaSum / deltaCount;
            // Convertimos a milisegundos
            immediateLatencyMs = avgLatencySeconds * 1000.0;
        }

        // Actualizamos el estado
        state.previousSum = currentSum;
        state.previousCount = currentCount;

        // Devolvemos la latencia promedio en ms (delta)
        return immediateLatencyMs;
    }

    /**
     * Obtiene la tasa de error [0..1] = (errores / total) para el servicio especificado.
     */
    public double getErrorRate(String serviceName) {
        // totalRequests = sum de http_server_requests_seconds_count (para todos los outcomes)
        double totalRequests = fetchPrometheusMetric(serviceName, "errorRate", "http_server_requests_seconds_count", null);
        // errorRequests = sum de http_server_requests_seconds_count (CLIENT_ERROR+SERVER_ERROR)
        double errorRequests = fetchPrometheusMetric(serviceName, "errorRate", "http_server_requests_seconds_count", "error");

        if (totalRequests == 0) {
            return 0.0; // Evitar divisiones por cero
        }
        return errorRequests / totalRequests; // Devuelve errorRate en formato [0..1]
    }

    // ==============================================================
    // Métodos privados para obtener métricas y parsear la respuesta
    // ==============================================================

    /**
     * Busca la definición del servicio y ejecuta extractPrometheusMetric con Retry.
     */
    private double fetchPrometheusMetric(String serviceName,
                                         String metricType,
                                         String metricName,
                                         String outcomeFilter) {

        // Buscamos la definición del servicio
        ResilienceConfig.ServiceDefinition service = resilienceConfig.findServiceByName(serviceName);
        if (service == null || service.getMetrics() == null) {
            logger.warn("No se encontró configuración de métricas para el servicio: {}", serviceName);
            return 0.0;
        }

        // Construimos la URL
        String endpointUrl = buildEndpointUrl(service, metricType);

        // Función que obtiene y parsea la métrica (decorada con Retry)
        Supplier<Double> metricSupplier = () -> extractPrometheusMetric(endpointUrl, metricName, outcomeFilter);

        // Obtenemos/creamos un Retry con la config por defecto
        Retry retry = retryRegistry.retry(serviceName, createRetryConfig());

        // Ejecutamos con el decorador de Retry
        return Retry.decorateSupplier(retry, metricSupplier).get();
    }

    /**
     * Construye la URL del endpoint para métricas.
     */
    private String buildEndpointUrl(ResilienceConfig.ServiceDefinition service, String metricType) {
        String endpoint = metricType.equals("latency")
                ? service.getMetrics().getLatencyEndpoint()
                : service.getMetrics().getErrorEndpoint();

        // Ej: http://localhost:8081/actuator/prometheus?name=...
        return "http://" + service.getHost() + ":" + service.getPort() + endpoint;
    }

    /**
     * Parsea la respuesta de Prometheus y acumula los valores correspondientes.
     */
    private double extractPrometheusMetric(String url, String metricName, String outcomeFilter) {
        try {
            String response = restTemplate.getForObject(url, String.class);
            if (response == null || response.isEmpty()) {
                logger.warn("Respuesta vacía o nula del endpoint: {}", url);
                return 0.0;
            }

            // Buscamos lineas tipo:
            //   http_server_requests_seconds_sum{... outcome="XXX" ...} <valor>
            Pattern pattern = Pattern.compile(metricName + "\\{.*outcome=\"(.*?)\".*\\}\\s(\\d+\\.?\\d*)");

            double sum = 0.0;
            boolean foundAny = false;

            try (Scanner scanner = new Scanner(response)) {
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine();
                    Matcher matcher = pattern.matcher(line);

                    // Usar find() en lugar de matches()
                    while (matcher.find()) {
                        foundAny = true;
                        String outcome = matcher.group(1);
                        double valueInSeconds = Double.parseDouble(matcher.group(2));

                        // outcomeFilter == null: acumulamos todos
                        if (outcomeFilter == null) {
                            sum += valueInSeconds;
                        } else if ("error".equals(outcomeFilter)
                                && (outcome.equals("CLIENT_ERROR") || outcome.equals("SERVER_ERROR"))) {
                            sum += valueInSeconds;
                        }
                    }
                }
            }

            if (!foundAny) {
                logger.warn("La métrica '{}' no se encontró en la respuesta del endpoint: {}", metricName, url);
            }
            return sum;

        } catch (HttpClientErrorException e) {
            handleHttpError(url, e);
        } catch (Exception e) {
            logger.error("Error general al obtener métricas desde: {}", url, e);
        }
        return 0.0;
    }

    /**
     * Manejo de errores HTTP específicos.
     */
    private void handleHttpError(String url, HttpClientErrorException e) {
        if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
            logger.error("Endpoint no encontrado: {}", url, e);
        } else {
            logger.error("Error HTTP en {}: {}", url, e.getStatusCode(), e);
        }
    }

    /**
     * Config por defecto de Retry (3 intentos, 2s espera).
     */
    private RetryConfig createRetryConfig() {
        return RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofSeconds(2))
                .retryExceptions(HttpClientErrorException.class)
                .build();
    }

    // ==============================================================
    // Clase interna para almacenar el estado previo (sum y count)
    // ==============================================================

    private static class LatencyState {
        double previousSum;
        double previousCount;
    }
}
