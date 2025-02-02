package com.example.resilience_library.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Configuration;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Configuración principal para la resiliencia dinámica.
 * Carga y almacena la configuración de los servicios, incluyendo las reglas
 * definidas en archivos (locales o remotos) que se aplicarán a cada servicio.
 */
@Configuration
@RefreshScope
@ConfigurationProperties(prefix = "resilience")
public class ResilienceConfig {

    private static final Logger logger = LoggerFactory.getLogger(ResilienceConfig.class);

    /**
     * Configuración de monitor para programar el intervalo de verificación de servicios.
     */
    private Monitor monitor;

    /**
     * Lista de definiciones de servicios que se monitorearán y gestionarán de forma resiliente.
     */
    private List<ServiceDefinition> services;

    /**
     * Caché para acceso rápido a las definiciones de servicio (clave = nombre del servicio en minúsculas).
     */
    private final Map<String, ServiceDefinition> serviceCache = new ConcurrentHashMap<>();

    // =============================================
    // Getters & Setters con lógica de inicialización
    // =============================================

    public Monitor getMonitor() {
        return monitor;
    }

    public void setMonitor(Monitor monitor) {
        this.monitor = monitor;
    }

    public List<ServiceDefinition> getServices() {
        return services;
    }

    /**
     * Asigna la lista de servicios y a la vez:
     * 1) Actualiza el caché de definiciones de servicio.
     * 2) Carga las reglas desde los archivos asociados a cada servicio.
     */
    public void setServices(List<ServiceDefinition> services) {
        this.services = services;
        updateServiceCache();
        loadRulesFromFiles();
    }

    // =============================================
    // Métodos clave
    // =============================================

    /**
     * Busca un servicio por nombre desde el cache dinámico.
     * @param serviceName Nombre del servicio (no es case-sensitive).
     * @return La definición del servicio, o null si no existe.
     */
    public ServiceDefinition findServiceByName(String serviceName) {
        if (serviceName == null) {
            return null;
        }
        return serviceCache.get(serviceName.toLowerCase());
    }

    /**
     * Actualiza el caché dinámico para búsquedas rápidas de servicios.
     */
    private void updateServiceCache() {
        serviceCache.clear();
        if (services != null) {
            for (ServiceDefinition service : services) {
                if (service.getName() != null) {
                    serviceCache.put(service.getName().toLowerCase(), service);
                }
            }
        }
    }

    /**
     * Carga las reglas desde los archivos especificados en cada servicio, asignándolas a cada
     * instancia de ServiceDefinition.
     */
    private void loadRulesFromFiles() {
        if (services == null) {
            logger.warn("No hay servicios configurados en 'resilience.services'.");
            return;
        }

        for (ServiceDefinition service : services) {
            String rulesFile = service.getRulesFile();
            if (rulesFile == null) {
                logger.debug("El servicio '{}' no tiene un archivo de reglas configurado.", service.getName());
                continue;
            }

            try (InputStream input = getInputStreamFromPath(rulesFile)) {
                if (input == null) {
                    logger.error("No se pudo abrir el archivo de reglas para el servicio: {}", service.getName());
                    continue;
                }

                Yaml yaml = new Yaml();
                Map<String, Object> rulesData = yaml.load(input);

                List<RuleDefinition> rules = new ArrayList<>();
                if (rulesData != null && rulesData.containsKey("rules")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, String>> rawRules = (List<Map<String, String>>) rulesData.get("rules");
                    for (Map<String, String> rawRule : rawRules) {
                        RuleDefinition rule = new RuleDefinition();
                        rule.setCondition(rawRule.get("condition"));
                        rule.setAction(rawRule.get("action"));
                        rules.add(rule);
                    }
                }

                service.setRules(rules);
                logger.info("Reglas cargadas correctamente para el servicio: {}", service.getName());

            } catch (IOException e) {
                logger.error("Error al cargar el archivo de reglas para el servicio: {}", service.getName(), e);
            } catch (ClassCastException e) {
                logger.error("Error de conversión al cargar las reglas para el servicio: {}", service.getName(), e);
            }
        }
    }

    /**
     * Obtiene un InputStream desde una ruta local o URL remota.
     * @param path Ruta o URL del archivo de reglas.
     * @return InputStream listo para leer, o null si no existe.
     * @throws IOException si ocurre un error de IO.
     */
    private InputStream getInputStreamFromPath(String path) throws IOException {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            // Cargar desde URL remota
            return new URL(path).openStream();
        } else {
            // Cargar desde archivo local
            File file = new File(path);
            if (file.exists()) {
                return new FileInputStream(file);
            } else {
                logger.error("Archivo no encontrado: {}", path);
                return null;
            }
        }
    }

    // =============================================
    // Clases internas de configuración
    // =============================================

    /**
     * Clase interna que define el intervalo de monitoreo para el orquestador de resiliencia.
     */
    public static class Monitor {
        private String interval;

        public String getInterval() {
            return interval;
        }

        public void setInterval(String interval) {
            this.interval = interval;
        }
    }

    /**
     * Clase interna para la definición de cada servicio que se pretende monitorear
     * y gestionar con mecanismos de resiliencia.
     */
    public static class ServiceDefinition {
        private String name;
        private String host;
        private int port;
        private MetricsDefinition metrics;
        private Map<String, String> customMetrics; // Métricas adicionales opcionales
        private String rulesFile;                  // Ruta del archivo de reglas
        private List<RuleDefinition> rules;        // Reglas cargadas dinámicamente

        public String getName() {
            return name;
        }
        public void setName(String name) {
            this.name = name;
        }

        public String getHost() {
            return host;
        }
        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }
        public void setPort(int port) {
            this.port = port;
        }

        public MetricsDefinition getMetrics() {
            return metrics;
        }
        public void setMetrics(MetricsDefinition metrics) {
            this.metrics = metrics;
        }

        public Map<String, String> getCustomMetrics() {
            return customMetrics;
        }
        public void setCustomMetrics(Map<String, String> customMetrics) {
            this.customMetrics = customMetrics;
        }

        public String getRulesFile() {
            return rulesFile;
        }
        public void setRulesFile(String rulesFile) {
            this.rulesFile = rulesFile;
        }

        public List<RuleDefinition> getRules() {
            return rules;
        }
        public void setRules(List<RuleDefinition> rules) {
            this.rules = rules;
        }
    }

    /**
     * Clase interna que define los endpoints de métricas a consultar para cada servicio.
     */
    public static class MetricsDefinition {
        private String latencyEndpoint;
        private String errorEndpoint;

        public String getLatencyEndpoint() {
            return latencyEndpoint;
        }
        public void setLatencyEndpoint(String latencyEndpoint) {
            this.latencyEndpoint = latencyEndpoint;
        }

        public String getErrorEndpoint() {
            return errorEndpoint;
        }
        public void setErrorEndpoint(String errorEndpoint) {
            this.errorEndpoint = errorEndpoint;
        }
    }

    /**
     * Clase interna que representa una regla de resiliencia compuesta por
     * una condición (expresión MVEL) y una acción que se debe tomar si la
     * condición se cumple.
     */
    public static class RuleDefinition {
        private String condition;
        private String action;

        public String getCondition() {
            return condition;
        }
        public void setCondition(String condition) {
            this.condition = condition;
        }

        public String getAction() {
            return action;
        }
        public void setAction(String action) {
            this.action = action;
        }
    }
}
