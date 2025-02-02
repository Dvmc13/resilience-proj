package com.example.resilience_library.rules;

import com.example.resilience_library.config.ResilienceConfig;
import com.example.resilience_library.config.ResilienceConfig.RuleDefinition;
import org.mvel2.MVEL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Motor de reglas que evalúa condiciones MVEL para cada servicio y determina las acciones de resiliencia.
 */
@Component
public class RuleEngine {

    private static final Logger logger = LoggerFactory.getLogger(RuleEngine.class);

    private final ResilienceConfig resilienceConfig;

    /**
     * Caché que asocia cada condición (String) con su expresión MVEL compilada (Serializable).
     */
    private final Map<String, Serializable> compiledExpressions = new ConcurrentHashMap<>();

    public RuleEngine(ResilienceConfig resilienceConfig) {
        this.resilienceConfig = resilienceConfig;
    }

    /**
     * Evalúa las reglas definidas en un servicio para ver si se cumple alguna condición,
     * usando MVEL para condiciones dinámicas.
     *
     * @param serviceName Nombre del servicio a evaluar.
     * @param latency     Latencia medida en el microservicio.
     * @param errorRate   Tasa de error medida en el microservicio.
     * @return Un {@link Optional} que contendrá la acción a ejecutar si se cumple alguna regla,
     *         o vacío si no se cumple ninguna.
     */
    public Optional<String> evaluate(String serviceName, double latency, double errorRate) {
        ResilienceConfig.ServiceDefinition service = resilienceConfig.findServiceByName(serviceName);

        if (service == null || service.getRules() == null || service.getRules().isEmpty()) {
            logger.warn("No se encontraron reglas configuradas para el servicio: {}", serviceName);
            return Optional.empty();
        }

        // Variables disponibles en las expresiones MVEL
        Map<String, Object> variables = new HashMap<>();
        variables.put("latency", latency);
        variables.put("errorRate", errorRate);

        for (RuleDefinition rule : service.getRules()) {
            String condition = rule.getCondition();
            String action = rule.getAction();

            if (condition == null || condition.isBlank()) {
                logger.debug("Regla en servicio '{}' con condición vacía/ nula, se ignora.", serviceName);
                continue;
            }
            if (action == null || action.isBlank()) {
                logger.debug("Regla en servicio '{}' con acción vacía/ nula, se ignora.", serviceName);
                continue;
            }

            try {
                // Usa cache para evitar compilar la misma condición repetidamente
                Serializable compiledCondition = compiledExpressions.computeIfAbsent(
                        condition, MVEL::compileExpression
                );

                boolean result = (boolean) MVEL.executeExpression(compiledCondition, variables);
                if (result) {
                    logger.info("Regla cumplida para '{}': condición='{}' => acción='{}'",
                            serviceName, condition, action);
                    return Optional.of(action);
                }

            } catch (Exception e) {
                logger.error("Error evaluando la condición '{}' en el servicio '{}'", condition, serviceName, e);
            }
        }

        return Optional.empty();
    }
}
