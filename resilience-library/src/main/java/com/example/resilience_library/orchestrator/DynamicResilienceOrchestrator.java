package com.example.resilience_library.orchestrator;

import com.example.resilience_library.config.ResilienceConfig;
import com.example.resilience_library.metrics.MetricsClient;
import com.example.resilience_library.rules.RuleEngine;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
//import org.springframework.web.context.annotation.RefreshScope;
import org.springframework.cloud.context.config.annotation.RefreshScope;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Orquestador que, de forma periódica, obtiene métricas de los servicios,
 * evalúa las reglas de resiliencia y aplica acciones en circuit breakers,
 * retries y rate limiters.
 * 
 * El intervalo de monitoreo se lee de 'resilience.monitor.interval' en milisegundos,
 * y se reprograma dinámicamente al hacer /actuator/refresh.
 */
@RefreshScope
@Component
public class DynamicResilienceOrchestrator implements InitializingBean, DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(DynamicResilienceOrchestrator.class);

    private final MetricsClient metricsClient;
    private final RuleEngine ruleEngine;
    private final ResilienceConfig resilienceConfig;

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final RateLimiterRegistry rateLimiterRegistry;

    private final ConcurrentMap<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Retry> retries = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();

    // Programador de tareas y referencia a la tarea programada.
    private final ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
    private ScheduledFuture<?> scheduledTask;

    public DynamicResilienceOrchestrator(MetricsClient metricsClient,
                                         RuleEngine ruleEngine,
                                         ResilienceConfig resilienceConfig,
                                         CircuitBreakerRegistry circuitBreakerRegistry,
                                         RetryRegistry retryRegistry,
                                         RateLimiterRegistry rateLimiterRegistry) {
        this.metricsClient = metricsClient;
        this.ruleEngine = ruleEngine;
        this.resilienceConfig = resilienceConfig;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.retryRegistry = retryRegistry;
        this.rateLimiterRegistry = rateLimiterRegistry;
    }

    /**
     * Se invoca al iniciar el bean. Inicializamos el taskScheduler y programamos la tarea.
     */
    @Override
    public void afterPropertiesSet() {
        taskScheduler.initialize();
        scheduleCheckServices();
    }

    /**
     * Se invoca al destruir el bean. Apagamos el scheduler.
     */
    @Override
    public void destroy() {
        taskScheduler.shutdown();
    }

    /**
     * Evento que se dispara cuando hacemos un /actuator/refresh y cambia la configuración.
     * Cancelamos la tarea anterior y programamos una nueva con el nuevo intervalo.
     */
    @EventListener(RefreshScopeRefreshedEvent.class)
    public void onRefresh() {
        logger.info(">>> Received RefreshScopeRefreshedEvent, re-scheduling checkServices task.");
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
        }
        scheduleCheckServices();
    }

    /**
     * Programa la ejecución periódica de checkServices() usando la propiedad
     * resilience.monitor.interval (en milisegundos), o 30000 por defecto.
     */
    private void scheduleCheckServices() {
        long intervalMs = parseInterval(resilienceConfig.getMonitor().getInterval());
        logger.info(">>> Scheduling checkServices with interval = {} ms", intervalMs);
        // scheduleAtFixedRate: la primera ejecución será tras intervalMs
        // (Si deseas que sea inmediato y luego repetido cada interval, usar scheduleWithFixedDelay)
        scheduledTask = taskScheduler.scheduleAtFixedRate(this::checkServices, intervalMs);
    }

    /**
     * Convierte la cadena interval en milisegundos.
     * Por ejemplo, "30000" -> 30000 ms. 
     * Si necesitas parsear "30s" -> 30000, ajusta la lógica aquí.
     */
    private long parseInterval(String interval) {
        if (interval == null || interval.isEmpty()) {
            return 30000L;
        }
        try {
            return Long.parseLong(interval);
        } catch (NumberFormatException ex) {
            logger.warn(">>> Cannot parse interval '{}'. Fallback to 30000 ms", interval);
            return 30000L;
        }
    }

    /**
     * Lógica principal de monitoreo de servicios, que se ejecuta según el intervalo programado.
     */
    public void checkServices() {
        if (resilienceConfig.getServices() == null || resilienceConfig.getServices().isEmpty()) {
            logger.warn("No se encontraron servicios configurados en 'resilience.services'.");
            return;
        }

        for (ResilienceConfig.ServiceDefinition service : resilienceConfig.getServices()) {
            String serviceName = service.getName();
            if (serviceName == null || serviceName.isBlank()) {
                logger.debug("Un servicio definido no tiene nombre, se ignora.");
                continue;
            }

            // Obtener métricas (latencia y errorRate)
            double latency = metricsClient.getLatency(serviceName);
            double errorRate = metricsClient.getErrorRate(serviceName);

            // Evaluar reglas con el motor de reglas (RuleEngine)
            Optional<String> actionOptional = ruleEngine.evaluate(serviceName, latency, errorRate);
            String action = actionOptional.orElse("NoAction");

            logger.info("Service: {}, Latency={}ms, ErrorRate={}, Action={}", 
                        serviceName, latency, errorRate, action);

            // Aplicar la acción si corresponde
            applyAction(action, serviceName);
        }
    }

    /**
     * Aplica la acción indicada (CircuitBreaker, Retry, RateLimiter).
     */
    private void applyAction(String action, String serviceName) {
        if (action == null || action.isEmpty() || "noaction".equalsIgnoreCase(action)) {
            logger.debug(">>> No action specified for '{}'", serviceName);
            return;
        }

        String normalizedAction = action.toLowerCase();

        if (normalizedAction.startsWith("circuitbreaker.")) {
            handleCircuitBreakerAction(normalizedAction, serviceName);
        } else if (normalizedAction.startsWith("retry.")) {
            handleRetryAction(normalizedAction, serviceName);
        } else if (normalizedAction.startsWith("ratelimiter.")) {
            handleRateLimiterAction(normalizedAction, serviceName);
        } else {
            logger.info(">>> No recognized action pattern: {}", action);
        }
    }

    // ------------------------------------------------------------------------------------
    // Manejo de Circuit Breaker
    // ------------------------------------------------------------------------------------

    private void handleCircuitBreakerAction(String action, String serviceName) {
        CircuitBreaker breaker = circuitBreakers.computeIfAbsent(
                serviceName, s -> circuitBreakerRegistry.circuitBreaker(s));

        if (action.contains("open()")) {
            breaker.transitionToOpenState();
            logger.info(">>> CircuitBreaker for '{}' => OPEN", serviceName);
        } else if (action.contains("close()")) {
            breaker.transitionToClosedState();
            logger.info(">>> CircuitBreaker for '{}' => CLOSED", serviceName);
        } else if (action.contains("halfopen()")) {
            breaker.transitionToHalfOpenState();
            logger.info(">>> CircuitBreaker for '{}' => HALF-OPEN", serviceName);
        } else {
            logger.warn(">>> Unrecognized circuitBreaker action: {}", action);
        }
    }

    // ------------------------------------------------------------------------------------
    // Manejo de Retry
    // ------------------------------------------------------------------------------------

    private void handleRetryAction(String action, String serviceName) {
        // Ejemplo: "retry.maxattempts = 3"
        if (action.startsWith("retry.maxattempts")) {
            String[] parts = action.split("=");
            if (parts.length == 2) {
                try {
                    int maxAttempts = Integer.parseInt(parts[1].trim());
                    logger.info(">>> Updating Retry maxAttempts={} for '{}'", maxAttempts, serviceName);

                    // 1. Remover la instancia anterior del Registry, si existe
                    retryRegistry.remove(serviceName);

                    // 2. Construir nueva config
                    RetryConfig newConfig = RetryConfig.custom()
                            .maxAttempts(maxAttempts)
                            .waitDuration(Duration.ofMillis(500))
                            .build();

                    // 3. Registrar y guardar en el mapa local
                    Retry newRetry = retryRegistry.retry(serviceName, newConfig);
                    retries.put(serviceName, newRetry);

                } catch (NumberFormatException e) {
                    logger.error(">>> Invalid maxAttempts value: {}", parts[1], e);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // Manejo de Rate Limiter
    // ------------------------------------------------------------------------------------

    private void handleRateLimiterAction(String action, String serviceName) {
        // Ejemplo: "ratelimiter.limitforperiod = 10"
        if (action.startsWith("ratelimiter.limitforperiod")) {
            String[] parts = action.split("=");
            if (parts.length == 2) {
                try {
                    int limit = Integer.parseInt(parts[1].trim());
                    logger.info(">>> Updating RateLimiter limitForPeriod={} for '{}'", limit, serviceName);

                    // 1. Remover la instancia anterior del Registry, si existe
                    rateLimiterRegistry.remove(serviceName);

                    // 2. Crear nueva config de RateLimiter
                    RateLimiterConfig newConfig = RateLimiterConfig.custom()
                            .limitForPeriod(limit)
                            .limitRefreshPeriod(Duration.ofSeconds(1))
                            .timeoutDuration(Duration.ofMillis(100))
                            .build();

                    // 3. Registrar y guardar en el mapa local
                    RateLimiter newRateLimiter = rateLimiterRegistry.rateLimiter(serviceName, newConfig);
                    rateLimiters.put(serviceName, newRateLimiter);

                } catch (NumberFormatException e) {
                    logger.error(">>> Invalid limitForPeriod value: {}", parts[1], e);
                }
            }
        }
    }
}
