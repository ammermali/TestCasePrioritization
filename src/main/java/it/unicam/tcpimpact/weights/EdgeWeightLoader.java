package it.unicam.tcpimpact.weights;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class EdgeWeightLoader {
    private static final Path FIXED_MODEL_PATH = Path.of("settings", "edge-weight.json");

    private final ObjectMapper objectMapper;

    public EdgeWeightLoader() {
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    public EdgeWeightConfig loadFixedIfExists() throws IOException {
        return loadIfExists(fixedModelPath());
    }

    public Path fixedModelPath() {
        return FIXED_MODEL_PATH.toAbsolutePath().normalize();
    }

    public EdgeWeightConfig loadIfExists(Path configPath) throws IOException {
        if(!Files.exists(configPath)){
            return EdgeWeightConfig.empty();
        }
        EdgeWeightConfig config = objectMapper.readValue(configPath.toFile(), EdgeWeightConfig.class);
        validate(config, configPath);
        return config;
    }

    private void validate(EdgeWeightConfig config, Path configPath) {
        validateWeights(config.defaults(), "defaults", configPath);
        validateWeights(config.callKinds(), "callKinds", configPath);
        for(EdgeWeightConfig.EdgeWeightOverride override : config.overrides()){
            if(override == null){
                throw new IllegalArgumentException("Invalid null override in " + configPath);
            }
            if(isBlank(override.sourceMethodId())){
                throw new IllegalArgumentException("Missing sourceMethodId in edge weight override: " + configPath);
            }
            if(isBlank(override.targetMethodId())){
                throw new IllegalArgumentException("Missing targetMethodId in edge weight override: " + configPath);
            }
            if(override.edgeType() == null){
                throw new IllegalArgumentException("Missing edgeType in edge weight override: " + configPath);
            }
            validateWeight(override.weight(), "override " + override.sourceMethodId() + " -> " + override.targetMethodId(), configPath);
        }
    }

    private void validateWeights(Map<?, Double> weights, String section, Path configPath) {
        for(Map.Entry<?, Double> entry : weights.entrySet()){
            validateWeight(entry.getValue(), section + "." + entry.getKey(), configPath);
        }
    }

    private void validateWeight(Double weight, String label, Path configPath) {
        if(weight == null || weight < 0.0 || weight > 1.0){
            throw new IllegalArgumentException("Invalid edge weight for " + label + " in " + configPath + ": " + weight);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
