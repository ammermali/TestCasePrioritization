package it.unicam.tcpimpact.risk;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Loads configurable weights for the initial risk formula.
 * The fixed settings path keeps the risk model inside this prototype, while still leaving a simple JSON contract that a future calibration step can edit.
 */
public class RiskWeightLoader {
    private static final Path FIXED_MODEL_PATH = Path.of("settings", "risk-weight.json");
    // for possible decimal error
    private static final double SUM_EPSILON = 0.0001;
    private static final List<String> REQUIRED_FIELDS = List.of(
            "callExposure",
            "changeExtent",
            "changeSensitivity"
    );

    private final ObjectMapper objectMapper;

    public RiskWeightLoader() {
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    /**
     * Loads {@code settings/risk-weight.json}; falls back to defaults when the settings file is intentionally absent.
     */
    public RiskWeights loadFixedIfExists() throws IOException {
        return loadIfExists(fixedModelPath());
    }

    public Path fixedModelPath() {
        return FIXED_MODEL_PATH.toAbsolutePath().normalize();
    }

    /**
     * Loads risk weights from a JSON file and validates that the complete formula is present and normalized.
     */
    public RiskWeights loadIfExists(Path configPath) throws IOException {
        if(!Files.exists(configPath)){
            return RiskWeights.defaults();
        }
        JsonNode root = objectMapper.readTree(configPath.toFile());
        validateRequiredFields(root, configPath);
        RiskWeights weights = objectMapper.treeToValue(root, RiskWeights.class);
        validate(weights, configPath);
        return weights;
    }

    private void validateRequiredFields(JsonNode root, Path configPath) {
        if(root == null || !root.isObject()){
            throw new IllegalArgumentException("Risk weight config must be a JSON object: " + configPath);
        }
        for(String field : REQUIRED_FIELDS){
            if(!root.hasNonNull(field)){
                throw new IllegalArgumentException("Missing risk weight '" + field + "' in " + configPath);
            }
        }
    }

    private void validate(RiskWeights weights, Path configPath) {
        validateWeight(weights.callExposure(), "callExposure", configPath);
        validateWeight(weights.changeExtent(), "changeExtent", configPath);
        validateWeight(weights.changeSensitivity(), "changeSensitivity", configPath);
        validateSum(
                weights.callExposure() + weights.changeExtent() + weights.changeSensitivity(),
                "riskValue",
                configPath
        );
    }

    private void validateWeight(double weight, String label, Path configPath) {
        if(Double.isNaN(weight) || weight < 0.0 || weight > 1.0){
            throw new IllegalArgumentException("Invalid risk weight for " + label + " in " + configPath + ": " + weight);
        }
    }

    private void validateSum(double sum, String label, Path configPath) {
        if(Math.abs(sum - 1.0) > SUM_EPSILON){
            throw new IllegalArgumentException("Risk weights for " + label + " must sum to 1.0 in " + configPath + ": " + sum);
        }
    }
}
